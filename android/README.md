# AceStream TV pour Android — version 1.4.0

Application **Android native** (Kotlin, vues Android AppCompat + Media3), privée et sans Play Store. L'interface reprend l'identité graphique d'AceStream Web : fond marine sombre, cartes arrondies, contours subtils, boutons violets dégradés et navigation fixe à quatre onglets et raccourci Paramètres en haut à droite.

## Nouveautés de la version 1.2

- **Écran de connexion** repensé avec logo original, formulaire sombre, adresse serveur configurable et mémorisée.
- **Tableau de bord** avec lecteur Media3 natif, lecture manuelle, boutons plein écran et PiP, accès rapide Recherche et Favoris, panneaux d'état VPN/engine/diffusion (valeurs récupérées depuis le serveur).
- **Recherche** : quatre sources, affichage en cartes homogènes, effacement des résultats, actions Lire et Favori.
- **Favoris / Historique** avec les données réelles de ton compte AceStream Web.
- **Paramètres** : changement de serveur, informations système et changement de compte.
- **Chromecast** (Google Cast) conservé : bouton dans la barre d'en-tête et lien HLS temporaire généré par le serveur.
- **Icône Android adaptative** avec dégradé violet/bleu, icône monochrome Android 13+, écran de démarrage natif Android 12+, et logo de l'interface Web réutilisé dans les écrans.
- Les médias affichés dans la maquette étaient des **illustrations de design** : l'API de recherche ne fournit pas de miniatures d'événements, de résolution ou de débit vérifiés. L'application montre les vrais noms, catégories et flux, sans inventer ces métadonnées.

## Compiler et télécharger

Lancer ou consulter [Build native Android APK](https://github.com/Moosketair/acestream-web/actions/workflows/android-apk.yml). Après une compilation réussie, télécharger l'artefact `AceStream-TV-Android-APK`, décompresser le ZIP, puis installer `AceStream-TV-Android.apk`.

L'APK GitHub est **une version debug signée automatiquement**, réservée à un usage privé. Si Android refuse une mise à jour à cause d'une signature différente, désinstalle l'ancienne application avant d'installer la nouvelle. Les comptes et favoris restent sur le serveur ; les préférences locales, dont l'adresse enregistrée, seront effacées à la désinstallation.

L'application requiert Android 8 ou plus récent, l'accès réseau à ton serveur AceStream Web, et Google Play Services pour Google Cast. Aucune modification Docker n'est requise. La vidéo P2P reste traitée sur Debian derrière Gluetun/ProtonVPN ; le téléphone lit du HLS.


## Corrections Android 1.3

- **Barre inférieure** : gestion des marges système Android 15+ (barre d'état et barre de navigation) via `WindowInsetsCompat`. La navigation de l'application reste au-dessus des gestes Android.
- **Recherche** : le dernier mot-clé, la source et les résultats sont conservés lorsque tu reviens de la lecture (et entre les démarrages si tu te reconnectes au même compte sur le même serveur). Le scroll de la liste est mémorisé pendant les changements d'onglet. Le bouton Effacer supprime également le cache local.
- **Lecture** : le panneau « Prêt pour la lecture » et son cercle Play disparaissent dès le lancement réel du flux. Le grand bouton Play ne s'étire plus sur toute la largeur du lecteur.
- **Rotation et plein écran** : quand un flux est en lecture et le téléphone tourne en paysage, affichage vidéo immersif avec la barre d'état et les gestes Android masqués. Un bouton **Retour au menu** sur l'image et le bouton Retour du téléphone permettent de revenir à l'interface. La rotation revient au portrait via le capteur, même si le verrouillage de rotation global est actif.
- **Veille** : tant qu'une lecture locale est demandée (même pendant le chargement/buffering), le téléphone ne se met pas en veille ; cette protection est levée à l'arrêt ou lors d'une pause.
- **Picture-in-Picture automatique** : option activée par défaut, réglable depuis **Paramètres → Mini-lecteur automatique**. En quittant avec Accueil/Applications récentes, Android 12+ utilise `setAutoEnterEnabled` ; Android 8–11 utilise `onUserLeaveHint`. Le bouton PiP manuel fonctionne indépendamment de ce réglage. Il faut autoriser l'affichage PiP pour l'application dans les paramètres Android.
- **Compte et confidentialité** : le cache des recherches est local et associé au serveur + utilisateur ; il est effacé lors du changement d'adresse ou de compte.
- Chromecast, compte, favoris et serveur Docker demeurent inchangés.

**Validation** : la compilation GitHub vérifie les ressources et le code Kotlin. Un test sur appareil réel reste nécessaire pour confirmer les transitions de rotation, la navigation gestuelle et la compatibilité PiP selon la version de One UI.

## Nouveautés Android 1.4

- **Empreinte digitale (facultative)** : sur l'écran de connexion, cocher « Activer la connexion par empreinte » puis effectuer une connexion classique réussie pour enregistrer les identifiants. Après confirmation biométrique, les identifiants sont chiffrés avec AES-GCM par une clé du Keystore Android exigeant **BIOMETRIC_STRONG à chaque usage**. Le mot de passe n'est **jamais enregistré en clair**, ni dans les sources ni dans les préférences Android. Au prochain lancement, toucher « Se connecter avec mon empreinte » pour déverrouiller les identifiants et se connecter à l'API existante. L'adresse doit être en **HTTPS**. En cas de suppression/changement des empreintes enregistrées, de changement de protection d'écran ou de réinstallation, refaire une connexion par mot de passe. Une option **Désactiver l'empreinte** dans Paramètres efface les données chiffrées et la clé. Le stockage local ne remplace pas le contrôle du serveur ; une révocation du compte/mot de passe impose une connexion normale.
- **Animation de chargement** : anneau indéterminé violet au centre de la vidéo pendant la création du flux et durant le buffering HLS, disparaissant au passage de Media3 à l'état READY. Une erreur affiche le message et arrête l'animation. Les retours tardifs d'anciennes demandes de lecture sont ignorés.
- **Grand bouton Play corrigé** : cercle réduit de 74dp à 58dp et triangle vectoriel centré dans un vrai conteneur circulaire, avec correction optique de 3dp.
- **Interface simplifiée** : supprimer « Diffuser sur Chromecast » sous le lecteur (le bouton Cast de l'en-tête reste opérationnel), et supprimer l'onglet Paramètres de la barre inférieure (accès conservé par l'icône ⚙ en haut). Barre inférieure à **Accueil, Recherche, Favoris, Historique**.
- Aucun changement sur le serveur, la recherche et les favoris. Version installable via le workflow GitHub Actions.

