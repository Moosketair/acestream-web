package fr.hobbitdecheval.acestream

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class StreamItem(val name: String, val ref: String, val id: Int = 0, val category: String = "")

class MainActivity : Activity() {
    private val purple = Color.rgb(142, 112, 239)
    private val bg = Color.rgb(9, 14, 27)
    private val panel = Color.rgb(21, 32, 52)
    private val white = Color.WHITE
    private val muted = Color.rgb(172, 184, 212)
    private val executor = Executors.newSingleThreadExecutor()
    private val cookies = mutableListOf<Cookie>()
    private var server = "https://ace.hobbit-de-cheval.fr"
    private var csrf = ""
    private var user = ""
    private var activeStream = ""
    private var activeName = ""
    private var query = ""
    private var searchSource = "site"
    private var results = listOf<StreamItem>()
    private lateinit var client: OkHttpClient
    private lateinit var player: ExoPlayer
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = getSharedPreferences("acestream", MODE_PRIVATE).getString("server", server) ?: server
        client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, fresh: List<Cookie>) {
                synchronized(cookies) {
                    fresh.forEach { cookie ->
                        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        if (cookie.expiresAt > System.currentTimeMillis()) cookies.add(cookie)
                    }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookies) { cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } }
        }).followRedirects(false).readTimeout(55, TimeUnit.SECONDS).build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                runOnUiThread { showMessage("Lecture impossible : " + error.message.orEmpty()) }
            }
        })
        showLogin()
    }

    private fun request(path: String, method: String = "GET", data: JSONObject? = null): JSONObject {
        val url = if (path.startsWith("http")) path else server + path
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (method != "GET") {
            if (csrf.isNotEmpty()) builder.header("X-CSRF-Token", csrf)
            builder.method(method, (data?.toString() ?: "").toRequestBody("application/json".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { response ->
            val value = response.body?.string().orEmpty()
            val result = try { JSONObject(value) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(
                result.optString("error").ifBlank { "Erreur HTTP " + response.code }
            )
            if (result.has("csrf")) csrf = result.optString("csrf")
            return result
        }
    }

    private fun background(block: () -> Unit) {
        executor.execute {
            try { block() }
            catch (e: Exception) { runOnUiThread { showMessage(e.message ?: "Erreur de connexion") } }
        }
    }

    private fun showMessage(text: String) {
        if (::notice.isInitialized) notice.text = text
    }

    private fun text(label: String, size: Float = 15f): TextView =
        TextView(this).apply { this.text = label; setTextColor(white); textSize = size; setPadding(4, 6, 4, 6) }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(white)
            backgroundTintList = ColorStateList.valueOf(purple)
            setOnClickListener { onClick() }
        }

    private fun field(hint: String, initial: String = ""): EditText =
        EditText(this).apply {
            setText(initial)
            this.hint = hint
            setTextColor(white)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 23, 40))
                cornerRadius = 20f
                setStroke(2, Color.rgb(60, 75, 105))
            }
        }

    private fun setup(title: String, navigation: Boolean = true) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        setContentView(root)
        root.addView(text("▶ AceStream TV", 23f).apply {
            setPadding(20, 24, 12, 15)
            setTextColor(purple)
        })
        if (navigation) {
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, section) in listOf("▶" to 0, "⌕" to 1, "★" to 2, "◷" to 3, "VPN" to 4)) {
                val b = button(label) {
                    when (section) {
                        0 -> showPlayer()
                        1 -> showSearch()
                        2 -> showFavorites()
                        3 -> showHistory()
                        else -> showStatus()
                    }
                }
                bar.addView(b, LinearLayout.LayoutParams(0, 56, 1f))
            }
            root.addView(bar)
        }
        root.addView(text(title, 20f).apply { setPadding(18, 12, 18, 12) })
        notice = text("").apply { setTextColor(muted); setPadding(18, 6, 18, 10) }
        root.addView(notice)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 18)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun addBox(view: android.view.View) {
        content.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 12
        })
    }

    private fun showLogin() {
        setup("Connexion à ton serveur", navigation = false)
        val address = field("Adresse du serveur", server)
        val login = field("Identifiant")
        val password = field("Mot de passe").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addBox(address); addBox(login); addBox(password)
        addBox(button("Se connecter") {
            val given = address.text.toString().trim().trimEnd('/')
            val valid = given.toHttpUrlOrNull()
            if (valid == null || (valid.scheme != "https" && valid.scheme != "http")) {
                showMessage("Indique une adresse HTTP(S) valide.")
                return@button
            }
            if (given != server) {
                synchronized(cookies) { cookies.clear() }
                csrf = ""
            }
            server = given
            getSharedPreferences("acestream", MODE_PRIVATE).edit().putString("server", server).apply()
            showMessage("Connexion…")
            background {
                request("/api/auth/session")
                val r = request("/api/auth/login", "POST", JSONObject().apply {
                    put("username", login.text.toString())
                    put("password", password.text.toString())
                })
                user = r.getJSONObject("user").optString("username")
                runOnUiThread { showPlayer() }
            }
        })
    }

    private fun showPlayer() {
        setup("Lecteur vidéo")
        if (activeName.isNotBlank()) addBox(text(activeName))
        val screen = PlayerView(this).apply { player = this@MainActivity.player; useController = true }
        val w = resources.displayMetrics.widthPixels
        content.addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (w * 9f / 16f).toInt()))
        addBox(button("⛶ Plein écran paysage") { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE })
        addBox(button("▣ Mini-lecteur (PiP)") { enterPip() })
        val manual = field("Content ID ou lien AceStream")
        addBox(manual)
        addBox(button("▶ Lire") {
            val id = manual.text.toString().trim()
            if (id.isNotBlank()) play(StreamItem("Flux manuel", id))
        })
        addBox(button("■ Arrêter") { stop() })
        if (activeStream.isNotBlank()) showMessage("Lecture active") else showMessage("Prêt pour la lecture")
    }

    private fun play(item: StreamItem) {
        showPlayer()
        showMessage("Préparation du flux AceStream…")
        background {
            if (activeStream.isNotBlank()) {
                try { request("/api/streams/" + activeStream, "DELETE") } catch (_: Exception) {}
            }
            val result = request("/api/streams/start", "POST", JSONObject().apply {
                put("content_id", item.ref)
                put("name", item.name)
            })
            activeStream = result.getString("stream_id")
            activeName = item.name
            val playlist = server + result.getString("playlist")
            runOnUiThread {
                player.setMediaItem(MediaItem.Builder()
                    .setUri(playlist).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.prepare()
                player.playWhenReady = true
                showMessage("Lecture en préparation…")
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        setPictureInPictureParams(PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9)).setAutoEnterEnabled(true).build())
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stop() {
        val old = activeStream
        activeStream = ""
        activeName = ""
        player.stop()
        player.clearMediaItems()
        showMessage("Lecture arrêtée")
        if (old.isNotBlank()) background { request("/api/streams/" + old, "DELETE") }
    }

    private fun showSearch() {
        setup("Recherche d'événements")
        val input = field("Nom de chaîne ou événement", query)
        addBox(input)
        val choice = Spinner(this)
        val keys = listOf("site", "all", "engine", "playlist")
        val labels = listOf("Événements du site", "Toutes les sources", "Index AceStream", "Playlist")
        choice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        choice.setSelection(keys.indexOf(searchSource).coerceAtLeast(0))
        addBox(choice)
        addBox(button("⌕ Rechercher") {
            query = input.text.toString()
            searchSource = keys[choice.selectedItemPosition]
            showMessage("Recherche…")
            background {
                val target = (server + "/api/search").toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("q", query).addQueryParameter("source", searchSource).build()
                val array = request(target.toString()).optJSONArray("results") ?: JSONArray()
                results = (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    StreamItem(o.optString("name"), o.optString("playback_ref").ifBlank { o.optString("content_id") },
                        0, o.optString("category"))
                }
                runOnUiThread { showSearch(); showMessage(results.size.toString() + " résultat(s)") }
            }
        })
        addBox(button("✕ Effacer") {
            query = ""; results = emptyList(); showSearch()
        })
        for (item in results) addItem(item, favoriteAction = true)
    }

    private fun addItem(item: StreamItem, favoriteAction: Boolean = false, delete: Boolean = false) {
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13, 12, 13, 12)
            setBackgroundColor(panel)
        }
        area.addView(text(item.name, 16f))
        if (item.category.isNotBlank()) area.addView(text(item.category, 12f).apply { setTextColor(muted) })
        val actions = LinearLayout(this)
        actions.addView(button("▶ Lire") { play(item) }, LinearLayout.LayoutParams(0, 52, 1f))
        if (favoriteAction) actions.addView(button("★ Favori") {
            background {
                request("/api/favorites", "POST", JSONObject().apply {
                    put("name", item.name)
                    put("content_id", item.ref)
                    put("group_name", "Mes favoris")
                    put("notes", "")
                })
                runOnUiThread { showMessage("Favori ajouté") }
            }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        if (delete) actions.addView(button("✕ Retirer") {
            background {
                request("/api/favorites/" + item.id, "DELETE")
                runOnUiThread { showFavorites() }
            }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        area.addView(actions)
        addBox(area)
    }

    private fun showFavorites() {
        setup("Mes favoris")
        showMessage("Chargement…")
        background {
            val arr = request("/api/favorites").optJSONArray("favorites") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map {
                StreamItem(it.optString("name"), it.optString("content_id"), it.optInt("id"), it.optString("group_name"))
            }
            runOnUiThread {
                setup("Mes favoris")
                if (items.isEmpty()) addBox(text("Aucun favori pour le moment."))
                items.forEach { addItem(it, delete = true) }
            }
        }
    }

    private fun showHistory() {
        setup("Historique")
        background {
            val arr = request("/api/history").optJSONArray("history") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map {
                StreamItem(it.optString("name"), it.optString("content_id"))
            }
            runOnUiThread {
                setup("Historique")
                if (items.isEmpty()) addBox(text("Aucune lecture récente."))
                items.forEach { addItem(it) }
            }
        }
    }

    private fun showStatus() {
        setup("État du serveur")
        addBox(text("Serveur : " + server))
        background {
            val result = request("/api/monitor")
            val vpn = result.optJSONObject("vpn")
            val engine = result.optJSONObject("engine")
            runOnUiThread {
                addBox(text("Proton VPN : " + if (vpn?.optBoolean("connected") == true) "Connecté" else "Non confirmé"))
                addBox(text("IP de sortie : " + (vpn?.optString("public_ip") ?: "—")))
                addBox(text("Moteur AceStream : " + if (engine?.optBoolean("online") == true) "En ligne" else "Hors ligne"))
                addBox(button("Actualiser") { showStatus() })
                addBox(button("Changer de compte ou de serveur") {
                    stop()
                    user = ""
                    synchronized(cookies) { cookies.clear() }
                    csrf = ""
                    showLogin()
                })
            }
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && player.currentMediaItem != null) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            catch (_: Exception) { showMessage("PiP non pris en charge sur cet appareil.") }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying) enterPip()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (activeStream.isNotBlank() && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val view = PlayerView(this).apply { player = this@MainActivity.player; useController = true }
            setContentView(view)
        } else if (user.isNotBlank()) {
            showPlayer()
        }
    }

    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        if (inPictureInPictureMode) {
            setContentView(PlayerView(this).apply { player = this@MainActivity.player; useController = false })
        } else if (user.isNotBlank()) showPlayer()
    }

    override fun onDestroy() {
        player.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
