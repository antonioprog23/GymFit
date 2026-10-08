package com.rutinaboxeo.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/** Estado visible de un documento respecto al mes actual y a la selección principal. */
enum class MonthlyRoutineStatus { ACTIVE, PROGRAMMED, HISTORICAL, ALTERNATIVE }

/** Instantánea coherente que evita releer JSON al dibujar una pantalla completa. */
data class MonthlyRoutineSnapshot(
    val documents: List<MonthlyRoutine>,
    val primaryIds: Map<YearMonth, String>,
    val currentMonth: YearMonth
) {
    val active: MonthlyRoutine? get() = primaryIds[currentMonth]?.let { id -> documents.firstOrNull { it.id == id } }

    fun primary(month: YearMonth): MonthlyRoutine? =
        primaryIds[month]?.let { id -> documents.firstOrNull { it.id == id } }

    fun status(document: MonthlyRoutine): MonthlyRoutineStatus {
        val selected = primary(document.month)
        if (selected?.id != document.id) return if (selected != null && document.importedAt < selected.importedAt)
            MonthlyRoutineStatus.HISTORICAL else MonthlyRoutineStatus.ALTERNATIVE
        return when {
            document.month == currentMonth -> MonthlyRoutineStatus.ACTIVE
            document.month > currentMonth -> MonthlyRoutineStatus.PROGRAMMED
            else -> MonthlyRoutineStatus.HISTORICAL
        }
    }
}

/** Repositorio de documentos mensuales con selección principal por período. */
class MonthlyRepository(
    private val folder: File,
    /** Proveedor inyectable para probar cambios de mes sin depender del reloj real. */
    private val currentMonth: () -> YearMonth = { YearMonth.now() }
) {
    /** Recupera exclusivamente la rutina principal del mes actual. */
    @Synchronized fun active(): MonthlyRoutine? = primary(currentMonth())

    /** Recupera la rutina principal elegida para un período, si todavía existe. */
    @Synchronized fun primary(month: YearMonth): MonthlyRoutine? =
        principals()[month.toString()]?.takeIf(::exists)?.let(::read)

    /** Indica que ya existe almacenamiento mensual, aunque este mes no tenga principal. */
    @Synchronized fun hasMonthlyState(): Boolean = File(folder, POINTER).exists() ||
        File(folder, PRINCIPALS).exists() || rawFiles().isNotEmpty()

    /** Clasifica un documento sin confundir una alternativa con un histórico. */
    @Synchronized fun status(document: MonthlyRoutine): MonthlyRoutineStatus {
        val primaryId = principals()[document.month.toString()]
        if (primaryId != document.id) {
            val selected = primaryId?.takeIf(::exists)?.let(::read)
            return if (selected != null && document.importedAt < selected.importedAt)
                MonthlyRoutineStatus.HISTORICAL else MonthlyRoutineStatus.ALTERNATIVE
        }
        return when {
            document.month == currentMonth() -> MonthlyRoutineStatus.ACTIVE
            document.month > currentMonth() -> MonthlyRoutineStatus.PROGRAMMED
            else -> MonthlyRoutineStatus.HISTORICAL
        }
    }

    /** Marca un documento como principal de su mes sin borrar la selección anterior. */
    @Synchronized fun setPrimary(id: String): MonthlyRoutine {
        val document = read(id)
        val current = active()
        check(document.month != currentMonth() || current?.id == id || current?.hasPendingWorkout() != true) {
            "Finaliza el entrenamiento pendiente antes de cambiar la rutina activa."
        }
        savePrincipals(principals().toMutableMap().apply { put(document.month.toString(), id) })
        if (document.month == currentMonth()) writeAtomic(File(folder, POINTER), id)
        return document
    }

    /** Elimina una importación concreta sin tocar sus hermanas ni elegir sustitutas. */
    @Synchronized fun delete(id: String): Boolean {
        val document = read(id)
        check(!document.hasPendingWorkout()) {
            "Finaliza los entrenamientos pendientes antes de eliminar la rutina."
        }
        val wasActive = active()?.id == id
        val selected = principals().toMutableMap()
        if (selected[document.month.toString()] == id) selected.remove(document.month.toString())
        Files.delete(File(folder, "$id.json").toPath())
        savePrincipals(selected)
        if (wasActive) writeAtomic(File(folder, POINTER), "")
        return wasActive
    }

    /** Lee un documento validando antes su identificador y su versión. */
    @Synchronized fun read(id: String): MonthlyRoutine {
        require(ID.matches(id)) { "Identificador de rutina no válido" }
        val root = JSONObject(File(folder, "$id.json").readText())
        require(root.getInt("schemaVersion") in 2..3) { "Versión de rutina no compatible" }
        val plan = RoutineJson.decodePlan(root.getJSONObject("plan").toString())
            ?: error("La planificación guardada está dañada")
        val workouts = mutableListOf<WorkoutRecord>()
        val array = root.getJSONArray("workouts")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            workouts += WorkoutRecord(item.getString("id"), item.getInt("week"), item.getString("day"),
                item.getString("startedAt"), item.getString("finishedAt"),
                RoutineJson.decodeProgress(item.getJSONObject("results").toString()), item.getString("kind"),
                item.optInt("dayOfMonth").takeIf { it > 0 }, decodeResumeState(item.optJSONObject("resumeState")))
        }
        return MonthlyRoutine(id, YearMonth.parse(root.getString("month")), root.getString("importedAt"),
            plan, RoutineJson.decodeProgress(root.getJSONObject("progress").toString()), workouts,
            root.optBoolean("migrated"))
    }

    /** Lista todas las importaciones conservadas, con las más recientes primero. */
    @Synchronized fun list(): List<MonthlyRoutine> = rawList()

    /** Lee documentos y selección una sola vez para consultas y renderizados agregados. */
    @Synchronized fun snapshot(): MonthlyRoutineSnapshot {
        val documents = rawList()
        val ids = principals().mapNotNull { (month, id) ->
            runCatching { YearMonth.parse(month) }.getOrNull()?.let { it to id }
        }.toMap()
        return MonthlyRoutineSnapshot(documents, ids, currentMonth())
    }

    /** Crea sin sobrescribir y solo la hace principal cuando el usuario lo ha decidido. */
    @Synchronized fun create(plan: RoutinePlan, month: YearMonth,
        legacy: Map<String, ExerciseProgress>? = null, makePrimary: Boolean = primary(month) == null): MonthlyRoutine {
        check(folder.isDirectory || folder.mkdirs()) { "No se pudo crear la carpeta de rutinas" }
        val base = "%02d_%04d".format(Locale.ROOT, month.monthValue, month.year)
        var id = base
        var suffix = 1
        while (exists(id)) id = "${base}_${suffix++}"
        val document = MonthlyRoutine(id, month, now(), plan, migrated = legacy != null)
        legacy?.let {
            document.progress.putAll(copyResults(it))
            if (it.isNotEmpty()) document.workouts += WorkoutRecord(UUID.randomUUID().toString(), 0,
                "Datos anteriores (fecha desconocida)", "", results = copyResults(it), kind = "anterior")
        }
        save(document)
        if (makePrimary) setPrimary(id)
        return document
    }

    /** Persiste un documento completo manteniendo el esquema compatible actual. */
    @Synchronized fun save(document: MonthlyRoutine) {
        require(ID.matches(document.id))
        val root = JSONObject().put("schemaVersion", 3).put("month", document.month.toString())
            .put("importedAt", document.importedAt).put("migrated", document.migrated)
            .put("plan", JSONObject(RoutineJson.encodePlan(document.plan)))
            .put("progress", JSONObject(RoutineJson.encodeProgress(document.progress)))
            .put("workouts", JSONArray().apply {
                document.workouts.forEach { record -> put(JSONObject().put("id", record.id)
                    .put("week", record.week).put("day", record.day).put("kind", record.kind)
                    .put("dayOfMonth", record.dayOfMonth ?: JSONObject.NULL)
                    .put("startedAt", record.startedAt).put("finishedAt", record.finishedAt)
                    .put("results", JSONObject(RoutineJson.encodeProgress(record.results)))
                    .put("resumeState", record.resumeState?.let(::encodeResumeState) ?: JSONObject.NULL)) }
            })
        writeAtomic(File(folder, "${document.id}.json"), root.toString(2))
    }

    /** Abre o recupera una realización semanal antigua en el documento activo. */
    @Synchronized fun begin(week: Int, day: String, repeat: Boolean = false) {
        val doc = active() ?: return
        beginWorkout(doc, doc.plan.forDay(week, day), week, day, null, repeat, "tarde") {
            it.week == week && it.day == day
        }
    }

    /** Abre una realización mensual en el documento indicado; puede ser una sesión extra. */
    @Synchronized fun beginDate(id: String, dayOfMonth: Int, repeat: Boolean = false, extra: Boolean = false) {
        val doc = read(id)
        require(dayOfMonth in 1..doc.month.lengthOfMonth()) { "Día fuera del mes de la rutina" }
        val items = doc.plan.forDate(dayOfMonth)
        val kind = if (extra) "extra" else "tarde"
        beginWorkout(doc, items, (dayOfMonth - 1) / 7 + 1, items.firstOrNull()?.day.orEmpty(),
            dayOfMonth, repeat, kind) { it.dayOfMonth == dayOfMonth && it.kind == kind }
    }

    @Synchronized fun beginDate(dayOfMonth: Int, repeat: Boolean = false) {
        active()?.let { beginDate(it.id, dayOfMonth, repeat) }
    }

    /** Actualiza resultados y las realizaciones abiertas del documento indicado. */
    @Synchronized fun updateProgress(id: String, values: Map<String, ExerciseProgress>) {
        val doc = read(id)
        doc.progress.clear()
        doc.progress.putAll(copyResults(values))
        doc.workouts.filter { it.kind in setOf("tarde", "extra") && it.finishedAt.isEmpty() }.forEach { record ->
            val exercises = record.dayOfMonth?.let(doc.plan::forDate) ?: doc.plan.forDay(record.week, record.day)
            exercises.forEach { exercise ->
                values[exercise.key()]?.let { record.results[exercise.key()] = it.copy() }
            }
        }
        save(doc)
    }

    @Synchronized fun updateProgress(values: Map<String, ExerciseProgress>) {
        active()?.let { updateProgress(it.id, values) }
    }

    /** Recupera una copia del punto de reanudación de la realización mensual abierta. */
    @Synchronized fun resumeState(id: String, dayOfMonth: Int, extra: Boolean = false): WorkoutResumeState? {
        val kind = if (extra) "extra" else "tarde"
        return read(id).workouts.lastOrNull {
            it.dayOfMonth == dayOfMonth && it.kind == kind && it.finishedAt.isEmpty()
        }?.resumeState?.copyState()
    }

    /** Guarda el punto de reanudación sin alterar resultados ni realizaciones finalizadas. */
    @Synchronized fun updateResumeState(id: String, dayOfMonth: Int, extra: Boolean = false,
        state: WorkoutResumeState) {
        val document = read(id)
        val kind = if (extra) "extra" else "tarde"
        val pending = document.workouts.lastOrNull {
            it.dayOfMonth == dayOfMonth && it.kind == kind && it.finishedAt.isEmpty()
        } ?: return
        pending.resumeState = state.copyState()
        save(document)
    }

    /** Cierra una realización semanal heredada sin alterar las ya finalizadas. */
    @Synchronized fun finish(week: Int, day: String) {
        val doc = active() ?: return
        finishWorkout(doc) { it.week == week && it.day == day && it.kind == "tarde" }
    }

    /** Cierra la realización abierta del documento y fecha indicados. */
    @Synchronized fun finishDate(id: String, dayOfMonth: Int, extra: Boolean = false) {
        val doc = read(id)
        require(dayOfMonth in 1..doc.month.lengthOfMonth()) { "Día fuera del mes de la rutina" }
        val kind = if (extra) "extra" else "tarde"
        finishWorkout(doc) { it.dayOfMonth == dayOfMonth && it.kind == kind }
    }

    @Synchronized fun finishDate(dayOfMonth: Int) {
        active()?.let { finishDate(it.id, dayOfMonth) }
    }

    /** Conserva el comienzo real de una sesión matinal activa. */
    @Synchronized fun beginMorning() {
        val doc = active() ?: return
        if (doc.workouts.any { it.kind == "mañana" && it.finishedAt.isBlank() }) return
        doc.workouts += WorkoutRecord(UUID.randomUUID().toString(), 0, "Mañana", now(), kind = "mañana")
        save(doc)
    }

    /** Completa la realización matinal abierta conservando su fecha real de comienzo. */
    @Synchronized fun finishMorning() {
        beginMorning()
        val doc = active() ?: return
        doc.workouts.lastOrNull { it.kind == "mañana" && it.finishedAt.isBlank() }?.finishedAt = now()
        save(doc)
    }

    /** Elimina solo los resultados del documento activo tras confirmación del usuario. */
    @Synchronized fun clearProgress() {
        val doc = active() ?: return
        doc.progress.clear()
        doc.workouts.clear()
        save(doc)
    }

    /** Migra active.txt y elige una principal segura para cada mes sin tocar sus JSON. */
    private fun principals(): Map<String, String> {
        val file = File(folder, PRINCIPALS)
        if (file.exists()) {
            val root = JSONObject(file.readText())
            return root.keys().asSequence().associateWith { root.getString(it) }.filterValues(::exists)
        }
        val documents = rawList()
        if (documents.isEmpty()) return emptyMap()
        val legacyPointer = File(folder, POINTER)
        val legacyActive = legacyPointer.takeIf(File::exists)?.readText()?.trim()
        val selected = documents.groupBy { it.month }.mapNotNull { (month, options) ->
            if (month == currentMonth() && legacyPointer.exists() && legacyActive.isNullOrEmpty()) null
            else month.toString() to (options.firstOrNull { it.id == legacyActive }?.id
                ?: options.maxBy { it.importedAt }.id)
        }.toMap()
        savePrincipals(selected)
        return selected
    }

    private fun savePrincipals(values: Map<String, String>) {
        writeAtomic(File(folder, PRINCIPALS), JSONObject().apply {
            values.filterValues(::exists).forEach { (month, id) -> put(month, id) }
        }.toString(2))
    }

    private fun rawFiles(): List<File> = folder.listFiles().orEmpty()
        .filter { it.extension == "json" && ID.matches(it.nameWithoutExtension) }

    private fun rawList(): List<MonthlyRoutine> = rawFiles().map { read(it.nameWithoutExtension) }
        .sortedByDescending { it.importedAt }

    private fun exists(id: String): Boolean = ID.matches(id) && File(folder, "$id.json").isFile

    private fun MonthlyRoutine.hasPendingWorkout(): Boolean =
        workouts.any { it.kind != "anterior" && it.finishedAt.isBlank() }

    private fun writeAtomic(target: File, text: String) {
        check(folder.isDirectory || folder.mkdirs())
        val temporary = File(folder, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyResults(values: Map<String, ExerciseProgress>): MutableMap<String, ExerciseProgress> =
        values.mapValuesTo(mutableMapOf()) { it.value.copy() }

    private fun WorkoutResumeState.copyState() = WorkoutResumeState(exerciseIndex,
        exerciseRuns.mapValuesTo(mutableMapOf()) { it.value.copy() })

    private fun encodeResumeState(state: WorkoutResumeState) = JSONObject()
        .put("exerciseIndex", state.exerciseIndex)
        .put("exerciseRuns", JSONObject().apply {
            state.exerciseRuns.forEach { (key, run) -> put(key, JSONObject()
                .put("phase", run.phase.name).put("round", run.round)
                .put("remainingSeconds", run.remainingSeconds)) }
        })

    private fun decodeResumeState(value: JSONObject?): WorkoutResumeState? {
        if (value == null) return null
        val runs = mutableMapOf<String, ExerciseRun>()
        val encodedRuns = value.optJSONObject("exerciseRuns") ?: JSONObject()
        encodedRuns.keys().forEach { key ->
            val run = encodedRuns.optJSONObject(key) ?: return@forEach
            runs[key] = ExerciseRun(
                phase = run.optString("phase").let { name ->
                    runCatching { ExercisePhase.valueOf(name) }.getOrDefault(ExercisePhase.READY)
                },
                round = run.optInt("round", 1).coerceAtLeast(1),
                remainingSeconds = run.optInt("remainingSeconds", 0).coerceAtLeast(0)
            )
        }
        return WorkoutResumeState(value.optInt("exerciseIndex", 0).coerceAtLeast(0), runs)
    }

    private fun beginWorkout(document: MonthlyRoutine, items: List<RoutineExercise>, week: Int, day: String,
        dayOfMonth: Int?, repeat: Boolean, kind: String, matches: (WorkoutRecord) -> Boolean) {
        if (items.isEmpty()) return
        val pending = document.workouts.lastOrNull { it.kind == kind && it.finishedAt.isEmpty() && matches(it) }
        if (pending != null && !repeat) return
        if (repeat) pending?.apply {
            finishedAt = now()
            resumeState = null
        }
        val record = WorkoutRecord(UUID.randomUUID().toString(), week, day, now(),
            dayOfMonth = dayOfMonth, kind = kind)
        items.forEach { item ->
            val value = if (repeat) ExerciseProgress() else
                document.progress[item.key()]?.copy() ?: ExerciseProgress()
            record.results[item.key()] = value.copy()
            document.progress[item.key()] = value
        }
        document.workouts += record
        save(document)
    }

    private fun finishWorkout(document: MonthlyRoutine, matches: (WorkoutRecord) -> Boolean) {
        document.workouts.lastOrNull {
            it.kind in setOf("tarde", "extra") && it.finishedAt.isEmpty() && matches(it)
        }?.apply {
            finishedAt = now()
            resumeState = null
        }
        save(document)
    }

    private fun now(): String = ZonedDateTime.now().toOffsetDateTime().toString()

    private companion object {
        const val POINTER = "active.txt"
        const val PRINCIPALS = "principals.json"
        val ID = Regex("\\d{2}_\\d{4}(?:_\\d+)?")
    }
}
