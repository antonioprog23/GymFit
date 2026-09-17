package com.rutinaboxeo.app

data class RoutineExercise(
    val week: Int,
    val day: String,
    val block: String,
    val exercise: String,
    val series: String,
    val reps: String,
    val rest: String,
    val note: String = "",
    val order: Int = 0,
    val instruction: String = "",
    val videoUrl: String = "",
    val alternatives: List<ExerciseAlternative> = emptyList()
) {
    fun key(): String = "$week|$day|$order|$exercise"
}

data class ExerciseAlternative(
    val title: String,
    val instruction: String = "",
    val videoUrl: String = ""
)

data class SessionInfo(
    val title: String,
    val focus: String,
    val note: String = ""
)

data class RoutinePlan(
    val exercises: List<RoutineExercise>,
    val sessions: Map<String, SessionInfo> = emptyMap(),
    val morningSteps: List<MorningStep> = emptyList()
) {
    fun hasSameExerciseStructure(other: RoutinePlan): Boolean =
        exercises.size == other.exercises.size && exercises.zip(other.exercises).all { (a, b) ->
            a.week == b.week && a.day == b.day && a.block == b.block &&
                a.exercise == b.exercise && a.series == b.series && a.reps == b.reps &&
                a.rest == b.rest && a.order == b.order
        }

    fun session(day: String): SessionInfo = sessions[day] ?: SessionInfo(day, "")
    fun weeks(): List<Int> = exercises.map { it.week }.distinct().sorted()
    fun days(week: Int): List<String> {
        val order = listOf("Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo")
        return exercises.filter { it.week == week }.map { it.day }.distinct().sortedBy { order.indexOf(it) }
    }
    fun forDay(week: Int, day: String): List<RoutineExercise> =
        exercises.filter { it.week == week && it.day == day }.sortedBy { it.order }
}

data class ExerciseProgress(
    var weight: String = "",
    var actualReps: String = "",
    var rir: String = "",
    var userNote: String = "",
    var done: Boolean = false
)
