package com.rutinaboxeo.app

/** Paso temporizado de la rutina matinal. */
data class MorningStep(
    /** Nombre visible del movimiento. */
    val title: String,
    /** Duración total del paso en segundos. */
    val seconds: Int,
    /** Instrucciones de ejecución incluidas en la plantilla. */
    val instruction: String,
    /** Enlace HTTPS opcional a una demostración. */
    val videoUrl: String = ""
)
