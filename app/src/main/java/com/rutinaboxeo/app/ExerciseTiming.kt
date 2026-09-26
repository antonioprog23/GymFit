package com.rutinaboxeo.app

/** Interpreta series, descansos y duraciones escritos en lenguaje natural. */
object ExerciseTiming {
    /** Extrae una cantidad segura de series; usa una serie cuando no hay un número válido. */
    fun seriesCount(value: String): Int = NUMBER.find(value)?.value?.toIntOrNull()?.coerceIn(1, MAX_SERIES) ?: 1

    /** Convierte un descanso a segundos y toma el valor alto cuando recibe un intervalo. */
    fun restSeconds(value: String): Int {
        val duration = NUMBER.findAll(value).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: return 0
        val seconds = duration.toLong() * if (value.contains("min", ignoreCase = true)) SECONDS_PER_MINUTE else 1
        return seconds.coerceAtMost(MAX_REST_SECONDS.toLong()).toInt()
    }

    /** Calcula el descanso acumulado, incluida la pausa posterior a la última serie. */
    fun totalRestSeconds(exercise: RoutineExercise): Int =
        seriesCount(exercise.series) * restSeconds(exercise.rest)

    /** Suma las duraciones de trabajo y duplica el tiempo cuando se indica por lado. */
    fun workSeconds(value: String): Int {
        val seconds = DURATION.findAll(value).sumOf { match ->
            val amount = match.groupValues[2].ifBlank { match.groupValues[1] }
                .replace(',', '.').toDoubleOrNull() ?: 0.0
            amount * if (match.groupValues[3].startsWith("min", true)) SECONDS_PER_MINUTE else 1
        }
        val sides = if (PER_SIDE.containsMatchIn(value)) 2 else 1
        return (seconds * sides).toInt().coerceIn(0, MAX_WORK_SECONDS)
    }

    /** Expresión reutilizada para encontrar números enteros. */
    private val NUMBER = Regex("\\d+")

    /** Expresión que reconoce duraciones y rangos en minutos o segundos. */
    private val DURATION = Regex(
        "(\\d+(?:[.,]\\d+)?)(?:\\s*[-–]\\s*(\\d+(?:[.,]\\d+)?))?\\s*(min(?:utos?)?|seg(?:undos?)?|s)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Expresión que identifica duraciones que deben repetirse en ambos lados. */
    private val PER_SIDE = Regex("por (lado|pierna|mano)", RegexOption.IGNORE_CASE)

    /** Máximo razonable de series aceptado desde la plantilla. */
    private const val MAX_SERIES = 30

    /** Límite defensivo de una pausa individual. */
    private const val MAX_REST_SECONDS = 3_600

    /** Límite defensivo de un bloque de trabajo. */
    private const val MAX_WORK_SECONDS = 7_200

    /** Factor para convertir minutos en segundos. */
    private const val SECONDS_PER_MINUTE = 60
}
