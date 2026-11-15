# Validation matérielle du protocole Lockink

Cette procédure prépare la confrontation de [l'analyse statique](PROTOCOLE-BLUETOOTH.md) aux échanges de votre serrure. Elle n'a pas encore été exécutée. Aucun appareil n'a été piloté pendant l'analyse de l'APK.

## Préparer une session identifiable

Consigner avant la capture : modèle commercial, nom BLE exact, firmware s'il est affiché, modèle du téléphone, version Android, version de l'application installée, état de connexion et d'association initial. Vérifier que l'application utilisée correspond à la version 1.5.7 analysée ; une autre version constitue une nouvelle source à comparer. Ne pas réinitialiser ni dissocier la serrure pour commencer cette validation.

Les outils possibles sont les journaux Bluetooth Android et [Wireshark](https://www.wireshark.org/docs/dfref/b/btatt.html). Aucun outil maison de capture ou de parsing n'est nécessaire. ADB est facultatif pour exporter un rapport ; sa présence n'a pas été requise pour l'analyse statique.

Placer les captures et rapports dans `analysis/captures/`, déjà exclu de Git. Un bugreport peut contenir d'autres données du téléphone : conserver l'original localement et n'extraire que les échanges nécessaires au partage. Utiliser un pseudonyme stable pour les MAC et identifiants dans le compte-rendu, sans modifier les octets des paquets conservés comme preuves.

## Capturer depuis Android

1. Dans les options développeur, activer le journal **Bluetooth HCI snoop log**. Choisir le mode complet si le téléphone le propose. Suivre les indications du téléphone pour réactiver le Bluetooth si nécessaire.
2. Démarrer la collecte avant la connexion à la serrure. Ouvrir l'application de référence et effectuer un seul scénario à la fois. Noter pour chaque action l'heure du téléphone avec le fuseau et l'état mécanique réellement observé.
3. Exporter un rapport de bug depuis les options développeur, ou utiliser depuis la racine du workspace, avec un téléphone déjà autorisé pour ADB :

   ```bash
   mkdir -p analysis/captures
   adb bugreport analysis/captures/session-lockink.zip
   unzip -l analysis/captures/session-lockink.zip
   ```

4. Repérer dans la liste un fichier `btsnoop_hci.log` ou une variante de journal Bluetooth, puis extraire **l'entrée effectivement présente**, dans le workspace. Le chemin varie selon Android et le fabricant ; ne pas supposer que `/sdcard/btsnoop_hci.log` est accessible. Si aucun journal exploitable n'est inclus, consigner le modèle et les options disponibles avant de choisir une autre méthode.
5. Ouvrir le journal avec Wireshark et confirmer que les paquets ATT contiennent bien les valeurs. Une capture filtrée sans payload ne permet pas de vérifier les commandes.
6. Désactiver la collecte HCI une fois les scénarios terminés. Conserver pour chaque capture le SHA-256 et le journal des actions.

Sources de la procédure : [options développeur Android](https://developer.android.com/studio/debug/dev-options.html), [vérification et débogage Bluetooth AOSP](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging). Le chiffrement du lien BLE n'empêche généralement pas de lire les valeurs ATT dans le journal HCI du téléphone ; le chiffrement applicatif AES de la famille E reste à décoder séparément.

## Retrouver les échanges utiles

Commencer avec le filtre Wireshark `btatt`, puis isoler la connexion de la serrure. Ne pas réutiliser un handle ATT d'une autre session ou d'un autre modèle sans vérifier son association aux UUID.

| Échange | Vérification |
| --- | --- |
| Annonces BLE | Nom exact, adresse, RSSI ; correspondance avec le modèle physique |
| Découverte GATT | Service `8ac0` et caractéristiques `8ac1`/`8ac2`, ou service `8653000a-…` et caractéristiques `…000b`/`…000c` |
| Propriétés | Lecture, écriture avec/sans réponse, notification ; noter les propriétés réellement annoncées |
| CCCD | Écriture de `01 00` sur le descripteur `2902` de la bonne caractéristique |
| Type ATT | `btatt.opcode == 0x12` : Write Request ; `0x52` : Write Command ; `0x1b` : notification |
| Lecture/erreur | `0x0a` : Read Request ; `0x0b` : Read Response ; `0x01` : Error Response |
| Valeurs | Relever `btatt.handle` et `btatt.value`, direction et horodatage |
| Appairage/sécurité | Échanges SMP, chiffrement du lien et éventuelles demandes Android ; leur absence dans une session déjà associée ne suffit pas à conclure |

Référence des champs : [filtres ATT Wireshark](https://www.wireshark.org/docs/dfref/b/btatt.html). L'absence de découverte dans la capture peut venir du cache GATT ; la consigner avant d'en déduire l'absence d'un service.

## Première observation fournie par l'application de test

Le 15 novembre 2026, une session sur `AA-A1002` a rapporté les services ci-dessous. L'adresse MAC n'est pas recopiée ici. Les bits de propriétés Android correspondent à READ `0x02`, WRITE_NO_RESPONSE `0x04`, WRITE `0x08`, NOTIFY `0x10` et INDICATE `0x20`.

| Service | Caractéristique(s) et propriétés observées | Lecture prudente |
| --- | --- | --- |
| `1800` | `2a00: 0x0a` | Nom du périphérique, lecture/écriture GATT standard |
| `1801` | `2a05: 0x20` | Indication standard Service Changed |
| `ee01` | `ee02: 0x10`, `ee03: 0x08` | Canal notification + écriture ; sens inconnu |
| `dd01` | `dd02: 0x10`, `dd03: 0x08` | Canal notification + écriture ; sens inconnu |
| `cc01` | `cc02: 0x10`, `cc03: 0x08` | Canal notification + écriture ; sens inconnu |
| `8ac0` | `8ac2: 0x10`, `8ac1: 0x08` | Service classique identifié par l'APK Lockink |
| `ae00` | `ae01: 0x04`, `ae02: 0x10` | Écriture sans réponse + notifications ; sens inconnu |

Aucune des caractéristiques propriétaires listées n'annonce READ. Les quatre services `ee01`, `dd01`, `cc01` et `ae00` ne figurent pas parmi les UUID employés par l'APK décompilé. Ils pourraient exposer d'autres canaux de commande ou de notification, mais leur présence ne suffit pas à leur attribuer une fonction. L'application de test actuelle ne s'abonne qu'à `8ac2` ; une prochaine capture peut essayer les autres notifications séparément, sans envoyer de commandes sur leurs caractéristiques d'écriture.

La capture rapporte `AA 03` puis `BB 03 XX` (valeurs `XX` observées : `05`, `32`, `3C`, `46`, `50`, `0A`). Le décodeur Lockink interprète le dernier octet comme la batterie en pourcentage, mais ces valeurs varient fortement sur quelques dizaines de secondes ; cette interprétation matérielle reste à confirmer.

Après `AA 01 01 00`, l'écriture GATT est signalée terminée côté Android et l'ouverture mécanique est rapportée, puis le lien tombe avec le statut 147 (`0x93`) entre environ une et huit secondes plus tard. L'application n'appelle pas `disconnect()` après l'ouverture. La capture établit une corrélation temporelle, pas si le firmware coupe le lien, si la radio perd la connexion, ou si une autre condition intervient. Capturer la valeur ATT et les paquets de terminaison avec HCI snoop permettrait de distinguer Write Response, notification et raison de déconnexion.

Pour la famille E uniquement, utiliser une bibliothèque AES existante ou OpenSSL avec le mode et la clé de la spécification. Exemple local reproductible sur une donnée synthétique, **qui n'envoie rien par Bluetooth** :

```bash
python3 -c "import sys; sys.stdout.buffer.write(bytes.fromhex('00e10100650000000000000000000000'))" > analysis/captures/clair-test.bin
openssl enc -aes-128-ecb -K 8F2A1C7B4D5E9063C8B7A6D5E4F32109 -nopad -in analysis/captures/clair-test.bin -out analysis/captures/chiffre-test.bin
od -An -tx1 analysis/captures/chiffre-test.bin
```

Résultat attendu : `a2 7d ff 7d 65 12 5d ef aa 8d 61 3f 15 f8 10 4f`. Pour une notification réelle, extraire les 16 octets de **valeur ATT**, sans les en-têtes HCI/ATT, et employer `openssl enc -d -aes-128-ecb ... -nopad`. Garder côte à côte valeur brute et valeur déchiffrée. Une longueur incorrecte ou une décryption incohérente doit rester un résultat à expliquer ; ne pas ajuster arbitrairement clé, padding ou offsets.

## Scénarios et critères

Commencer par les scénarios de lecture et de reconnexion. Les actions mécaniques ci-dessous se font avec l'application de référence sur la serrure libre, accessible et hors usage sur une personne. Les fonctions électriques/rotation/vibration sont seulement inventoriées à ce stade ; elles ne font pas partie du test de la serrure.

| ID | Action | Données attendues / question résolue |
| --- | --- | --- |
| S1 | Découvrir puis connecter la serrure | Nom BLE, profil GATT, CCCD, type d'écriture et éventuel appairage |
| S2 | Attendre l'initialisation et lire la batterie | A : `AA 09 05 … FF` ; B : `AA 03` ; C : `AA 00` ; E : demande AES de jeton puis commande batterie |
| S3 | Rester connecté sans action pendant au moins trois périodes nominales de 10 s | Présence ou absence de polling, intervalle réel, notifications spontanées ; ne pas attribuer les événements serveur au périphérique |
| S4 | Verrouiller puis déverrouiller une fois | Pour A, `OP=03` puis `02` ; pour B, documenter l'absence éventuelle de commande de fermeture et `AA 01 01 V` à l'ouverture ; relever l'effet physique séparément des messages de l'UI |
| S5 | Déconnecter puis reconnecter | Nouvel abonnement, nouvelle demande de jeton éventuelle, persistance des états ; comparer les deux connexions |
| S6 | Après une connexion réussie, couper uniquement l'accès Internet en conservant le Bluetooth actif | L'application continue-t-elle le polling ? Le clic produit-il une requête BLE ? Distinguer blocage par l'UI/serveur et rejet par le périphérique |
| S7 | Reconnexion sans Internet si l'application la permet | Établissement du lien et batterie ; un échec d'enregistrement serveur ne prouve pas un besoin réseau du firmware |
| S8 | Sur une session séparée, configurer un timer connu via l'UI, si cette fonction est voulue | Comparer durée saisie, valeur serveur si disponible, minutes envoyées, et comportement après déconnexion ; rétablir ensuite la configuration initiale |

S4 et S8 modifient l'état de la serrure ; les mener séparément des lectures. Si un scénario n'est pas accessible dans l'UI du modèle, le marquer « non applicable » plutôt que d'envoyer une commande d'une autre famille. Ne pas tester réinitialisation, mise à jour firmware, dissociation ou commandes inconnues dans cette première validation.

Pour E, comparer entre deux connexions les jetons reçus aux offsets 4–7 et leur réutilisation dans les commandes aux offsets 3–6. Relever les réponses de statut non nul si elles surviennent naturellement, sans provoquer des essais de clés ou de jetons invalides. Leur durée de validité et leur liaison à une connexion restent inconnues tant qu'elles ne sont pas observées.

Un scénario hors ligne effectué avec l'application constructeur mesure d'abord son comportement. Il ne suffit pas à prouver qu'une application indépendante peut ou ne peut pas communiquer : celle-ci nécessitera une phase d'intégration ultérieure et, actuellement, une extension à demander pour `android-ocaml-lib`.

## Format du compte-rendu

Pour chaque échange retenu, renseigner :

| Champ | Contenu à fournir |
| --- | --- |
| Session / scénario | Identifiant, modèle/firmware, application/Android, état réseau et association initiale |
| Preuve | Fichier, SHA-256, numéros des paquets Wireshark |
| Action | Heure, fuseau, action utilisateur et état avant/après |
| GATT | UUID et handle, sens, opcode ATT |
| Valeur brute | Hexadécimal exact et longueur |
| Déchiffrement éventuel | Clair exact, jeton pseudonymisé dans le rapport partagé, octets originaux conservés localement |
| Interprétation | Famille, commande, paramètres, réponse ; source statique comparée |
| Conclusion | Confirmé matériellement, contradictoire, non observé ou non applicable |

Une commande n'est confirmée que lorsque la valeur transmise et son effet sont corrélés. Une ouverture affichée dans l'UI, une soumission Android réussie et une ouverture mécanique sont trois observations distinctes. Mettre à jour la spécification avec les paquets justificatifs et conserver toute contradiction au lieu de la masquer.
