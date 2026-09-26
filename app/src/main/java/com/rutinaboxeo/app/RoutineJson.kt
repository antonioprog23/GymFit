package com.rutinaboxeo.app

import org.json.JSONArray
import org.json.JSONObject

/** Traduce los modelos de rutina y progreso a su representación JSON compatible. */
internal object RoutineJson {
    /** Serializa un plan completo para almacenarlo en preferencias. */
    fun encodePlan(plan: RoutinePlan): String = JSONObject()
        .put("month", plan.month?.toString() ?: "")
        .put("exercises", JSONArray().apply { plan.exercises.forEach { put(it.toJson()) } })
        .put("sessions", JSONObject().apply {
            plan.sessions.forEach { (day, info) -> put(day, info.toJson()) }
        })
        .put("morning", JSONArray().apply { plan.morningSteps.forEach { put(it.toJson()) } })
        .toString()

    /** Deserializa tanto el formato actual como la lista usada por versiones antiguas. */
    fun decodePlan(text: String): RoutinePlan? = runCatching {
        val root = text.takeUnless { it.trimStart().startsWith("[") }?.let(::JSONObject)
        val exercises = (root?.getJSONArray("exercises") ?: JSONArray(text))
            .objects().map(::exerciseFromJson).toList()
        val sessions = buildMap {
            root?.optJSONObject("sessions")?.let { saved ->
                saved.keys().forEach { day -> saved.optJSONObject(day)?.let { put(day, sessionFromJson(it)) } }
            }
        }
        val morning = root?.optJSONArray("morning")?.objects()?.map(::morningFromJson)?.toList().orEmpty()
        RoutinePlan(exercises, sessions, morning, root?.optString("month")?.takeIf { it.isNotBlank() }?.let(java.time.YearMonth::parse))
    }.getOrNull()

    /** Serializa el progreso indexado por la clave estable de cada ejercicio. */
    fun encodeProgress(progress: Map<String, ExerciseProgress>): String = JSONObject().apply {
        progress.forEach { (key, value) -> put(key, value.toJson()) }
    }.toString()

    /** Deserializa el progreso y descarta el conjunto completo si está dañado. */
    fun decodeProgress(text: String): MutableMap<String, ExerciseProgress> = runCatching {
        val root = JSONObject(text)
        root.keys().asSequence().associateWithTo(mutableMapOf()) { key -> progressFromJson(root.getJSONObject(key)) }
    }.getOrDefault(mutableMapOf())

    /** Convierte un ejercicio y sus alternativas en un objeto JSON. */
    private fun RoutineExercise.toJson(): JSONObject = JSONObject()
        .put("week", week).put("day", day).put("block", block).put("exercise", exercise)
        .put("series", series).put("reps", reps).put("rest", rest).put("note", note).put("order", order)
        .put("instruction", instruction).put("videoUrl", videoUrl)
        .put("alternatives", JSONArray().apply { alternatives.forEach { put(it.toJson()) } })

    /** Convierte una alternativa en un objeto JSON. */
    private fun ExerciseAlternative.toJson(): JSONObject = JSONObject()
        .put("title", title).put("instruction", instruction).put("videoUrl", videoUrl)

    /** Convierte los metadatos de una sesión en un objeto JSON. */
    private fun SessionInfo.toJson(): JSONObject = JSONObject()
        .put("title", title).put("focus", focus).put("note", note)

    /** Convierte un paso matinal en un objeto JSON. */
    private fun MorningStep.toJson(): JSONObject = JSONObject()
        .put("title", title).put("seconds", seconds).put("instruction", instruction).put("videoUrl", videoUrl)

    /** Convierte un registro de progreso en un objeto JSON. */
    private fun ExerciseProgress.toJson(): JSONObject = JSONObject()
        .put("weight", weight).put("reps", actualReps).put("rir", rir)
        .put("userNote", userNote).put("done", done)

    /** Reconstruye un ejercicio tolerando campos añadidos en versiones posteriores. */
    private fun exerciseFromJson(value: JSONObject): RoutineExercise = RoutineExercise(
        week = value.getInt("week"),
        day = value.getString("day"),
        block = value.getString("block"),
        exercise = value.getString("exercise"),
        series = value.getString("series"),
        reps = value.getString("reps"),
        rest = value.getString("rest"),
        note = value.optString("note"),
        order = value.optInt("order"),
        instruction = value.optString("instruction"),
        videoUrl = value.optString("videoUrl"),
        alternatives = value.optJSONArray("alternatives")?.objects()?.map(::alternativeFromJson)?.toList().orEmpty()
    )

    /** Reconstruye una alternativa desde JSON. */
    private fun alternativeFromJson(value: JSONObject): ExerciseAlternative = ExerciseAlternative(
        title = value.optString("title"),
        instruction = value.optString("instruction"),
        videoUrl = value.optString("videoUrl")
    )

    /** Reconstruye los metadatos de una sesión desde JSON. */
    private fun sessionFromJson(value: JSONObject): SessionInfo = SessionInfo(
        title = value.optString("title"),
        focus = value.optString("focus"),
        note = value.optString("note")
    )

    /** Reconstruye un paso matinal desde JSON. */
    private fun morningFromJson(value: JSONObject): MorningStep = MorningStep(
        title = value.getString("title"),
        seconds = value.getInt("seconds"),
        instruction = value.getString("instruction"),
        videoUrl = value.optString("videoUrl")
    )

    /** Reconstruye un registro de progreso desde JSON. */
    private fun progressFromJson(value: JSONObject): ExerciseProgress = ExerciseProgress(
        weight = value.optString("weight"),
        actualReps = value.optString("reps"),
        rir = value.optString("rir"),
        userNote = value.optString("userNote"),
        done = value.optBoolean("done")
    )

    /** Expone los objetos de un array como una secuencia sin listas temporales. */
    private fun JSONArray.objects(): Sequence<JSONObject> = sequence {
        for (index in 0 until length()) yield(getJSONObject(index))
    }
}
