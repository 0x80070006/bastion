# Permissions Android

Principe : moindre privilège, demande au moment du besoin (écran Santé). Toute permission du
manifeste DOIT figurer ici.

| Permission | Fonction | Demandée quand | Si refusée |
|---|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | relay, état réseau dans les rapports | installation (normales) | — |
| `CAMERA` | scan du QR d'appairage (CameraX, décodage local ZXing) ; photo anti-intrusion et flux caméra en direct | au scan | collage du lien / action indisponible |
| `RECORD_AUDIO` | écoute audio en direct (micro) | à la première écoute | action indisponible |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | service de protection (type `location`) | installation | — |
| `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | types de service requis pour caméra/micro en arrière-plan (Android 11+) | installation | — |
| `FOREGROUND_SERVICE_SPECIAL_USE` | même service quand la localisation est refusée (Android 14+), sous-type déclaré | installation | — |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | localisation (`LocationManager`, sans Google) | écran Santé | protection sans position |
| `ACCESS_BACKGROUND_LOCATION` | position après redémarrage / appli fermée | écran Santé, après la précédente | suivi tant que le service tourne |
| `POST_NOTIFICATIONS` | notification de service, sonnerie, mode Perdu | écran Santé | service actif mais invisible |
| `RECEIVE_BOOT_COMPLETED` | relance après redémarrage | installation | — |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | exemption Doze (agent antivol qui doit rester joignable) | écran Santé | avertissement Santé |
| `USE_FULL_SCREEN_INTENT` | écran mode Perdu au-dessus de l'écran verrouillé | — | notification simple |
| `VIBRATE` | sonnerie à distance | installation | sonnerie sans vibration |
| `USE_BIOMETRIC` | verrou de l'application | installation | code de l'appareil |
| Admin d'appareil (`BIND_DEVICE_ADMIN`) : `force-lock`, `wipe-data`, `watch-login` | verrouillage, effacement, échecs de déverrouillage | écran Santé | verrouillage / effacement indisponibles |

La lampe torche (`CameraManager.setTorchMode`) et la lecture de l'opérateur SIM
(`TelephonyManager.getSimOperator`, pour la détection de changement de SIM) ne nécessitent pas
de permission.

L'indicateur caméra/micro du système reste visible pendant toute capture : la photo
anti-intrusion, le flux caméra et l'écoute audio ne sont jamais discrets (THREAT_MODEL.md).
Filmer ou enregistrer un tiers peut être encadré par la loi ; l'interface du PC l'indique avant
de démarrer caméra ou micro.
