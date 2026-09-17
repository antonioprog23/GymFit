package com.rutinaboxeo.app

import java.net.URI

object VideoLinks {
    fun clean(value: String): String {
        val url = value.trim()
        return try {
            val parsed = URI(url)
            if (parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.userInfo == null) url else ""
        } catch (_: Exception) { "" }
    }

}
