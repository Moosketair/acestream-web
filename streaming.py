"""Single-process FFmpeg manager. Gunicorn must run with one worker."""
import re
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit

HASH = re.compile(r"^[0-9a-fA-F]{40}$")


def parse_content(value):
    if not isinstance(value, str):
        return None, None
    value = value.strip()
    if HASH.fullmatch(value):
        return "infohash", value.lower()
    if value.lower().startswith("acestream://"):
        possible = value[12:]
        if HASH.fullmatch(possible):
            return "infohash", possible.lower()
    try:
        parsed = urlsplit(value)
        if parsed.scheme in ("http", "https", "acestream"):
            for key in ("infohash", "id", "content_id"):
                content_id = parse_qs(parsed.query).get(key, [None])[0]
                if content_id and HASH.fullmatch(content_id):
                    return key, content_id.lower()
    except ValueError:
        pass
    return None, None


class StreamManager:
    def __init__(self, root, engine, max_streams=3):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        # In-memory process tracking is intentionally single-worker; remove stale HLS files on restart.
        for old in self.root.iterdir():
            if old.is_dir():
                shutil.rmtree(old, ignore_errors=True)
        self.engine = engine.rstrip("/")
        self.max_streams = max_streams
        self.lock = threading.RLock()
        self.streams = {}

    def stop(self, sid):
        with self.lock:
            item = self.streams.pop(sid, None)
            if item:
                proc = item["process"]
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=3)
                shutil.rmtree(item["directory"], ignore_errors=True)
            return bool(item)

    def stop_all(self):
        with self.lock:
            for sid in list(self.streams):
                self.stop(sid)

    def list(self, user_id=None, admin=False):
        with self.lock:
            items = []
            for sid, item in list(self.streams.items()):
                if item["process"].poll() is not None:
                    self.stop(sid)
                    continue
                if admin or user_id == item["owner"]:
                    items.append({"stream_id": sid, "owner": item["owner"], "name": item["name"],
                                  "content_id": item["content_id"], "created": item["created"],
                                  "playlist": f"/hls/{sid}/index.m3u8"})
            return items

    def owned(self, sid, user_id, admin=False):
        with self.lock:
            item = self.streams.get(sid)
            return item if item and (admin or item["owner"] == user_id) else None

    def start(self, user_id, parameter, content_id, name):
        with self.lock:
            for sid, item in list(self.streams.items()):
                if item["owner"] == user_id:
                    self.stop(sid)
            self.list(admin=True)  # prune exited processes
            if len(self.streams) >= self.max_streams:
                raise ValueError(f"Limite de {self.max_streams} flux simultanés atteinte.")
            sid = uuid.uuid4().hex[:16]
            folder = self.root / sid
            folder.mkdir(parents=True, exist_ok=True)
            stream_url = f"{self.engine}/ace/getstream?{urlencode({parameter: content_id})}"
            cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning", "-i", stream_url,
                   "-map", "0:v:0?", "-map", "0:a:0?", "-c:v", "copy", "-c:a", "aac",
                   "-b:a", "128k", "-f", "hls", "-hls_time", "3", "-hls_list_size", "7",
                   "-hls_flags", "delete_segments+append_list", "-hls_segment_type", "mpegts",
                   str(folder / "index.m3u8")]
            log = open(folder / "ffmpeg.log", "w")
            try:
                proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=log)
            except Exception:
                log.close()
                shutil.rmtree(folder, ignore_errors=True)
                raise
            log.close()
            self.streams[sid] = {"owner": user_id, "content_id": content_id, "name": name,
                                 "created": time.time(), "directory": folder, "process": proc}
        deadline = time.monotonic() + 30
        playlist = folder / "index.m3u8"
        while time.monotonic() < deadline:
            if playlist.exists() and playlist.stat().st_size > 0:
                return sid
            if proc.poll() is not None:
                self.stop(sid)
                raise RuntimeError("FFmpeg s'est arrêté avant de créer le flux. Le lien peut être indisponible.")
            time.sleep(.25)
        self.stop(sid)
        raise TimeoutError("Le flux n'a pas démarré après 30 secondes.")
