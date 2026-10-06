# Modèle de menaces (STRIDE)

Version : jalon 1. Révisé à chaque jalon ; les tests de sécurité du jalon 7 référencent les
identifiants `T-xx` ci-dessous.

## 1. Actifs

| Id | Actif | Où |
|---|---|---|
| A1 | Position courante et historique du téléphone | téléphone, transit, PC |
| A2 | Capacité de commander le téléphone (sonner, verrouiller, **effacer**) | PC |
| A3 | Clés d'identité (`IK`, `XK`), clé privilégiée `PK`, clés WireGuard | téléphone, PC |
| A4 | Photos anti-intrusion | téléphone, transit, PC |
| A5 | Disponibilité de la protection (le téléphone reste joignable) | téléphone, relay |
| A6 | Métadonnées (qui parle à qui, quand, depuis quelle IP) | relay, réseau |
| A7 | Trafic VPN de l'utilisateur | tunnel, relay |

## 2. Frontières de confiance

```
[Téléphone] ──(1) réseau hostile──► [Relay] ◄──(2) réseau hostile── [PC]
     │                                  │
  (3) accès physique              (4) opérateur / hébergeur du relay
```

Le relay est **semi-fiable** : on lui confie la disponibilité, jamais la confidentialité ni
l'intégrité des messages.

## 3. Adversaires

| Id | Adversaire | Capacités | Hors périmètre |
|---|---|---|---|
| ADV1 | Voleur opportuniste | possession physique, pas de code, pas d'outils | — |
| ADV2 | Voleur avancé | éteint, mode avion depuis l'écran verrouillé, retire la SIM, cage de Faraday, réinitialisation d'usine | exploitation 0-day du bootloader / SoC |
| ADV3 | Attaquant réseau | MITM, rejeu, injection, coupure, DNS | casser libsodium |
| ADV4 | Relay compromis | lit/modifie/supprime/rejoue tout ce que le relay voit | — |
| ADV5 | PC compromis | malware utilisateur sur le PC | malware avec keylogger pendant la saisie du mot de passe maître (atténué, non empêché) |
| ADV6 | QR intercepté | photo/capture du QR d'appairage | — |

## 4. Analyse STRIDE

### Spoofing (usurpation)

| Id | Menace | Atténuation |
|---|---|---|
| T-01 | Un tiers se fait passer pour le PC et envoie des commandes | Signature Ed25519 de chaque message, clé appairée ; contresignature `PK` pour les commandes sensibles |
| T-02 | Un tiers se fait passer pour le relay (MITM TLS) | SPKI du relay épinglée dans le QR ; aucune autorité de certification n'est de confiance |
| T-03 | Attaquant ayant le QR (ADV6) appaire son propre téléphone/PC | Jeton à usage unique, TTL ≤ 5 min, **SAS 6 chiffres confirmé des deux côtés** ; le vrai téléphone échoue visiblement |
| T-04 | Un pair se fait passer pour un autre auprès du relay | Requêtes signées par `IK`, horodatées, nonce unique |

### Tampering (altération)

| Id | Menace | Atténuation |
|---|---|---|
| T-05 | Modification d'une enveloppe en transit / par le relay | AEAD XChaCha20-Poly1305 + en-tête en AAD + signature interne |
| T-06 | Redirection d'un message vers un autre destinataire | `recipient_id` dans l'AAD **et** dans l'entrée signée |
| T-07 | Altération de la config WireGuard livrée à l'enrôlement | Canal TLS épinglé ; empreinte de la clé WireGuard du relay présente dans le QR |
| T-08 | Altération du stockage local (téléphone) | SQLCipher + clés Keystore ; données d'appli inaccessibles sans déverrouillage (FBE) |

### Repudiation (répudiation)

| Id | Menace | Atténuation |
|---|---|---|
| T-09 | « Je n'ai jamais envoyé cet effacement » | Journal de sécurité local des deux côtés, messages signés conservés ; la répudiation vis-à-vis de tiers n'est pas un objectif |

### Information disclosure (fuite)

| Id | Menace | Atténuation |
|---|---|---|
| T-10 | Le relay lit positions/photos/commandes (ADV4) | Chiffrement E2E ; le relay ne stocke que des blobs, TTL court |
| T-11 | Métadonnées révélées par le relay (A6) | Pas de log d'IP par défaut ; tailles de blobs non masquées (limite assumée, padding par paliers envisagé au jalon 7) |
| T-12 | IP du PC fuitée aux serveurs de tuiles | Tuiles via proxy du relay ; CSP interdit toute autre origine |
| T-13 | Secrets dans les logs / le presse-papiers / les captures d'écran | Logs sans données sensibles (lint) ; `FLAG_SECURE` ; presse-papiers effacé automatiquement (30 s) ; aperçu masqué dans les récents |
| T-14 | Extraction des données par sauvegarde ADB/cloud | `allowBackup=false`, `dataExtractionRules` vides |
| T-15 | Compromission future des clés ⇒ déchiffrement du passé | Rotation `XK` tous les 7 jours + effacement des anciennes clés ; pas de PFS par message en v1 (ADR-0007) |
| T-16 | Photo anti-intrusion montrant le propriétaire | Photo prise uniquement après N échecs, stockée chiffrée, purgée selon rétention |

### Denial of service

| Id | Menace | Atténuation |
|---|---|---|
| T-17 | Voleur éteint / met en mode avion / retire la SIM (ADV2) | **Impossible à empêcher sans privilèges OS** (voir LIMITATIONS). Beacon de dernière chance, alerte SIM, détection coupure, file hors-ligne côté PC |
| T-18 | Réinitialisation d'usine | Impossible à survivre ; seule la protection OS (FRP / GrapheneOS) s'applique. Documenté |
| T-19 | Désinstallation / révocation de permissions | Admin d'appareil (désinstallation nécessite retrait préalable), alerte « protection affaiblie » |
| T-20 | Saturation du relay | Rate limiting par pair et par IP, taille max, quotas de boîte aux lettres, TTL |
| T-21 | Relay supprime les messages (ADV4) | Non empêchable ; détecté via accusés `CommandResult` et horodatage « dernière vue » ; topologie B possible |
| T-22 | Message malformé faisant planter le parseur | Taille vérifiée avant parsing, limites Protobuf, **fuzzing** du parseur (jalon 2) |

### Elevation of privilege

| Id | Menace | Atténuation |
|---|---|---|
| T-23 | Malware sur le PC (ADV5) envoie un effacement | `PK` sous Argon2id(mot de passe maître), ré-auth + délai 30 s + mot-clé ; le téléphone exige la contresignature |
| T-24 | Une autre app Android pilote Bastion | Aucun composant exporté inutile ; services protégés par permission signature ; `DeviceAdminReceiver` protégé par `BIND_DEVICE_ADMIN` |
| T-25 | Rejeu d'une ancienne commande (ADV3/ADV4) | Compteur monotone + fenêtre + `message_id` + TTL + horodatage |
| T-26 | Downgrade de version de protocole | Version minimale négociée et persistée ; `protocol_version` dans le corps signé et `version` dans l'AAD |
| T-27 | Désynchronisation d'horloge exploitée pour rejouer | Tolérance ±30 s ; l'anti-rejeu repose sur le compteur, pas seulement sur l'horloge |
| T-28 | Voleur utilise l'app Bastion déverrouillée pour désactiver la protection | Verrou d'app (BiometricPrompt + code) ; désactiver la protection exige l'authentification et génère une alerte |
| T-29 | Dépendance compromise (supply chain) | Versions épinglées, lockfiles, `cargo audit`, `pnpm audit`, OWASP dependency-check, SBOM, build reproductible |

## 5. Risques résiduels acceptés

* Un voleur avancé qui éteint le téléphone immédiatement ne laisse que la dernière position
  connue (T-17).
* Le relay voit qui communique avec qui, quand, et la taille des messages (T-11).
* Un PC compromis **déverrouillé** (keylogger) dispose des mêmes pouvoirs que le propriétaire.
* Pas de confidentialité persistante par message en v1 (T-15).

## 6. Cas de test de sécurité dérivés (jalons 2 et 7)

Rejeu exact · rejeu hors fenêtre · message modifié (chaque champ) · mauvais destinataire ·
mauvaise époque · signature d'un autre pair · contresignature absente pour `Wipe` ·
jeton d'invitation expiré · jeton réutilisé · `protocol_version` inférieure · horloge +/- 31 s ·
enveloppe > 512 KiB · Protobuf profondément imbriqué / champs géants · SAS différents.
