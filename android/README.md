# AceStream TV — Android natif

Application Android native utilisant ton serveur AceStream Web, sans publication Play Store.

## Version 1.1 — Chromecast et serveur configurable

- Sur la page de **connexion**, modifie l'adresse du serveur. Elle est mémorisée.
- Une fois connecté, touche **⚙ en haut à droite** pour accéder aux paramètres et changer le serveur. Arrête un flux en cours avant de changer d'adresse.
- Le bouton **Cast** en haut à droite détecte les Chromecast / Google TV du même réseau. Sélectionne un appareil, puis lance un flux ; ou connecte le Chromecast pendant une lecture.
- La lecture est envoyée au **Google Cast Default Media Receiver**, sans compte développeur ni application réceptrice personnalisée. C'est un véritable transfert HLS, pas une duplication d'écran.
- Le serveur produit un lien d'accès spécifique, temporaire et révocable via `POST /api/streams/<stream_id>/cast-link`. Il ne communique pas tes cookies ou ton mot de passe au Chromecast.
- Le téléphone et le Chromecast doivent pouvoir joindre **l'adresse du serveur**. Le HTTPS avec un nom de domaine valide est recommandé. Les adresses locales ou DNS privés ne sont pas toujours joignables par les appareils Cast.
- Il faut un appareil avec **Google Play Services**, et un Chromecast/Google TV compatible. Pour un téléviseur Apple TV, Google Cast n'est pas équivalent à AirPlay.
- Le lecteur Media3, les favoris, la recherche et le Picture-in-Picture restent disponibles.

## Installation

Depuis GitHub Actions, ouvre **Build native Android APK**, télécharge l'artifact `AceStream-TV-Android-APK`, décompresse le ZIP et installe l'APK.

L'APK GitHub Actions est un **debug APK signé avec une clé éphémère du runner**. Si tu as installé l'APK précédent et qu'Android refuse cette version avec une erreur de signature, désinstalle l'ancienne APK puis installe celle-ci. Tes favoris et comptes restent sauvegardés sur le serveur, mais les préférences locales seront réinitialisées.

Le streaming reste effectué par AceStream Web + FFmpeg sur le serveur Debian ; aucun VPN P2P ne tourne sur le téléphone.
