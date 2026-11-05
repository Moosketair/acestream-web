package fr.hobbitdecheval.acestream

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.MediaLoadRequestData
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

class MainActivity : AppCompatActivity() {
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
    private var castContext: CastContext? = null
    private var castListener: SessionManagerListener<CastSession>? = null
    private var castRequestPending = false
    private var castedStreamId = ""
    private var currentScreen = "Connexion"
    private var playerView: PlayerView? = null
    private var searchGeneration = 0
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
        initializeCast()
        showLogin()
    }

    private fun initializeCast() {
        try {
            val context = CastContext.getSharedInstance(this)
            castContext = context
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    if (activeStream.isNotBlank()) castActiveStream()
                    else showMessage("Chromecast connecté : lancez un flux.")
                }
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    showMessage("Connexion Chromecast impossible (code " + error + ").")
                }
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionEnded(session: CastSession, error: Int) {
                    castedStreamId = ""
                    showMessage("Chromecast déconnecté. Lecture locale disponible.")
                }
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    if (activeStream.isNotBlank()) castActiveStream()
                }
                override fun onSessionResumeFailed(session: CastSession, error: Int) {}
                override fun onSessionSuspended(session: CastSession, reason: Int) {}
            }
            castListener = listener
            context.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        } catch (_: Exception) {
            // Google Play Services is optional. Local HLS playback still works.
            castContext = null
        }
    }

    private fun castActiveStream() {
        val sid = activeStream
        if (sid.isBlank() || castRequestPending) return
        if (castContext?.sessionManager?.currentCastSession?.isConnected != true) {
            showMessage("Appuie d'abord sur l'icône Cast pour choisir ton Chromecast.")
            return
        }
        castRequestPending = true
        showMessage("Préparation du lien Chromecast sécurisé…")
        executor.execute {
            try {
                val relative = request("/api/streams/" + sid + "/cast-link", "POST", JSONObject())
                    .optString("url")
                if (!relative.startsWith("/cast/") || !relative.endsWith("/index.m3u8")) {
                    throw IllegalStateException("Lien de diffusion TV invalide.")
                }
                val castUrl = server + relative
                runOnUiThread {
                    castRequestPending = false
                    if (activeStream != sid) return@runOnUiThread
                    val session = castContext?.sessionManager?.currentCastSession
                    val remote = session?.remoteMediaClient
                    if (session?.isConnected != true || remote == null) {
                        showMessage("Chromecast déconnecté avant le démarrage.")
                        return@runOnUiThread
                    }
                    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                        putString(MediaMetadata.KEY_TITLE, activeName.ifBlank { "AceStream TV" })
                    }
                    val media = MediaInfo.Builder(castUrl)
                        .setContentType("application/x-mpegURL")
                        .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                        .setMetadata(metadata)
                        .build()
                    remote.load(MediaLoadRequestData.Builder()
                        .setMediaInfo(media).setAutoplay(true).build())
                        .setResultCallback { result ->
                            if (result.status.isSuccess && activeStream == sid) {
                                castedStreamId = sid
                                player.pause()
                                showMessage("Diffusion en cours sur Chromecast.")
                            } else {
                                showMessage("Le Chromecast n'a pas pu ouvrir le flux HLS.")
                            }
                        }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    castRequestPending = false
                    showMessage("Cast indisponible : " + (error.message ?: "Erreur réseau"))
                }
            }
        }
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
        runOnUiThread {
            if (::notice.isInitialized) {
                notice.text = text
                notice.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun d(value: Int): Int = AceDesign.dp(this, value)

    private fun text(label: String, size: Float = 15f): TextView =
        AceDesign.label(this, label, size)

    private fun button(label: String, onClick: () -> Unit): TextView =
        AceDesign.action(this, label, primary = true, onClick = onClick)

    private fun secondary(label: String, compact: Boolean = false, onClick: () -> Unit): TextView =
        AceDesign.action(this, label, primary = false, compact = compact, onClick = onClick)

    private fun smallButton(label: String, onClick: () -> Unit): TextView =
        AceDesign.action(this, label, primary = true, compact = true, onClick = onClick)

    private fun field(hint: String, initial: String = ""): EditText =
        AceDesign.input(this, hint, initial)

    private fun card(accent: Int? = null, inset: Int = 16): LinearLayout =
        AceDesign.card(this, accent, inset)

    private fun caption(label: String): TextView =
        AceDesign.caption(this, label)

    private fun heading(label: String, size: Float = 17f): TextView =
        AceDesign.label(this, label, size, AceDesign.text, true)

    private fun labelledInput(label: String, edit: EditText): LinearLayout =
        AceDesign.inputSection(this, label, edit)

    private fun row(): LinearLayout = AceDesign.row(this)

    private fun put(parent: LinearLayout, view: View, bottom: Int = 12) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = d(bottom)
        })
    }

    private fun setup(title: String, navigation: Boolean = true) {
        playerView?.player = null
        playerView = null
        currentScreen = title
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AceDesign.gradient(this@MainActivity,
                Color.rgb(12, 20, 39), AceDesign.bg, Color.rgb(14, 13, 34), radius = 0)
        }
        setContentView(root)
        window.statusBarColor = AceDesign.sidebar
        window.navigationBarColor = AceDesign.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        if (navigation) {
            val header = row().apply {
                setPadding(d(15), d(7), d(15), d(7))
                setBackgroundColor(AceDesign.sidebar)
            }
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.brand_logo)
                contentDescription = "Logo AceStream TV"
            }, LinearLayout.LayoutParams(d(36), d(36)).apply { marginEnd = d(9) })
            header.addView(heading("AceStream TV", 19f),
                LinearLayout.LayoutParams(0, -2, 1f))
            if (castContext != null) {
                try {
                    val castButton = MediaRouteButton(this).apply {
                        contentDescription = "Choisir un Chromecast"
                                            }
                    CastButtonFactory.setUpMediaRouteButton(applicationContext, castButton)
                    header.addView(castButton, LinearLayout.LayoutParams(d(46), d(47)))
                } catch (_: Exception) { /* Chromecast optional */ }
            }
            val settingsButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_nav_settings)
                setColorFilter(AceDesign.text)
                contentDescription = "Paramètres"
                setPadding(d(11), d(11), d(11), d(11))
                setOnClickListener { showServerSettings() }
            }
            header.addView(settingsButton, LinearLayout.LayoutParams(d(45), d(47)))
            root.addView(header)
            root.addView(View(this).apply { setBackgroundColor(AceDesign.border) },
                LinearLayout.LayoutParams(-1, d(1)))
        }

        val scroller = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(if (navigation) 21 else 24), d(16), d(25))
        }
        scroller.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        if (navigation) {
            put(content, heading(title, 23f), 5)
            put(content, caption(when (title) {
                "Tableau de bord" -> "Recherchez, diffusez et retrouvez vos flux en toute simplicité."
                "Recherche" -> "Retrouvez vos chaînes et événements AceStream."
                "Mes favoris" -> "Vos chaînes et événements enregistrés."
                "Historique" -> "Les derniers flux que vous avez regardés."
                "Paramètres" -> "Personnalisez votre connexion et votre diffusion."
                else -> "AceStream TV"
            }), 12)
        }
        notice = caption("").apply {
            setTextColor(AceDesign.purple)
            visibility = View.GONE
            setPadding(d(6), d(7), d(6), d(11))
        }
        put(content, notice, 0)

        if (navigation) {
            val nav = row().apply {
                setPadding(d(7), d(7), d(7), d(9))
                setBackgroundColor(AceDesign.sidebar)
            }
            val tabs = listOf(
                Triple("Accueil", R.drawable.ic_nav_home, "Tableau de bord"),
                Triple("Recherche", R.drawable.ic_nav_search, "Recherche"),
                Triple("Favoris", R.drawable.ic_nav_star, "Mes favoris"),
                Triple("Historique", R.drawable.ic_nav_history, "Historique"),
                Triple("Paramètres", R.drawable.ic_nav_settings, "Paramètres")
            )
            for ((index, tab) in tabs.withIndex()) {
                val active = title == tab.third || (title == "État du système" && index == 4)
                val tabView = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    orientation = LinearLayout.VERTICAL
                    setPadding(d(2), d(7), d(2), d(7))
                    background = if (active) AceDesign.gradient(this@MainActivity,
                        Color.rgb(80, 62, 169), Color.rgb(48, 38, 107), radius = 13)
                        else AceDesign.fill(AceDesign.sidebar, 13, context = this@MainActivity)
                    setOnClickListener {
                        when (index) {
                            0 -> showPlayer()
                            1 -> showSearch()
                            2 -> showFavorites()
                            3 -> showHistory()
                            else -> showServerSettings()
                        }
                    }
                    isClickable = true
                    isFocusable = true
                }
                val tint = if (active) Color.rgb(202, 177, 255) else AceDesign.muted
                tabView.addView(ImageView(this).apply {
                    setImageResource(tab.second)
                    setColorFilter(tint)
                    contentDescription = tab.first
                }, LinearLayout.LayoutParams(d(24), d(24)))
                tabView.addView(AceDesign.label(this, tab.first, 10f,
                    if (active) AceDesign.text else AceDesign.muted, active),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(5) })
                nav.addView(tabView, LinearLayout.LayoutParams(0, d(60), 1f).apply {
                    marginEnd = if (index == 4) 0 else d(3)
                })
            }
            root.addView(View(this).apply { setBackgroundColor(AceDesign.border) },
                LinearLayout.LayoutParams(-1, d(1)))
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addBox(view: View, bottom: Int = 13) {
        put(content, view, bottom)
    }

    private fun normalizedServer(value: String): String? {
        val candidate = value.trim().trimEnd('/')
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" && url.scheme != "http") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return candidate
    }

    private fun showServerSettings() {
        setup("Paramètres")
        val settingsCard = card()
        put(settingsCard, heading("⚙  Connexion au serveur", 17f), 8)
        put(settingsCard, caption("L'adresse est enregistrée sur ton téléphone. Elle sert également à la lecture Chromecast."), 16)
        val address = field("https://ace.hobbit-de-cheval.fr", server).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        put(settingsCard, labelledInput("Adresse du serveur", address), 18)
        put(settingsCard, button("✓  Enregistrer l'adresse") {
            val value = normalizedServer(address.text.toString())
            if (value == null) {
                showMessage("Adresse invalide : utilise http:// ou https://.")
                return@button
            }
            if (value == server) {
                showMessage("Cette adresse est déjà enregistrée.")
                return@button
            }
            if (activeStream.isNotBlank()) {
                showMessage("Arrête d'abord la lecture avant de changer de serveur.")
                return@button
            }
            server = value
            synchronized(cookies) { cookies.clear() }
            csrf = ""
            user = ""
            getSharedPreferences("acestream", MODE_PRIVATE).edit().putString("server", server).apply()
            showLogin()
        }, 0)
        addBox(settingsCard, 16)

        val account = card()
        put(account, heading("♙  Mon compte"), 9)
        put(account, caption(if (user.isBlank()) "Non connecté" else "Connecté : " + user), 14)
        put(account, secondary("↪  Changer de compte") {
            stop()
            user = ""
            synchronized(cookies) { cookies.clear() }
            csrf = ""
            showLogin()
        }, 0)
        addBox(account, 16)

        val infos = card()
        put(infos, heading("🛡  Serveur et diffusion"), 9)
        put(infos, caption("Vérifie l'état du VPN, du moteur AceStream et des diffusions."), 13)
        put(infos, secondary("Consulter les informations système") { showStatus() }, 0)
        addBox(infos)
        addBox(caption("AceStream TV · Android 1.2.0 · Application privée"), 3)
    }

    private fun showLogin() {
        setup("Connexion", navigation = false)
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.brand_logo)
            contentDescription = "AceStream TV"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(logo, LinearLayout.LayoutParams(-1, d(112)).apply {
            topMargin = d(23)
            bottomMargin = d(8)
        })
        val title = heading("AceStream TV", 29f).apply { gravity = Gravity.CENTER }
        put(content, title, 4)
        put(content, caption("Lecture de flux AceStream en toute simplicité").apply {
            gravity = Gravity.CENTER
        }, 29)

        val loginCard = card(inset = 16)
        val address = field("Adresse HTTP(S) du serveur", server).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val login = field("Nom d'utilisateur")
        val password = field("Mot de passe").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        put(loginCard, labelledInput("Adresse du serveur", address), 14)
        put(loginCard, labelledInput("Nom d'utilisateur", login), 14)
        put(loginCard, labelledInput("Mot de passe", password), 23)
        put(loginCard, button("→  Se connecter") {
            val given = normalizedServer(address.text.toString())
            if (given == null) {
                showMessage("Indique une adresse HTTP(S) valide.")
                return@button
            }
            val username = login.text.toString().trim()
            val passwordValue = password.text.toString()
            if (username.isBlank() || passwordValue.isBlank()) {
                showMessage("Renseigne ton identifiant et ton mot de passe.")
                return@button
            }
            if (given != server) {
                synchronized(cookies) { cookies.clear() }
                csrf = ""
            }
            server = given
            getSharedPreferences("acestream", MODE_PRIVATE).edit().putString("server", server).apply()
            showMessage("Connexion à ton serveur…")
            background {
                request("/api/auth/session")
                val result = request("/api/auth/login", "POST", JSONObject().apply {
                    put("username", username)
                    put("password", passwordValue)
                })
                user = result.getJSONObject("user").optString("username")
                runOnUiThread { showPlayer() }
            }
        }, 0)
        addBox(loginCard, 19)
        val tip = card(inset = 13)
        put(tip, heading("ⓘ  Adresse personnalisable", 14f), 5)
        put(tip, caption("Tu peux utiliser ton domaine HTTPS ou l'adresse IP locale de ton serveur. Cette adresse restera mémorisée."), 0)
        addBox(tip, 28)
    }

    private fun shortcut(icon: String, name: String, detail: String, onClick: () -> Unit): LinearLayout {
        val shortcut = card(inset = 14)
        shortcut.background = AceDesign.gradient(this, Color.rgb(30, 38, 70), Color.rgb(19, 28, 47), radius = 15)
        shortcut.addView(heading(icon, 27f), AceDesign.margin(this, 8))
        shortcut.addView(heading(name, 16f), AceDesign.margin(this, 5))
        shortcut.addView(caption(detail), AceDesign.margin(this, 0))
        shortcut.isClickable = true
        shortcut.isFocusable = true
        shortcut.setOnClickListener { onClick() }
        return shortcut
    }

    private fun statusCard(symbol: String, label: String, color: Int): Triple<LinearLayout, TextView, TextView> {
        val card = card(accent = Color.argb(105, Color.red(color), Color.green(color), Color.blue(color)), inset = 13)
        val row = row()
        row.addView(AceDesign.label(this, symbol, 23f, color, true),
            LinearLayout.LayoutParams(d(37), -2))
        row.addView(heading(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        val badge = AceDesign.chip(this, "Vérification", color, Color.rgb(33, 41, 60))
        row.addView(badge)
        put(card, row, 7)
        val detail = caption("Chargement des informations…")
        put(card, detail, 0)
        return Triple(card, badge, detail)
    }

    private fun showPlayer() {
        setup("Tableau de bord")
        val playerCard = card()
        val header = row()
        header.addView(heading("▶  Lecteur vidéo", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(AceDesign.chip(this,
            if (activeStream.isBlank()) "En attente" else "En lecture",
            if (activeStream.isBlank()) AceDesign.muted else AceDesign.green,
            if (activeStream.isBlank()) Color.rgb(39, 49, 72) else Color.rgb(22, 55, 47)))
        put(playerCard, header, 13)

        val frame = FrameLayout(this).apply {
            background = AceDesign.fill(Color.rgb(8, 14, 28), 14, AceDesign.border, this@MainActivity)
            clipToOutline = true
        }
        val playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setBackgroundColor(Color.rgb(8, 14, 28))
        }
        this.playerView = playerView
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        if (activeStream.isBlank()) {
            val placeholder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            put(placeholder, AceDesign.label(this, "▶", 41f, AceDesign.purple, true).apply {
                gravity = Gravity.CENTER
                background = AceDesign.fill(Color.rgb(49, 35, 93), 80, AceDesign.purple, this@MainActivity)
                minWidth = d(73)
                minHeight = d(73)
            }, 8)
            put(placeholder, heading("Prêt pour la lecture", 16f).apply {
                gravity = Gravity.CENTER
            }, 4)
            put(placeholder, caption("Choisissez un résultat, un favori ou collez un identifiant.").apply {
                gravity = Gravity.CENTER
            }, 0)
            frame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        }
        val screenWidth = resources.displayMetrics.widthPixels - d(68)
        put(playerCard, frame, 12)
        frame.layoutParams.height = ((screenWidth * 9f / 16f).toInt()).coerceAtLeast(d(185))
        if (activeName.isNotBlank()) put(playerCard, heading(activeName, 14f), 11)

        val playerActions = row()
        val landscape = secondary("⛶  Plein écran", true) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val pip = secondary("▣  Mini-lecteur", true) { enterPip() }
        playerActions.addView(landscape, LinearLayout.LayoutParams(0, d(42), 1f).apply { marginEnd = d(8) })
        playerActions.addView(pip, LinearLayout.LayoutParams(0, d(42), 1f))
        put(playerCard, playerActions, 15)

        val manual = field("Hash ou lien AceStream…")
        put(playerCard, labelledInput("Lecture manuelle", manual), 10)
        val buttons = row()
        buttons.addView(smallButton("▶  Lire") {
            val input = manual.text.toString().trim()
            if (input.isNotBlank()) play(StreamItem("Flux manuel", input))
            else showMessage("Saisis un identifiant AceStream.")
        }, LinearLayout.LayoutParams(0, d(43), 1f).apply { marginEnd = d(9) })
        buttons.addView(secondary("■  Arrêter", true) { stop() },
            LinearLayout.LayoutParams(0, d(43), 1f))
        put(playerCard, buttons, 9)
        if (castContext != null) {
            put(playerCard, secondary("▣  Diffuser sur Chromecast", true) { castActiveStream() }, 0)
        } else {
            put(playerCard, caption("Google Cast disponible sur les appareils compatibles."), 0)
        }
        addBox(playerCard, 18)

        addBox(heading("Accès rapide", 18f), 12)
        val actions = row()
        actions.addView(shortcut("⌕", "Recherche", "Trouver un flux AceStream") { showSearch() },
            LinearLayout.LayoutParams(0, d(132), 1f).apply { marginEnd = d(10) })
        actions.addView(shortcut("★", "Favoris", "Vos flux enregistrés") { showFavorites() },
            LinearLayout.LayoutParams(0, d(132), 1f))
        addBox(actions, 19)

        addBox(heading("État du système", 18f), 12)
        val vpn = statusCard("◇", "Proton VPN", AceDesign.green)
        val engine = statusCard("⚙", "Moteur AceStream", Color.rgb(111, 183, 255))
        val broadcast = statusCard("◉", "Diffusion", AceDesign.purple)
        vpn.second.text = "Vérification"
        engine.second.text = "Vérification"
        broadcast.second.text = if (activeStream.isNotBlank()) "Active" else "Inactive"
        broadcast.third.text = if (activeStream.isNotBlank()) activeName else "Aucun flux actif."
        addBox(vpn.first, 10)
        addBox(engine.first, 10)
        addBox(broadcast.first, 10)
        background {
            val data = request("/api/monitor")
            val vpnData = data.optJSONObject("vpn")
            val engData = data.optJSONObject("engine")
            runOnUiThread {
                if (currentScreen != "Tableau de bord") return@runOnUiThread
                vpn.second.text = if (vpnData?.optBoolean("connected") == true) "Protégé" else "Indisponible"
                vpn.third.text = if (vpnData?.optBoolean("connected") == true)
                    "Tunnel actif · IP de sortie : " + vpnData.optString("public_ip", "—")
                    else "Vérifie le tunnel Gluetun avant de lancer un flux."
                engine.second.text = if (engData?.optBoolean("online") == true) "En ligne" else "Hors ligne"
                engine.third.text = if (engData?.optBoolean("online") == true)
                    "Version : " + engData.optString("version", "—") +
                    " · Latence : " + engData.optString("latency_ms", "—") + " ms"
                    else "Moteur indisponible ou VPN déconnecté."
                broadcast.second.text = if (activeStream.isNotBlank()) "Active" else "Inactive"
                broadcast.third.text = if (activeStream.isNotBlank()) activeName else
                    "Aucune diffusion active · " + data.optInt("streams", 0) + " flux serveur."
            }
        }
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
                if (castContext?.sessionManager?.currentCastSession?.isConnected == true) {
                    player.stop()
                    player.clearMediaItems()
                    castActiveStream()
                } else {
                    player.setMediaItem(MediaItem.Builder()
                        .setUri(playlist).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    player.prepare()
                    player.playWhenReady = true
                    showMessage("Lecture en préparation…")
                }
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
        if (castedStreamId == old && old.isNotBlank()) {
            castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.stop()
        }
        castedStreamId = ""
        showMessage("Lecture arrêtée")
        if (old.isNotBlank()) background { request("/api/streams/" + old, "DELETE") }
    }

    private fun showSearch() {
        setup("Recherche")
        val searchCard = card()
        put(searchCard, heading("⌕  Rechercher un flux", 17f), 11)
        val input = field("Chaîne, événement ou mot-clé...", query).apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        put(searchCard, input, 10)
        val labels = listOf("Recherche du site (événements)", "Toutes les sources",
            "Index du moteur (P2P)", "Playlist du site")
        val keys = listOf("site", "all", "engine", "playlist")
        val adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(AceDesign.text)
                    it.setPadding(d(12), d(13), d(10), d(13))
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(AceDesign.text)
                    it.setBackgroundColor(AceDesign.panel2)
                    it.setPadding(d(14), d(13), d(14), d(13))
                }
        }
        val selector = Spinner(this).apply {
            this.adapter = adapter
            setSelection(keys.indexOf(searchSource).coerceAtLeast(0))
            background = AceDesign.fill(Color.rgb(13, 22, 39), 11, AceDesign.border, this@MainActivity)
        }
        val sourceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        put(sourceContainer, AceDesign.label(this, "Source de recherche", 12f, AceDesign.muted, true), 7)
        sourceContainer.addView(selector, LinearLayout.LayoutParams(-1, d(49)))
        put(searchCard, sourceContainer, 15)
        val controls = row()
        controls.addView(button("⌕  Rechercher") {
            val requested = input.text.toString().trim()
            if (requested.length < 2) {
                showMessage("Saisis au moins deux caractères.")
                return@button
            }
            query = requested
            searchSource = keys[selector.selectedItemPosition]
            val generation = ++searchGeneration
            val source = searchSource
            showMessage("Recherche en cours…")
            background {
                val url = (server + "/api/search").toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("q", requested)
                    .addQueryParameter("source", source)
                    .build()
                val response = request(url.toString())
                if (generation != searchGeneration) return@background
                val array = response.optJSONArray("results") ?: JSONArray()
                results = (0 until array.length()).mapNotNull { index ->
                    val value = array.optJSONObject(index) ?: return@mapNotNull null
                    StreamItem(
                        value.optString("name"),
                        value.optString("playback_ref").ifBlank { value.optString("content_id") },
                        category = value.optString("category")
                    )
                }
                runOnUiThread {
                    if (generation == searchGeneration && currentScreen == "Recherche") {
                        showSearch()
                        showMessage(results.size.toString() + " résultat(s) affiché(s).")
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, d(46), 1f).apply { marginEnd = d(9) })
        controls.addView(secondary("✕  Effacer", true) {
            searchGeneration++
            query = ""
            results = emptyList()
            showSearch()
        }, LinearLayout.LayoutParams(d(95), d(46)))
        put(searchCard, controls, 0)
        addBox(searchCard, 16)

        put(content, heading(
            if (results.isEmpty() && query.isBlank()) "Résultats de recherche" else
                results.size.toString() + " résultat(s) trouvés", 17f), 12)
        if (results.isEmpty()) {
            val empty = card(inset = 22)
            put(empty, AceDesign.label(this, "⌕", 33f, AceDesign.purple, true).apply {
                gravity = Gravity.CENTER
            }, 11)
            put(empty, caption(if (query.isBlank()) "Saisis un événement ou une chaîne pour commencer."
                else "Aucun résultat. Essaie une autre recherche.").apply { gravity = Gravity.CENTER }, 0)
            addBox(empty)
        } else {
            results.forEach { addItem(it, favoriteAction = true) }
        }
    }

    private fun addItem(item: StreamItem, favoriteAction: Boolean = false, delete: Boolean = false) {
        val itemCard = card(inset = 13)
        val top = row()
        val thumbnail = AceDesign.label(this, "▶", 27f, AceDesign.purple, true).apply {
            gravity = Gravity.CENTER
            background = AceDesign.gradient(this@MainActivity,
                Color.rgb(42, 54, 88), Color.rgb(43, 30, 85), radius = 12)
        }
        top.addView(thumbnail, LinearLayout.LayoutParams(d(62), d(65)).apply { marginEnd = d(12) })
        val descriptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        put(descriptions, heading(item.name, 14.5f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, 5)
        put(descriptions, caption(item.category.ifBlank { "Flux AceStream" }), 0)
        top.addView(descriptions, LinearLayout.LayoutParams(0, -2, 1f))
        put(itemCard, top, 11)
        val actions = row()
        actions.addView(smallButton("▶  Lire") { play(item) },
            LinearLayout.LayoutParams(0, d(42), 1f).apply { marginEnd = d(8) })
        if (favoriteAction) {
            actions.addView(secondary("☆  Favori", true) {
                background {
                    request("/api/favorites", "POST", JSONObject().apply {
                        put("name", item.name)
                        put("content_id", item.ref)
                        put("group_name", "Mes favoris")
                        put("notes", "")
                    })
                    showMessage("Ajouté aux favoris : " + item.name)
                }
            }, LinearLayout.LayoutParams(0, d(42), 1f))
        }
        if (delete) {
            actions.addView(secondary("✕  Retirer", true) {
                background {
                    request("/api/favorites/" + item.id, "DELETE")
                    runOnUiThread { if (currentScreen == "Mes favoris") showFavorites() }
                }
            }, LinearLayout.LayoutParams(0, d(42), 1f))
        }
        put(itemCard, actions, 0)
        addBox(itemCard, 10)
    }

    private fun showFavorites() {
        setup("Mes favoris")
        val listTitle = heading("★  Ma bibliothèque", 17f)
        addBox(listTitle, 14)
        showMessage("Chargement des favoris…")
        background {
            val array = request("/api/favorites").optJSONArray("favorites") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i) }.map {
                StreamItem(it.optString("name"), it.optString("content_id"),
                    it.optInt("id"), it.optString("group_name"))
            }
            runOnUiThread {
                if (currentScreen != "Mes favoris") return@runOnUiThread
                showMessage(items.size.toString() + " favori(s).")
                if (items.isEmpty()) {
                    val empty = card()
                    put(empty, caption("Aucun favori. Ajoute-en depuis une recherche."), 0)
                    addBox(empty)
                } else items.forEach { addItem(it, delete = true) }
            }
        }
    }

    private fun showHistory() {
        setup("Historique")
        addBox(heading("◷  Lectures récentes", 17f), 14)
        showMessage("Chargement de l'historique…")
        background {
            val array = request("/api/history").optJSONArray("history") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i) }.map {
                StreamItem(it.optString("name"), it.optString("content_id"))
            }
            runOnUiThread {
                if (currentScreen != "Historique") return@runOnUiThread
                showMessage(items.size.toString() + " lecture(s) récente(s).")
                if (items.isEmpty()) {
                    val empty = card()
                    put(empty, caption("Aucune lecture enregistrée."), 0)
                    addBox(empty)
                } else items.forEach { addItem(it) }
            }
        }
    }

    private fun showStatus() {
        setup("État du système")
        addBox(caption("Serveur : " + server), 15)
        val vpn = statusCard("◇", "Proton VPN", AceDesign.green)
        val engine = statusCard("⚙", "Moteur AceStream", Color.rgb(111, 183, 255))
        val broadcast = statusCard("◉", "Diffusion", AceDesign.purple)
        addBox(vpn.first, 10)
        addBox(engine.first, 10)
        addBox(broadcast.first, 12)
        addBox(button("↻  Actualiser") { showStatus() }, 14)
        addBox(secondary("←  Retour aux paramètres") { showServerSettings() })
        background {
            val data = request("/api/monitor")
            val vpnData = data.optJSONObject("vpn")
            val engineData = data.optJSONObject("engine")
            runOnUiThread {
                if (currentScreen != "État du système") return@runOnUiThread
                vpn.second.text = if (vpnData?.optBoolean("connected") == true) "Protégé" else "Indisponible"
                vpn.third.text = "IP de sortie : " + (vpnData?.optString("public_ip") ?: "—")
                engine.second.text = if (engineData?.optBoolean("online") == true) "En ligne" else "Hors ligne"
                engine.third.text = "Version : " + (engineData?.optString("version") ?: "—") +
                    " · Latence : " + (engineData?.optString("latency_ms") ?: "—") + " ms"
                broadcast.second.text = if (activeStream.isNotBlank()) "Active" else "Inactive"
                broadcast.third.text = if (activeStream.isNotBlank()) activeName else
                    data.optInt("streams", 0).toString() + " flux serveur / max. " + data.optInt("max_streams", 0)
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
            playerView?.player = null
            val view = PlayerView(this).apply {
                player = this@MainActivity.player
                useController = true
                setBackgroundColor(Color.BLACK)
            }
            playerView = view
            setContentView(view)
        } else if (user.isNotBlank()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showPlayer()
        }
    }

    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        if (inPictureInPictureMode) {
            playerView?.player = null
            val view = PlayerView(this).apply {
                player = this@MainActivity.player
                useController = false
            }
            playerView = view
            setContentView(view)
        } else if (user.isNotBlank()) showPlayer()
    }

    override fun onDestroy() {
        playerView?.player = null
        castListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
        player.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
