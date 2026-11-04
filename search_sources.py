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
                raise RuntimeError("Recherche API Ace Stream indisponible sur ce moteur.") from exc


# This upstream has its OWN searchable catalogue. It is not the Ace Stream
# Engine P2P search index, nor the public M3U playlist. The endpoint returns
# a JSON list of {content_id, name, translated_name} entries.
SITE_SEARCH_URL = "https://search-ace.stream/search"
SITE_SEARCH_BYTES = 256 * 1024
SITE_SEARCH_RESULTS = 60
CONTROL_CHARS = re.compile(
    "[\\x00-\\x1f\\x7f-\\x9f\\u200b-\\u200f\\u2028-\\u202e\\u2060\\u2066-\\u2069\\ufeff]"
)


class SiteSearch:
    """Query the same search index as search-ace.stream, server-side.

    URL and redirect policy are fixed to avoid exposing an arbitrary proxy.
    Results are validated and minimized before being returned to clients.
    """

    def __init__(self, url=SITE_SEARCH_URL):
        self.url = url

    @staticmethod
    def _clean(text, limit=180):
        return CONTROL_CHARS.sub("", text).strip()[:limit] if isinstance(text, str) else ""

    @classmethod
    def _parse(cls, payload, limit=SITE_SEARCH_RESULTS):
        if not isinstance(payload, list):
            raise ValueError("Réponse JSON de recherche invalide.")
        entries, seen = [], set()
        for item in payload[:1000]:
            if not isinstance(item, dict):
                continue
            content_id = item.get("content_id")
            if not isinstance(content_id, str) or not HASH.fullmatch(content_id):
                continue
            content_id = content_id.lower()
            if content_id in seen:
                continue
            name = cls._clean(item.get("name"))
            translated = cls._clean(item.get("translated_name"))
            if not name and not translated:
                continue
            seen.add(content_id)
            entry = {"name": name or translated, "content_id": content_id,
                     "source": "Site search-ace.stream", "category": "Diffusion"}
            if translated and translated != name:
                entry["translated_name"] = translated
            entries.append(entry)
            if len(entries) >= limit:
                break
        return entries, len(entries), None

    def search(self, query, limit=SITE_SEARCH_RESULTS):
        query = self._clean(query, 100)
        if len(query) < 2:
            return [], 0, None
        limit = min(max(int(limit), 1), SITE_SEARCH_RESULTS)
        try:
            with requests.get(
                self.url,
                params={"query": query},
                headers={"Accept": "application/json", "Accept-Encoding": "identity",
                         "User-Agent": "AceStreamWeb/0.3"},
                timeout=(4, 10), stream=True, allow_redirects=False,
            ) as response:
                # Block redirects to unexpected hosts, including on the same domain.
                if not 200 <= response.status_code < 300:
                    raise ValueError("Service de recherche HTTP " + str(response.status_code))
                chunks, total = [], 0
                for chunk in response.iter_content(chunk_size=16384):
                    total += len(chunk)
                    if total > SITE_SEARCH_BYTES:
                        raise ValueError("Réponse de recherche trop volumineuse")
                    chunks.append(chunk)
                import json
                payload = json.loads(b"".join(chunks).decode("utf-8"))
            return self._parse(payload, limit)
        except (requests.RequestException, ValueError, UnicodeDecodeError) as exc:
            raise RuntimeError("La recherche directe sur search-ace.stream est indisponible.") from exc
