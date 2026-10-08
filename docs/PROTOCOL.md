# Protocole Bastion — v1

Statut : **normatif, implémenté** (Rust : `crates/bastion-crypto`, relay, desktop ; Kotlin :
`mobile/core/crypto`, `mobile/core/agent`). Les définitions Protobuf font foi :
[`protocol/proto/bastion/v1`](../protocol/proto/bastion/v1). L'interopérabilité octet par octet
des deux implémentations est vérifiée par les vecteurs partagés
[`protocol/testvectors/v1.txt`](../protocol/testvectors/v1.txt) (générés par Rust, recalculés par
Kotlin/libsodium en CI). Toute évolution passe par `buf breaking` et un ADR.

Mots-clés DOIT / NE DOIT PAS / DEVRAIT au sens RFC 2119.

## 1. Primitives

| Usage | Primitive (équivalent libsodium) |
|---|---|
| Signature | Ed25519 détachée (`crypto_sign_detached`), vérification stricte (clés de petit ordre refusées) |
| Accord de clé | X25519 (`crypto_scalarmult`) ; un secret tout à zéro est refusé |
| Chiffrement authentifié | XChaCha20-Poly1305 IETF |
| Hachage / KDF / MAC | BLAKE2b (`crypto_generichash`), clé de 16 à 64 octets |
| Boîte scellée | `crypto_box_seal` |
| Mot de passe (PC) | Argon2id v1.3, 1 voie (`OPSLIMIT_MODERATE`/`MEMLIMIT_MODERATE` : 3 passes, 256 Mio) |
| Aléa | CSPRNG du système |

Côté Android : libsodium (lazysodium). Côté Rust : implémentations RustCrypto/dalek
compatibles (ADR-0016). Aucune construction au-delà de la composition documentée ici.

### 1.1 Transcriptions

Toute entrée signée ou hachée est une **transcription** non ambiguë, jamais une sérialisation
Protobuf (non canonique) :

```
T(ctx, f1, …, fn) = u32be(len(ctx)) ‖ ctx ‖ u32be(len(f1)) ‖ f1 ‖ … ‖ u32be(len(fn)) ‖ fn
```

Les entiers sont des champs à largeur fixe big-endian (`u32be`, `u64be`), eux aussi préfixés de
leur longueur. Contextes (ASCII) : `bastion-xk-v1`, `bastion-invite-v1`, `bastion-enroll-v1`,
`bastion-enroll-sig-v1`, `bastion-sas-v1`, `bastion-session-v1`, `bastion-session-key-v1`,
`bastion-env-v1`, `bastion-msg-v1`, `bastion-req-v1`, `bastion-fp-v1`.

## 2. Identités

Chaque appareil génère localement (le téléphone en génère de nouvelles à chaque appairage) :

* `IK` : Ed25519 ; `device_id = BLAKE2b-128(IK.pub)` ;
* `XK` : X25519 d'époque `e`, signée : `XKsig = Sign(IK, T("bastion-xk-v1", XK.pub, u32be(e)))`.

Empreinte affichée : `BLAKE2b-256(T("bastion-fp-v1", IK.pub, XK.pub))`, 16 premiers octets en
hexadécimal, 8 groupes de 4.

Le PC possède une clé Ed25519 de **commande privilégiée** `PK`, scellée dans son coffre sous une
clé Argon2id(mot de passe maître, sel propre) distincte de la clé du coffre : elle n'est
déchiffrée qu'après ré-authentification, puis effacée.

## 3. Appairage

### 3.1 Invitation (QR)

Le PC tire `token` (16 octets), dépose au relay `token_hash = BLAKE2b-256(key="bastion-invite-v1",
token)` avec une expiration ≤ 300 s (`POST /v1/invites`). Le relay ne connaît jamais `token`.

QR : `bastion://pair/v1#<base64url sans remplissage(PairingInvite)>` : endpoint HTTPS du relay,
empreinte SPKI SHA-256 de son certificat, `DeviceKeys` du PC, `PK.pub`, `token`, `expires_at`,
nom du PC. Le téléphone DOIT refuser une invitation expirée (ou expirant à plus de 330 s), un
endpoint non `https://` ou avec chemin/requête, et une `XKsig` invalide.

### 3.2 Enrôlement

Le téléphone génère `IK`, `XK`, puis envoie `POST /v1/enroll` (TLS 1.3 épinglé) avec
`EnrollRequest` :

* `proof = BLAKE2b-256(key=token, T("bastion-enroll-v1", phone.IK, phone.XK, wg.pub, pc.IK))`
  (`wg.pub` vide tant que WireGuard n'est pas implémenté) ;
* `signature = Sign(phone.IK, T("bastion-enroll-sig-v1", token_hash, IK, XK, XKsig,
  u32be(epoch), wg.pub, proof, u32be(role)))` ;
* `sealed_hello = crypto_box_seal(pc.XK, PairingHello{device, proof, label, max_version})`.

Le relay vérifie `XKsig` et la signature, **consomme l'invitation** (usage unique, supprimée même
si la suite échoue), enregistre le téléphone, le lie au PC invitant et dépose `sealed_hello` dans
la boîte du PC (élément `KIND_PAIRING_HELLO`) — de façon atomique. Il ne peut pas vérifier
`proof`. Le PC ouvre la boîte scellée, vérifie `device_id`, `XKsig` et `proof` contre ses
invitations en cours : seul un détenteur du QR peut produire `proof`.

Le PC lui-même s'enregistre (rôle contrôleur) avec un **jeton d'administration à usage unique**
(en-tête `Bastion-Admin-Token`), créé par le relay intégré ou par `bastion-relay admin-token`.

### 3.3 Code de vérification (SAS)

```
A = pc.IK ‖ pc.XK ; B = phone.IK ‖ phone.XK
sas  = BLAKE2b-256(key=token, T("bastion-sas-v1", min(A,B), max(A,B)))
code = u32be(sas[0..4]) mod 10^6, affiché "123 456"
```

Chaque côté envoie un `PairingConfirm{confirmed}` chiffré (§5) quand l'utilisateur a comparé les
codes. La paire n'est `ACTIVE` qu'une fois **les deux** confirmations reçues ; avant, seul
`PairingConfirm` est accepté. Un refus d'un côté supprime la paire des deux côtés et au relay.

### 3.4 Révocation

`DELETE /v1/peers/{id}` signé : un appareil peut se retirer lui-même, ou retirer un pair auquel il
est lié. Le relay supprime le lien, les messages en attente entre eux, et le téléphone s'il n'a
plus de contrôleur. `Unpair` (commande sensible) fait se retirer le téléphone après avoir
acquitté ; un téléphone qui reçoit trois `401` consécutifs se considère désappairé.

## 4. Clés de session

Pour un message de `S` vers `R` :

```
ss   = X25519(local.XK, peer.XK)
salt = BLAKE2b-256(T("bastion-session-v1", min(S.IK, R.IK), max(S.IK, R.IK)))
key  = BLAKE2b-256(key=ss, T("bastion-session-key-v1", salt, S.IK, S.XK, R.IK, R.XK))
```

Une clé par direction ; `ss` est effacé après dérivation. `KeyRotation` (signée par `IK`, époque
strictement croissante) remplace la `XK` d'un pair ; la rotation périodique automatique n'est pas
encore déclenchée par les applications (voir LIMITATIONS).

## 5. Messages

```
AAD       = T("bastion-env-v1", u32be(version), sender_id, recipient_id, u32be(key_epoch))
signature = Sign(sender.IK, T("bastion-msg-v1", AAD, body))
privileged_signature = Sign(pc.PK, même entrée)        (commandes sensibles)
Envelope.ciphertext = XChaCha20-Poly1305(key, nonce 24 aléatoire, AAD, SignedMessage)
```

`MessageBody` : `protocol_version`, `message_id` (16 octets), `counter` (u64 strictement
croissant par direction, persisté avant envoi), `timestamp_ms`, `ttl_seconds`, `payload`
(`Command`, `Event`, `KeyRotation`, `CommandResult`, `PairingConfirm`).

Commandes PC→téléphone : `Ring`/`StopRing`, `LocateNow`, `SetTrackingMode`, `LostMode`, `Lock`*,
`Wipe`*, `Unpair`*, `RequestStatus`, `CapturePhoto`, `StreamControl` (flux caméra), `AudioControl`
(flux micro), `SetGeofences` (zones), `ScreenControl` (recopie d'écran), `RemoteInput`
(contrôle à distance : toucher/balayage/texte/navigation, coordonnées normalisées 0..1).
Événements téléphone→PC : `LocationReport`, `StatusReport`,
`Alert` (dont SIM, géorepérage, échec de déverrouillage), `PhotoReport`, `MediaFrame` (JPEG),
`AudioChunk` (PCM 16 bits mono), `ScreenFrame` (JPEG, avec `locked`), `LastChanceBeacon`.
(* = contresignature `PK`.) La photo, les trames caméra/audio/écran et les zones empruntent le
même canal chiffré que les commandes (ADR-0022, ADR-0026). `RemoteInput` est envoyé avec un TTL
court (15 s) et sans historique de commande.

## 6. Règles de réception (DOIT, dans cet ordre)

1. Enveloppe ≤ 512 KiO avant tout parsing ; version 1 ; identifiants de 16 octets, nonce de 24.
2. `recipient_id` = soi ; `sender_id` = pair connu ; `key_epoch` = époque connue du pair.
3. Déchiffrement AEAD puis signature `IK` ; une `privileged_signature` présente mais invalide
   rejette le message. Échecs 1–3 : **rejet silencieux** (pas d'oracle).
4. `protocol_version` ≥ version négociée (anti-downgrade) et ≤ version locale.
5. Paire non active : seul `PairingConfirm` est accepté.
6. **Anti-rejeu** : fenêtre glissante de 1024 compteurs (bitmap) + cache des `message_id`
   jusqu'à expiration ; l'état est **persisté avant** tout effet. Un rejeu est ignoré sans réponse.
7. Fraîcheur : `now − 30 s ≤ timestamp + ttl` et `timestamp ≤ now + 30 s`, `ttl` = 60 s par
   défaut, plafonné : 15 min (`Ring`, `StopRing`, `LocateNow`, `RequestStatus`, `CapturePhoto`),
   24 h (`SetTrackingMode`, `LostMode`, `Lock`, `Wipe`, `Unpair`, `PairingConfirm`), 7 jours
   (événements). Une commande périmée reçoit `CommandResult{REJECTED, "expired" | "clock_skew"}`.
8. `Lock`, `Wipe`, `Unpair` sans contresignature `PK` valide :
   `CommandResult{REJECTED, "privileged_signature_required"}`. Commande inconnue : `UNSUPPORTED`.

## 7. Commandes sensibles

Côté PC : ré-saisie du mot de passe maître (Argon2id) pour déchiffrer `PK` à chaque commande ;
pour `Wipe`, deux confirmations, un mot-clé et un **délai de 30 s imposé par le backend** (armement
puis envoi entre 30 s et 5 min). Conséquence : un PC compromis mais verrouillé ne peut ni
effacer, ni verrouiller, ni désappairer le téléphone.

## 8. Transport — API du relay

TLS 1.3 uniquement, certificat auto-signé, client épinglé sur le SHA-256 du SPKI (aucune AC de
confiance ; la signature de la poignée de main TLS reste vérifiée). Corps Protobuf.

| Méthode | Chemin | Auth | Rôle |
|---|---|---|---|
| `GET` | `/v1/health` | — | santé |
| `POST` | `/v1/enroll` | preuve / jeton admin | enrôlement (§3.2) |
| `POST` | `/v1/invites` | contrôleur | dépôt d'un `token_hash` (≤ 8 actifs) |
| `PUT` | `/v1/mailbox/{recipient_hex}` | pair lié | dépôt d'`Envelope` (expéditeur = authentifié) |
| `GET` | `/v1/mailbox[?wait=N]` | pair | ≤ 64 éléments ; attente longue ≤ 30 s (`MailboxBatch`) |
| `POST` | `/v1/mailbox/ack` | pair | suppression (`MailboxAck`) |
| `DELETE` | `/v1/peers/{id_hex}` | pair concerné | révocation |

En-tête `Bastion-Auth: hex(device_id).timestamp_ms.hex(nonce16).base64url(sig)` avec
`sig = Sign(IK, T("bastion-req-v1", méthode, chemin?requête, u64be(timestamp), nonce,
BLAKE2b-256(corps)))`. Refus si horodatage hors ±30 s ou nonce déjà vu (cache 61 s, enregistré
seulement après vérification de la signature). Débit : 30 req/s (rafale 60) par appareil,
2 req/s (rafale 20) par IP pour l'enrôlement ; 2 attentes longues simultanées par appareil.
Erreurs : `RelayError{code}` opaque. Aucune adresse IP n'est journalisée.

## 9. Limites et constantes

| Constante | Valeur |
|---|---|
| Taille max enveloppe / corps de requête | 512 KiO / 513 KiO |
| `sealed_hello` | ≤ 4 KiO |
| TTL invitation | ≤ 300 s |
| Dérive d'horloge tolérée | 30 s |
| Fenêtre anti-rejeu | 1024 |
| TTL boîte aux lettres | 7 jours |
| Quota par destinataire | 512 éléments, 32 MiO |

## 10. Versionnage

`package bastion.v1` ; un changement incompatible crée `bastion.v2`. Les champs ne sont jamais
renumérotés ; les champs retirés sont `reserved`.
