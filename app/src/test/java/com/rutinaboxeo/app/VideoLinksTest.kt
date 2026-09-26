package com.rutinaboxeo.app

import org.junit.Assert.*
import org.junit.Test

/** Verifica los formatos admitidos y el rechazo de enlaces suplantados. */
class VideoLinksTest {
    /** Admite enlaces compartidos, normales, Shorts y emisiones. */
    @Test fun supportsYouTubeFormats() {
        listOf("https://youtu.be/abcdefghijk?si=example", "https://www.youtube.com/watch?v=abcdefghijk&t=10",
            "https://m.youtube.com/shorts/abcdefghijk", "https://youtube.com/live/abcdefghijk",
            "https://www.youtube.com/embed/abcdefghijk").forEach {
            assertEquals("abcdefghijk", VideoLinks.youtubeId(it))
        }
    }

    /** Rechaza protocolos inseguros, credenciales, dominios ajenos e identificadores inválidos. */
    @Test fun rejectsUnsafeAndInvalidLinks() {
        listOf("", "http://youtu.be/abcdefghijk", "https://youtube.com.evil.test/watch?v=abcdefghijk",
            "https://user:pass@youtube.com/watch?v=abcdefghijk", "https://youtube.com/watch?v=bad",
            "https://youtube.com/playlist?list=abcdefghijk", "https://youtu.be:444/abcdefghijk",
            "javascript:alert(1)").forEach { assertNull(VideoLinks.youtubeId(it)) }
    }
}
