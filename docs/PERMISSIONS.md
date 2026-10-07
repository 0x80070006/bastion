# Permissions Android

Principe : moindre privilège, demande au moment du besoin (écran Santé). Toute permission du
manifeste DOIT figurer ici.

| Permission | Fonction | Demandée quand | Si refusée |
|---|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | relay, état réseau dans les rapports | installation (normales) | — |
| `CAMERA` | scan du QR d'appairage (CameraX, décodage local ZXing) | au scan | collage du lien |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | service de protection (type `location`) | installation | — |
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

La lampe torche (`CameraManager.setTorchMode`) ne nécessite pas de permission.
