# Chaster — application Android de contrôle d'une serrure connectée

**Statut :** spécification fonctionnelle et technique, version initiale  
**Date de vérification de la documentation :** 14 novembre 2026  
**Cible :** application Android native, logique principale en OCaml, pont JNI vers les API Android  
**Architecture privilégiée :** aucun backend personnel ; communications directes avec Chaster et la serrure

## 1. Objet et périmètre

L'application permet à une personne possédant un compte Chaster de piloter une serrure connectée et de suivre une session de verrouillage Chaster depuis un téléphone Android.

Fonctions recherchées :

1. Authentifier chaque utilisateur sur **son propre compte Chaster**, sans récupérer son mot de passe.
2. Associer une serrure physique au téléphone, initialement par Bluetooth Low Energy (BLE).
3. Consulter les sessions de verrouillage Chaster de l'utilisateur et leur état.
4. Afficher le temps restant **lorsque Chaster autorise son affichage et fournit les données nécessaires**.
5. Créer une session Chaster lorsque l'utilisateur lance le verrouillage de sa serrure, sous réserve des paramètres et des méthodes de verrouillage admis par l'API.
6. Vérifier l'état réel de la serrure et synchroniser son interface avec l'état distant.
7. Autoriser le déverrouillage normal lorsque Chaster confirme que la session le permet, sans supprimer les mécanismes de sortie d'urgence.

Cette application n'est **pas**, par défaut, un *Locking Provider* officiel de Chaster. La Public API donne accès aux verrous du compte ; elle ne transforme pas une serrure Bluetooth quelconque en méthode de verrouillage reconnue par le site. Ces deux niveaux d'intégration ne doivent jamais être confondus.

### 1.1. Principes non négociables

- Ne jamais intégrer de `client_secret` ni de *developer token* global dans une APK distribuée.
- Ne jamais demander les identifiants ou le mot de passe Chaster dans l'application : utiliser le navigateur système.
- Le compte à rebours local est **un affichage**, pas une autorisation de déverrouillage.
- Une réponse HTTP réussie n'atteste pas qu'un moteur BLE a effectivement verrouillé la serrure : attendre un acquittement et, si possible, un capteur d'état.
- Un téléphone éteint, un token expiré, une panne réseau ou une rupture Bluetooth ne doivent pas empêcher une **sortie d'urgence locale et indépendante**. Ne pas utiliser ce montage comme verrou unique d'un dispositif retenant physiquement une personne.
- Ne pas relancer automatiquement une fermeture physique à la reconnexion après une perte d'état ; demander une vérification explicite.

## 2. Architecture

```text
  +------------------------------------------------------------+
  |                       Application Android                  |
  |                                                            |
  |  UI native                                                 |
  |  - connexion / déconnexion Chaster                          |
  |  - choix de la serrure                                     |
  |  - liste des sessions et timer                             |
  |  - commandes verrouiller / déverrouiller                   |
  |  - état de synchronisation et erreurs                      |
  |                                                            |
  |  OCaml                                                     |
  |  - modèle métier                                           |
  |  - client HTTP JSON Chaster                                |
  |  - état des sessions / machine à états                     |
  |  - synchronisation et validation des réponses              |
  |                                                            |
  |  JNI / API Android                                         |
  |  - navigateur + callback OAuth                             |
  |  - Android Keystore et stockage local                      |
  |  - BLE scan / connexion / GATT                             |
  |  - cycle de vie et tâches Android                          |
  +------------------+------------------------+----------------+
                     |                        |
               HTTPS |                        | BLE
                     v                        v
        +------------------------+  +-------------------------+
        | Chaster                |  | Serrure connectée       |
        | - OAuth / OIDC         |  | - état réel             |
        | - API REST             |  | - lock / unlock         |
        | - sessions             |  | - acquittements         |
        +------------------------+  +-------------------------+
```

**Aucun backend Dream n'est requis pour le client BLE ni pour les appels REST ordinaires.** La possibilité d'une authentification OAuth multiutilisateur **sans backend** dépend néanmoins de la prise en charge d'un **client public OAuth avec PKCE** par Chaster. Ce point n'est pas confirmé par la documentation disponible : voir § 3.

## 3. Authentification Chaster

### 3.1. Capacités officiellement documentées

Chaster utilise OAuth 2 *Authorization Code* et annonce la prise en charge d'OpenID Connect.

| Élément | Valeur |
|---|---|
| Autorisation | `https://sso.chaster.app/auth/realms/app/protocol/openid-connect/auth` |
| Échange / renouvellement de token | `https://sso.chaster.app/auth/realms/app/protocol/openid-connect/token` |
| API REST | `https://api.chaster.app` |
| Référence Swagger | `https://api.chaster.app/api` |
| Spécification OpenAPI | `https://api.chaster.app/api-json` |

Le flux documenté par Chaster exige `client_id` **et** `client_secret` pour échanger un code d'autorisation puis renouveler un token. **La prise en charge de PKCE et des clients publics sans secret n'est pas établie dans cette documentation.** OpenID Connect ou l'utilisation d'un serveur compatible Keycloak ne suffisent pas, à eux seuls, à garantir l'autorisation des clients publics.

**Décision bloquante pour une APK multiutilisateur distribuable sans backend :** obtenir de Chaster la confirmation qu'une application Android peut être enregistrée en *public client*, avec `Authorization Code + PKCE (S256)`, sans `client_secret`, et avec une URI de redirection utilisable par Android.

### 3.2. Flux cible sans backend : Authorization Code + PKCE

**À implémenter seulement si Chaster confirme ce mode.**

1. L'application crée un `state` aléatoire cryptographiquement sûr et un `code_verifier` aléatoire de haute entropie.
2. Elle calcule `code_challenge = BASE64URL(SHA256(code_verifier))`, sans remplissage `=`.
3. Elle ouvre le **navigateur système** (par exemple via Custom Tabs), jamais une WebView embarquée pour saisir le mot de passe.
4. L'utilisateur s'authentifie sur le domaine Chaster et accepte les permissions demandées.
5. Chaster redirige vers une URI enregistrée, gérée par l'application (`Android App Link` vérifié ou schéma personnalisé approuvé par le fournisseur).
6. L'application vérifie strictement `state`, la destination de la redirection et la présence éventuelle de `error`.
7. Elle échange le `code` contre les tokens en envoyant `code_verifier`, **sans** `client_secret` dans le scénario *public client*.
8. Elle conserve les tokens de façon protégée sur le téléphone.
9. Elle lit l'identité via `GET /auth/profile`, puis les verrous via `GET /locks`.

Exemple **illustratif et conditionnel** de demande d'autorisation (les valeurs sont à générer et URL-encoder) :

```text
GET https://sso.chaster.app/auth/realms/app/protocol/openid-connect/auth
    ?response_type=code
    &client_id=ANDROID_PUBLIC_CLIENT_ID
    &redirect_uri=REGISTERED_ANDROID_REDIRECT_URI
    &scope=profile%20locks%3Aread%20locks%3Awrite
    &state=RANDOM_STATE
    &code_challenge=BASE64URL_SHA256_OF_VERIFIER
    &code_challenge_method=S256
```

Exemple **conditionnel** d'échange PKCE :

```http
POST /auth/realms/app/protocol/openid-connect/token HTTP/1.1
Host: sso.chaster.app
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&client_id=ANDROID_PUBLIC_CLIENT_ID&code=AUTH_CODE&redirect_uri=REGISTERED_ANDROID_REDIRECT_URI&code_verifier=ORIGINAL_VERIFIER
```

**Attention :** il s'agit du flux standard souhaité, **pas** d'une preuve que Chaster accepte cette requête telle quelle. L'URI de redirection doit être déclarée auprès de Chaster et correspondre exactement à celle utilisée lors de l'autorisation.

### 3.3. Alternative si Chaster exige un `client_secret`

Deux choix, selon le périmètre :

**Application personnelle :** l'utilisateur développeur saisit lui-même son *developer token* dans les paramètres avancés. Ce token ne doit pas être fourni avec l'APK, ni partagé entre utilisateurs. Il accorde des privilèges étendus, dont le scope `developer` : à réserver aux essais ou à un usage personnel assumé.

**Application multiutilisateur :** un composant serveur confidentiel est nécessaire pour l'échange du code et le renouvellement des tokens avec `client_secret`. Il peut être minimal et n'a pas besoin de contrôler le BLE. Le navigateur peut toujours authentifier directement l'utilisateur chez Chaster ; le serveur ne reçoit pas son mot de passe.

**Ne pas contourner cette contrainte** en cachant le `client_secret` dans du code natif OCaml, dans une bibliothèque JNI, par obfuscation, ou avec une clé chiffrée dont la clé de déchiffrement se trouve également dans l'APK.

### 3.4. Scopes à demander

Les permissions sont granulaires (mise à jour de l'API en novembre 2026) :

| Scope | Utilisation |
|---|---|
| `profile` | Identifiant, nom d'utilisateur et avatar ; inclus pour les clients OAuth |
| `locks:read` | Liste et détail des verrous, suivi et historique |
| `locks:write` | Création de verrous et actions autorisées au porteur |
| `shared_locks:read` | Optionnel : consulter les configurations de verrous partagés |
| `keyholder:read` / `keyholder:write` | Optionnel : fonctions de détenteur des clés (*keyholder*), distinctes des droits du porteur |

**MVP :** `profile locks:read locks:write`. Un mode uniquement consultatif peut se limiter à `profile locks:read`. Le droit `locks:write` **n'autorise pas** à passer outre les conditions et permissions propres à un verrou ou à un keyholder.

Ne pas demander le scope `email` : il n'existe plus. Les adresses électroniques et les numéros de téléphone ne sont pas fournis aux applications OAuth ; identifier les comptes par leur identifiant Chaster.

### 3.5. Durée de vie, renouvellement, stockage

La réponse OAuth peut fournir `access_token`, `refresh_token`, `expires_in` et `refresh_expires_in`. Les valeurs d'exemple de la documentation ne doivent pas être codées en dur.

- Ne jamais afficher de token dans les logs, les captures de diagnostic ou les sauvegardes.
- Stocker les secrets chiffrés avec une clé protégée par **Android Keystore** ; exclure les secrets des sauvegardes système si leur restauration ne peut être sécurisée.
- Renouveler avant l'expiration ou à réception d'une erreur d'authentification selon les réponses réelles du serveur.
- Gérer la rotation possible du `refresh_token` : remplacer atomiquement l'ancien token par le nouveau.
- En cas de token invalide / révoqué, arrêter les opérations protégées et redemander une connexion.
- À la déconnexion, supprimer les tokens locaux et dissocier l'état de l'utilisateur courant ; une éventuelle révocation côté serveur dépend des endpoints explicitement pris en charge par Chaster.

## 4. API REST Chaster

### 4.1. Transport

- HTTPS uniquement, avec validation TLS normale ; ne pas désactiver la vérification des certificats.
- Pour chaque requête authentifiée : `Authorization: Bearer <access_token>`.
- JSON pour les charges utiles lorsque le schéma OpenAPI le prévoit.
- Valider les réponses avec des types explicites et tolérer les champs inconnus.
- Gérer `401` (authentification), `403` (permission), `404` (ressource), `429` (limite) et `5xx` (erreur distante) sans supposer que toutes ces réponses signifient la même chose.

Exemple vérifié de requête :

```http
GET /locks HTTP/1.1
Host: api.chaster.app
Authorization: Bearer ACCESS_TOKEN
Accept: application/json
```

### 4.2. Endpoints à intégrer

Les chemins et méthodes ci-dessous figurent dans la référence OpenAPI Chaster consultée. **Les corps JSON, les noms précis des champs de dates et les permissions endpoint par endpoint doivent être générés/vérifiés à partir du schéma OpenAPI avant codage.**

| Méthode et chemin | Usage dans l'application |
|---|---|
| `GET /auth/profile` | Identifier le compte connecté |
| `GET /locks` | Obtenir la liste des verrous du compte |
| `GET /locks/{lockId}` | Lire l'état à jour du verrou sélectionné |
| `POST /locks/search` | Recherche de verrous selon les filtres du schéma |
| `POST /locks` | Créer un verrou suivant une configuration valide |
| `POST /locks/{lockId}/history` | Consulter l'historique |
| `POST /locks/{lockId}/update-time` | Ajuster la durée si l'utilisateur y est autorisé |
| `POST /locks/{lockId}/freeze` | Action de gel si disponible et permise |
| `POST /locks/{lockId}/unlock` | Demande de déverrouillage Chaster **si autorisée** |
| `POST /locks/{lockId}/emergency-unlock` | Déverrouillage d'urgence côté Chaster, distinct de la sécurité physique |
| `GET /locks/{lockId}/combination` | Récupérer une combinaison quand Chaster permet sa révélation |
| `POST /public-locks/{sharedLockId}/create-lock` | Créer une session à partir d'un verrou partagé, si pertinent |

L'API comporte aussi des opérations de *temporary opening*, mais celles-ci doivent être étudiées séparément et **ne sont pas à assimiler à la fin du verrouillage**.

**Attention aux « actions » :** certaines opérations ne sont possibles que pour le porteur, certaines nécessitent un keyholder ou une permission spécifique, et les restrictions métier de Chaster priment sur les scopes OAuth.

### 4.3. Temps restant

Ne pas supposer qu'un verrou possède toujours une date de fin fixe publiquement visible.

L'interface suit :

- l'identifiant du verrou Chaster ;
- son état courant (*actif*, *terminé*, *ouverture temporaire*, etc., mappé depuis les valeurs officielles) ;
- les informations temporelles réellement exposées par la réponse de l'API ;
- la possibilité de consulter ou non le temps restant ;
- la date du dernier rafraîchissement réussi.

Si une échéance est visible et exploitable, afficher un décompte calculé à partir du temps serveur et d'une horloge monotone locale. Corriger l'écart lors de chaque synchronisation. Si le temps est masqué, afficher **« Temps restant non communiqué par Chaster »**, sans reconstruction par des informations censées rester cachées.

Le timer se réactualise **visuellement** chaque seconde dans l'écran ouvert. Ce rafraîchissement ne doit pas engendrer une requête HTTP chaque seconde.

### 4.4. Rate limiting et synchronisation

La documentation mentionne notamment **100 requêtes par minute et par IP** comme limite générale, avec d'éventuelles limites supplémentaires sur certaines opérations. Les en-têtes `X-RateLimit-Limit`, `X-RateLimit-Remaining` et `X-RateLimit-Reset` renseignent le client.

Politique initiale proposée (à adapter après essais) :

- Lecture immédiate à l'ouverture de l'écran et après une action utilisateur.
- Consultation périodique modérée **uniquement lorsque l'application est active**, par exemple toutes les 30 à 60 secondes ; mutualiser les appels entre écrans.
- En cas de `429`, attendre le délai indiqué par les en-têtes/la réponse et appliquer un *backoff*.
- En cas d'échec réseau, conserver le dernier état **marqué comme périmé** ; ne pas convertir une ancienne lecture en autorisation de déverrouillage.
- Éviter la répétition automatique d'un `POST` dont le résultat est inconnu : d'abord relire l'état distant afin de prévenir les doublons.

Sans serveur, l'application n'a pas de webhook Chaster et **ne peut pas garantir une notification immédiate d'une modification de session pendant qu'Android l'a suspendue**. `WorkManager` permet des synchronisations différées, mais son travail périodique a un intervalle minimal de 15 minutes et n'offre pas une échéance exacte.

## 5. Bluetooth : contrat de la serrure

L'API Chaster ne définit **aucun protocole Bluetooth**. Le module BLE devra être adapté au modèle choisi et ne doit pas prétendre que toutes les serrures acceptent des commandes génériques `lock`/`unlock`.

### 5.1. Interface métier recommandée

```ocaml
type physical_lock_state =
  | Locked
  | Unlocked
  | Unknown

type device_error =
  | Disconnected
  | Timeout
  | Authentication_failed
  | Protocol_error of string
  | Hardware_error of string

module type SMART_LOCK = sig
  type device

  val connect : device -> (unit, device_error) result Lwt.t
  val read_state : device -> (physical_lock_state, device_error) result Lwt.t
  val lock : device -> (unit, device_error) result Lwt.t
  val unlock : device -> (unit, device_error) result Lwt.t
end
```

Ces signatures sont une **proposition de conception**, pas des fonctions fournies par Chaster. La couche native Android doit gérer les opérations BLE, les permissions et la reconnexion ; la logique OCaml expose une abstraction stable.

### 5.2. Contraintes matérielles

- Découverte des appareils, association et mémorisation d'un identifiant stable.
- Authentification des commandes par un protocole qui résiste à la relecture (*replay*) et à l'usurpation, si le matériel le permet.
- Vérification de l'état **après** chaque commande, de préférence via retour du capteur mécanique plutôt que simple acquittement GATT.
- Distinguer « commande envoyée », « confirmée par l'appareil » et « fermeture physique vérifiée ».
- Gérer un appareil déconnecté, à batterie faible, déjà actionné par une autre application, ou dans un état inconnu.
- Prévoir un mécanisme de sortie manuelle qui reste fonctionnel sans BLE, sans téléphone et sans compte Chaster.

Sur Android 12+, déclarer les permissions appropriées, notamment `BLUETOOTH_SCAN` pour découvrir les appareils et `BLUETOOTH_CONNECT` pour communiquer. Les demander à l'exécution. Le `CompanionDeviceManager` peut être utile pour l'association. Les détails dépendent de la version Android ciblée et de la manière dont le scan est effectué.

## 6. Modèle des données locales

```ocaml
type remote_lock_status =
  | Active
  | Finished
  | Temporary_opening
  | Unknown_remote of string

type sync_status =
  | Up_to_date of float
  | Stale of float
  | Offline
  | Authentication_required

type linked_session = {
  account_id : string;
  chaster_lock_id : string;
  device_id : string;
  last_remote_status : remote_lock_status;
  last_physical_status : physical_lock_state;
  sync_status : sync_status;
}
```

Les types et les étiquettes sont proposés : ne pas déduire du code ci-dessus l'existence de champs JSON identiques côté Chaster.

Persistences locales minimales : identifiant du compte, identifiant de la serrure, identifiant du verrou Chaster, dernier état observé, horodatage de synchronisation, configuration Bluetooth et secrets OAuth protégés. Éviter d'enregistrer inutilement l'historique détaillé des sessions.

### 6.1. États de l'interface

| État | Présentation / action |
|---|---|
| Déconnecté de Chaster | Connexion requise ; aucune action Chaster possible |
| Compte connecté, appareil absent | Afficher les sessions ; actions BLE désactivées |
| Prêt à verrouiller | Compte, serrure et vérifications initiales valides |
| Création Chaster en cours | Action en attente ; ne pas envoyer de commandes en double |
| Verrou physique confirmé, session active | Afficher statut et timer si autorisé |
| Session active, connexion Chaster perdue | Timer marqué périmé ; aucune conclusion sur la fin de session |
| Session terminée vérifiée, appareil connecté | Déverrouillage normal possible |
| État incohérent | Afficher explicitement l'écart Chaster / serrure et les moyens de récupération |
| Urgence | Procédure de sortie physique indépendante toujours accessible |

## 7. Scénarios de fonctionnement

### 7.1. Premier démarrage

1. Présenter la nécessité d'un compte Chaster et d'une serrure compatible.
2. Connecter l'utilisateur par OAuth, ou importer volontairement un *developer token* en mode personnel.
3. Appeler `GET /auth/profile` pour vérifier les permissions et l'identité.
4. Afficher les verrous obtenus par `GET /locks`.
5. Associer la serrure BLE et vérifier la lecture de son état.
6. Tester la commande de verrouillage **sur un matériel non contraignant**, puis celle de déverrouillage et l'accès d'urgence, avant toute utilisation réelle.

### 7.2. Création d'une session et verrouillage de l'appareil

Deux modes doivent être distingués.

**Mode A — prototype Public API (liaison locale) :**

1. Vérifier que Chaster est accessible, que les tokens fonctionnent et que la serrure répond.
2. Faire choisir à l'utilisateur une configuration de verrou Chaster valide. `POST /locks` doit satisfaire les règles de création et les exigences de combinaison de Chaster ; **une serrure BLE générique ne peut pas être déclarée comme *locking method* via la seule Public API**.
3. Créer le verrou via `POST /locks`, puis enregistrer son identifiant. Ne pas supposer qu'il existe une transaction atomique entre Chaster et le matériel.
4. Envoyer `lock` à la serrure et vérifier son état. Tant que l'état physique n'est pas confirmé, montrer « Verrouillage matériel non confirmé ».
5. Si l'opération BLE échoue, conserver la possibilité d'ouvrir l'appareil et expliquer que la session Chaster a pu démarrer : les procédures de correction doivent respecter les permissions et les possibilités de l'API, sans inventer un endpoint d'annulation.
6. Persister l'association `(compte, lockId, deviceId)` et afficher la session.

Le mode A produit une **association suivie localement**, pas une méthode de verrouillage reconnue par Chaster. Une suppression de l'application ou un changement de téléphone peut faire perdre cette association ; prévoir une restauration par les identifiants Chaster et le réappairage matériel.

**Mode B — Locking Provider officiel :** Chaster permet à l'utilisateur de choisir la serrure dans le parcours de création d'un verrou ; la configuration est enregistrée comme une extension. Ce mode exige l'activation par Chaster et une infrastructure d'extension (§ 10).

### 7.3. Affichage d'une session existante

1. Lire `GET /locks/{lockId}`.
2. Mapper l'état distant sans déduire l'état matériel.
3. Afficher les informations autorisées : compte, statut, temps, dernier contact, appareil associé.
4. Rafraîchir les données périodiquement tant que l'écran est actif.
5. Afficher séparément les deux états : `Chaster : actif` et `Serrure : verrouillée`, par exemple.

### 7.4. Déverrouillage normal

1. Vérifier que l'utilisateur a sélectionné le bon verrou Chaster et la bonne serrure.
2. Reconsulter l'état distant **au moment de la décision**, sauf impossibilité technique explicitement gérée par une procédure de secours.
3. Si Chaster confirme la fin du verrou ou une ouverture temporaire autorisée, demander l'ouverture BLE.
4. Vérifier le retour physique de l'appareil.
5. Conserver une trace locale limitée du succès ou de l'échec de synchronisation.

Ne pas appeler `POST /locks/{lockId}/unlock` pour forcer artificiellement la fin d'une session simplement parce que l'utilisateur souhaite ouvrir son dispositif : cette opération est soumise aux règles de Chaster. Une demande d'« emergency unlock » Chaster est un mécanisme **distinct** d'une sortie d'urgence physique.

En présence d'une urgence réelle, la priorité est le déverrouillage physique local, indépendamment de l'état réseau ou des règles de la session. Le logiciel ne doit jamais désactiver ce secours.

### 7.5. Situations particulières

- **Temps ajouté / gel / modification par un keyholder :** la lecture suivante remplace la projection précédente ; ne jamais utiliser l'ancienne échéance comme preuve de fin.
- **Réouverture temporaire :** gérer séparément son autorisation, sa durée, et le retour au verrouillage ; ne pas assimiler son démarrage à une fin définitive.
- **Application fermée ou processus tué :** actualiser l'état au prochain lancement ; ne pas promettre de déverrouillage Bluetooth ponctuel à l'échéance lorsque le téléphone n'exécute pas l'application.
- **Téléphone hors de portée :** Chaster peut évoluer, mais la serrure reste inaccessible via BLE. Signaler cette limitation.
- **Changement de compte :** ne jamais associer automatiquement les verrous du nouveau compte à une serrure liée à l'ancien.
- **Redémarrage en cours de `POST /locks` :** tenter de retrouver la session distante avant une seconde création ; éviter les doublons.
- **État serveur et matériel contradictoires :** signaler le problème et privilégier la sécurité physique ; ne pas masquer l'incohérence.

## 8. Architecture logicielle OCaml / JNI

Organisation suggérée dans le projet Android natif :

```text
src/
  chaster/
    oauth.ml             # state / PKCE / token lifecycle (si autorisé)
    api.ml               # client REST et erreurs HTTP
    types.ml             # types métier et décodage JSON
    sync.ml              # polling, relecture, traitement des états
    store.ml             # abstraction de persistance
  smart_lock/
    driver.ml            # interface du pilote matériel
    protocol.ml          # protocole BLE propre au modèle
  application/
    state.ml             # machine à états globale
    workflows.ml         # scénarios de connexion / lock / unlock
android/
  ...                    # AndroidManifest, Activity, callbacks, JNI
```

**Frontière recommandée :** OCaml possède la logique métier, la validation et les transitions d'état ; Android fournit le navigateur d'authentification, les URI de retour, Keystore, Bluetooth, notifications et cycle de vie. Le code JNI ne doit pas dupliquer les règles d'autorisation Chaster.

Contrats conceptuels :

```ocaml
module type CHASTER_API = sig
  type client
  type lock
  type error

  val profile : client -> (string, error) result Lwt.t
  val list_locks : client -> (lock list, error) result Lwt.t
  val get_lock : client -> string -> (lock, error) result Lwt.t
  val create_lock : client -> Yojson.Safe.t -> (lock, error) result Lwt.t
end
```

Les opérations de modification doivent être introduites au fil de la validation de leurs schémas OpenAPI, avec des types de paramètres dédiés plutôt qu'un `Yojson.Safe.t` non validé en production.

## 9. Sécurité, fiabilité et confidentialité

### 9.1. Authentification / API

- Utiliser `state` anti-CSRF et PKCE S256 si le fournisseur accepte le client public.
- Interdire les redirections arbitraires ; protéger le callback Android contre les intents inattendus.
- Ne pas envoyer les tokens à d'autres domaines ; ne pas les inclure dans les URI ou les logs.
- Ne pas intégrer de secret serveur statique dans l'application distribuée.
- Séparer l'identité du compte et l'identité du téléphone / de la serrure.
- Vérifier le minimum de scopes ; ne pas confondre scopes et permissions propres au verrou.

### 9.2. Matériel / sûreté

- Définir une procédure de récupération sans cloud, sans application, sans batterie ni Bluetooth.
- Éviter que le micrologiciel verrouille automatiquement un appareil sur une simple heure locale.
- Documenter les limites de fiabilité des capteurs et de la liaison radio.
- Tester perte de réseau, redémarrage du téléphone, expiration des tokens, décharge batterie et panne du moteur.
- Tester d'abord sur un banc, sans dispositif de retenue physique.

### 9.3. Données

- Conserver uniquement ce qui est nécessaire : tokens, association compte/appareil/session et dernier état.
- Prévoir suppression locale de l'association et des secrets.
- Ne pas synchroniser les données des sessions avec un autre service sans nécessité et information claire de l'utilisateur.

## 10. Extension future : Locking Provider Chaster

Chaster définit un *Locking Provider* comme une extension qui remplace la combinaison de la boîte à clés par un appareil connecté. Dans ce mode, le site reconnaît le matériel comme méthode de verrouillage.

**Conditions :**

1. Créer une extension dans l'interface développeur.
2. Contacter Chaster avec le *slug* de l'extension pour demander l'activation du statut *Locking Provider*.
3. En mode personnel, l'extension peut rester en test et être visible uniquement par son auteur et ses testeurs.
4. Fournir une page de configuration capable d'enregistrer l'association avec le dispositif.
5. Héberger des endpoints HTTPS publics pour les callbacks et webhooks, et utiliser les credentials de l'Extensions API exclusivement côté serveur.

Événements cités dans la documentation :

| Événement | Sens |
|---|---|
| `extension_session.created` | Nouvelle session utilisant l'extension |
| `action_log.created` avec `unlocked` | Fin du verrouillage / autorisation de libérer la clé |
| `action_log.created` avec `temporary_opening_opened` | Ouverture temporaire |
| `action_log.created` avec `temporary_opening_locked` ou `temporary_opening_locked_late` | Re-verrouillage après l'ouverture |
| `extension_session.deleted` | Fin du contrôle de la session par cette extension |

La documentation recommande de relire l'état courant depuis les endpoints de session après une déconnexion, et non de se fier uniquement aux événements reçus. Les webhooks peuvent être redélivrés : dédupliquer par identifiant d'événement, et ne pas considérer la réception comme une preuve que la serrure physique a bougé.

**Conséquence architecturale :** ce mode n'est plus le prototype « APK seule ». Un petit backend public, par exemple Dream, devient nécessaire selon le contrat actuel de l'Extensions API ; le BLE reste piloté par l'application Android ou par une passerelle matérielle distincte. Sans passerelle ou téléphone connecté, le backend ne peut pas atteindre une serrure purement BLE.

## 11. Vérifications préalables à la réalisation

### Blocantes

- [ ] Chaster accepte-t-il d'enregistrer une application Android comme **client OAuth public sans `client_secret`** ?
- [ ] Accepte-t-il `Authorization Code + PKCE (S256)` pour l'autorisation **et** le renouvellement ?
- [ ] Quelles URI de redirection Android peuvent être enregistrées ?
- [ ] Le modèle de serrure permet-il un verrouillage et un déverrouillage authentifiés, un retour d'état fiable et une sortie indépendante ?
- [ ] La méthode de création `POST /locks` et ses obligations de combinaison conviennent-elles à ce prototype sans *Locking Provider* ?

### Validation API et UX

- [ ] Télécharger la version courante d'OpenAPI pour générer les types de requêtes et réponses.
- [ ] Vérifier les valeurs de statut, les champs de temps restant, les droits d'affichage et le traitement des sessions gelées.
- [ ] Vérifier les comportements des endpoints de création, déverrouillage et urgence sur un compte de test.
- [ ] Définir la politique lorsque le verrou Chaster existe mais que le BLE échoue.
- [ ] Définir le comportement de la réassociation après réinstallation / nouveau téléphone.
- [ ] Tester les restrictions d'exécution Android lorsque l'application est en arrière-plan.

## 12. Tests d'acceptation

1. **Authentification :** utilisateur A peut se connecter et voir uniquement les verrous correspondant aux permissions de son compte ; utilisateur B ne récupère pas ses données après changement de compte.
2. **PKCE :** `state` erroné, `code_verifier` invalide ou callback non sollicité conduisent à un refus.
3. **Scopes :** un token `locks:read` permet la consultation et échoue proprement lors d'une modification.
4. **Token expiré :** renouvellement ou reconnexion sans perte ni divulgation de secrets.
5. **Bluetooth :** une commande n'est annoncée réussie qu'après confirmation de l'appareil.
6. **Création partielle :** une session créée avec échec du verrouillage physique est affichée comme incohérente et récupérable.
7. **Modification temporelle :** une prolongation Chaster actualise le timer sans autoriser de déverrouillage anticipé.
8. **Temps masqué :** l'application n'affiche pas d'échéance reconstruite.
9. **Mode hors-ligne :** une dernière lecture ancienne est identifiée comme périmée ; aucune ouverture automatique basée sur le timer n'est déclenchée.
10. **Urgence :** la sortie physique fonctionne indépendamment de Chaster, d'Android et du BLE.
11. **Limites API :** la réponse `429` ne déclenche ni boucle rapide ni répétition incontrôlée d'un `POST`.
12. **Redémarrage :** l'association est reconstruite sans créer de session dupliquée.

## 13. Ordre de développement

**Phase 1 — prototype personnel :** token développeur saisi par l'utilisateur, lecture de `GET /auth/profile`, `GET /locks`, `GET /locks/{lockId}` et affichage du timer disponible. Aucune commande sur matériel de retenue.

**Phase 2 — serrure BLE :** appairage, lecture d'état, fermeture et ouverture sur banc d'essai, traitement des pannes, interface OCaml indépendante du périphérique.

**Phase 3 — comptes OAuth :** après validation du mode PKCE public avec Chaster, navigateur système, callback, gestion des tokens. Si le client public n'est pas autorisé, isoler un service d'authentification minimal au lieu d'embarquer le secret.

**Phase 4 — association de session :** création via API selon les schémas réels, lien `(compte, verrou Chaster, serrure)`, synchronisation, états incohérents et tests d'urgence.

**Phase 5 — intégration Chaster native, facultative :** demander le statut *Locking Provider* et introduire un backend pour les sessions d'extension et les webhooks.

## 14. Références

Documentation officielle consultée le 14 novembre 2026 :

- [Chaster — API, introduction](https://docs.chaster.app/api/basics/introduction/)
- [Chaster — Getting started](https://docs.chaster.app/api/basics/getting-started/)
- [Chaster — OAuth 2](https://docs.chaster.app/api/public-api/oauth-2/)
- [Chaster — Scopes OAuth](https://docs.chaster.app/api/reference/scopes/)
- [Chaster — Mise à jour des scopes](https://docs.chaster.app/api/reference/oauth-scopes-update/)
- [Chaster — Endpoints et rate limits](https://docs.chaster.app/api/public-api/endpoints/)
- [Chaster — Référence OpenAPI](https://api.chaster.app/api)
- [Chaster — Schéma OpenAPI JSON](https://api.chaster.app/api-json)
- [Chaster — Locking Providers](https://docs.chaster.app/api/extensions-api/locking-providers/)
- [Chaster — Extensions API, authentification](https://docs.chaster.app/api/extensions-api/authentication/)
- [Chaster — Webhooks](https://docs.chaster.app/api/extensions-api/create-your-extension/webhooks/)
- [Android — Permissions Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android — WorkManager, tâches périodiques](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

Les endpoints et comportements évoluent : Chaster indique que son API est en bêta. Le schéma OpenAPI courant reste la référence pour les signatures, champs et codes de retour.
