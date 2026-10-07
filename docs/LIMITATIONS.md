# Limites techniques

Bastion est une application **non root, non Device Owner**. Ce document dit ce qu'elle ne peut
pas faire et ce qui n'est **pas encore implémenté**. Aucune fonctionnalité listée ici n'est
simulée dans l'app.

## Pas encore implémenté (v0.1)

| Élément | État | Conséquence |
|---|---|---|
| Tunnel WireGuard (`:core:vpn`, `:feature:vpn`) | non implémenté | Le canal passe par TLS 1.3 épinglé direct vers le relay (déjà prévu par l'architecture §3.4). Sécurité applicative identique ; les métadonnées réseau ne sont pas masquées. |
| Rotation périodique des clés `XK` | réception gérée, émission non déclenchée | Les clés de session restent celles de l'appairage (fenêtre de compromission = durée de l'appairage ; ADR-0007). Ré-appairer renouvelle toutes les clés. |
| Photo anti-intrusion (`CapturePhoto`) | refusée (`UNSUPPORTED`) | — |
| Canal SMS de secours, géorepérage, alerte de changement de SIM, séparation Bluetooth | non implémentés | — |
| Proxy de tuiles via le relay distant | non | Les tuiles OpenStreetMap sont téléchargées par l'application PC (jamais par la page) : OSM voit l'IP du PC et la zone affichée. Désactivable dans Réglages. |
| Coffre PC protégé en plus par le coffre de l'OS | non | Le coffre dépend du seul mot de passe maître (Argon2id 256 Mio). |
| Changement du mot de passe maître | non | Recréer le coffre et ré-appairer. |

## Relay intégré au PC (mode par défaut)

* Le téléphone doit pouvoir joindre le PC : même réseau local, ou redirection de port / DNS
  dynamique depuis Internet. Hors de portée, les commandes attendent dans la boîte aux lettres du
  PC et le téléphone réessaie automatiquement. Pour une protection hors du domicile, utilisez un
  **relay distant** (VPS, serveur) : `bastion-relay`.
* Au premier lancement, Windows demande d'autoriser Bastion sur le réseau : acceptez pour les
  **réseaux privés** uniquement.
* Fermer la fenêtre laisse Bastion dans la zone de notification ; quitter l'application coupe le
  relay intégré.
* Coffre verrouillé (verrouillage automatique) : le PC ne lit plus les messages ; ils l'attendent
  sur le relay jusqu'au déverrouillage.

## Ce que Bastion ne peut pas faire (limites du système)

| Limite | Pourquoi | Ce que fait Bastion à la place |
|---|---|---|
| Empêcher l'extinction du téléphone | Réservé au système | Signal de dernière chance sur `ACTION_SHUTDOWN` quand il est délivré |
| Survivre à une réinitialisation d'usine | Les données de l'app sont effacées | Aucune. Protection d'usine de l'OS |
| Bloquer le mode avion depuis l'écran verrouillé | Réglage système | Signal immédiat (souvent trop tard) ; désactiver les tuiles rapides sur écran verrouillé |
| Fonctionner sans réseau | Pas de canal | File d'attente sur le relay (7 jours) |
| Se cacher | Notification de service obligatoire | Notification discrète et honnête |
| Démarrer avant le premier déverrouillage après redémarrage | Stockage chiffré par l'identifiant de l'utilisateur | Relance après le premier déverrouillage |
| `wipeData` pour un admin d'appareil sur Android 14+ | Restreint aux Device Owners | L'effacement est tenté ; s'il est refusé, le PC reçoit `FAILED wipe_not_permitted` |
| Verrouiller / effacer sans admin d'appareil | API réservée | Le PC reçoit `FAILED device_admin_inactive` ; l'écran Santé propose l'activation |

## GrapheneOS

* Permissions **Réseau** et **Capteurs** révocables par app : l'écran Santé le signale.
* Redémarrage automatique après une période verrouillée : Bastion reprend après le premier
  déverrouillage.
* Recommandé : ports USB « charge uniquement » verrouillé.

## Réglages OS recommandés

1. Code de verrouillage fort (6+ chiffres ou phrase de passe).
2. Délai de verrouillage court.
3. Désactiver l'accès aux tuiles rapides sur l'écran verrouillé.
4. Activer l'administrateur d'appareil Bastion et l'exemption d'optimisation de batterie.
5. Code PIN de SIM.
