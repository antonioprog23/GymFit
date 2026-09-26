package com.rutinaboxeo.app

import android.app.Application

/** Inicializa las preferencias visuales antes de crear la primera pantalla. */
class GymFitApplication : Application() {
    /** Aplica el tema persistido al arrancar la aplicación. */
    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applySaved(this)
    }
}
