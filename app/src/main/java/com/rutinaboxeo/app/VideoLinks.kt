package com.rutinaboxeo.app

import java.net.URI

/** Valida enlaces externos antes de incorporarlos al plan local. */
object VideoLinks {
    /** Extrae un vídeo de YouTube validando dominio, protocolo e identificador. */
    fun youtubeId(value: String): String? = try {
        val uri = URI(clean(value))
        val parts = uri.path.orEmpty().trim('/').split('/')
        val id = when (uri.host?.lowercase(java.util.Locale.ROOT)) {
            "youtu.be" -> parts.singleOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com", "www.youtube-nocookie.com" ->
                if (uri.path == "/watch") uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                else if (parts.size == 2 && parts[0] in setOf("embed", "shorts", "live")) parts[1] else null
            else -> null
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) && uri.port in listOf(-1, 443) }
    } catch (_: Exception) { null }

    /** Conserva únicamente direcciones HTTPS válidas sin credenciales incrustadas. */
    fun clean(value: String): String {
        val url = value.trim()
        return try {
            val parsed = URI(url)
            if (parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.userInfo == null) url else ""
        } catch (_: Exception) { "" }
    }
}
