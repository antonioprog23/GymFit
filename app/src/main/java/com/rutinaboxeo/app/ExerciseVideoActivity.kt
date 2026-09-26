package com.rutinaboxeo.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import com.rutinaboxeo.app.ui.GymFitActivity

/** Reproduce vídeos con el reproductor oficial, sin exponer archivos o interfaces nativas a JavaScript. */
class ExerciseVideoActivity : GymFitActivity() {
    /** Vista web creada solo al abrir un vídeo. */
    private var player: WebView? = null
    /** Enlace validado para abrir el mismo vídeo externamente. */
    private var externalUrl = ""
    /** Dirección del reproductor que se recarga al regresar a primer plano. */
    private var embedUrl = ""

    /** Valida el enlace y configura el reproductor con la identidad de esta aplicación. */
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = VideoLinks.youtubeId(intent.getStringExtra("video_url").orEmpty())
        if (id == null) { finish(); return }
        externalUrl = "https://www.youtube.com/watch?v=$id"
        val body = col(16)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(body) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        scroll.addView(body)
        setContentView(scroll)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        add(body, button("Volver al entrenamiento", false).apply { setOnClickListener { finish() } })
        add(body, text(intent.getStringExtra("exercise_title").orEmpty(), 20f, bold = true), 12)
        val notice = text("Necesitas Internet. Si el vídeo no está disponible aquí, pulsa Abrir en YouTube.", 14f, muted)
        add(body, notice, 12)
        add(body, button("Abrir en YouTube", false).apply { setOnClickListener { openYouTube() } }, 12, 12)
        val embed = "https://www.youtube.com/embed/$id?playsinline=1&fs=0"
        embedUrl = embed
        player = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                /** Bloquea la navegación principal fuera del reproductor previsto. */
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && request.url.toString() != embed

                /** Explica los errores de conexión conservando la alternativa externa. */
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) notice.text = "No se pudo cargar el vídeo. Comprueba la conexión o ábrelo en YouTube."
                }
            }
        }
        body.addView(player, LinearLayout.LayoutParams(-1, dp(250)))
    }

    /** Abre YouTube o el navegador sin fallar si no hay aplicaciones compatibles. */
    private fun openYouTube() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No hay ninguna aplicación para abrir YouTube", Toast.LENGTH_LONG).show()
        }
    }

    /** Descarga el contenido al salir para impedir audio o reproducción en segundo plano. */
    override fun onPause() {
        player?.stopLoading()
        player?.loadUrl("about:blank")
        player?.onPause()
        super.onPause()
    }

    /** Recarga el reproductor al volver, siempre esperando una pulsación para reproducir. */
    override fun onResume() {
        super.onResume()
        player?.onResume()
        if (embedUrl.isNotEmpty()) player?.loadUrl(embedUrl, mapOf("Referer" to "https://$packageName/"))
    }

    /** Destruye la vista y libera sus recursos al cerrar el vídeo. */
    override fun onDestroy() {
        player?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        player = null
        super.onDestroy()
    }
}
