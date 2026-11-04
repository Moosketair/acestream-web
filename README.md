# AceStream Web

Interface Web pour lire des flux Ace Stream dans le navigateur avec **Flask, FFmpeg et hls.js**.

Ce dépôt fournit un déploiement Docker Compose complet avec **Gluetun / Proton VPN (WireGuard)**, le moteur Ace Stream et l’interface Web. Le moteur partage exclusivement le réseau de Gluetun ; il n’est pas exposé directement sur le serveur.

## Installation (Docker Compose / Dockge Enhanced)

1. Créez une **nouvelle** configuration WireGuard dans Proton VPN.
2. Copiez `.env.example` en `.env` et renseignez `WIREGUARD_PRIVATE_KEY`.
3. Déployez `compose.yaml` dans Dockge, puis ouvrez `http://IP_DU_SERVEUR:8087`.

```bash
cp .env.example .env
# Renseigner votre clé WireGuard dans .env
docker compose pull
docker compose up -d
```

La stack installe 3 conteneurs :
- `gluetun` : VPN Proton WireGuard et pare-feu coupe-circuit.
- `acestream` : moteur `jopsis/aceserve:v3.2.17`, réseau `service:gluetun`.
- `acestream-web` : lecteur HLS et interface Web, accessible sur le réseau local.

Le fichier `.env` n’est **jamais** à envoyer sur GitHub. L’exemple fourni ne contient aucune clé valide.

### Configuration

| Variable | Utilité | Valeur par défaut |
|---|---|---|
| `WIREGUARD_PRIVATE_KEY` | Nouvelle clé privée Proton WireGuard | Obligatoire |
| `WEB_BIND_IP` | IP d’écoute de la page | `0.0.0.0` |
| `WEB_PORT` | Port Web publié | `8087` |
| `ACESTREAM_DNS_FILE` | Fichier DNS optionnel utilisé par le moteur | `./config/resolv.conf` |

Pour reprendre l’ancien fichier DNS : `ACESTREAM_DNS_FILE=/opt/acestream/dns/resolv.conf`.

### Mises à jour

Le workflow GitHub Actions construit et publie automatiquement `ghcr.io/moosketair/acestream-web:latest` à chaque push sur `main`, et des images versionnées pour les tags `v*`.

```bash
docker compose pull
docker compose up -d
```

Après la première publication, rendez le package GHCR **public** sur GitHub si vous souhaitez installer sans authentification.

## Fonctionnalités

- Hash Ace Stream de 40 caractères, URI `acestream://`, URL contenant `infohash`, `id` ou `content_id`.
- Moteur Ace Stream protégé par Gluetun.
- Vidéo MPEG-TS convertie en HLS par FFmpeg ; vidéo recopiée et audio AAC.
- Lecteur navigateur via hls.js (ou HLS natif Safari).
- Une seule session active à la fois, arrêt manuel et suivi de statut.

## Sécurité

**Ne jamais rendre public le fichier `.env`.** Révoquez toute clé WireGuard qui a été exposée. L’interface Web n’a pas d’authentification : ne l’exposez **pas** directement à Internet. Le statut « VPN requis » de l’interface n’est pas une preuve de connexion VPN en temps réel. Le coupe-circuit Gluetun doit rester actif ; un test de connectivité VPN en conditions réelles est nécessaire avant usage.

**Cette application ne contient ni moteur Ace Stream ni contenu audiovisuel.** Le moteur est une image tierce. Utilisez uniquement des flux autorisés.

## Développement

```bash
docker build -t acestream-web:local .
```

Routes : `/`, `/api/health`, `/api/start`, `/api/stop`, `/api/status`, `/hls/<stream_id>/index.m3u8`.

Le projet est une première version : la configuration Docker, le moteur et le VPN doivent être testés sur l’hôte de déploiement.
