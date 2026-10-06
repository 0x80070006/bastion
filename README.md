# Bastion

Protection anti-perte / anti-vol pour Android et GrapheneOS, pilotée depuis votre PC, sans Google,
sans compte cloud, sans télémétrie. *(Nom de code provisoire, défini dans `branding/product.json`.)*

| Composant | Dossier | Statut |
|---|---|---|
| Bastion Mobile (Kotlin, Compose) | [`mobile/`](mobile) | jalon 1 : squelette, thème, protocole |
| Bastion Desktop (Tauri 2, Svelte 5) | [`desktop/`](desktop) | jalon 1 : squelette, thème, i18n |
| Bastion Relay (Rust, axum) | [`relay/`](relay) | jalon 1 : santé `/v1/health` |
| Protocole (Protobuf) | [`protocol/`](protocol) | v1 brouillon |

Documentation : [architecture](docs/ARCHITECTURE.md) · [protocole](docs/PROTOCOL.md) ·
[menaces](docs/THREAT_MODEL.md) · [décisions](docs/DECISIONS.md) · [limites](docs/LIMITATIONS.md) ·
[permissions](docs/PERMISSIONS.md)

## Démarrer en 5 minutes (développeurs)

Prérequis : Node ≥ 22 + pnpm 10, Rust stable (+ MSVC Build Tools sous Windows, `libwebkit2gtk-4.1-dev`
sous Linux), JDK 21, Android SDK (plateforme 37).

```bash
pnpm install                 # outils partagés (buf, protoc-gen-es) + desktop
pnpm proto:lint              # lint Protobuf
pnpm gen:check               # tokens de design et branding à jour

cargo test --workspace       # relay, crates partagées, backend desktop
cargo run -p bastion-relay   # relay sur 127.0.0.1:8443

pnpm --dir desktop test      # tests frontend
pnpm --dir desktop tauri dev # application desktop

cd mobile && ./gradlew test assembleDebug   # APK : mobile/app/build/outputs/apk/debug/
```

## Principes

* Chiffrement de bout en bout (libsodium) : le relay ne voit que des blobs opaques.
* Aucune dépendance Google Play Services / Firebase ; compatible F-Droid.
* Ce que l'app **ne peut pas** faire est écrit noir sur blanc dans [LIMITATIONS.md](docs/LIMITATIONS.md).

## Licences

Applications, crates partagées et protocole : GPL-3.0-or-later ([LICENSE](LICENSE)).
Relay : AGPL-3.0-or-later ([relay/LICENSE](relay/LICENSE)).
