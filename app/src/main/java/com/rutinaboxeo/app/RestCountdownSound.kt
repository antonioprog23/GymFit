package com.rutinaboxeo.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/** Gestiona los avisos del descanso y su preferencia sin depender de archivos de audio. */
class RestCountdownSound(context: Context) {
    /** Preferencias privadas independientes de las rutinas del usuario. */
    private val preferences = context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE)
    /** Filtro que evita repetir un aviso en el mismo segundo. */
    private val cues = RestCountdownCues()
    /** Generador de audio reservado únicamente cuando hace falta emitir un tono. */
    private var generator: ToneGenerator? = null
    /** Elección persistida del usuario; los avisos están activados inicialmente. */
    var enabled: Boolean = preferences.getBoolean("rest_countdown", true)
        set(value) {
            field = value
            preferences.edit().putBoolean("rest_countdown", value).apply()
            if (!value) stop()
        }

    /** Prepara una cuenta nueva, incluido el aviso inicial si quedan cinco segundos o menos. */
    fun start(seconds: Int) {
        stop()
        cues.reset()
        tick(seconds)
    }

    /** Reproduce un pitido corto por cada segundo entre cinco y uno. */
    fun tick(seconds: Int) {
        if (cues.shouldBeep(seconds) && enabled) play(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    /** Anuncia el final del descanso con un sonido diferente y más largo. */
    fun finish() {
        if (enabled) play(ToneGenerator.TONE_PROP_ACK, 350)
        cues.reset()
    }

    /** Emite un tono sin interrumpir el entrenamiento si el dispositivo rechaza el audio. */
    private fun play(tone: Int, duration: Int) {
        runCatching {
            val audio = generator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { generator = it }
            audio.startTone(tone, duration)
        }
    }

    /** Silencia cualquier aviso al pausar o abandonar la sesión. */
    fun stop() { generator?.let { runCatching { it.stopTone() } } }

    /** Libera los recursos de audio al destruir la pantalla. */
    fun release() {
        generator?.let { runCatching { it.release() } }
        generator = null
    }
}
