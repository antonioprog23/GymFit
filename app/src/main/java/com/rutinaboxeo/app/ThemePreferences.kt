package com.rutinaboxeo.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Guarda el aspecto elegido sin mezclarlo con los documentos de las rutinas. */
object ThemePreferences {
    /** Recupera la elección; una instalación nueva sigue el tema del dispositivo. */
    fun applySaved(context: Context) {
        val mode = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Persiste y aplica el tema solicitado, recreando las pantallas cuando sea necesario. */
    fun setDark(context: Context, dark: Boolean) {
        val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit().putInt("night_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
