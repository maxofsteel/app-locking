# Console Bluetooth de test

Application Android autonome en Java, package `com.test.test.test`. Elle n'utilise ni OCaml, ni `android-ocaml-lib`, ni serveur. L'écran se limite aux profils identifiés comme serrures, à la batterie et aux commandes ouvrir/fermer établies. Le journal technique est masqué par défaut et reste en mémoire jusqu'à la fermeture de l'application.

## Configuration

`config.json` est local et ignoré par Git. Le fichier `config.example.json` documente les clés requises. Définir les versions explicitement avant le build :

```json
{
  "android_gradle_plugin": "8.13.2",
  "compile_sdk": 36,
  "target_sdk": 36,
  "min_sdk": 21,
  "build_tools": "36.0.0"
}
```

`local.properties` doit également définir `sdk.dir` vers le SDK Android installé. Le wrapper Gradle est inclus. Le client Chaster utilise `HttpsURLConnection` et `org.json`, fournis par Android. L'alternative serait OkHttp/Retrofit avec Moshi ou Gson ; pour ce client REST réduit, les API intégrées évitent une dépendance et une chaîne de sérialisation supplémentaires. L'API GATT Android est nécessaire pour le scan, les notifications et les écritures spécifiques à Android.

## Compiler

Depuis ce dossier :

```sh
./gradlew --no-daemon :app:assembleDebug
```

L'APK debug est produit dans `app/build/outputs/apk/debug/app-debug.apk`.

## Utilisation

1. Installer l'APK sur un téléphone Android avec Bluetooth LE.
2. Autoriser les permissions Bluetooth demandées (sur Android 6 à 11, autoriser la localisation et activer le service système requis pour le scan BLE ; l'application ne lit pas la position).
3. À l'ouverture, l'application recherche pendant 12 secondes la dernière serrure connectée avec la même adresse et le même nom BLE, puis s'y connecte automatiquement. Si elle ne la trouve pas, lancer une recherche manuelle, sélectionner un appareil reconnu, puis se connecter.
4. Une fois la connexion GATT et les notifications prêtes, l'application demande la batterie automatiquement. Pour les appareils AES, elle demande d'abord le jeton requis. L'écran connecté présente le statut du lien, la batterie et les commandes du profil.
5. Les boutons ouvrir/fermer affichés dépendent du profil. `AA-A1002` n'a qu'une commande d'ouverture établie dans l'interface de référence ; elle reprend la trame ordinaire `AA 01 01 00`. Dans l'événement d'ouverture temporisée, l'APK transmet un champ distinct nommé `unlockVoltage`. Son unité et son effet physique (tension ou force d'actionnement) restent à mesurer.

L'adresse et le nom du dernier appareil connecté sont conservés dans les préférences privées de l'application, sur ce téléphone uniquement. `adb install -r` conserve ces données ; désinstaller l'application ou effacer ses données les supprime. Si l'adresse BLE change, une recherche manuelle sera nécessaire. Les profils et commandes sont issus de l'analyse statique de `lockink-157.apk`. Les écritures indiquées comme terminées ne confirment pas l'effet mécanique. Aucune vérification sur serrure physique n'a encore été faite. Tester à proximité, avec le mécanisme dans un état sûr. La console ne lit pas l'état ouvert/fermé. Une connexion active est conservée lors d'un changement temporaire d'application ; elle s'arrête si l'utilisateur la déconnecte, si le périphérique ou Android termine le lien, ou si l'activité est détruite.

## Chaster

`chaster.jeton` est un jeton développeur personnel ignoré par Git. `installtest.sh` l'injecte dans les fichiers privés de l'APK debug, puis l'application le chiffre avec une clé Android Keystore et efface le fichier importé. Le jeton n'est jamais placé dans l'APK ni dans le journal Bluetooth. Ce jeton donne des droits étendus et ne doit pas être distribué.

L'application lit les verrous actifs via l'API Chaster. Elle affiche « déverrouillable » d'après `isReadyToUnlock` et n'affiche l'échéance que si Chaster autorise la consultation du temps. Pour ouvrir un appareil compatible, elle relit le verrou auprès de Chaster juste avant l'ordre BLE. Si l'API est indisponible, si aucun verrou actif n'est chargé, ou si Chaster ne permet pas l'ouverture, l'ordre est refusé. Après une écriture GATT réussie, elle appelle l'endpoint Chaster de déverrouillage ; l'écriture GATT ne confirme pas que le mécanisme a réellement bougé.

Quand aucun verrou n'est actif, l'écran permet de créer un verrou Chaster en saisissant explicitement les durées minimale et maximale ainsi que le code de combinaison. Le code n'est pas mémorisé. Les offres peuvent cibler un username, prérempli avec le dernier keyholder utilisé ; après l'offre, Android propose le partage du lien Chaster si l'API l'a fourni.

La Public API ne reconnaît pas `AA-A1002` comme méthode de verrouillage : le profil n'a qu'une commande d'ouverture connue, sans commande de fermeture ni état mécanique lisible. La création d'une session Chaster et le contrôle local de l'ouverture ne garantissent donc pas que la boîte est physiquement verrouillée. `HttpsURLConnection` et le JSON Android intégré évitent d'ajouter une dépendance REST. Le build n'effectue aucune requête au compte Chaster.
