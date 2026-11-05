# AceStream TV pour Android — version 1.2.0

Application **Android native** (Kotlin, vues Android AppCompat + Media3), privée et sans Play Store. L'interface reprend l'identité graphique d'AceStream Web : fond marine sombre, cartes arrondies, contours subtils, boutons violets dégradés et navigation fixe à cinq onglets.

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

