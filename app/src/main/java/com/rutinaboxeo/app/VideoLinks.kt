package com.rutinaboxeo.app

import java.net.URI

/** Valida enlaces externos antes de incorporarlos al plan local. */
object VideoLinks {
    /** Conserva únicamente direcciones HTTPS válidas sin credenciales incrustadas. */
    fun clean(value: String): String {
        val url = value.trim()
        return try {
            val parsed = URI(url)
            if (parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.userInfo == null) url else ""
        } catch (_: Exception) { "" }
    }
}
