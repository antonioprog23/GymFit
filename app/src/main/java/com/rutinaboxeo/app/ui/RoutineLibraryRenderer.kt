package com.rutinaboxeo.app.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.rutinaboxeo.app.MonthlyRoutine
import com.rutinaboxeo.app.MonthlyRoutineSnapshot
import com.rutinaboxeo.app.MonthlyRoutineStatus
import com.rutinaboxeo.app.RoutinePresentation

/** Renderiza la biblioteca mensual sin asumir navegación ni modificar persistencia. */
class RoutineLibraryRenderer(private val ui: GymFitActivity) {
    fun list(body: LinearLayout, catalog: MonthlyRoutineSnapshot, onImport: () -> Unit,
        onOpen: (MonthlyRoutine) -> Unit) {
        val documents = catalog.documents.filter { it.id != catalog.active?.id }
        if (documents.isEmpty()) {
            val empty = ui.col(18)
            ui.add(empty, ui.text("Todavía no hay otras rutinas", 20f, bold = true))
            ui.add(empty, ui.text("Las rutinas programadas, históricas y alternativas aparecerán aquí.",
                14f, ui.muted), 9)
            ui.add(empty, ui.button("Importar Excel").apply { setOnClickListener { onImport() } }, 16)
            ui.add(body, ui.card(empty))
            return
        }
        sections.forEach { (status, title) ->
            val group = documents.filter { catalog.status(it) == status }
            if (group.isEmpty()) return@forEach
            ui.add(body, ui.text(title, 13f, if (status == MonthlyRoutineStatus.ALTERNATIVE) ui.red else ui.muted,
                true), top = 10, bottom = 8)
            group.forEach { document ->
                val panel = ui.col(15)
                val heading = ui.row()
                heading.addView(ui.text(RoutinePresentation.monthTitle(document.month), 18f, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(ui.text(label(status), 12f,
                    if (status == MonthlyRoutineStatus.ALTERNATIVE) ui.red else ui.muted, true))
                ui.add(panel, heading)
                ui.add(panel, ui.text("${document.plan.exercises.size} ejercicios · ${document.workouts.size} entrenamientos",
                    13f, ui.muted), 7)
                ui.add(panel, ui.button("Consultar rutina", false).apply {
                    setOnClickListener { onOpen(document) }
                }, 12)
                ui.add(body, ui.card(panel), bottom = 9)
            }
        }
    }

    fun plan(body: LinearLayout, document: MonthlyRoutine, selectedDay: Int,
        onSelectDay: (Int) -> Unit, onExtraSession: (Int) -> Unit) {
        if (!document.plan.isMonthlyCalendar()) {
            ui.add(body, ui.text("Plan anterior de consulta", 18f, bold = true), bottom = 9)
            document.plan.exercises.forEach {
                ui.add(body, ui.text("${it.exercise} · ${it.series} × ${it.reps}", 14f), bottom = 7)
            }
            return
        }
        val period = document.month
        val day = selectedDay.coerceIn(1, period.lengthOfMonth())
        val weekHeader = ui.row()
        listOf("L", "M", "X", "J", "V", "S", "D").forEach { name ->
            weekHeader.addView(ui.text(name, 12f, ui.muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, ui.dp(28), 1f))
        }
        ui.add(body, weekHeader, bottom = 3)
        RoutinePresentation.calendarRows(period).forEach { week ->
            val line = ui.row()
            week.forEach { date ->
                if (date == null) line.addView(View(ui), LinearLayout.LayoutParams(0, ui.dp(45), 1f))
                else line.addView(ui.button(date.toString(), date == day).apply {
                    textSize = 12f
                    setOnClickListener { onSelectDay(date) }
                }, LinearLayout.LayoutParams(0, ui.dp(45), 1f).apply {
                    marginEnd = ui.dp(3); bottomMargin = ui.dp(3)
                })
            }
            ui.add(body, line)
        }
        val info = document.plan.calendarDay(day)
        val items = document.plan.forDate(day)
        val panel = ui.col(16)
        ui.add(panel, ui.text(RoutinePresentation.fullDate(period.atDay(day)), 19f, bold = true))
        ui.add(panel, ui.text("${info.type.uppercase()} · ${info.title}", 13f, ui.red, true), 7)
        if (info.focus.isNotBlank()) ui.add(panel, ui.text(info.focus, 13f, ui.muted), 6)
        items.forEach { exercise ->
            val result = document.progress[exercise.key()]
            val metrics = listOf(exercise.series + " series", exercise.reps,
                result?.weight?.takeIf(String::isNotBlank)?.let { "$it kg" }).filterNotNull().joinToString(" · ")
            ui.add(panel, ui.text("${exercise.exercise}\n$metrics", 14f), 10)
        }
        if (items.isNotEmpty()) ui.add(panel, ui.button("Realizar como sesión extra").apply {
            setOnClickListener { onExtraSession(day) }
        }, 15)
        ui.add(body, ui.card(panel), top = 10)
    }

    fun progress(body: LinearLayout, document: MonthlyRoutine) {
        val summary = RoutinePresentation.progress(document.plan, document.progress)
        val stats = ui.row()
        listOf("${summary.percent}%" to "Avance", summary.completedSessions.toString() to "Sesiones",
            summary.completedExercises.toString() to "Ejercicios").forEachIndexed { index, (number, name) ->
            val box = ui.col(9).apply { gravity = Gravity.CENTER }
            ui.add(box, ui.text(number, 22f, bold = true).apply { gravity = Gravity.CENTER })
            ui.add(box, ui.text(name, 12f, ui.muted).apply { gravity = Gravity.CENTER }, 4)
            stats.addView(ui.card(box), LinearLayout.LayoutParams(0, ui.dp(75), 1f).apply {
                if (index > 0) marginStart = ui.dp(5)
            })
        }
        ui.add(body, stats, bottom = 14)
        ui.add(body, ui.text("Últimos resultados", 18f, bold = true), bottom = 9)
        val weighted = RoutinePresentation.weightHistory(listOf(document)).take(12)
        if (weighted.isEmpty()) ui.add(body, ui.text("Todavía no hay pesos registrados.", 13f, ui.muted))
        weighted.forEach { result ->
            ui.add(body, ui.card(ui.col(13).apply {
                ui.add(this, ui.text(result.exercise, 15f, bold = true))
                ui.add(this, ui.text("${result.weight} kg${result.reps.takeIf(String::isNotBlank)?.let { " · $it reps" }.orEmpty()}${result.rir.takeIf(String::isNotBlank)?.let { " · RIR $it" }.orEmpty()}", 13f, ui.muted), 5)
                ui.add(this, ui.text(result.dateLabel, 12f, ui.muted), 4)
            }), bottom = 7)
        }
        ui.add(body, ui.text("Este progreso pertenece únicamente a esta rutina.", 13f, ui.muted), top = 10)
    }

    fun history(body: LinearLayout, document: MonthlyRoutine) {
        if (document.workouts.isEmpty()) {
            ui.add(body, ui.text("Aún no hay entrenamientos registrados.", 14f, ui.muted)); return
        }
        document.workouts.asReversed().forEach { record ->
            val panel = ui.col(14)
            val title = if (record.kind == "extra") "SESIÓN EXTRA · ${record.day}" else record.day
            ui.add(panel, ui.text(title, 15f, bold = true))
            ui.add(panel, ui.text(record.startedAt.ifBlank { "Fecha desconocida" }, 12f, ui.muted), 5)
            val completed = record.results.values.count { it.done }
            ui.add(panel, ui.text("$completed de ${record.results.size} ejercicios completados", 13f,
                if (record.finishedAt.isNotBlank()) ui.green else ui.muted), 6)
            record.results.filterValues { it.weight.isNotBlank() }.forEach { (key, result) ->
                val name = document.plan.exercises.firstOrNull { it.key() == key }?.exercise ?: key
                ui.add(panel, ui.text("$name · ${result.weight} kg · ${result.actualReps} reps · RIR ${result.rir}",
                    12f, ui.muted), 5)
            }
            ui.add(body, ui.card(panel), bottom = 8)
        }
    }

    companion object {
        fun label(status: MonthlyRoutineStatus): String = when (status) {
            MonthlyRoutineStatus.ACTIVE -> "ACTIVA"
            MonthlyRoutineStatus.PROGRAMMED -> "PROGRAMADA"
            MonthlyRoutineStatus.HISTORICAL -> "HISTÓRICA"
            MonthlyRoutineStatus.ALTERNATIVE -> "ALTERNATIVA"
        }

        private val sections = listOf(
            MonthlyRoutineStatus.PROGRAMMED to "PROGRAMADAS",
            MonthlyRoutineStatus.HISTORICAL to "HISTÓRICAS",
            MonthlyRoutineStatus.ALTERNATIVE to "ALTERNATIVAS"
        )
    }
}
