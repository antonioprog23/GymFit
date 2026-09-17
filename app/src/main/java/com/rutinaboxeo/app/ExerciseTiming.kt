package com.rutinaboxeo.app

object ExerciseTiming {
    fun seriesCount(value: String): Int = Regex("\\d+").find(value)?.value?.toIntOrNull()?.coerceIn(1, 30) ?: 1

    fun restSeconds(value: String): Int {
        val values = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
        val duration = values.maxOrNull() ?: return 0
        return (if (value.contains("min", ignoreCase = true)) duration * 60 else duration).coerceAtMost(3600)
    }

    fun totalRestSeconds(exercise: RoutineExercise): Int =
        seriesCount(exercise.series) * restSeconds(exercise.rest)

    fun workSeconds(value: String): Int {
        val pattern = Regex("(\\d+(?:[.,]\\d+)?)(?:\\s*[-–]\\s*(\\d+(?:[.,]\\d+)?))?\\s*(min(?:utos?)?|seg(?:undos?)?|s)\\b", RegexOption.IGNORE_CASE)
        val seconds = pattern.findAll(value).sumOf { match ->
            val amount = (match.groupValues[2].ifBlank { match.groupValues[1] })
                .replace(',', '.').toDoubleOrNull() ?: 0.0
            amount * if (match.groupValues[3].startsWith("min", true)) 60 else 1
        }
        val sides = if (Regex("por (lado|pierna|mano)", RegexOption.IGNORE_CASE).containsMatchIn(value)) 2 else 1
        return (seconds * sides).toInt().coerceIn(0, 7200)
    }
}
