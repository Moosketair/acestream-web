"""AceStream Web: local multi-user dashboard, VPN-guarded player, favorites and search."""
import hmac
import os
import secrets
import sqlite3
import threading
import time
from collections import defaultdict, deque
from datetime import timedelta
from functools import wraps
from pathlib import Path

import requests
from flask import (Flask, abort, g, jsonify, render_template, request, send_file,
                   send_from_directory, session)
from werkzeug.security import check_password_hash, generate_password_hash

from search_sources import PlaylistSearch, EngineSearch, SiteSearch
from streaming import StreamManager, parse_content

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id INTEGER PRIMARY KEY, username TEXT NOT NULL COLLATE NOCASE UNIQUE,
 password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('admin','user')),
 created_at INTEGER NOT NULL, last_login INTEGER);
CREATE TABLE IF NOT EXISTS favorites (
 id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name TEXT NOT NULL, content_id TEXT NOT NULL, group_name TEXT NOT NULL DEFAULT 'Mes favoris',
 notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
 UNIQUE (user_id, content_id));
CREATE TABLE IF NOT EXISTS history (
 id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name TEXT NOT NULL, content_id TEXT NOT NULL, played_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS history_user_date ON history(user_id, played_at DESC);
"""


def create_app(config=None):
    app = Flask(__name__, static_folder="static")
    app.config.update(
        DATA_DIR=os.environ.get("DATA_DIR", "/data"),
        ACESTREAM_URL=os.environ.get("ACESTREAM_URL", "http://127.0.0.1:6878"),
        GLUETUN_URL=os.environ.get("GLUETUN_URL", "http://127.0.0.1:8000"),
        SETUP_TOKEN=os.environ.get("SETUP_TOKEN", ""),
        SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE="Lax",
        MAX_CONTENT_LENGTH=16_384,
        SESSION_COOKIE_SECURE=os.environ.get("COOKIE_SECURE", "false").lower() == "true",
        PERMANENT_SESSION_LIFETIME=timedelta(days=7),
        MAX_STREAMS=int(os.environ.get("MAX_STREAMS", "3")),
    )
    if config:
        app.config.update(config)
    data = Path(app.config["DATA_DIR"])
    data.mkdir(parents=True, exist_ok=True)
    key_path = data / "flask-session-key"
    if not key_path.exists():
        try:
            fd = os.open(str(key_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as fp:
                fp.write(secrets.token_hex(32))
        except FileExistsError:
            pass
    app.secret_key = app.config.get("TEST_SECRET", key_path.read_text().strip())
    db_path = str(data / "acestream.db")
    with sqlite3.connect(db_path) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.executescript(SCHEMA)

    manager = StreamManager(data / "hls", app.config["ACESTREAM_URL"], app.config["MAX_STREAMS"])
    searcher = PlaylistSearch()
    engine_searcher = EngineSearch(app.config["ACESTREAM_URL"])
    site_searcher = SiteSearch()
    app.extensions["site_search"] = site_searcher
    app.extensions["engine_search"] = engine_searcher
    app.extensions["stream_manager"] = manager
    app.extensions["playlist_search"] = searcher
    login_attempts = defaultdict(deque)
    login_lock = threading.Lock()
    vpn_cache = {"time": 0, "value": None}
    vpn_lock = threading.Lock()

    def database():
        if "db" not in g:
            g.db = sqlite3.connect(db_path, timeout=10)
            g.db.row_factory = sqlite3.Row
            g.db.execute("PRAGMA foreign_keys=ON")
        return g.db

    @app.teardown_appcontext
    def close_db(_exc):
        conn = g.pop("db", None)
        if conn:
            conn.close()

    def count_users():
        return database().execute("SELECT COUNT(*) FROM users").fetchone()[0]

    def current_user():
        uid = session.get("uid")
        if not isinstance(uid, int):
            return None
        return database().execute("SELECT id, username, role FROM users WHERE id=?", (uid,)).fetchone()

    def public_user(row):
        return dict(row) if row else None

    def logged_in(function):
        @wraps(function)
        def wrapper(*args, **kwargs):
            if not current_user():
                return jsonify(error="Connexion requise"), 401
            return function(*args, **kwargs)
        return wrapper

    def admin_only(function):
        @wraps(function)
        @logged_in
        def wrapper(*args, **kwargs):
            if current_user()["role"] != "admin":
                return jsonify(error="Droits administrateur requis"), 403
            return function(*args, **kwargs)
        return wrapper

    @app.before_request
    def csrf():
        if "csrf" not in session:
            session["csrf"] = secrets.token_urlsafe(32)
        if request.method in ("POST", "PUT", "PATCH", "DELETE") and request.path.startswith("/api/"):
            supplied = request.headers.get("X-CSRF-Token", "")
            if not supplied or not hmac.compare_digest(supplied, session["csrf"]):
                return jsonify(error="Jeton de sécurité invalide. Rechargez la page."), 403

    @app.after_request
    def security_headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; "
            "style-src 'self'; img-src 'self' data:; connect-src 'self'; "
            "media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        if request.path.startswith("/api/") or request.path.startswith("/hls/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    def body():
        value = request.get_json(silent=True)
        return value if isinstance(value, dict) else {}

    def validate_password(text):
        return isinstance(text, str) and 10 <= len(text) <= 256

    def validate_username(text):
        return isinstance(text, str) and 3 <= len(text) <= 32 and all(
            x.isascii() and (x.isalnum() or x in "_.-") for x in text)

    def vpn_status(refresh=False):
        with vpn_lock:
            if not refresh and vpn_cache["value"] is not None and time.monotonic() - vpn_cache["time"] < 3:
                return dict(vpn_cache["value"])
            value = {"connected": False, "status": "inconnu", "public_ip": None, "error": None}
            try:
                response = requests.get(app.config["GLUETUN_URL"].rstrip("/") + "/v1/vpn/status", timeout=2)
                response.raise_for_status()
                state = response.json().get("status", "inconnu")
                value["status"] = str(state)
                value["connected"] = state == "running"
                if value["connected"]:
                    try:
                        r = requests.get(app.config["GLUETUN_URL"].rstrip("/") + "/v1/publicip/ip", timeout=2)
                        r.raise_for_status()
                        value["public_ip"] = r.json().get("public_ip")
                    except (requests.RequestException, ValueError):
                        pass
            except (requests.RequestException, ValueError) as exc:
                value["error"] = "API Gluetun inaccessible"
            vpn_cache.update(time=time.monotonic(), value=value)
            return dict(value)

    def engine_status():
        began = time.monotonic()
        state = {"online": False, "version": None, "latency_ms": None}
        try:
            response = requests.get(app.config["ACESTREAM_URL"].rstrip("/") + "/webui/api/service",
                                    params={"method": "get_version"}, timeout=3)
            response.raise_for_status()
            payload = response.json()
            if payload.get("error") is None and isinstance(payload.get("result"), dict):
                state.update(online=True, version=payload["result"].get("version"),
                             latency_ms=int((time.monotonic() - began) * 1000))
        except (requests.RequestException, ValueError, AttributeError):
            pass
        return state

    def watcher():
        while True:
            time.sleep(5)
            active = manager.list(admin=True)
            for item in active:
                if time.time() - item["created"] > 6 * 3600:
                    manager.stop(item["stream_id"])
            if active and not vpn_status(refresh=True)["connected"]:
                manager.stop_all()

    if not app.config.get("TESTING"):
        threading.Thread(target=watcher, daemon=True, name="vpn-stream-guard").start()

    @app.get("/")
    def homepage():
        return render_template("index.html")

    @app.get("/api/health")
    def health():
        return jsonify(status="ok")

    @app.get("/api/auth/session")
    def auth_session():
        return jsonify(user=public_user(current_user()), configured=count_users() > 0,
                       csrf=session["csrf"])

    @app.post("/api/auth/setup")
    def setup():
        if count_users():
            return jsonify(error="Compte administrateur déjà créé."), 409
        d = body()
        token = app.config["SETUP_TOKEN"]
        if not token or len(token) < 24 or token.startswith("REPLACE_") or not hmac.compare_digest(str(d.get("setup_token", "")), token):
            return jsonify(error="Clé d'initialisation incorrecte."), 403
        if not validate_username(d.get("username")) or not validate_password(d.get("password")):
            return jsonify(error="Identifiant invalide ou mot de passe de moins de 10 caractères."), 400
        con = database()
        try:
            con.execute("BEGIN IMMEDIATE")
            if count_users():
                con.rollback()
                return jsonify(error="Un compte existe déjà."), 409
            cursor = con.execute("INSERT INTO users(username,password_hash,role,created_at) VALUES(?,?,?,?)",
                                 (d["username"], generate_password_hash(d["password"]), "admin", int(time.time())))
            con.commit()
        except sqlite3.IntegrityError:
            con.rollback()
            return jsonify(error="Identifiant déjà utilisé."), 409
        session.clear()
        session.update(uid=cursor.lastrowid, csrf=secrets.token_urlsafe(32))
        session.permanent = True
        return jsonify(user=public_user(current_user()), csrf=session["csrf"]), 201

    @app.post("/api/auth/login")
    def login():
        d = body()
        username = str(d.get("username", ""))[:60]
        ip = request.remote_addr or "unknown"
        key = (ip, username.casefold())
        with login_lock:
            attempts = login_attempts[key]
            now = time.monotonic()
            while attempts and now - attempts[0] > 900:
                attempts.popleft()
            if len(attempts) >= 6:
                return jsonify(error="Trop d'essais. Réessayez dans 15 minutes."), 429
        row = database().execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        if not row or not check_password_hash(row["password_hash"], str(d.get("password", ""))):
            with login_lock:
                login_attempts[key].append(time.monotonic())
            return jsonify(error="Identifiant ou mot de passe incorrect."), 401
        with login_lock:
            login_attempts.pop(key, None)
        database().execute("UPDATE users SET last_login=? WHERE id=?", (int(time.time()), row["id"]))
        database().commit()
        session.clear()
        session.update(uid=row["id"], csrf=secrets.token_urlsafe(32))
        session.permanent = True
        return jsonify(user=public_user(current_user()), csrf=session["csrf"])

    @app.post("/api/auth/logout")
    def logout():
        session.clear()
        return jsonify(success=True)

    @app.post("/api/auth/password")
    @logged_in
    def password_change():
        d = body()
        user = current_user()
        row = database().execute("SELECT password_hash FROM users WHERE id=?", (user["id"],)).fetchone()
        if not check_password_hash(row["password_hash"], str(d.get("old_password", ""))):
            return jsonify(error="Mot de passe actuel incorrect."), 403
        if not validate_password(d.get("new_password")):
            return jsonify(error="Nouveau mot de passe : 10 caractères minimum."), 400
        database().execute("UPDATE users SET password_hash=? WHERE id=?",
                           (generate_password_hash(d["new_password"]), user["id"]))
        database().commit()
        return jsonify(success=True)

    @app.get("/api/users")
    @admin_only
    def list_users():
        rows = database().execute("SELECT id,username,role,created_at,last_login FROM users ORDER BY id").fetchall()
        return jsonify(users=[dict(r) for r in rows])

    @app.post("/api/users")
    @admin_only
    def add_user():
        d = body()
        if not validate_username(d.get("username")) or not validate_password(d.get("password")):
            return jsonify(error="Identifiant invalide ou mot de passe de moins de 10 caractères."), 400
        role = d.get("role", "user")
        if role not in ("admin", "user"):
            return jsonify(error="Rôle invalide."), 400
        try:
            cursor = database().execute(
                "INSERT INTO users(username,password_hash,role,created_at) VALUES(?,?,?,?)",
                (d["username"], generate_password_hash(d["password"]), role, int(time.time())))
            database().commit()
        except sqlite3.IntegrityError:
            return jsonify(error="Ce nom d'utilisateur existe déjà."), 409
        return jsonify(id=cursor.lastrowid), 201

    @app.delete("/api/users/<int:user_id>")
    @admin_only
    def delete_user(user_id):
        if current_user()["id"] == user_id:
            return jsonify(error="Impossible de supprimer ton propre compte."), 400
        con = database()
        row = con.execute("SELECT role FROM users WHERE id=?", (user_id,)).fetchone()
        if not row:
            return jsonify(error="Utilisateur introuvable."), 404
        if row["role"] == "admin" and con.execute("SELECT COUNT(*) FROM users WHERE role='admin'").fetchone()[0] <= 1:
            return jsonify(error="Le dernier administrateur ne peut pas être supprimé."), 400
        for stream in manager.list(user_id=user_id):
            manager.stop(stream["stream_id"])
        con.execute("DELETE FROM users WHERE id=?", (user_id,))
        con.commit()
        return jsonify(success=True)

    @app.get("/api/favorites")
    @logged_in
    def favorites():
        rows = database().execute("SELECT id,name,content_id,group_name,notes,created_at FROM favorites "
                                  "WHERE user_id=? ORDER BY group_name COLLATE NOCASE,name COLLATE NOCASE",
                                  (current_user()["id"],)).fetchall()
        return jsonify(favorites=[dict(r) for r in rows])

    def favorite_payload(d):
        name = d.get("name")
        group_name = d.get("group_name", "Mes favoris")
        notes = d.get("notes", "")
        _, content_id = parse_content(d.get("content_id"))
        if (not isinstance(name, str) or not 1 <= len(name.strip()) <= 120 or
            not isinstance(group_name, str) or not 1 <= len(group_name.strip()) <= 60 or
            not isinstance(notes, str) or len(notes) > 500 or not content_id):
            return None
        return name.strip(), content_id, group_name.strip(), notes.strip()

    @app.post("/api/favorites")
    @logged_in
    def add_favorite():
        values = favorite_payload(body())
        if not values:
            return jsonify(error="Favori invalide (nom, groupe ou hash)."), 400
        try:
            cursor = database().execute("INSERT INTO favorites(user_id,name,content_id,group_name,notes,created_at) "
                                        "VALUES(?,?,?,?,?,?)", (current_user()["id"], *values, int(time.time())))
            database().commit()
        except sqlite3.IntegrityError:
            return jsonify(error="Ce flux est déjà dans tes favoris."), 409
        return jsonify(id=cursor.lastrowid), 201

    @app.patch("/api/favorites/<int:favorite_id>")
    @logged_in
    def update_favorite(favorite_id):
        values = favorite_payload(body())
        if not values:
            return jsonify(error="Favori invalide."), 400
        try:
            cursor = database().execute("UPDATE favorites SET name=?,content_id=?,group_name=?,notes=? "
                                        "WHERE id=? AND user_id=?", (*values, favorite_id, current_user()["id"]))
            database().commit()
        except sqlite3.IntegrityError:
            return jsonify(error="Ce flux est déjà enregistré."), 409
        return jsonify(success=True) if cursor.rowcount else (jsonify(error="Favori introuvable."), 404)

    @app.delete("/api/favorites/<int:favorite_id>")
    @logged_in
    def delete_favorite(favorite_id):
        cursor = database().execute("DELETE FROM favorites WHERE id=? AND user_id=?",
                                    (favorite_id, current_user()["id"]))
        database().commit()
        return jsonify(success=True) if cursor.rowcount else (jsonify(error="Favori introuvable."), 404)

    @app.get("/api/history")
    @logged_in
    def history():
        rows = database().execute("SELECT name,content_id,played_at FROM history WHERE user_id=? "
                                  "ORDER BY played_at DESC LIMIT 50", (current_user()["id"],)).fetchall()
        return jsonify(history=[dict(r) for r in rows])

    @app.get("/api/search")
    @logged_in
    def search():
        query = request.args.get("q", "").strip()
        source = request.args.get("source", "all")
        if not 2 <= len(query) <= 100:
            return jsonify(error="Recherche entre 2 et 100 caractères requise."), 400
        if source not in ("all", "site", "playlist", "engine"):
            return jsonify(error="Source de recherche inconnue."), 400

        results, warnings, sources = [], [], []
        successful = 0

        # The site's /search JSON endpoint is the source used by its Web search,
        # distinct from the engine P2P index and from its M3U playlist.
        # Site results take priority when merging so event streams stay visible.
        if source in ("all", "site"):
            try:
                site_results, _total, _warning = site_searcher.search(query)
                results.extend(site_results)
                sources.append("Site search-ace.stream")
                successful += 1
            except RuntimeError:
                warnings.append("La recherche du site search-ace.stream est indisponible.")

        if source in ("all", "engine"):
            if not vpn_status()["connected"]:
                warnings.append("VPN indisponible : recherche dans l'index moteur désactivée.")
            else:
                try:
                    engine_results, _total, _warning = engine_searcher.search(query)
                    results.extend(engine_results)
                    sources.append("API moteur Ace Stream")
                    successful += 1
                except RuntimeError:
                    warnings.append("L'index de recherche du moteur Ace Stream est indisponible.")

        if source in ("all", "playlist"):
            try:
                playlist_results, _total, warning = searcher.search(query)
                results.extend(playlist_results)
                sources.append("Playlist search-ace.stream")
                successful += 1
                if warning:
                    warnings.append("Playlist momentanément inaccessible : résultats en cache.")
            except RuntimeError:
                warnings.append("La playlist search-ace.stream est indisponible.")

        if not successful:
            return jsonify(error="Aucune source de recherche disponible.",
                           warnings=warnings), 503

        merged, seen = [], set()
        for entry in results:
            content_id = entry["content_id"]
            if content_id not in seen:
                seen.add(content_id)
                merged.append(entry)
        # A valid empty reply from the engine is not an error: its index simply
        # doesn't necessarily contain the streams shown on search-ace.stream.
        if source == "engine" and not merged:
            warnings.append("Aucun résultat dans l'index du moteur. Essayez « Recherche du site ».")
        if source == "playlist" and not merged:
            warnings.append("Aucun résultat dans la playlist. Essayez « Recherche du site ».")
        return jsonify(results=merged[:60], total=len(merged),
                       warning=" ".join(warnings) if warnings else None,
                       sources=sources, source=" + ".join(sources))

    @app.get("/api/monitor")
    @logged_in
    def monitor():
        vpn = vpn_status()
        engine = engine_status() if vpn["connected"] else {"online": False, "version": None, "latency_ms": None}
        return jsonify(vpn=vpn, engine=engine, streams=len(manager.list(admin=True)),
                       max_streams=manager.max_streams)

    @app.get("/api/streams")
    @logged_in
    def streams():
        user = current_user()
        return jsonify(streams=manager.list(user_id=user["id"], admin=user["role"] == "admin"))

    @app.post("/api/streams/start")
    @logged_in
    def start():
        d = body()
        parameter, content_id = parse_content(d.get("content_id", d.get("infohash", "")))
        if not content_id:
            return jsonify(error="Content ID Ace Stream invalide."), 400
        if not vpn_status(refresh=True)["connected"]:
            return jsonify(error="VPN Gluetun déconnecté ou statut impossible à vérifier : lecture bloquée."), 503
        # Calling the engine is also gated on a fresh positive VPN status.
        title = str(d.get("name") or "Flux Ace Stream")[:120]
        try:
            sid = manager.start(current_user()["id"], parameter, content_id, title)
        except (ValueError, RuntimeError, TimeoutError, OSError) as exc:
            return jsonify(error=str(exc)), 503
        # Re-check the guard, in case the tunnel failed during the 30-second buffer period.
        if not vpn_status(refresh=True)["connected"]:
            manager.stop(sid)
            return jsonify(error="Le VPN s'est interrompu pendant le démarrage."), 503
        con = database()
        con.execute("INSERT INTO history(user_id,name,content_id,played_at) VALUES(?,?,?,?)",
                    (current_user()["id"], title, content_id, int(time.time())))
        con.execute("DELETE FROM history WHERE id IN (SELECT id FROM history WHERE user_id=? "
                    "ORDER BY played_at DESC LIMIT -1 OFFSET 200)", (current_user()["id"],))
        con.commit()
        return jsonify(stream_id=sid, playlist=f"/hls/{sid}/index.m3u8", name=title), 201

    @app.delete("/api/streams/<sid>")
    @logged_in
    def stop(sid):
        user = current_user()
        if not manager.owned(sid, user["id"], user["role"] == "admin"):
            return jsonify(error="Flux introuvable."), 404
        manager.stop(sid)
        return jsonify(success=True)

    def stream_owned_or_404(sid):
        user = current_user()
        item = manager.owned(sid, user["id"], user["role"] == "admin") if user else None
        if not item:
            abort(404)
        return item

    @app.get("/hls/<sid>/index.m3u8")
    def manifest(sid):
        item = stream_owned_or_404(sid)
        filename = item["directory"] / "index.m3u8"
        if not filename.is_file():
            abort(404)
        return send_file(filename, mimetype="application/vnd.apple.mpegurl", conditional=False)

    @app.get("/hls/<sid>/<path:filename>")
    def segment(sid, filename):
        item = stream_owned_or_404(sid)
        # Never serve log or arbitrary files from the FFmpeg working directory.
        import re
        if not re.fullmatch(r"index\d+\.ts", filename):
            abort(404)
        return send_from_directory(item["directory"], filename, max_age=0)

    return app


app = create_app()
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
