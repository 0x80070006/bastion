# Architecture

> Le nom produit « Bastion » est provisoire. Il est défini **une seule fois** dans
> [`branding/product.json`](../branding/product.json) et lu par Gradle, Cargo et Vite au build.

## 1. Vue d'ensemble

Bastion se compose de trois logiciels et d'une définition de protocole partagée :

| Composant | Rôle | Techno | Licence |
|---|---|---|---|
| **Bastion Mobile** (`mobile/`) | Agent sur le téléphone : VPN WireGuard, localisation, alarme, mode Perdu, détections | Kotlin, Compose, Hilt, Room/SQLCipher, WorkManager | GPL-3.0 |
| **Bastion Desktop** (`desktop/`) | Console du propriétaire : carte, actions, journal | Tauri 2 (Rust) + Svelte 5 + TypeScript | GPL-3.0 |
| **Bastion Relay** (`relay/`) | Point d'accès WireGuard + boîte aux lettres de blobs chiffrés + proxy de tuiles | Rust (axum, tokio), SQLite | AGPL-3.0 |
| **Protocole** (`protocol/`) | Messages Protobuf versionnés, source unique | Protobuf 3 + buf | GPL-3.0 |

```
┌──────────────┐   WireGuard (UDP)    ┌──────────────────────┐   TLS 1.3 épinglé    ┌────────────────┐
│ Bastion      │◄────────────────────►│ Bastion Relay        │◄────────────────────►│ Bastion        │
│ Mobile       │                      │ - endpoint WireGuard │   (ou WireGuard)     │ Desktop        │
│              │   enveloppes E2E     │ - boîte aux lettres  │   enveloppes E2E     │ (Tauri)        │
│              │   (blobs opaques)    │ - proxy de tuiles    │   (blobs opaques)    │                │
└──────────────┘                      └──────────────────────┘                      └────────────────┘
```

Le relay **ne déchiffre jamais rien** : il route des `Envelope` (en-tête minimal + texte chiffré)
d'un identifiant de pair vers un autre. Tout le contenu applicatif est chiffré et signé de bout
en bout entre le téléphone et le PC (voir [PROTOCOL.md](PROTOCOL.md)).

## 2. Organisation du monorepo

```
branding/product.json        nom produit, identifiants d'application (source unique)
design/tokens.json           tokens de design (source unique mobile + desktop)
protocol/proto/bastion/v1/   définitions Protobuf (source unique du protocole)
tools/                       générateurs (tokens), scripts de vérification
crates/
  bastion-proto/             code Rust généré (prost) depuis protocol/
  bastion-crypto/            primitives libsodium + enveloppe E2E (jalon 2)
relay/                       serveur (binaire Rust) + Dockerfile + docker-compose
desktop/                     frontend Svelte/TS (pnpm) ; desktop/src-tauri = backend Rust
mobile/                      projet Gradle Android
docs/                        documentation (architecture, protocole, menaces, ADR…)
.github/workflows/           CI
```

Le `Cargo.toml` racine est un **workspace** regroupant `crates/*`, `relay` et
`desktop/src-tauri` : le relay et le desktop partagent exactement le même code de protocole
et de cryptographie.

## 3. Bastion Mobile

### 3.1 Modules Gradle

```
:app                     point d'entrée, graphe Hilt, navigation, services Android
:feature:onboarding      Appairer → Autoriser → Vérifier
:feature:vpn             écran VPN simple/avancé
:feature:protection      localisation, alarme, mode Perdu, détections, santé
:feature:remote          exécution des commandes distantes, journal de sécurité
:core:designsystem       thème Compose 100 % custom, tokens générés, composants
:core:domain             entités, interfaces de dépôts, cas d'usage (Kotlin pur, JVM)
:core:crypto             identités, enveloppe E2E, anti-rejeu (Kotlin pur + libsodium)
:core:protocol           code Protobuf généré (lite) + codecs/validation (Kotlin pur)
:core:vpn                intégration com.wireguard.android:tunnel, stockage Keystore
```

Règle de dépendance (vérifiée en revue, et par la structure des modules) :

```
feature:* ──► core:domain ◄── core:crypto, core:protocol, core:vpn (implémentations data)
   │                                   ▲
   └──► core:designsystem              │
:app ──► tout (assemble le graphe Hilt) ┘
```

* `core:domain`, `core:crypto`, `core:protocol` sont des modules **JVM purs** : testables sans
  Android, sans émulateur, rapides en CI.
* Les `feature:*` ne dépendent jamais d'une implémentation `data`, seulement des interfaces de
  `core:domain`. Les liaisons sont faites par Hilt dans `:app` (ou dans des modules Hilt
  dédiés).
* Chaque `feature` suit MVVM : `ViewModel` (StateFlow d'état immuable + événements) ↔ écran
  Compose sans logique métier.

### 3.2 Services et cycle de vie

| Composant | Type | Rôle |
|---|---|---|
| `BastionVpnService` | `VpnService` (via GoBackend) | tunnel WireGuard |
| `ProtectionService` | service au premier plan `location` (+ `connectedDevice` si besoin) | canal relay, localisation, détections |
| `BastionDeviceAdmin` | `DeviceAdminReceiver` | `lockNow()`, effacement, échecs de déverrouillage |
| `BootReceiver` | `LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED` | relance après redémarrage |
| Workers | WorkManager | purge d'historique, contrôle de santé, rotation de clés |

Pas de Google Play Services : localisation via `LocationManager` (GPS + réseau), pas de FCM.
Le téléphone maintient lui-même une connexion sortante (WebSocket TLS dans le tunnel
WireGuard quand il est actif) avec backoff exponentiel + jitter.

### 3.3 Stockage

* Clés d'identité et clé privée WireGuard : chiffrées au repos par une clé AES-GCM de
  l'**Android Keystore** (StrongBox si disponible), non exportable.
* Base Room chiffrée par **SQLCipher** ; la phrase de passe est elle-même scellée par le Keystore.
* `DataStore` pour les préférences non sensibles.
* `allowBackup=false`, règles d'extraction de données vides.

### 3.4 Coexistence des VPN

Android n'autorise **qu'un seul VPN actif**. Bastion gère donc deux cas :

1. **Tunnel unique (défaut)** : le tunnel Bastion *est* le VPN de l'utilisateur. La config
   WireGuard contient le pair relay ; l'utilisateur peut choisir `AllowedIPs` = sous-réseau
   Bastion uniquement (split) ou `0.0.0.0/0, ::/0` (tout le trafic via le relay).
2. **Autre VPN actif** (ex. VPN commercial) : le tunnel Bastion ne peut pas monter ; le canal
   de gestion bascule sur **TLS 1.3 épinglé** directement vers le relay. Les messages restant
   chiffrés de bout en bout, la sécurité applicative est identique ; seule la confidentialité
   des métadonnées réseau diffère. L'écran Santé signale ce mode.

Un mode multi-tunnels (avancé) permet d'avoir plusieurs configurations, une seule active.

## 4. Bastion Desktop

* **Backend Rust (src-tauri)** : toute la cryptographie, le stockage des clés, la connexion au
  relay et la validation des messages. Le webview **ne voit jamais de clé privée** ; il reçoit
  des objets déjà vérifiés via des commandes Tauri typées.
* **Frontend Svelte 5** : état dans des stores dédiés (`connection`, `devices`, `journal`,
  `session`), composants sans logique de protocole.
* Stockage : coffre local chiffré (XChaCha20-Poly1305) dont la clé est dérivée du mot de passe
  maître par **Argon2id**, et dont une clé d'enveloppe est protégée par le coffre de l'OS
  (Windows Credential Manager / Secret Service). Verrouillage automatique sur inactivité.
* Carte : MapLibre GL ; les tuiles passent par le **proxy du relay** (l'IP du PC n'est pas
  exposée aux serveurs OSM). Style sombre désaturé dérivé des tokens.
* CSP stricte, aucune ressource distante chargée directement par le webview.

## 5. Bastion Relay

* Un binaire Rust (axum) avec **deux écouteurs** exposant la même API :
  * HTTPS public (TLS 1.3, certificat auto-signé généré à l'amorçage, **épinglé par SPKI** dans
    le QR d'appairage) — nécessaire pour l'enrôlement initial et pour les clients qui ne
    passent pas par WireGuard ;
  * HTTP(S) sur l'interface WireGuard (`10.77.0.1`) pour les pairs du tunnel.
* WireGuard : conteneur dédié (`wg-quick` / noyau) piloté par le relay pour ajouter / retirer
  des pairs. Le relay n'a besoin que de `NET_ADMIN` dans ce conteneur.
* Stockage : SQLite (pairs, invitations hachées, boîtes aux lettres avec TTL).
* Proxy de tuiles avec liste blanche de sources, cache disque borné, `User-Agent` neutre.
* Aucun log d'IP en clair (désactivé par défaut ; haché avec sel rotatif si activé).

## 6. Topologies

* **A — Relay (défaut)** : VPS, serveur maison, Raspberry Pi ou VM Proxmox.
* **B — Direct (avancé)** : le PC est un pair WireGuard direct (port-forward / DDNS) et embarque
  un mini-relay local (même binaire, mode `embedded`). Même protocole, mêmes garanties E2E.

## 7. Transverse

* **Erreurs** : `Result`/sealed classes en Kotlin, `Result<T, E>` + `thiserror` en Rust ;
  aucune exception avalée ; logs structurés sans donnée sensible (lint dédié en jalon 7).
* **Observabilité locale uniquement** : journal de sécurité chiffré sur chaque appareil.
* **i18n** : français par défaut, anglais ; aucune chaîne en dur (Android `strings.xml`,
  desktop dictionnaires typés).
* **Design** : tokens uniques dans `design/tokens.json`, générés vers Kotlin et CSS par
  `tools/gen-tokens.mjs` ; la CI échoue si le code généré n'est pas à jour.
* **Protocole** : source unique `protocol/`, `buf lint` + `buf breaking` en CI, génération
  Kotlin (protobuf-gradle-plugin, runtime lite), Rust (prost, `protoc` vendored), TypeScript
  (protobuf-es).
