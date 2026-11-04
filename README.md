# AceStream Web 0.3.0 — Tableau de bord Docker

Interface Web **auto-hébergée** pour consulter et lire des flux Ace Stream autorisés, avec interface responsive, comptes utilisateurs, favoris privés, recherche et monitoring Gluetun/Proton VPN.

## Architecture et sécurité

Trois services dans **un seul `compose.yaml` Dockge** :

- **Gluetun + Proton WireGuard** : isolant réseau et coupe-circuit pour le **moteur Ace Stream seulement**. API de supervision accessible à AceStream Web sur le bridge Docker privé ; les deux routes GET autorisées sont configurées dans `config/gluetun-control.toml`.
- **Ace Stream Engine** : `jopsis/aceserve:v3.2.17`, réseau partagé avec Gluetun. **Le port 6878 n'est jamais publié sur l'hôte.**
- **AceStream Web** : Flask + SQLite + FFmpeg + HLS.js, sur un réseau Docker bridge **normal, sans VPN**. Le moteur et l'API de supervision sont accessibles seulement dans ce réseau par `http://gluetun:6878` et `http://gluetun:8000`. Seule l'interface Web est publiée sur le port hôte 8087 ; les ports 6878 et 8000 restent privés.

Le serveur Web vérifie `GET /v1/vpn/status` avant d'autoriser la lecture, vérifie de nouveau après le démarrage et un surveillant coupe les flux si le VPN n'est plus confirmé connecté. Cette vérification s'ajoute au pare-feu de Gluetun ; **elle ne remplace pas un audit du coupe-circuit en conditions réelles**. Le badge connecté ne garantit pas le pays du serveur ni l'absence de toute fuite DNS. Les connexions P2P du moteur doivent toujours sortir par Gluetun. La liaison Web ↔ moteur est du HTTP privé au bridge Docker, sans exposer le port 6878 sur l'hôte.

**Important :** une clé privée WireGuard précédemment copiée dans un message doit être **révoquée et renouvelée** sur Proton VPN. Ne publiez jamais le fichier `.env` ni les données SQLite.

## Installation / migration Dockge Enhanced

1. Clonez le dépôt ou récupérez les fichiers à côté du `compose.yaml`. **Ne déployez pas une deuxième stack en parallèle de l'ancienne** (conflits de noms et de ports).
2. Révoquez la clé WireGuard exposée et créez une **nouvelle** configuration dans Proton.
3. Copiez `.env.example` en `.env` et remplissez `WIREGUARD_PRIVATE_KEY` et `SETUP_TOKEN` (longue chaîne aléatoire). Modifiez `WEB_BIND_IP` pour limiter l'accès à votre LAN si nécessaire.
4. Si vous utilisiez `/opt/acestream/dns/resolv.conf`, définissez `ACESTREAM_DNS_FILE=/opt/acestream/dns/resolv.conf` dans `.env`.
5. **Sauvegardez votre stack actuelle**. Dans Dockge, remplacez le compose et ajoutez les nouveaux fichiers `.env` et `config/gluetun-control.toml` dans le dossier de stack.
6. Déployez avec `docker compose pull` puis `docker compose up -d`. Attendez que Gluetun soit healthy. L'interface est accessible sur `http://IP_DU_SERVEUR:8087` (port hôte modifiable).
7. À la première ouverture, renseignez un identifiant, un mot de passe de **10 caractères minimum**, puis la `SETUP_TOKEN` de `.env`. **Aucun identifiant/mot de passe par défaut**.
8. Rendez le package GHCR public dans les paramètres GitHub Packages, si vous voulez pouvoir le télécharger sans connexion.

> Le volume Docker nommé `acestream-data` conserve la base SQLite, les comptes, les favoris, l'historique et la clé de session. Pensez à **le sauvegarder**. La migration ne récupère pas de données du précédent lecteur, qui n'avait pas de comptes ou de favoris.

### Paramètres

| Variable | Rôle | Exemple |
|---|---|---|
| `WIREGUARD_PRIVATE_KEY` | Clé Proton WireGuard **nouvelle** | à conserver en privé |
| `SETUP_TOKEN` | Clé pour initialiser le premier administrateur | chaîne aléatoire d'au moins 16 caractères |
| `WEB_BIND_IP` | Adresse de liaison du port Web | `192.168.1.10` (LAN) ou `127.0.0.1` (proxy) |
| `WEB_PORT` | Port Web publié | `8087` |
| `MAX_STREAMS` | Maximum de lectures simultanées par serveur | `3` |
| `COOKIE_SECURE` | Cookies uniquement HTTPS (avec proxy TLS) | `false` en HTTP LAN, `true` en HTTPS |
| `ACESTREAM_DNS_FILE` | DNS personnalisé du conteneur moteur | `./config/resolv.conf` |

### HTTPS, vie privée et accès distant

Le lecteur Web **ne passe pas par Proton VPN** : son port 8087 sert directement les pages et les segments HLS sur le réseau local, ou à travers un reverse proxy HTTPS. Ce découplage n'empêche pas le moteur Ace Stream de communiquer uniquement via Proton VPN grâce à `network_mode: service:gluetun` et au coupe-circuit.

**Le port 8087 n'est pas HTTPS par lui-même.** `http://IP_DU_SERVEUR:8087` est en clair : utilisez un reverse proxy TLS ou Tailscale/WireGuard pour les connexions sur Internet. Activez `COOKIE_SECURE=true` seulement si l'interface est utilisée **exclusivement via HTTPS**, sinon le navigateur ne renverra pas les cookies de session en HTTP. En HTTPS, l'opérateur réseau voit la connexion vers votre serveur mais pas, en règle générale, les URLs HLS et le contenu de la vidéo chiffrés.

Les requêtes sortantes faites **par l'interface Web** (par exemple le téléchargement de la playlist de recherche externe) utilisent désormais l'IP publique du **serveur**, et non celle de Proton. Si cela pose un problème de confidentialité, il faudra proxyfier **uniquement les recherches externes** ou les désactiver ; le trafic P2P Ace Stream, lui, reste dans Gluetun.

**Ne publiez pas directement le port Web sur Internet.** Pour un accès distant, privilégiez un VPN d'accès comme Tailscale ou un reverse proxy HTTPS protégé. L'API Gluetun port 8000 n'est pas publiée ; seules deux routes de lecture sont permises à l'intérieur de la stack.

## Fonctionnalités

- **Tableau de bord réorganisé** : lecteur vidéo en grand à gauche, recherche et favoris à droite, puis état du VPN, du moteur, des lectures et compte utilisateur en bas. Sur écran étroit, les blocs s'empilent pour garder le lecteur en premier.
- **Lecteur amélioré** : animation de chargement dès le clic sur « Lire », pendant l'initialisation Ace Stream et pendant la mise en mémoire tampon. L'animation disparaît lorsque la lecture démarre, et les erreurs sont affichées plutôt que de laisser un chargement infini.
- **Android paysage** : pendant une lecture active, la vidéo occupe automatiquement toute la fenêtre lors du passage en paysage et revient à sa taille normale en portrait. Le navigateur peut interdire le **plein écran système natif** sans interaction utilisateur : l'application tente le mode natif quand c'est autorisé, et utilise autrement un affichage plein écran dans la page ; le bouton ⛶ permet de demander explicitement le mode natif. La rotation automatique du téléphone doit être activée.
- **Icône Web** : favicon SVG violet/bleu et manifeste de raccourci pour Android. Le nouveau favicon peut nécessiter un rechargement forcé ou la suppression du cache du navigateur.
- **Picture-in-Picture (PiP)** : bouton mini-lecteur ajouté au-dessus de la vidéo. Le lecteur s'enregistre également auprès de Media Session (`enterpictureinpicture`) afin que les navigateurs compatibles puissent déclencher automatiquement le PiP lorsqu'on change d'onglet ou d'application. Une tentative est aussi effectuée au passage en arrière-plan sur Android, sans interrompre la lecture en cas de refus du navigateur. **Ce fonctionnement automatique n'est pas garanti**, car l'autorisation PiP et l'activation utilisateur dépendent du navigateur ; l'accès HTTPS est recommandé, et l'accès HTTP sur IP locale peut empêcher l'API Web PiP de fonctionner.
- **Mots de passe** : minimum de **10 caractères** pour l'initialisation, les nouveaux utilisateurs et les changements de mot de passe ; les mots de passe existants restent valables à la connexion.
- Connexion, première initialisation sécurisée, changement de mot de passe, rôles `admin` / `user` et administration des comptes.
- Favoris **propres à chaque compte** : nom, hash, groupe et notes ; création, édition, suppression.
- Historique de lecture par compte.
- **Recherche multi-sources** : choix `Toutes les sources`, `API Ace Stream` (moteur P2P derrière Gluetun) ou `search-ace.stream` (playlist publique mise en cache **20 minutes**). L'API est interrogée côté serveur, avec prise en charge du nouveau `/search` et de l'ancien `/server/api` lorsqu'ils sont proposés par la version du moteur ; le jeton de l'ancien endpoint n'est jamais transmis au navigateur. Les résultats sont dédoublonnés par identifiant et peuvent inclure le statut de disponibilité estimé par le moteur. Une source indisponible n'empêche pas l'autre de répondre. Le téléchargement de la playlist par AceStream Web **ne passe pas par Proton VPN**. Les résultats ne garantissent pas qu'un flux fonctionne, et la recherche playlist ne couvre pas forcément tous les résultats du site Web.
- Monitoring à partir des API Gluetun (`/v1/vpn/status`, `/v1/publicip/ip`) et Ace Stream (`get_version`). Les valeurs non disponibles sont affichées comme telles — aucun débit ou pays fictif.
- Lecture HLS via FFmpeg ; autorisations sur chaque playlist et chaque segment.
- Hash brut, URI `acestream://` et liens `infohash`, `id`, `content_id`.

### PiP Android : prérequis et limites

- Le PiP automatique lorsque l'onglet devient masqué repose sur le navigateur : en général, l'API Web PiP exige un geste utilisateur, et l'API Media Session n'autorise l'ouverture automatique que dans certaines conditions. Sur certains navigateurs Android, il faudra utiliser le **bouton PiP** avant de quitter l'application, ou passer par les contrôles vidéo natifs.
- Autorisez l'application du navigateur à utiliser Picture-in-Picture dans les paramètres Android. Pour obtenir le meilleur support de l'API Web PiP, utilisez l'interface via **HTTPS** ; `http://IP_DU_SERVEUR:8088` peut ne pas être éligible aux API sécurisées.
- Le bouton PiP ne démarre pas de vidéo : une lecture doit déjà être en cours. Le mode paysage plein écran reste indépendant du PiP.

### Limites connues

- Le lecteur et le suivi des processus sont gérés par **un seul worker Gunicorn** ; ne montez pas le nombre de workers sans externaliser ce gestionnaire.
- FFmpeg conserve pour l'instant la vidéo en `copy`. Certaines sources avec codecs non compatibles HLS/navigateur peuvent échouer.
- La bibliothèque de favoris conserve l'identifiant du flux ; si l'ID change, modifiez le favori. Aucun contrôle de disponibilité continu.
- Le CDN hls.js requiert un accès réseau du navigateur (HLS natif utilisable sur Safari) ; on pourra embarquer hls.js pour une future version entièrement hors ligne.
- Le mode paysage Android agrandit automatiquement le lecteur à toute la surface disponible, mais ne peut pas toujours masquer les barres système/navigation sans geste utilisateur, restriction imposée par le navigateur.
- Le VPN et le moteur doivent être réellement testés dans votre stack : cette version a été testée en local avec des API simulées, pas avec vos identifiants Proton.

## Développement

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
# Tests :
python -m unittest discover -s tests -v
# En local :
DATA_DIR=./data SETUP_TOKEN=une-cle-de-test-de-plus-de-16-caracteres flask --app app run --port 8080
```

L'interface locale démarre, mais **la lecture est bloquée** jusqu'à ce que Gluetun confirme la connexion. C'est volontaire.

Le workflow GitHub Actions publie `ghcr.io/moosketair/acestream-web:latest` sur les pushes `main` et les images taguées `v*`.

L'application ne fournit pas de contenu audiovisuel. Utilisez uniquement des flux que vous êtes autorisé à consulter.
