# Capacités des appareils reconnus par Lockink 1.5.7

Inventaire statique de l'APK dont le SHA-256 est consigné dans [la spécification Bluetooth](PROTOCOLE-BLUETOOTH.md). Il décrit ce que le client propose pour chaque nom BLE, pas des essais des capacités physiques. Dix-sept noms sont reconnus. Les noms commerciaux ne sont pas déduits des images : `ToyAdapter` affiche le nom et l'image fournis par le modèle serveur.

## Matrice par modèle

Tous ces appareils ont un chemin de connexion et de lecture de batterie. Les termes « stimulation électrique », « vibration », « rotation » et « aspiration » correspondent aux champs et libellés du client ; unités et mécanismes physiques restent à confirmer.

Dans le tableau, **modes communs** désigne les onglets classique, secousse du téléphone, réaction au son, mode manuel et gestion de formes d'onde. Les modes classique, berserk et aléatoire sont présents pour les profils correspondants. « Son » signifie mesure du microphone du téléphone, pas reconnaissance de commandes vocales.

| Nom BLE exact | Fonctions proposées / commandes reliées | Particularités et limites |
| --- | --- | --- |
| `AA-A1001` | Serrure : ouvrir/fermer ; minuterie ; restriction géographique ; stimulation électrique | Format A de dix octets. Onglet électrique dédié. |
| `AA-A1002` | Ouverture ; minuterie et restriction géographique dans l'app | Format B court. Le handler n'envoie pas de fermeture et ne programme pas le timer dans le périphérique comme pour `1001`. Commande électrique ancienne présente dans des callbacks partagés, mais aucun onglet électrique dédié pour ce modèle. |
| `AA-A1003` | Stimulation électrique manuelle, programmée dans le temps, formes d'onde prédéfinies | Pas d'interface de serrure dans l'écran affecté à ce modèle. |
| `AA-A1004` | Vibration et stimulation électrique ; modes communs | Paramètres d'intensité et de fréquence ; modes berserk/aléatoire. |
| `AA-A1005` | Rotation et stimulation électrique ; modes communs | Commandes de rotation avec direction ; format C court. Le code emploie aussi des identifiants UI « vibrate », mais les valeurs BLE sont nommées `spin*`. |
| `AA-A1006` | Vibration et stimulation électrique ; modes communs | Même écran et mêmes contrôles conditionnels que `1004`. |
| `AA-A1007` | Aspiration et stimulation électrique ; modes communs | Le client remplace explicitement le libellé vibration par « Suction Mode ». Cela établit l'intention de l'UI, pas la technique d'aspiration du matériel. |
| `AA-A1008` | Contrôle de vibration/mouvement ; modes communs ; lumière | Sélecteur de motifs électriques masqué en mode classique ; canal électrique masqué en modes secousse, son et manuel. Ne pas conclure à une capacité électrique sur la seule présence de callbacks communs. |
| `AA-A1009` | Contrôle de vibration/mouvement ; modes communs ; lumière | Mêmes conditions explicites que `1008`. |
| `AA-A1010` | Modes communs, avec interface électrique partiellement masquée | Pas de bouton lumière. Canal électrique masqué en secousse/son/manuel ; le sélecteur électrique classique n'est pas masqué par la condition appliquée à `1008/1009/1016/1017`. Capacités physiques ambiguës. |
| `AA-A1011` | Modes communs, avec interface électrique partiellement masquée | Mêmes conditions explicites que `1010`. |
| `AA-A1012` | Serrure ; minuterie ; stimulation électrique ; modes communs | Dans le mode classique, les contrôles de vibration sont cachés par le layout et les commandes classiques utilisées passent des paramètres de vibration nuls. Ne pas lui attribuer la vibration du seul fait des classes partagées. |
| `AA_A1013` | Stimulation électrique sur canaux A/B ; intensité ; playlists de motifs ; lecture temporisée/aléatoire ; pilotage par secousse et son | Nom avec underscore. Profil AES et jeton BLE ; pas d'onglet serrure ou de mode manuel/gestion de formes d'onde identique aux autres écrans. Ne pas confondre durée de lecture et minuterie de serrure. |
| `AA-A1014` | Vibration et stimulation électrique ; modes communs | Même écran et mêmes contrôles conditionnels que `1004`. |
| `AA-A1015` | Serrure ; minuterie ; vibration et stimulation électrique ; modes communs | Contrôles de vibration visibles dans le layout classique, contrairement à `1012`. |
| `AA-A1016` | Contrôle de vibration/mouvement ; modes communs ; lumière | Mêmes conditions explicites que `1008`. |
| `AA-A1017` | Contrôle de vibration/mouvement ; modes communs ; lumière | Mêmes conditions explicites que `1008`. |

Un « mode commun » est une fonction de l'application, parfois calculée sur le téléphone ou orchestrée par le serveur. Sa présence n'implique pas une commande BLE distincte, ni un capteur dans l'objet. La restriction géographique repose sur la localisation du téléphone et la logique applicative/serveur ; aucun GPS de serrure ou opcode BLE géographique n'a été identifié.

## Sources de la matrice

Les chemins ci-dessous sont relatifs à `analysis/lockink-157/jadx/sources/com/weather/life/`, sauf les layouts qui sont dans `analysis/lockink-157/apktool/res/layout/`.

| Point établi | Sources |
| --- | --- |
| Dix-sept noms et routage vers les écrans | `ble/BindBlePresenter.java`, constantes `DEVICE_NAME_*` ; `fragment/HomeFragment.java`, `onItemClick` |
| Noms et images non figés localement | `adapter/ToyAdapter.java`, `convert` : `DeviceBean.getName()` et `getImg()` |
| Serrure, minuterie et localisation de `1001/1002` | `activity/DeviceControlActivity.java`, `getDataSuccess`, `onLockChange`, `onTimeLockChange` ; `ElectricShockFragment` seulement si `DEVICE_NAME_ONE` |
| Électrique manuel, temporel et motifs de `1003` | `activity/DeviceControlActivity2.java`, `getDataSuccess` : `TimeElectricFragment`, `ManualElectricFragment`, `PresetWaveFragment` |
| Groupe `1004/1006/1007/1008/1009/1010/1011/1014/1016/1017` | `activity/DeviceControlActivity4.java`, `getDataSuccess` ; fragments `ClassicModeFragment2`, `ShakeFragment2`, `VoiceControlFragment2`, `ManualElectricFragment3`, `WaveformManageFragment2` |
| Lumière seulement sur `1008/1009/1016/1017` dans ce groupe | `DeviceControlActivity4.getDataSuccess`, condition d'affichage de `iv_light` |
| Aspiration `1007` | `ClassicModeFragment2.initUI`, `ShakeFragment2.initUI`, `VoiceControlFragment2.initUI` ; ressources `values-en/strings.xml`, `shunxi_mode` / `shunxi_mode_prefix` |
| Masquage électrique | `ClassicModeFragment2.initUI` et listener aléatoire ; `ShakeFragment2.initUI`, `VoiceControlFragment2.initUI`, `ManualElectricFragment3.initUI` |
| Rotation `1005` | `activity/DeviceControlActivity5.java`, `onCupData` ; `ble/BindBlePresenter.java`, `writeCommand4(mac,...)`, `writeCommand7` |
| Différence `1012` / `1015` | `DeviceControlActivity6/8.getDataSuccess` ; fragments `ClassicModeFragment4/6` ; layouts `fragment_classic_mode4.xml` et `fragment_classic_mode6.xml` : visibilité des parents des contrôles de vibration |
| Canaux A/B et playlists `1013` | `DeviceControlActivity7.sendCupData5` : canaux 1 et 2 ; `ClassicModeFragment5`, `ShakeFragment5`, `VoiceControlFragment5` ; réception `onQiuiData` puis `writeCommand8` |
| Batterie et commandes binaires | `BindDeviceActivity.initData`, `MainActivity.initView`, `ble/BindBlePresenter.java` ; détails et preuves smali dans la spécification du protocole |

`DeviceControlActivity3` et ses anciens fragments existent également dans l'APK, mais aucun des dix-sept noms n'est dirigé vers cet écran par les routages examinés (`HomeFragment`, `SelectRoleActivity`, `SelectRoleActivity2`). Cela ne justifie pas d'inventer un dix-huitième appareil.

## Fonctions de service distinctes des capacités de l'objet

L'application possède aussi des rôles de contrôle, permissions révocables, invitations, contrôle à distance, historique et règles de verrouillage. Ces fonctions s'appuient sur ses API et WebSockets, avec des disponibilités conditionnées par les rôles, les données serveur et l'écran utilisé. Elles ne prouvent pas un stockage de ces règles dans les appareils.

Pour la future application, choisir le sous-ensemble utile après identification du nom BLE réel de la serrure. La confirmation matérielle de la matrice suivra [la procédure de validation](VALIDATION-BLUETOOTH.md).
