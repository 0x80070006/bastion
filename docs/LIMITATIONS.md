# Limites techniques

Bastion est une application **non root, non Device Owner**. Ce document dit ce qu'elle ne peut pas
faire. Il est volontairement direct ; aucune fonctionnalité listée ici n'est simulée dans l'app.

## Ce que Bastion ne peut pas faire

| Limite | Pourquoi | Ce que fait Bastion à la place |
|---|---|---|
| Empêcher l'extinction du téléphone | Réservé au système | Beacon de dernière chance sur `ACTION_SHUTDOWN` quand il est délivré (pas toujours : appui long forcé, batterie retirée) |
| Survivre à une réinitialisation d'usine ou un reflash | Les données de l'app sont effacées | Aucune. Protection d'usine de l'OS (verrou du bootloader, GrapheneOS) |
| Bloquer le mode avion depuis l'écran verrouillé | Réglage système | Détection de la coupure + beacon immédiat ; recommandation de désactiver les tuiles rapides sur écran verrouillé |
| Garantir un fonctionnement identique à une protection niveau OS | Restrictions d'arrière-plan, Doze, permissions révocables | Service au premier plan, exemption d'optimisation de batterie guidée, écran Santé |
| Fonctionner sans réseau | Pas de canal | File hors ligne côté PC ; SMS de secours optionnel |
| Se cacher de l'utilisateur / du voleur | Notification de service obligatoire, et c'est souhaitable | Notification discrète et honnête |
| Prendre une photo sans indicateur | Android/GrapheneOS affichent l'indicateur caméra | Photo prise, indicateur visible ; documenté |
| Effacer une carte SD externe de façon fiable | API limitée | Effacement des données utilisateur via `wipeData` ; le reste dépend de l'OS |
| Exécuter `wipeData` pour un admin d'appareil classique sur les versions récentes | Restreint par Android 14+ pour les admins non-DO selon la politique | Vérifié au jalon 5 sur l'appareil cible ; si refusé, l'action est désactivée et documentée ici |

## GrapheneOS

GrapheneOS durcit encore ces limites, volontairement :

* permissions **Réseau** et **Capteurs** révocables par app : sans elles, Bastion ne peut ni joindre le
  relay ni lire l'accéléromètre — l'écran Santé le signale ;
* redémarrage automatique après une période verrouillée (par défaut) : après ce redémarrage, l'app
  ne peut s'exécuter qu'en mode Direct Boot limité jusqu'au premier déverrouillage ;
* contrôle des ports USB : recommandé (« charge uniquement » quand verrouillé).

## Réglages OS recommandés (guide utilisateur)

1. Code de verrouillage fort (6+ chiffres ou phrase de passe), empreinte en complément.
2. Délai de verrouillage court.
3. Désactiver l'accès aux tuiles rapides / au panneau de notifications sur l'écran verrouillé.
4. GrapheneOS : ports USB « charge uniquement » verrouillé ; redémarrage auto activé.
5. Code PIN de SIM.
