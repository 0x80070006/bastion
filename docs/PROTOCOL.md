# Protocole Bastion — v1

Statut : **brouillon normatif** (jalon 1). Les définitions Protobuf font foi :
[`protocol/proto/bastion/v1`](../protocol/proto/bastion/v1). Toute évolution passe par
`buf breaking` et un ADR.

Mots-clés DOIT / NE DOIT PAS / DEVRAIT au sens RFC 2119.

## 1. Primitives (libsodium uniquement)

| Usage | Primitive libsodium |
|---|---|
| Signature d'identité | Ed25519 (`crypto_sign_detached`) |
| Accord de clé | X25519 (`crypto_scalarmult`) |
| Chiffrement authentifié | XChaCha20-Poly1305 IETF (`crypto_aead_xchacha20poly1305_ietf_*`) |
| Hachage / KDF / MAC | BLAKE2b (`crypto_generichash`, avec clé pour MAC/KDF) |
| Dérivation de mot de passe | Argon2id (`crypto_pwhash`, `OPSLIMIT_MODERATE`, `MEMLIMIT_MODERATE`) |
| Aléa | `randombytes_buf` |

Aucune construction cryptographique maison au-delà de la composition documentée ici.
Les chaînes de contexte (`"bastion-…-v1"`) sont des constantes ASCII sans terminateur.

## 2. Identités

Chaque appareil génère localement :

* `IK` : paire Ed25519 (identité, signature) ;
* `XK` : paire X25519 (accord de clé), **signée** par `IK` (`XKsig = Sign(IK, "bastion-xk-v1" ‖ XK.pub ‖ epoch_u32be)`) ;
* `device_id` : 16 octets = `BLAKE2b-128(IK.pub)`.

**Empreinte affichée** : `BLAKE2b-256(IK.pub ‖ XK.pub)` en hexadécimal, groupes de 4, 8
premiers groupes (police monospace).

Les clés privées ne quittent jamais l'appareil (Keystore Android / coffre OS + Argon2id sur PC).

Le PC possède en plus une clé **Ed25519 de commande privilégiée** `PK`, stockée uniquement sous
la clé dérivée du mot de passe maître (jamais dans le coffre OS seul). Elle n'est déverrouillée
qu'après ré-authentification et sert à contresigner les commandes sensibles (§7).

## 3. Appairage

### 3.1 Invitation (QR)

Le PC demande au relay une invitation : il génère `token` (16 octets aléatoires, 128 bits) et
envoie au relay `token_hash = BLAKE2b-256(key="bastion-invite-v1", token)` avec un TTL ≤ 300 s.
Le relay ne connaît jamais `token`.

Le QR contient l'URI `bastion://pair/v1#<base64url(PairingInvite)>` (le fragment n'est jamais
transmis par un navigateur). `PairingInvite` contient : version, endpoints du relay (HTTPS +
WireGuard), empreinte SPKI SHA-256 du certificat TLS du relay, clé publique WireGuard du relay,
`IK.pub`/`XK.pub`/`XKsig`/`PK.pub` du PC, `token`, `expires_at`.

### 3.2 Enrôlement

1. Le téléphone vérifie `expires_at`, la signature `XKsig`, génère `IK`, `XK`, la paire WireGuard.
2. Il se connecte au relay en TLS 1.3 **en épinglant** l'empreinte SPKI de l'invitation.
3. `POST /v1/enroll` avec `EnrollRequest` :
   * `token_hash`, clés publiques du téléphone, clé publique WireGuard ;
   * `proof = BLAKE2b-256(key=token, "bastion-enroll-v1" ‖ phone.IK.pub ‖ phone.XK.pub ‖ wg.pub ‖ pc.IK.pub)` ;
   * `signature = Sign(phone.IK, "bastion-enroll-sig-v1" ‖ token_hash ‖ IK.pub ‖ XK.pub ‖ XKsig ‖ u32be(epoch) ‖ wg.pub ‖ proof ‖ u32be(role))`.
     La signature porte toujours sur une transcription explicite, jamais sur une sérialisation
     Protobuf (non canonique).
4. Le relay vérifie que `token_hash` existe, n'est pas expiré, **l'invalide atomiquement**
   (usage unique, même en cas d'échec ultérieur), vérifie la signature, attribue une IP dans
   `10.77.0.0/24` (IPv4) / `fd77::/64` (IPv6), ajoute le pair WireGuard et renvoie
   `EnrollResponse` (config de pair, `peer_id` du PC).
   Le relay **ne peut pas** vérifier `proof` (il n'a pas `token`) ; il la transmet.
5. Le téléphone dépose dans la boîte du PC un `PairingHello` scellé
   (`crypto_box_seal` vers `pc.XK.pub`) contenant ses clés publiques et `proof`.
6. Le PC vérifie `proof` avec `token` : seul quelqu'un ayant lu le QR peut la produire.

### 3.3 Code de vérification (SAS anti-MITM)

```
sas_input = BLAKE2b-256(key = token,
            "bastion-sas-v1" ‖ min(A,B) ‖ max(A,B))       où A = pc.IK.pub‖pc.XK.pub, B = phone.IK.pub‖phone.XK.pub
code      = (u32be(sas_input[0..4]) mod 1 000 000) affiché "123 456"
```

Les deux écrans affichent le code ; l'utilisateur confirme sur **les deux** appareils. Tant
qu'il n'est pas confirmé, la paire est `PENDING` et aucune commande n'est acceptée. Le biais
du modulo (2³²/10⁶) est négligeable (< 2,4·10⁻⁴ relatif). Un mode avancé affiche l'empreinte
complète.

### 3.4 Révocation

`Unpair` (commande sensible) ou action locale. Chaque côté efface les clés de session de la paire
et notifie le relay (`DELETE /v1/peers/{id}` signé), qui retire le pair WireGuard et vide la
boîte aux lettres.

## 4. Clés de session

Pour une paire (a, b) et une époque `e` (u32) :

```
ss       = X25519(a.XK.priv, b.XK.pub)
salt     = BLAKE2b-256("bastion-session-v1" ‖ min(a.IK.pub,b.IK.pub) ‖ max(...) ‖ u32be(e))
k_a→b    = BLAKE2b-256(key = ss, salt ‖ "a2b" ‖ a.IK.pub)
k_b→a    = BLAKE2b-256(key = ss, salt ‖ "a2b" ‖ b.IK.pub)
```

Une clé par direction. `ss` est effacé de la mémoire après dérivation.

**Rotation** : chaque appareil publie périodiquement (défaut 7 jours) une nouvelle `XK` signée
(`KeyRotation`), qui incrémente l'époque. Les anciennes clés sont conservées au plus 24 h pour
les messages en vol puis détruites. Cela borne la fenêtre de compromission ; v1 n'offre pas de
confidentialité persistante par message (pas de double ratchet), choix documenté dans
`DECISIONS.md` (ADR-0007).

## 5. Format des messages

### 5.1 Enveloppe (visible du relay)

```protobuf
message Envelope {
  uint32 version      = 1;  // version de l'enveloppe = 1
  bytes  sender_id    = 2;  // 16 octets
  bytes  recipient_id = 3;  // 16 octets
  uint32 key_epoch    = 4;
  bytes  nonce        = 5;  // 24 octets aléatoires
  bytes  ciphertext   = 6;  // XChaCha20-Poly1305(SignedMessage)
}
```

`AAD = "bastion-env-v1" ‖ u32be(version) ‖ sender_id ‖ recipient_id ‖ u32be(key_epoch)`.

### 5.2 Message signé (chiffré)

```protobuf
message SignedMessage {
  bytes body      = 1;  // MessageBody sérialisé
  bytes signature = 2;  // Ed25519(sender.IK, "bastion-msg-v1" ‖ AAD ‖ body)
  bytes privileged_signature = 3;  // optionnelle : Ed25519(pc.PK, même entrée)
}
```

Signer puis chiffrer, en liant la signature à l'en-tête : un message ne peut être ni redirigé
vers un autre destinataire ni rejoué sous une autre époque.

### 5.3 Corps

`MessageBody` contient `protocol_version`, `message_id` (16 octets aléatoires),
`counter` (u64 monotone par direction), `timestamp_ms`, `ttl_seconds`, et un `oneof payload`
(commandes PC→téléphone, événements téléphone→PC). Voir `messages.proto`.

## 6. Règles de réception (DOIT, dans cet ordre)

1. Taille de l'enveloppe ≤ **512 KiB** ; sinon rejet avant tout parsing.
2. `version` connue ; `recipient_id` = soi ; `sender_id` = pair appairé `ACTIVE`.
3. Époque connue (courante ou précédente non expirée).
4. Déchiffrement AEAD ; échec ⇒ rejet silencieux + compteur d'anomalies.
5. Vérification de `signature` avec `IK` du pair.
6. Parsing de `body` (limites Protobuf : profondeur, taille de champs `bytes` ≤ 384 KiB).
7. `protocol_version` ≥ version minimale négociée à l'appairage (**anti-downgrade**) et ≤ version locale.
8. Fraîcheur : `now - skew ≤ timestamp + ttl` et `timestamp ≤ now + skew`, `skew = 30 s`.
   `ttl_seconds` par défaut **60** ; plafonné par type (file hors-ligne explicite) :

   | Type | TTL max |
   |---|---|
   | `Ring`, `LocateNow`, `CapturePhoto` | 15 min |
   | `SetTrackingMode`, `LostMode`, `Lock` | 24 h |
   | `Wipe` | 24 h (+ contresignature `PK` obligatoire) |
   | événements téléphone → PC | 7 jours |

9. **Anti-rejeu** : fenêtre glissante de 1024 sur `counter` (bitmap, comme IPsec/WireGuard) +
   cache des `message_id` vus jusqu'à expiration de leur TTL. Le compteur le plus haut est
   persisté **avant** l'exécution de la commande.
10. Commandes sensibles : vérification de `privileged_signature` avec `pc.PK` (§7).

Une commande rejetée produit un `CommandResult` d'erreur (sauf rejet aux étapes 1–5, silencieux
pour ne pas servir d'oracle).

## 7. Commandes sensibles

`Lock`, `Wipe`, `Unpair` DOIVENT porter `privileged_signature`. Côté PC, `PK` n'est déchiffrée
qu'après ré-authentification (mot de passe maître ou TOTP) et effacée de la mémoire après usage.
`Wipe` exige en plus, côté PC, deux confirmations, un délai annulable de 30 s et la saisie d'un
mot-clé ; côté téléphone, un délai de grâce configurable (défaut 0) avant exécution.

Conséquence : un PC compromis **mais verrouillé** (malware sans mot de passe maître) ne peut
ni effacer, ni verrouiller, ni désappairer le téléphone.

## 8. Transport

* **Relay HTTP API** (TLS 1.3, SPKI épinglé ; ou HTTP dans le tunnel WireGuard) :

  | Méthode | Chemin | Auth | Rôle |
  |---|---|---|---|
  | `GET` | `/v1/health` | — | santé |
  | `POST` | `/v1/invites` | contrôleur | dépôt d'un `token_hash` |
  | `POST` | `/v1/enroll` | preuve d'invitation | enrôlement |
  | `PUT` | `/v1/mailbox/{recipient_id}` | pair | dépôt d'`Envelope` |
  | `GET` | `/v1/mailbox` | pair | récupération (≤ 64 enveloppes) |
  | `POST` | `/v1/mailbox/ack` | pair | acquittement (suppression) |
  | `GET` | `/v1/ws` | pair | notification « nouveau courrier » (WebSocket) |
  | `DELETE` | `/v1/peers/{id}` | pair concerné | révocation |
  | `GET` | `/v1/tiles/{source}/{z}/{x}/{y}` | pair | proxy de tuiles |

* **Authentification des requêtes** : en-tête `Bastion-Auth` =
  `device_id.timestamp_ms.nonce.signature` avec
  `signature = Ed25519(IK, "bastion-req-v1" ‖ method ‖ path ‖ timestamp ‖ nonce ‖ BLAKE2b-256(body))`.
  Le relay refuse un horodatage hors ±30 s et un nonce déjà vu.
* **Contrôleur initial** : le script d'amorçage du relay produit un jeton d'administration à
  usage unique pour enrôler le premier PC (rôle `controller`).
* **Canal SMS de secours** (optionnel, désactivé par défaut) : seulement `Ring` et `Lock`,
  format binaire compact signé (`type ‖ counter ‖ timestamp ‖ PK-sig`), encodé base64url ;
  authentifié mais non chiffré (le contenu n'est pas secret). Détails au jalon 5.

## 9. Limites et constantes

| Constante | Valeur |
|---|---|
| Taille max enveloppe | 512 KiB |
| Taille max champ `bytes` | 384 KiB |
| TTL invitation | ≤ 300 s |
| Dérive d'horloge tolérée | 30 s |
| Fenêtre anti-rejeu | 1024 |
| TTL boîte aux lettres relay | 7 jours (événements) |
| Rotation `XK` | 7 jours |
| Débit relay par pair | 30 req/s, rafale 60 |

## 10. Versionnage

* `package bastion.v1` ; un changement incompatible crée `bastion.v2`.
* Les champs ne sont jamais renumérotés ; les champs retirés sont `reserved`.
* `protocol_version` (entier) est incrémenté à chaque ajout de type de message ; un récepteur
  ignore les `payload` inconnus en renvoyant `CommandResult{status=UNSUPPORTED}`.
