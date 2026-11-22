# App-locking

Projet de contrôle local d'une serrure connectée Android. Le dépôt rassemble l'analyse de l'application de référence Lockink 1.5.7 et un APK Android autonome de diagnostic Bluetooth et Chaster.

## État du projet

Le code Android actuellement compilable est dans [`android-test/`](android-test/). C'est une application Java de test, package `com.test.test.test`.

## Fonctionnalités du APK de test

- Recherche et connexion BLE aux noms de serrures reconnus dans l'APK de référence; mémorisation locale du dernier appareil et reconnexion automatique.
- Lecture automatique de la batterie après connexion.
- Commandes proposées selon le profil analysé. Pour `AA-A1002`, seule l'ouverture est établie; le protocole ne fournit pas d'état ouvert/fermé fiable.
- Lecture des verrous Chaster actifs et de leur état. L'échéance n'est affichée que si Chaster autorise l'affichage du temps.
- Ouverture BLE conditionnée à une vérification Chaster récente. Après une écriture GATT réussie, l'application demande le déverrouillage Chaster.
- Création d'un verrou Chaster avec durées et code de combinaison saisis dans l'application, puis offre à un keyholder et partage Android si Chaster renvoie un lien.

La Public API Chaster ne transforme pas une serrure BLE générique en méthode de verrouillage reconnue. `AA-A1002` ne possède pas de commande de fermeture connue et ne confirme pas son état mécanique. Le suivi Chaster et le contrôle de l'ouverture dans l'application ne prouvent donc pas que la boîte est physiquement verrouillée. L'écriture GATT indique seulement qu'Android a accepté la commande.

## Préparer et compiler l'application Android

Les versions Android Gradle Plugin, SDK de compilation/cible/minimale et Build Tools sont à définir explicitement dans `android-test/config.json`, à partir de [`android-test/config.example.json`](android-test/config.example.json). Définir aussi `sdk.dir` dans `android-test/local.properties`. Ces fichiers locaux sont ignorés par Git. La configuration détaillée et les instructions de compilation sont dans [`android-test/README.md`](android-test/README.md).

L'icône est dessinée dans [`assets/lock-cylinder.svg`](assets/lock-cylinder.svg). Après modification du dessin, générer les calques adaptatifs, les PNG legacy et les aperçus avec `bash scripts/generate-android-icons.sh` (Inkscape et ImageMagick requis), puis compiler. Android et les lanceurs des fabricants appliquent leur propre masque; l'application ne peut pas le désactiver. Les icônes adaptatives gardent l'illustration dans la zone sûre centrale et remplissent le fond, tandis que les PNG legacy ciblent les anciennes versions.

L'aperçu carré est [`assets/lock-cylinder-preview.png`](assets/lock-cylinder-preview.png); l'aperçu avec un masque circulaire est [`assets/lock-cylinder-adaptive-preview.png`](assets/lock-cylinder-adaptive-preview.png). Le générateur utilise Inkscape pour exporter le groupe SVG séparément et ImageMagick pour le centrer dans la zone sûre; cette approche garde un SVG unique au lieu de dupliquer les tracés de l'illustration.

Depuis la racine du dépôt, après configuration :

```sh
bash android-test/gradlew --no-daemon -p android-test :app:assembleDebug
```

L'APK de débogage est produit dans `android-test/app/build/outputs/apk/debug/app-debug.apk`.

Pour installer sur un seul téléphone connecté et autorisé par ADB :

```sh
bash installtest.sh
```

Le script installe l'APK existant; il ne le compile pas. Il importe aussi `chaster.jeton` s'il existe à la racine. L'application chiffre ce jeton avec une clé Android Keystore au démarrage et efface le fichier d'import privé. Ne jamais intégrer ni distribuer un jeton développeur. Sans ce fichier, les fonctions Chaster restent indisponibles.

Le client Chaster utilise `HttpsURLConnection` et `org.json`, fournis par Android. OkHttp/Retrofit avec Moshi ou Gson sont des alternatives connues; elles ne sont pas ajoutées pour éviter des dépendances supplémentaires pour ce client REST limité. L'API GATT Android est utilisée directement pour le Bluetooth.

## Analyse de l'APK de référence

[`lockink-157.apk`](lockink-157.apk) est la source de l'analyse statique. La spécification détaillée du protocole est dans [`notes/PROTOCOLE-BLUETOOTH.md`](notes/PROTOCOLE-BLUETOOTH.md); les capacités visibles par modèle sont recensées dans [`notes/CAPACITES-APPAREILS.md`](notes/CAPACITES-APPAREILS.md), et les essais matériels prévus dans [`notes/VALIDATION-BLUETOOTH.md`](notes/VALIDATION-BLUETOOTH.md).

La reproduction de la décompilation requiert JADX 1.5.6 et Apktool 3.0.3, ainsi que quatre chemins locaux (APK, deux outils, nouveau répertoire de sortie). Voir la procédure et les empreintes attendues dans la spécification du protocole, puis lancer :

```sh
bash scripts/analyse-apk.sh APK JADX_JAR APKTOOL_JAR NOUVEAU_REPERTOIRE_SORTIE
```

Le script refuse les chemins hors workspace et une sortie qui existe déjà. Les outils, caches et résultats d'analyse ne sont pas versionnés.

## Documentation

- [`chaster.md`](chaster.md) : conception et limites de l'intégration Chaster.
- [`PROJECT_MAP.md`](PROJECT_MAP.md) : carte initiale du dépôt et état de l'architecture OCaml prévue.

Les conclusions issues de l'APK sont des observations statiques, pas des garanties de compatibilité matérielle. Le protocole n'a pas été confirmé sur la serrure réelle.
