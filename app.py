import os
import re
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse, parse_qs, urlencode

import requests
from flask import Flask, jsonify, render_template, request, send_file, send_from_directory

app = Flask(__name__)
ACESTREAM_URL = os.environ.get("ACESTREAM_URL", "http://gluetun:6878").rstrip("/")
HLS_ROOT = Path("/tmp/hls")
HLS_ROOT.mkdir(parents=True, exist_ok=True)
streams = {}
streams_lock = threading.RLock()
operation_lock = threading.Lock()
HASH = re.compile(r"^[0-9a-fA-F]{40}$")

def parse_acestream_input(value):
    if not isinstance(value, str):
        return None, None
    value = value.strip()
    if HASH.fullmatch(value):
        return "infohash", value.lower()
    match = re.fullmatch(r"acestream://([0-9a-fA-F]{40})", value, re.I)
    if match:
        return "infohash", match.group(1).lower()
    try:
        parsed = urlparse(value)
        query = parse_qs(parsed.query)
        for parameter in ("infohash", "id", "content_id"):
            if query.get(parameter) and HASH.fullmatch(query[parameter][0]):
                return parameter, query[parameter][0].lower()
    except ValueError:
        pass
    match = re.search(r"(?:infohash|content_id|id)=([0-9a-fA-F]{40})(?![0-9a-fA-F])", value, re.I)
    if match:
        return "infohash", match.group(1).lower()
    return None, None

def stop_stream(stream_id):
    with streams_lock:
        stream = streams.pop(stream_id, None)
    if not stream:
        return
    process = stream["process"]
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    shutil.rmtree(stream["directory"], ignore_errors=True)

def monitor_stream(stream_id):
    while True:
        time.sleep(5)
        with streams_lock:
            stream = streams.get(stream_id)
        if not stream:
            return
        if stream["process"].poll() is not None:
            stop_stream(stream_id)
            return

@app.get("/")
def index():
    return render_template("index.html")

@app.get("/api/health")
def health():
    return jsonify(status="ok")

@app.get("/api/status")
def status():
    with streams_lock:
        return jsonify([{
            "stream_id": sid,
            "content_id": item["content_id"],
            "parameter": item["parameter"],
            "running": item["process"].poll() is None,
            "created": item["created"],
        } for sid, item in streams.items()])

@app.post("/api/stop")
def stop():
    data = request.get_json(silent=True) or {}
    sid = data.get("stream_id")
    if not isinstance(sid, str):
        return jsonify(error="Identifiant invalide"), 400
    with operation_lock:
        stop_stream(sid)
    return jsonify(success=True)

@app.post("/api/start")
def start():
    data = request.get_json(silent=True) or {}
    parameter, content_id = parse_acestream_input(data.get("infohash", ""))
    if not content_id:
        return jsonify(error="Identifiant invalide : utilise un hash de 40 caractères, un lien acestream:// ou une URL avec infohash, id ou content_id."), 400

    # Serialize start/stop operations. A new stream replaces the previous one.
    with operation_lock:
        with streams_lock:
            previous = list(streams)
        for sid in previous:
            stop_stream(sid)

        sid = uuid.uuid4().hex[:12]
        directory = HLS_ROOT / sid
        directory.mkdir(parents=True, exist_ok=True)
        playlist = directory / "index.m3u8"
        stream_url = f"{ACESTREAM_URL}/ace/getstream?{urlencode({parameter: content_id})}"
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "warning",
            "-i", stream_url, "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
            "-f", "hls", "-hls_time", "2", "-hls_list_size", "6",
            "-hls_flags", "delete_segments+append_list+independent_segments",
            "-hls_segment_type", "mpegts", str(playlist),
        ]
        try:
            with open(directory / "ffmpeg.log", "w", encoding="utf-8") as logfile:
                process = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=logfile)
        except OSError as exc:
            shutil.rmtree(directory, ignore_errors=True)
            return jsonify(error=f"Impossible de démarrer FFmpeg : {exc}"), 500

        with streams_lock:
            streams[sid] = {
                "process": process, "directory": directory, "created": time.time(),
                "content_id": content_id, "parameter": parameter,
            }
        threading.Thread(target=monitor_stream, args=(sid,), daemon=True).start()

        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if playlist.exists() and playlist.stat().st_size > 0:
                return jsonify(success=True, stream_id=sid, content_id=content_id,
                               parameter=parameter, playlist=f"/hls/{sid}/index.m3u8")
            if process.poll() is not None:
                try:
                    details = (directory / "ffmpeg.log").read_text(errors="replace")[-1500:]
                except OSError:
                    details = ""
                stop_stream(sid)
                return jsonify(error="FFmpeg s'est arrêté avant de créer la playlist HLS.", ffmpeg=details), 502
            time.sleep(.2)
        stop_stream(sid)
        return jsonify(error="Délai dépassé : aucune playlist vidéo créée après 30 secondes."), 504

@app.get("/hls/<stream_id>/index.m3u8")
def hls_manifest(stream_id):
    with streams_lock:
        item = streams.get(stream_id)
    if not item:
        return "Flux introuvable", 404
    playlist = item["directory"] / "index.m3u8"
    if not playlist.is_file():
        return "Playlist indisponible", 404
    response = send_file(playlist, mimetype="application/vnd.apple.mpegurl", conditional=False)
    response.headers["Cache-Control"] = "no-store"
    return response

@app.get("/hls/<stream_id>/<path:filename>")
def hls_segment(stream_id, filename):
    with streams_lock:
        item = streams.get(stream_id)
    if not item:
        return "Flux introuvable", 404
    return send_from_directory(item["directory"], filename, max_age=0)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080, threaded=True)
