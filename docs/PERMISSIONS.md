# Permissions Android

Principe : moindre privilège, demande **au moment du besoin**, écran d'explication avant chaque
demande système. Ce tableau est la référence ; toute permission ajoutée au manifeste DOIT y figurer
(vérification automatique prévue au jalon 7).

| Permission | Jalon | Fonction | Demandée quand | Si refusée |
|---|---|---|---|---|
| `INTERNET` | 1 | relay, VPN | install (normale) | — |
| `ACCESS_NETWORK_STATE` | 4 | reconnexion, détection de coupure | install (normale) | — |
| `CAMERA` | 4 | scan du QR d'appairage ; photo anti-intrusion (5) | au scan | import par fichier / lien |
| `BIND_VPN_SERVICE` | 4 | service VPN (protège le service, pas une demande) | — | — |
| `FOREGROUND_SERVICE` | 4 | service de protection | install (normale) | — |
| `FOREGROUND_SERVICE_LOCATION` | 5 | type de service `location` | install (normale) | — |
| `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | 4 | type de service du VPN | install (normale) | — |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 5 | localisation | activation de la protection | protection sans position (Santé : rouge) |
| `ACCESS_BACKGROUND_LOCATION` | 5 | position quand l'app est fermée | après la précédente, écran dédié | suivi uniquement service actif |
| `POST_NOTIFICATIONS` | 4 | notification de service, alertes locales | activation de la protection | service toujours actif, notification masquée |
| `RECEIVE_BOOT_COMPLETED` | 5 | relance après redémarrage | install (normale) | — |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 5 | exemption Doze (guidée) | écran Santé | Santé : avertissement |
| `USE_FULL_SCREEN_INTENT` | 5 | écran mode Perdu | activation mode Perdu | notification simple |
| `READ_PHONE_STATE` | 5 | détection de changement de SIM | activation de l'option | option désactivée |
| `BLUETOOTH_CONNECT` | 5 | alerte de séparation | activation de l'option | option désactivée |
| `USE_BIOMETRIC` | 4 | verrou d'app | install (normale) | code seul |
| `VIBRATE` / torche (`CAMERA`) | 5 | alarme | — | alarme sonore seule |
| `RECEIVE_SMS` | 5 | canal SMS de secours (désactivé par défaut) | activation de l'option | option désactivée |
| Admin d'appareil (`BIND_DEVICE_ADMIN`) | 5 | `lockNow`, échecs de déverrouillage, effacement | activation guidée | verrouillage / effacement indisponibles |

Jalon 1 : seul `INTERNET` est déclaré.
