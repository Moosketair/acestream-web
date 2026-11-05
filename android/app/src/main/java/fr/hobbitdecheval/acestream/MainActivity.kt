package fr.hobbitdecheval.acestream

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.view.OrientationEventListener
import android.hardware.SensorManager
import android.graphics.Rect
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
    private var searchScrollY = 0
    private var screenScrollView: ScrollView? = null
    private var screenBeforePip = "Tableau de bord"
    private var autoPipEnabled = true
    private var fullscreen = false
    private var fullscreenRequestedByUser = false
    private var suppressLandscapeFullscreen = false
    private var screenBeforeFullscreen = "Tableau de bord"
    private var placeholderView: android.view.View? = null
    private var playbackBadge: TextView? = null
    private var loadingOverlay: View? = null
    private var playbackStarting = false
    private var playbackGeneration = 0
    private val biometricLogin by lazy { BiometricLogin(this) }
    private val orientationListener by lazy {
        object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || isInPictureInPictureMode ||
                    fullscreenRequestedByUser) return
                val isLandscape = degrees in 60..120 || degrees in 240..300
                val isPortrait = degrees in 0..30 || degrees in 330..359 ||
                    degrees in 150..210
                if (isPortrait) {
                    suppressLandscapeFullscreen = false
                    // Return to portrait even when the phone's global auto
                    // rotation switch is disabled.
                    if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                } else if (isLandscape &&
                    player.currentMediaItem != null &&
                    !fullscreen && !suppressLandscapeFullscreen &&
                    requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }
    private lateinit var client: OkHttpClient
    private lateinit var player: ExoPlayer
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val settings = getSharedPreferences("acestream", MODE_PRIVATE)
        server = settings.getString("server", server) ?: server
        autoPipEnabled = settings.getBoolean("auto_pip", true)
        restoreSearchCache()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen -> exitFullscreen()
                    isInPictureInPictureMode -> Unit
                    currentScreen != "Tableau de bord" && currentScreen != "Connexion" ->
                        showPlayer()
                    autoPipEnabled && shouldOfferPip() -> enterPip()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
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
                runOnUiThread {
                    playbackStarting = false
                    showMessage("Lecture impossible : " + error.message.orEmpty())
                    updatePlaybackState()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackState()
            override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackState()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlaybackState()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updatePlaybackState()
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

    private fun showSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun shouldOfferPip(): Boolean =
        activeStream.isNotBlank() &&
        player.currentMediaItem != null &&
        player.playWhenReady &&
        player.playbackState != Player.STATE_ENDED

    private fun updatePlaybackState() {
        if (!::player.isInitialized) return
        val keepsAwake = shouldOfferPip()
        if (keepsAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val rect = Rect()
                playerView?.getGlobalVisibleRect(rect)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(autoPipEnabled && keepsAwake)
                if (!rect.isEmpty()) params.setSourceRectHint(rect)
                setPictureInPictureParams(params.build())
            } catch (_: Exception) { /* Picture in Picture is optional. */ }
        }
        if (player.playbackState == Player.STATE_READY) playbackStarting = false
        val buffering = player.currentMediaItem != null &&
            player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        val waiting = playbackStarting || buffering
        val showingVideo = activeStream.isNotBlank() ||
            player.currentMediaItem != null || waiting
        placeholderView?.visibility = if (showingVideo) View.GONE else View.VISIBLE
        loadingOverlay?.visibility = if (waiting) View.VISIBLE else View.GONE
        playbackBadge?.let {
            it.text = when {
                waiting -> "Chargement…"
                showingVideo -> "Lecture en cours"
                else -> "En attente"
            }
        }
    }

    private fun restoreSearchCache() {
        val prefs = getSharedPreferences("acestream", MODE_PRIVATE)
        if (prefs.getString("search_server", "") != server) return
        query = prefs.getString("search_query", "") ?: ""
        searchSource = prefs.getString("search_source", "site") ?: "site"
        results = try {
            val array = JSONArray(prefs.getString("search_results", "[]") ?: "[]")
            (0 until minOf(array.length(), 200)).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val name = obj.optString("name")
                val ref = obj.optString("ref")
                if (name.isBlank() || ref.isBlank()) null else
                    StreamItem(name, ref, category = obj.optString("category"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun clearSearchCache() {
        searchGeneration++
        query = ""
        searchSource = "site"
        results = emptyList()
        searchScrollY = 0
        saveSearchCache()
    }

    private fun saveSearchCache() {
        val array = JSONArray()
        results.take(200).forEach { item ->
            array.put(JSONObject().put("name", item.name).put("ref", item.ref)
                .put("category", item.category))
        }
        getSharedPreferences("acestream", MODE_PRIVATE).edit()
            .putString("search_user", user)
            .putString("search_server", server)
            .putString("search_query", query)
            .putString("search_source", searchSource)
            .putString("search_results", array.toString())
            .apply()
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
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
        if (currentScreen == "Recherche") {
            searchScrollY = screenScrollView?.scrollY ?: searchScrollY
        }
        playerView?.player = null
        playerView = null
        placeholderView = null
        playbackBadge = null
        loadingOverlay = null
        fullscreen = false
        currentScreen = title
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AceDesign.gradient(this@MainActivity,
                Color.rgb(12, 20, 39), AceDesign.bg, Color.rgb(14, 13, 34), radius = 0)
        }
        setContentView(root)
        showSystemBars()
        window.statusBarColor = AceDesign.sidebar
        window.navigationBarColor = AceDesign.bg
        // Target SDK 35 draws below the system bars. Apply the real safe-area
        // insets to the entire dashboard, including the bottom tab bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, safe.top, 0, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

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
        screenScrollView = scroller
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
                Triple("Historique", R.drawable.ic_nav_history, "Historique")
            )
            for ((index, tab) in tabs.withIndex()) {
                val active = title == tab.third
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
                            else -> showHistory()
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
                    marginEnd = if (index == tabs.lastIndex) 0 else d(3)
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
            clearSearchCache()
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

        val biometricCard = card()
        put(biometricCard, heading("◉  Connexion par empreinte", 17f), 8)
        val enrolledUser = biometricLogin.savedUser(server)
        put(biometricCard, caption(when {
            !biometricLogin.available() ->
                "L'empreinte forte n'est pas configurée ou prise en charge sur cet appareil."
            enrolledUser != null ->
                "Activée pour le compte " + enrolledUser + " sur ce serveur."
            else ->
                "Pour l'activer, déconnecte-toi puis coche l'option sur la page de connexion."
        }), 12)
        if (enrolledUser != null) {
            put(biometricCard, secondary("✕  Désactiver l'empreinte") {
                biometricLogin.forget()
                showServerSettings()
                showMessage("Les identifiants biométriques ont été supprimés du téléphone.")
            }, 0)
        }
        addBox(biometricCard, 16)

        val pipCard = card()
        put(pipCard, heading("▣  Mini-lecteur automatique", 17f), 10)
        val pipRow = row()
        val pipLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        put(pipLabels, heading("PiP en quittant l'application", 14f), 5)
        put(pipLabels, caption(
            "Réduire la vidéo quand tu reviens à l'accueil Android ou aux applications récentes."
        ), 0)
        pipRow.addView(pipLabels, LinearLayout.LayoutParams(0, -2, 1f)
            .apply { marginEnd = d(10) })
        val pipToggle = SwitchCompat(this).apply {
            isChecked = autoPipEnabled
            contentDescription = "Activer ou désactiver le PiP automatique"
            thumbTintList = ColorStateList.valueOf(AceDesign.purple)
            setOnCheckedChangeListener { _, checked ->
                autoPipEnabled = checked
                getSharedPreferences("acestream", MODE_PRIVATE).edit()
                    .putBoolean("auto_pip", checked).apply()
                updatePlaybackState()
            }
        }
        pipRow.addView(pipToggle, LinearLayout.LayoutParams(-2, d(55)))
        put(pipCard, pipRow, 12)
        put(pipCard, caption(
            "Tu peux toujours activer le PiP manuellement avec le bouton Mini-lecteur. " +
            "Autorise également le mode Picture-in-Picture pour AceStream TV dans les paramètres Android."
        ), 0)
        addBox(pipCard, 16)

        val infos = card()
        put(infos, heading("🛡  Serveur et diffusion"), 9)
        put(infos, caption("Vérifie l'état du VPN, du moteur AceStream et des diffusions."), 13)
        put(infos, secondary("Consulter les informations système") { showStatus() }, 0)
        addBox(infos)
        addBox(caption("AceStream TV · Android 1.3.0 · Application privée"), 3)
    }

    private fun signIn(username: String, password: String,
                       enableBiometricAfterLogin: Boolean = false) {
        showMessage("Connexion au serveur…")
        background {
            request("/api/auth/session")
            val response = request("/api/auth/login", "POST", JSONObject().apply {
                put("username", username)
                put("password", password)
            })
            val signedInUser = response.getJSONObject("user").optString("username")
            runOnUiThread {
                if (getSharedPreferences("acestream", MODE_PRIVATE)
                        .getString("search_user", "") != signedInUser) {
                    user = signedInUser
                    clearSearchCache()
                } else {
                    user = signedInUser
                }
                showPlayer()
                if (enableBiometricAfterLogin && biometricLogin.available()) {
                    biometricLogin.enable(server, signedInUser, password) { _, status ->
                        showMessage(status)
                    }
                }
            }
        }
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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val login = field("Nom d'utilisateur", biometricLogin.savedUser(server) ?: "")
        val password = field("Mot de passe").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        put(loginCard, labelledInput("Adresse du serveur", address), 14)
        put(loginCard, labelledInput("Nom d'utilisateur", login), 14)
        put(loginCard, labelledInput("Mot de passe", password), 14)

        val fingerprintAvailable = biometricLogin.available()
        val enrollCheckbox = CheckBox(this).apply {
            text = "Activer la connexion par empreinte après cette connexion"
            setTextColor(AceDesign.text)
            textSize = 13f
            buttonTintList = ColorStateList.valueOf(AceDesign.purple)
            isChecked = false
            isEnabled = fingerprintAvailable
        }
        if (fingerprintAvailable) {
            put(loginCard, enrollCheckbox, 7)
            put(loginCard, caption(
                "Facultatif : identifiants chiffrés dans le coffre sécurisé Android, " +
                "déverrouillés uniquement avec ton empreinte. HTTPS obligatoire."
            ), 15)
        }

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
                server = given
                clearSearchCache()
                biometricLogin.forget()
            }
            server = given
            getSharedPreferences("acestream", MODE_PRIVATE)
                .edit().putString("server", server).apply()
            val biometricRequested = fingerprintAvailable && enrollCheckbox.isChecked
            if (biometricRequested && !server.startsWith("https://", true)) {
                showMessage("Désactive l'empreinte ou utilise une adresse HTTPS.")
                return@button
            }
            signIn(username, passwordValue, biometricRequested)
        }, 9)

        val savedUser = biometricLogin.savedUser(server)
        if (fingerprintAvailable && savedUser != null) {
            put(loginCard, secondary("◉  Se connecter avec mon empreinte") {
                val given = normalizedServer(address.text.toString())
                if (given == null || biometricLogin.savedUser(given) == null) {
                    showMessage("Cette empreinte est enregistrée pour une autre adresse serveur.")
                    return@secondary
                }
                if (given != server) {
                    synchronized(cookies) { cookies.clear() }
                    csrf = ""
                    server = given
                    clearSearchCache()
                }
                getSharedPreferences("acestream", MODE_PRIVATE)
                    .edit().putString("server", server).apply()
                biometricLogin.authenticate(server,
                    { savedLogin, decryptedPassword ->
                        login.setText(savedLogin)
                        password.setText("")
                        signIn(savedLogin, decryptedPassword)
                    },
                    { error -> showMessage(error) })
            }, 0)
        }
        addBox(loginCard, 19)

        val tip = card(inset = 13)
        put(tip, heading("ⓘ  Adresse personnalisable", 14f), 5)
        put(tip, caption(
            "Utilise ton domaine HTTPS ou l'adresse IP locale de ton serveur. " +
            "L'adresse reste mémorisée sur ton téléphone."
        ), 0)
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
        val statusChip = AceDesign.chip(this,
            if (activeStream.isBlank()) "En attente" else "Lecture en cours",
            if (activeStream.isBlank()) AceDesign.muted else AceDesign.green,
            if (activeStream.isBlank()) Color.rgb(39, 49, 72) else Color.rgb(22, 55, 47))
        playbackBadge = statusChip
        header.addView(statusChip)
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
        // This placeholder is completely removed from the player surface
        // as soon as an AceStream session or HLS media item exists.
        val placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(d(10), d(8), d(10), d(8))
            background = AceDesign.fill(Color.rgb(8, 14, 28), 14,
                context = this@MainActivity)
        }
        val circularPlay = FrameLayout(this).apply {
            background = AceDesign.fill(Color.rgb(49, 35, 93), 80,
                AceDesign.purple, this@MainActivity)
        }
        val playSymbol = ImageView(this).apply {
            setImageResource(R.drawable.ic_play_centered)
            contentDescription = "Lecture"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        circularPlay.addView(playSymbol, FrameLayout.LayoutParams(d(26), d(26),
            Gravity.CENTER).apply {
            // Triangle has more visual mass to its left: optical correction.
            leftMargin = d(3)
        })
        placeholder.addView(circularPlay, LinearLayout.LayoutParams(d(58), d(58)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = d(12)
        })
        placeholder.addView(heading("Prêt pour la lecture", 15f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(5) })
        placeholder.addView(caption(
            "Choisissez un résultat, un favori ou collez un identifiant."
        ).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        placeholderView = placeholder
        frame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))

        // Indeterminate animation while the AceStream server prepares a stream
        // and whenever Media3 needs to buffer a new HLS segment.
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(231, 8, 14, 28))
            visibility = View.GONE
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(AceDesign.purple)
        }
        loading.addView(spinner, LinearLayout.LayoutParams(d(49), d(49)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = d(10)
        })
        loading.addView(heading("Chargement du flux…", 14f).apply {
            gravity = Gravity.CENTER
        })
        loading.addView(caption("Connexion au moteur AceStream").apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = d(6)
        })
        loadingOverlay = loading
        frame.addView(loading, FrameLayout.LayoutParams(-1, -1))
        val screenWidth = resources.displayMetrics.widthPixels - d(68)
        put(playerCard, frame, 12)
        frame.layoutParams.height = ((screenWidth * 9f / 16f).toInt()).coerceAtLeast(d(185))
        if (activeName.isNotBlank()) put(playerCard, heading(activeName, 14f), 11)

        val playerActions = row()
        val landscape = secondary("⛶  Plein écran", true) {
            if (player.currentMediaItem == null) {
                showMessage("Lance une vidéo locale avant de passer en plein écran.")
            } else {
                fullscreenRequestedByUser = true
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    enterFullscreen()
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
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
        // Google Cast is available from the header; avoid a duplicate
        // button that pushes the player controls further down the screen.
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
        updatePlaybackState()
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
        val generation = ++playbackGeneration
        val previousStream = activeStream
        activeStream = ""
        activeName = item.name
        // Stop the old media before showing the new loading animation.
        player.stop()
        player.clearMediaItems()
        playbackStarting = true
        showPlayer()
        updatePlaybackState()
        showMessage("Connexion au flux AceStream…")

        executor.execute {
            try {
                if (previousStream.isNotBlank()) {
                    try { request("/api/streams/" + previousStream, "DELETE") }
                    catch (_: Exception) { /* The previous stream may have ended. */ }
                }
                val result = request("/api/streams/start", "POST", JSONObject().apply {
                    put("content_id", item.ref)
                    put("name", item.name)
                })
                val streamId = result.getString("stream_id")
                val playlist = server + result.getString("playlist")

                runOnUiThread {
                    if (generation != playbackGeneration) {
                        // Playback was cancelled while the server prepared the stream.
                        executor.execute {
                            try { request("/api/streams/" + streamId, "DELETE") }
                            catch (_: Exception) {}
                        }
                        return@runOnUiThread
                    }
                    activeStream = streamId
                    activeName = item.name
                    if (castContext?.sessionManager?.currentCastSession?.isConnected == true) {
                        playbackStarting = false
                        player.stop()
                        player.clearMediaItems()
                        castActiveStream()
                    } else {
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(playlist)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .build()
                        )
                        player.prepare()
                        player.playWhenReady = true
                        showMessage("Chargement de la vidéo…")
                    }
                    updatePlaybackState()
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        !fullscreen && !suppressLandscapeFullscreen && !isInPictureInPictureMode) {
                        enterFullscreen()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation != playbackGeneration) return@runOnUiThread
                    playbackStarting = false
                    activeStream = ""
                    activeName = ""
                    updatePlaybackState()
                    showMessage("Lecture impossible : " + (error.message ?: "Flux indisponible"))
                }
            }
        }
    }

    private fun stop() {
        playbackGeneration++
        playbackStarting = false
        val old = activeStream
        activeStream = ""
        activeName = ""
        player.stop()
        player.clearMediaItems()
        if (castedStreamId == old && old.isNotBlank()) {
            castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.stop()
        }
        castedStreamId = ""
        updatePlaybackState()
        if (fullscreen) exitFullscreen()
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
            searchScrollY = 0
            screenScrollView?.scrollTo(0, 0)
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
                saveSearchCache()
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
            searchScrollY = 0
            screenScrollView?.scrollTo(0, 0)
            saveSearchCache()
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
        if (searchScrollY > 0) {
            screenScrollView?.post { screenScrollView?.scrollTo(0, searchScrollY) }
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

    private fun restoreScreen(screen: String) {
        when (screen) {
            "Recherche" -> showSearch()
            "Mes favoris" -> showFavorites()
            "Historique" -> showHistory()
            "Paramètres" -> showServerSettings()
            "État du système" -> showStatus()
            else -> showPlayer()
        }
    }

    private fun enterFullscreen() {
        if (fullscreen || isInPictureInPictureMode) return
        if (player.currentMediaItem == null) {
            showMessage("Aucune vidéo locale à afficher en plein écran.")
            return
        }
        screenBeforeFullscreen = currentScreen
        fullscreen = true
        playerView?.player = null
        placeholderView = null
        playbackBadge = null

        val fullscreenFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        val video = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            controllerShowTimeoutMs = 4500
            setBackgroundColor(Color.BLACK)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView = video
        fullscreenFrame.addView(video, FrameLayout.LayoutParams(-1, -1))

        // An on-screen exit control is mandatory: system bars and Back
        // gestures are deliberately hidden while watching video.
        val back = secondary("←  Retour au menu", true) { exitFullscreen() }.apply {
            contentDescription = "Quitter le plein écran et retrouver les onglets"
            background = AceDesign.fill(Color.rgb(24, 32, 52), 11,
                AceDesign.border, this@MainActivity)
        }
        val backParams = FrameLayout.LayoutParams(-2, d(48),
            Gravity.TOP or Gravity.START).apply {
            leftMargin = d(14)
            topMargin = d(14)
        }
        fullscreenFrame.addView(back, backParams)
        ViewCompat.setOnApplyWindowInsetsListener(fullscreenFrame) { _, insets ->
            // Keep the exit button outside the notch/cutout, but let the
            // video itself occupy the screen edge-to-edge.
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val lp = back.layoutParams as FrameLayout.LayoutParams
            val left = d(14) + cutout.left
            val top = d(14) + cutout.top
            if (lp.leftMargin != left || lp.topMargin != top) {
                lp.leftMargin = left
                lp.topMargin = top
                back.layoutParams = lp
            }
            insets
        }
        setContentView(fullscreenFrame)
        hideSystemBars()
        ViewCompat.requestApplyInsets(fullscreenFrame)
        updatePlaybackState()
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        playerView?.player = null
        playerView = null
        val restore = screenBeforeFullscreen
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Explicitly leaving fullscreen should not immediately
            // force the same landscape frame back into fullscreen.
            suppressLandscapeFullscreen = true
        }
        if (fullscreenRequestedByUser) {
            fullscreenRequestedByUser = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        showSystemBars()
        restoreScreen(restore)
    }

    private fun enterPip() {
        if (!shouldOfferPip()) {
            showMessage("Lance un flux sur le téléphone avant d'activer le mini-lecteur.")
            return
        }
        try {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(autoPipEnabled)
            }
            enterPictureInPictureMode(builder.build())
        } catch (_: Exception) {
            showMessage("Vérifie l'autorisation Picture-in-Picture d'AceStream TV dans Android.")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters smoothly via setAutoEnterEnabled; Android
        // 8-11 requires a manual transition when the user presses Home.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            autoPipEnabled && shouldOfferPip()) {
            enterPip()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureMode) return
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            suppressLandscapeFullscreen = false
            if (fullscreen) exitFullscreen()
        } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (player.currentMediaItem != null &&
                !fullscreen && !suppressLandscapeFullscreen) {
                enterFullscreen()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen && !isInPictureInPictureMode) hideSystemBars()
    }

    override fun onPictureInPictureModeChanged(
        inPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        if (inPictureInPictureMode) {
            screenBeforePip = if (fullscreen) screenBeforeFullscreen else currentScreen
            playerView?.player = null
            playerView = null
            fullscreen = false
            val pipPlayer = PlayerView(this).apply {
                player = this@MainActivity.player
                useController = false
                setBackgroundColor(Color.BLACK)
            }
            playerView = pipPlayer
            setContentView(pipPlayer)
        } else {
            playerView?.player = null
            playerView = null
            fullscreen = false
            showSystemBars()
            restoreScreen(screenBeforePip)
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                player.currentMediaItem != null && !suppressLandscapeFullscreen) {
                enterFullscreen()
            }
        }
        updatePlaybackState()
    }

    override fun onDestroy() {
        orientationListener.disable()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView?.player = null
        castListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
        player.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
