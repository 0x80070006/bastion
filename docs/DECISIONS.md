# Décisions d'architecture (ADR)

Format court : contexte → décision → conséquences. Les ADR ne sont jamais réécrits ; un ADR
remplacé est marqué *Remplacé par ADR-xxxx*.

---

## ADR-0001 — Monorepo unique

* **Contexte** : trois logiciels partagent un protocole, des tokens de design et un nom de code provisoire.
* **Décision** : un seul dépôt ; `protocol/`, `design/` et `branding/` sont les sources uniques.
  Workspace Cargo à la racine (`crates/*`, `relay`, `desktop/src-tauri`), projet Gradle dans
  `mobile/`, workspace pnpm à la racine (`desktop`, `protocol`).
* **Conséquences** : une modification du protocole est atomique sur les trois composants ; la CI est plus longue (jobs parallélisés et filtrés par chemin).

## ADR-0002 — Nom de code dans un seul fichier

* **Décision** : `branding/product.json` contient le nom affiché, l'identifiant d'application
  et le schéma d'URI. Gradle (`resValue`/`BuildConfig`), Cargo (`build.rs`) et Vite (import JSON)
  le lisent au build. Les chaînes i18n utilisent un paramètre/ressource `product_name`.
* **Conséquences** : renommer = modifier un fichier (+ l'`applicationId` si souhaité). Les noms de
  packages Kotlin / crates restent `bastion` (identifiants techniques, non affichés).

## ADR-0003 — Frontend desktop : Svelte 5

* **Contexte** : le prompt laisse le choix Svelte ou React.
* **Décision** : **Svelte 5** (runes) + TypeScript + Vite.
* **Raisons** : compilé, runtime minuscule (surface d'attaque et taille du binaire Tauri
  réduites), réactivité explicite sans bibliothèque d'état externe, très peu de boilerplate pour
  une console à quelques écrans. React apporterait surtout un écosystème de composants que la
  direction artistique custom n'utiliserait pas.
* **Conséquences** : tests avec Vitest + `@testing-library/svelte` ; ESLint `eslint-plugin-svelte`.

## ADR-0004 — Relay en Rust (axum), partage de code avec le desktop

* **Décision** : relay en Rust/axum/tokio plutôt qu'en Go.
* **Raisons** : le backend Tauri est en Rust ; le relay et le desktop partagent `bastion-proto`
  et `bastion-crypto`. Une seule implémentation serveur/PC du protocole à auditer.
* **Conséquences** : image Docker basée sur `distroless/cc` (libsodium lié statiquement).

## ADR-0005 — Liaisons libsodium

* **Décision** :
  * Rust : `libsodium-sys-stable` (maintenu par l'auteur de libsodium), encapsulé dans une API
    sûre minimale dans `bastion-crypto`. Pas de `sodiumoxide` (archivé).
  * Kotlin : `lazysodium-java` (tests JVM) / `lazysodium-android` (app), via une interface
    commune dans `:core:crypto` pour garder le module testable sur la JVM.
  * TypeScript : **aucune crypto dans le webview** ; le frontend ne manipule que des données déjà
    vérifiées par le backend Rust. Le code TS généré depuis Protobuf sert au typage des vues et aux
    outils de diagnostic.
* **Conséquences** : vecteurs de test partagés (`protocol/testvectors/`) exécutés par Rust et Kotlin
  pour garantir l'interopérabilité (jalon 2).

## ADR-0006 — Génération Protobuf sans `protoc` système

* **Décision** : `buf` (paquet npm `@bufbuild/buf`) pour lint, détection de rupture et génération
  TS (`protobuf-es`) ; `protoc-bin-vendored` pour `prost-build` côté Rust ; artefact Maven
  `com.google.protobuf:protoc` via `protobuf-gradle-plugin` côté Kotlin (runtime **lite**).
* **Conséquences** : aucun binaire à installer à la main ; versions épinglées dans les lockfiles.

## ADR-0007 — Clés de session statiques par époque, sans double ratchet en v1

* **Contexte** : la boîte aux lettres doit fonctionner hors ligne (le téléphone peut être injoignable
  des heures) ; un double ratchet ajoute une complexité d'état importante et des risques de désynchronisation.
* **Décision** : clés directionnelles dérivées de X25519 statique-statique par époque, rotation
  signée des clés X25519 tous les 7 jours, nonce aléatoire 24 octets (XChaCha).
* **Conséquences** : compromission d'une `XK` ⇒ messages de son époque lisibles (fenêtre ≤ 7 j + 24 h).
  Ré-évaluable en v2 (Noise/MLS-like) sans casser v1.

## ADR-0008 — Clé de commande privilégiée `PK`

* **Contexte** : le prompt exige une ré-authentification sur le PC pour les commandes sensibles ;
  sans preuve cryptographique, un malware sur le PC pourrait contourner l'interface.
* **Décision** : une clé Ed25519 `PK` scellée par Argon2id(mot de passe maître), enregistrée chez le
  téléphone à l'appairage, contresigne `Lock`, `Wipe`, `Unpair`.
* **Conséquences** : le téléphone peut vérifier que la ré-authentification a eu lieu. Perte du mot de passe
  maître ⇒ ré-appairage nécessaire pour les commandes sensibles.

## ADR-0009 — TLS auto-signé épinglé plutôt que PKI publique

* **Décision** : le relay génère son certificat à l'amorçage ; l'empreinte SPKI est transmise dans le QR.
  Un reverse-proxy Let's Encrypt reste possible mais n'est pas requis et n'est jamais *la* source de confiance.
* **Conséquences** : fonctionne avec une IP brute ou un nom local ; changer de certificat impose de
  ré-épingler (flux « rotation du relay » documenté au jalon 3).

## ADR-0010 — Modules Gradle supplémentaires

* **Contexte** : le prompt liste `:core:crypto`, `:core:protocol`, `:core:vpn`, `:feature:*`, `:app`.
* **Décision** : ajout de `:core:domain` (cas d'usage purs, JVM) et `:core:designsystem` (thème
  et composants partagés par les features). Les trois modules `domain`, `crypto`, `protocol` sont
  des modules **Kotlin/JVM** (pas Android) pour des tests rapides.
* **Conséquences** : le respect de la Clean Architecture est imposé par le graphe de modules.

## ADR-0011 — Versions de la chaîne Android (vérifiées le 2026-10-06)

* **Décision** : AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20, `compileSdk`/`targetSdk` 37
  (Android 17, dernière plateforme stable publiée dans le SDK Manager), `minSdk` 29, JDK 21.
* **Écarts constatés par rapport aux habitudes antérieures** : AGP 9 intègre le support Kotlin
  (*built-in Kotlin*) ; le plugin `org.jetbrains.kotlin.android` n'est plus appliqué aux modules
  Android. Le plugin Compose compiler reste `org.jetbrains.kotlin.plugin.compose`.
* **Conséquences** : version catalog `mobile/gradle/libs.versions.toml` ; mises à jour via PR dédiées.

## ADR-0012 — Licences

* **Décision** : GPL-3.0-or-later pour Mobile, Desktop, crates partagées et protocole ;
  **AGPL-3.0-or-later** pour le relay.
* **Raisons** : copyleft fort pour les apps ; l'AGPL couvre le cas « relay hébergé comme service »
  (les utilisateurs d'un relay modifié peuvent en obtenir le code). Les crates partagées en GPL sont
  compatibles avec un binaire AGPL (GPLv3 §13).
* **Conséquences** : `LICENSE` (GPL-3.0) à la racine, `relay/LICENSE` (AGPL-3.0). Dépendances vérifiées
  compatibles (cargo-deny, jalon 7).

## ADR-0013 — Tokens de design générés

* **Décision** : `design/tokens.json` → `tools/gen-tokens.mjs` → `Tokens.kt` (mobile) et
  `tokens.css` (desktop). Fichiers générés commités ; la CI exécute le générateur et échoue sur diff.
* **Conséquences** : impossible d'introduire une couleur hors palette sans passer par le fichier unique.

## ADR-0014 — Polices

* **Décision** : **Inter Tight** (UI) et **JetBrains Mono** (données techniques), toutes deux sous
  licence OFL, embarquées dans les binaires (jamais chargées depuis Google Fonts). Côté desktop via les
  paquets `@fontsource/*` ; côté Android, fichiers `res/font` ajoutés au jalon 4 (le jalon 1 utilise
  les familles système en repli).

## ADR-0015 — Ajustement de la couleur « danger »

* **Contexte** : `#C4554D` proposé dans le cahier des charges atteint 4,48:1 sur `#0A0A0B`, sous le
  seuil AA (4,5:1) exigé pour tout texte. Le générateur de tokens vérifie le contraste et a refusé la palette.
* **Décision** : `danger = #C5574F` (4,57:1), écart imperceptible. `textTertiary` (3,28:1) est réservé aux
  contrôles désactivés et séparateurs (exemptés par WCAG 1.4.3) ; le générateur impose ≥ 3:1 pour lui
  et ≥ 4,5:1 pour toutes les autres couleurs de texte/état.
* **Conséquences** : toute nouvelle couleur passe la même vérification en CI.

## ADR-0016 — Cryptographie Rust pure, compatible libsodium (remplace en partie ADR-0005)

* **Contexte** : le workspace interdit `unsafe_code` ; `libsodium-sys` imposerait une enveloppe
  `unsafe` et une chaîne C sous Windows.
* **Décision** : côté Rust, `ed25519-dalek` (vérification stricte), `x25519-dalek`,
  `chacha20poly1305`, `blake2`, `argon2`, `crypto_box` (sealed box) — byte-compatibles avec
  libsodium. Android garde libsodium (lazysodium).
* **Conséquences** : l'équivalence est prouvée par `protocol/testvectors/v1.txt`, généré par Rust et
  recalculé par Kotlin/libsodium (y compris une boîte scellée ouverte de part et d'autre).

## ADR-0017 — Transcriptions préfixées par la longueur

* **Décision** : toute entrée signée/hachée est `T(ctx, champs…)` avec `u32be(len)` avant chaque
  champ (PROTOCOL.md §1.1), y compris l'AAD et la clé de session qui lie les deux `IK` et `XK`.
* **Conséquences** : aucune ambiguïté de concaténation (champs optionnels vides compris).

## ADR-0018 — Attente longue plutôt que WebSocket

* **Décision** : `GET /v1/mailbox?wait=N` (≤ 30 s) remplace `/v1/ws`.
* **Raisons** : même authentification par requête signée que le reste de l'API, aucun état de
  connexion, traverse tous les proxys ; latence équivalente pour une console à quelques appareils.

## ADR-0019 — Relay intégré au PC par défaut ; WireGuard reporté

* **Décision** : l'application PC embarque le relay (topologie B) et l'annonce sur le réseau local
  ; un relay distant reste configurable. Le tunnel WireGuard n'est pas livré en v0.1 : le canal
  TLS 1.3 épinglé (ARCHITECTURE.md §3.4, cas 2) est utilisé partout.
* **Conséquences** : installation immédiate sans serveur ; portée limitée au réseau du PC tant
  qu'aucune redirection de port ou relay distant n'est configuré (LIMITATIONS.md).

## ADR-0020 — Module `:core:agent` et état du téléphone

* **Décision** : la logique protocolaire du téléphone (invitation, enrôlement, règles §6,
  décodage des commandes) vit dans un module Kotlin/JVM pur `:core:agent`, testé sans émulateur
  contre un contrôleur simulé. L'état (clés, compteurs, fenêtre anti-rejeu) est un message
  Protobuf local chiffré AES-256-GCM par une clé Keystore (StrongBox si disponible), dans
  `noBackupFilesDir`, plutôt qu'une base Room/SQLCipher.
* **Raisons** : volume minime, écriture atomique unique, persistance avant effet triviale à garantir.

## ADR-0021 — Coffre PC

* **Décision** : fichier unique `vault.bin` (en-tête Argon2id authentifié en AAD, paramètres
  inférieurs à `INTERACTIVE` refusés pour empêcher une rétrogradation), écrit atomiquement ;
  `PK` scellée une seconde fois avec son propre sel. Le webview ne reçoit jamais de clé ; le délai
  de 30 s de l'effacement est imposé par le backend.

## ADR-0022 — Média en direct sur la boîte aux lettres (pas de WebRTC)

* **Contexte** : prendre une photo, voir la caméra ou entendre le micro à distance.
* **Décision** : la photo (`CapturePhoto`/`PhotoReport`) et les flux « quasi directs » caméra
  (`StreamControl`/`MediaFrame`, JPEG) et audio (`AudioControl`/`AudioChunk`, PCM 16 bits mono
  16 kHz) passent par le **même canal chiffré de bout en bout** que les commandes : des
  événements ordinaires relayés via la boîte aux lettres et l'attente longue. Pas de WebRTC ni de
  flux temps réel (qui exigeraient WireGuard et un chemin média séparé, ADR-0019).
* **Conséquences** : latence bornée par le cycle d'attente longue (quelques i/s en pratique),
  chaque trame bornée à 384 KiO ; côté PC, lecture par Web Audio et affichage image par image.
  Les photos sont persistées dans un magasin média chiffré séparé (une clé par fichier dérivée du
  coffre), hors du coffre réécrit fréquemment.

## ADR-0023 — Photo/caméra/micro toujours visibles, jamais discrets

* **Décision** : toutes les captures s'appuient sur les services au premier plan typés `camera`
  et `microphone` (Android 11+), donc l'indicateur système est affiché. Aucun mode caché. Le PC
  affiche un avertissement légal avant d'activer caméra ou micro.
* **Conséquences** : cohérent avec le modèle de menaces (anti-surveillance discrète). Un voleur
  voit l'indicateur ; c'est assumé et documenté (LIMITATIONS.md, PERMISSIONS.md).

## ADR-0024 — Appairage par lien profond

* **Décision** : en plus du QR, l'URI `bastion://pair/v1#…` ouvre l'application via un
  `intent-filter` VIEW et lance le même flux d'appairage. Il n'est traité qu'après le **verrou
  d'application** et la **confirmation SAS**, donc la surface d'attaque est équivalente à celle du
  QR (ADV6) : un lien malveillant ne peut pas appairer sans l'action explicite du propriétaire.

## ADR-0025 — Géorepérage évalué sur le téléphone

* **Décision** : les zones (`SetGeofences`) sont stockées sur le téléphone et évaluées localement à
  chaque position (Haversine + hystérésis), ce qui permet d'alerter (`GEOFENCE_ENTER/EXIT`) et de
  passer en suivi rapproché même quand le PC est hors ligne. Logique pure testée sans Android.

