# Bastion

Protection anti-perte / anti-vol pour Android et GrapheneOS, pilotée depuis votre PC, sans Google,
sans compte cloud, sans télémétrie. *(Nom de code provisoire, défini dans `branding/product.json`.)*

| Composant | Dossier | Statut v0.1 |
|---|---|---|
| Bastion Mobile (Kotlin, Compose) | [`mobile/`](mobile) | appairage QR + SAS, service de protection, sonnerie, localisation, suivi, mode Perdu, verrouillage / effacement (admin d'appareil), alertes |
| Bastion Desktop (Tauri 2, Svelte 5) | [`desktop/`](desktop) | coffre chiffré, relay intégré, appairage, carte, commandes, journal |
| Bastion Relay (Rust, axum) | [`relay/`](relay) | enrôlement, boîtes aux lettres chiffrées de bout en bout, TLS 1.3 épinglé |
| Protocole (Protobuf) | [`protocol/`](protocol) | v1 normatif + vecteurs d'interopérabilité |

Documentation : [architecture](docs/ARCHITECTURE.md) · [protocole](docs/PROTOCOL.md) ·
[menaces](docs/THREAT_MODEL.md) · [décisions](docs/DECISIONS.md) · [limites](docs/LIMITATIONS.md) ·
[permissions](docs/PERMISSIONS.md)

## Utilisation

1. **PC (Windows)** : installez `Bastion_x.y.z_x64-setup.exe`, lancez Bastion, choisissez un mot de
   passe maître (12 caractères minimum, irrécupérable) et le **relay intégré** (ou un relay
   distant). Autorisez Bastion sur les réseaux **privés** si Windows le demande.
2. **Téléphone** : installez l'APK (sources inconnues), ouvrez Bastion, touchez **Appairer** et
   scannez le QR affiché par le PC (*Téléphones › Ajouter un téléphone*).
3. **Comparez le code à 6 chiffres** sur les deux écrans et confirmez des deux côtés.
4. Sur le téléphone, corrigez chaque ligne de **Santé de la protection** (localisation
   « Toujours », administrateur d'appareil, batterie, notifications).

Le relay intégré fonctionne quand le téléphone peut joindre le PC (même réseau ou redirection de
port). Pour une protection partout, déployez `bastion-relay` sur un serveur :

```bash
BASTION_RELAY_LISTEN=0.0.0.0:8443 BASTION_RELAY_DATA=/var/lib/bastion bastion-relay
bastion-relay pin           # empreinte TLS à saisir dans le PC
bastion-relay admin-token   # jeton à usage unique pour enregistrer le PC
```

## Développement

Prérequis : Node ≥ 22 + pnpm 10, Rust stable (+ MSVC Build Tools sous Windows,
`libwebkit2gtk-4.1-dev` sous Linux), Android SDK (plateforme 37). Le JDK 21 de Gradle est
provisionné automatiquement.

```bash
pnpm install && pnpm proto:lint && pnpm gen:check
cargo test --workspace && cargo clippy --workspace --all-targets -- -D warnings
pnpm --dir desktop test && pnpm --dir desktop tauri build --bundles nsis
cd mobile && ./gradlew ktlintCheck detekt lintDebug test assembleRelease
```

Signature de l'APK release : `~/.bastion-signing/signing.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`) ou variable `BASTION_SIGNING_PROPERTIES` ; sans ce
fichier, l'APK release n'est pas signé.

Test de bout en bout avec un téléphone ou un émulateur :
`cargo run -p bastion-desktop --example e2e_controller -- https://<ip-du-pc>:8443`
(affiche le lien d'appairage, accepte le SAS, envoie état, localisation, sonnerie et un
verrouillage contresigné).

## Principes

* Chiffrement et signature de bout en bout : le relay ne voit que des blobs opaques.
* Aucune dépendance Google Play Services / Firebase.
* Ce que l'app **ne peut pas** faire est écrit dans [LIMITATIONS.md](docs/LIMITATIONS.md).

## Licences

Applications, crates partagées et protocole : GPL-3.0-or-later ([LICENSE](LICENSE)).
Relay : AGPL-3.0-or-later ([relay/LICENSE](relay/LICENSE)).
