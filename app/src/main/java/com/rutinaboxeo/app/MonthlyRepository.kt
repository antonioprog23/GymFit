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

/** Repositorio de documentos mensuales con sustitución atómica y selección activa persistida. */
class MonthlyRepository(
    /** Carpeta privada que contiene exclusivamente las rutinas de la aplicación. */
    private val folder: File
) {
    /** Recupera la rutina activa; un archivo dañado produce un error explícito. */
    @Synchronized fun active(): MonthlyRoutine? {
        val pointer = File(folder, "active.txt")
        if (!pointer.exists()) return null
        return read(pointer.readText().trim())
    }

    /** Lee un documento validando antes su identificador y su versión. */
    @Synchronized fun read(id: String): MonthlyRoutine {
        require(ID.matches(id)) { "Identificador de rutina no válido" }
        val root = JSONObject(File(folder, "$id.json").readText())
        require(root.getInt("schemaVersion") == 2) { "Versión de rutina no compatible" }
        val plan = RoutineJson.decodePlan(root.getJSONObject("plan").toString())
            ?: error("La planificación guardada está dañada")
        val workouts = mutableListOf<WorkoutRecord>()
        val array = root.getJSONArray("workouts")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            workouts += WorkoutRecord(item.getString("id"), item.getInt("week"), item.getString("day"),
                item.getString("startedAt"), item.getString("finishedAt"),
                RoutineJson.decodeProgress(item.getJSONObject("results").toString()), item.getString("kind"))
        }
        return MonthlyRoutine(id, YearMonth.parse(root.getString("month")), root.getString("importedAt"),
            plan, RoutineJson.decodeProgress(root.getJSONObject("progress").toString()), workouts,
            root.optBoolean("migrated"))
    }

    /** Lista todas las importaciones conservadas, con las más recientes primero. */
    @Synchronized fun list(): List<MonthlyRoutine> = folder.listFiles().orEmpty()
        .filter { it.extension == "json" && ID.matches(it.nameWithoutExtension) }
        .map { read(it.nameWithoutExtension) }.sortedByDescending { it.importedAt }

    /** Crea un documento sin sobrescribir colisiones y lo activa solo después de guardarlo. */
    @Synchronized fun create(plan: RoutinePlan, month: YearMonth,
        legacy: Map<String, ExerciseProgress>? = null): MonthlyRoutine {
        check(folder.isDirectory || folder.mkdirs()) { "No se pudo crear la carpeta de rutinas" }
        val base = "%02d_%04d".format(Locale.ROOT, month.monthValue, month.year)
        var id = base
        var suffix = 1
        while (File(folder, "$id.json").exists()) id = "${base}_${suffix++}"
        val document = MonthlyRoutine(id, month, now(), plan, migrated = legacy != null)
        legacy?.let {
            document.progress.putAll(copyResults(it))
            if (it.isNotEmpty()) document.workouts += WorkoutRecord(UUID.randomUUID().toString(), 0,
                "Datos anteriores (fecha desconocida)", "", results = copyResults(it), kind = "anterior")
        }
        save(document)
        writeAtomic(File(folder, "active.txt"), id)
        return document
    }

    /** Persiste un documento completo, manteniendo el archivo anterior si falla la escritura. */
    @Synchronized fun save(document: MonthlyRoutine) {
        require(ID.matches(document.id))
        val root = JSONObject().put("schemaVersion", 2).put("month", document.month.toString())
            .put("importedAt", document.importedAt).put("migrated", document.migrated)
            .put("plan", JSONObject(RoutineJson.encodePlan(document.plan)))
            .put("progress", JSONObject(RoutineJson.encodeProgress(document.progress)))
            .put("workouts", JSONArray().apply {
                document.workouts.forEach { record -> put(JSONObject().put("id", record.id)
                    .put("week", record.week).put("day", record.day).put("kind", record.kind)
                    .put("startedAt", record.startedAt).put("finishedAt", record.finishedAt)
                    .put("results", JSONObject(RoutineJson.encodeProgress(record.results)))) }
            })
        writeAtomic(File(folder, "${document.id}.json"), root.toString(2))
    }

    /** Abre o recupera una realización pendiente; repetir conserva los resultados anteriores. */
    @Synchronized fun begin(week: Int, day: String, repeat: Boolean = false) {
        val doc = active() ?: return
        val pending = doc.workouts.lastOrNull { it.week == week && it.day == day && it.kind == "tarde" && it.finishedAt.isEmpty() }
        if (pending != null && !repeat) return
        if (repeat) pending?.finishedAt = now()
        val items = doc.plan.forDay(week, day)
        if (items.isEmpty()) return
        val record = WorkoutRecord(UUID.randomUUID().toString(), week, day, now())
        items.forEach { item ->
            val value = if (repeat) ExerciseProgress() else doc.progress[item.key()]?.copy() ?: ExerciseProgress()
            record.results[item.key()] = value.copy()
            doc.progress[item.key()] = value
        }
        doc.workouts += record
        save(doc)
    }

    /** Actualiza resultados actuales y sus realizaciones abiertas sin tocar sesiones cerradas. */
    @Synchronized fun updateProgress(values: Map<String, ExerciseProgress>) {
        val doc = active() ?: return
        doc.progress.clear()
        doc.progress.putAll(copyResults(values))
        doc.workouts.filter { it.kind == "tarde" && it.finishedAt.isEmpty() }.forEach { record ->
            doc.plan.forDay(record.week, record.day).forEach { exercise ->
                values[exercise.key()]?.let { record.results[exercise.key()] = it.copy() }
            }
        }
        save(doc)
    }

    /** Cierra una realización una sola vez y conserva su fecha real. */
    @Synchronized fun finish(week: Int, day: String) {
        val doc = active() ?: return
        doc.workouts.lastOrNull { it.kind == "tarde" && it.week == week && it.day == day && it.finishedAt.isEmpty() }
            ?.finishedAt = now()
        save(doc)
    }

    /** Conserva el comienzo real de una sesión matinal y reanuda una pendiente. */
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

    /** Escribe y sincroniza un temporal antes de reemplazar atómicamente el destino. */
    private fun writeAtomic(target: File, text: String) {
        check(folder.isDirectory || folder.mkdirs())
        val temporary = File(folder, "${target.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Copia registros mutables para evitar compartirlos entre realizaciones. */
    private fun copyResults(values: Map<String, ExerciseProgress>): MutableMap<String, ExerciseProgress> =
        values.mapValuesTo(mutableMapOf()) { it.value.copy() }

    /** Captura la fecha real con el desplazamiento horario del dispositivo. */
    private fun now(): String = ZonedDateTime.now().toOffsetDateTime().toString()

    private companion object {
        /** Patrón cerrado que impide rutas externas a la carpeta privada. */
        val ID = Regex("\\d{2}_\\d{4}(?:_\\d+)?")
    }
}
