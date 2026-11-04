"""Search public playlist metadata from the documented search-ace.stream feed.

No user-provided URL is ever fetched; searches happen against a capped, cached
playlist to avoid open-proxy/SSRF and overloading the remote service.
"""
import re
import threading
import time
import unicodedata
from urllib.parse import parse_qs, urlsplit
import requests

PLAYLIST_URL = "https://search-ace.stream/playlist"
MAX_BYTES = 6_000_000
MAX_ITEMS = 20_000
CACHE_SECONDS = 20 * 60
HASH = re.compile(r"^[0-9a-fA-F]{40}$")
ATTR = re.compile(r'([\w-]+)="([^"]*)"')


def extract_hash(link):
    try:
        if link.startswith("acestream://"):
            candidate = link.replace("acestream://", "", 1).strip()
            if HASH.fullmatch(candidate):
                return candidate.lower()
        parsed = urlsplit(link)
        if parsed.scheme not in ("http", "https"):
            return None
        for key in ("id", "infohash", "content_id"):
            value = parse_qs(parsed.query).get(key, [None])[0]
            if value and HASH.fullmatch(value):
                return value.lower()
    except ValueError:
        pass
    return None


def parse_playlist(body):
    items, metadata, seen = [], None, set()
    if not body.lstrip().startswith("#EXTM3U"):
        raise ValueError("La source n'a pas renvoyé une playlist M3U.")
    for line in body.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF:"):
            attrs = dict(ATTR.findall(line))
            name = line.rsplit(",", 1)[-1].strip() if "," in line else ""
            metadata = {"name": (attrs.get("tvg-name") or name or "Sans titre")[:180],
                        "category": attrs.get("group-title", "Divers")[:80]}
        elif line and not line.startswith("#") and metadata:
            content_id = extract_hash(line)
            if content_id and content_id not in seen:
                seen.add(content_id)
                items.append({**metadata, "content_id": content_id, "source": "search-ace.stream"})
            metadata = None
            if len(items) >= MAX_ITEMS:
                break
    return items


def normalize(s):
    return "".join(c for c in unicodedata.normalize("NFKD", s.casefold())
                   if not unicodedata.combining(c))


class PlaylistSearch:
    def __init__(self, url=PLAYLIST_URL):
        self.url = url
        self._cache = []
        self._loaded = 0
        self._lock = threading.Lock()
        self.error = None

    def refresh(self):
        with self._lock:
            if self._loaded and time.time() - self._loaded < CACHE_SECONDS:
                return
            try:
                response = requests.get(self.url, timeout=(4, 12), headers={"User-Agent": "AceStreamWeb/0.2"}, stream=True)
                response.raise_for_status()
                chunks, length = [], 0
                for chunk in response.iter_content(chunk_size=65536):
                    length += len(chunk)
                    if length > MAX_BYTES:
                        raise ValueError("Playlist trop volumineuse")
                    chunks.append(chunk)
                self._cache = parse_playlist(b"".join(chunks).decode("utf-8-sig", errors="replace"))
                self._loaded = time.time()
                self.error = None
            except (requests.RequestException, ValueError) as exc:
                self.error = str(exc)[:180]
                if not self._loaded:
                    raise RuntimeError("La playlist externe est momentanément indisponible.") from exc
                self._loaded = time.time() - CACHE_SECONDS + 60  # retry in 60 seconds

    def search(self, text, limit=60):
        self.refresh()
        query = normalize(text)
        matched = [item for item in self._cache
                   if query in normalize(item["name"]) or query in normalize(item["category"])]
        return matched[:limit], len(matched), self.error


class EngineSearch:
    """Query the VPN-protected Ace Stream Engine search API (server-side only).

    The modern /search endpoint is preferred. Some older engines expose
    /server/api?method=search with an access token. Tokens never reach browsers.
    """

    def __init__(self, engine_url):
        self.engine_url = engine_url.rstrip("/")

    @staticmethod
    def _parse(payload, limit):
        if not isinstance(payload, dict) or payload.get("error"):
            raise ValueError("Réponse d'API Ace Stream invalide")
        result = payload.get("result")
        if not isinstance(result, dict) or not isinstance(result.get("results"), list):
            raise ValueError("Résultats de recherche Ace Stream invalides")

        entries, seen = [], set()
        for group in result["results"]:
            if not isinstance(group, dict):
                continue
            variants = group.get("items")
            candidates = variants if isinstance(variants, list) else [group]
            for item in candidates:
                if not isinstance(item, dict):
                    continue
                infohash = item.get("infohash")
                if not isinstance(infohash, str) or not HASH.fullmatch(infohash):
                    continue
                content_id = infohash.lower()
                if content_id in seen:
                    continue
                seen.add(content_id)
                name = item.get("name") or group.get("name") or "Sans titre"
                categories = item.get("categories") or group.get("categories") or []
                category = categories[0] if isinstance(categories, list) and categories else "Divers"
                entry = {"name": str(name)[:180], "category": str(category)[:80],
                         "content_id": content_id, "source": "API Ace Stream"}
                status = item.get("status")
                if isinstance(status, int) and not isinstance(status, bool) and status in (1, 2):
                    entry["status"] = status
                availability = item.get("availability")
                if isinstance(availability, (int, float)) and not isinstance(availability, bool) and 0 <= availability <= 1:
                    entry["availability"] = round(float(availability), 2)
                bitrate = item.get("bitrate")
                if isinstance(bitrate, (int, float)) and not isinstance(bitrate, bool) and bitrate > 0:
                    entry["bitrate"] = int(bitrate)
                entries.append(entry)
                if len(entries) >= limit:
                    return entries, len(entries)
        return entries, len(entries)

    def _modern_search(self, query, limit):
        response = requests.get(self.engine_url + "/search",
                                params={"query": query, "page": 0, "page_size": limit},
                                timeout=(3, 8))
        response.raise_for_status()
        return self._parse(response.json(), limit)

    def _legacy_search(self, query, limit):
        response = requests.get(self.engine_url + "/server/api",
                                params={"method": "get_api_access_token"}, timeout=(3, 5))
        response.raise_for_status()
        payload = response.json()
        token = payload.get("result", {}).get("token") if isinstance(payload.get("result"), dict) else None
        if not isinstance(token, str) or not token:
            raise ValueError("Token de recherche du moteur indisponible")
        response = requests.get(self.engine_url + "/server/api",
                                params={"method": "search", "token": token,
                                        "query": query, "page": 0, "page_size": limit},
                                timeout=(3, 8))
        response.raise_for_status()
        return self._parse(response.json(), limit)

    def search(self, text, limit=60):
        limit = min(max(int(limit), 1), 100)
        try:
            return (*self._modern_search(text, limit), None)
        except (requests.RequestException, ValueError, TypeError, KeyError):
            try:
                return (*self._legacy_search(text, limit), None)
            except (requests.RequestException, ValueError, TypeError, KeyError) as exc:
                raise RuntimeError("Recherche API Ace Stream non disponible sur ce moteur.") from exc
