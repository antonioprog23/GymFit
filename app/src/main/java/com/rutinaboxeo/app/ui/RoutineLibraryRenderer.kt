package com.rutinaboxeo.app.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.rutinaboxeo.app.MonthlyRoutine
import com.rutinaboxeo.app.MonthlyRoutineSnapshot
import com.rutinaboxeo.app.MonthlyRoutineStatus
import com.rutinaboxeo.app.R
import com.rutinaboxeo.app.RoutineArchivePresentation
import com.rutinaboxeo.app.RoutinePresentation
import com.rutinaboxeo.app.WorkoutRecord
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Renderiza la biblioteca mensual sin asumir navegación ni modificar persistencia. */
class RoutineLibraryRenderer(private val ui: GymFitActivity) {
    fun archive(body: LinearLayout, catalog: MonthlyRoutineSnapshot, selectedYear: Int,
        onSelectYear: (Int) -> Unit, onImport: () -> Unit, onOpenMonth: (YearMonth) -> Unit) {
        if (catalog.documents.isEmpty()) {
            val empty = ui.col(18)
            ui.add(empty, ui.text("Todavía no hay rutinas mensuales", 20f, bold = true))
            ui.add(empty, ui.text("Las rutinas activas, programadas, históricas y alternativas aparecerán aquí.",
                14f, ui.muted), 9)
            ui.add(empty, ui.button("Importar Excel").apply { setOnClickListener { onImport() } }, 16)
            ui.add(body, ui.card(empty))
            return
        }

        val years = RoutineArchivePresentation.years(catalog)
        val yearIndex = years.indexOf(selectedYear).coerceAtLeast(0)
        val yearNavigation = ui.row()
        yearNavigation.addView(ui.button("‹", false).apply {
            contentDescription = "Año más reciente"
            isEnabled = yearIndex > 0
            setOnClickListener { years.getOrNull(yearIndex - 1)?.let(onSelectYear) }
        }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(48)))
        yearNavigation.addView(ui.text(selectedYear.toString(), 25f, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        yearNavigation.addView(ui.button("›", false).apply {
            contentDescription = "Año anterior"
            isEnabled = yearIndex in 0 until years.lastIndex
            setOnClickListener { years.getOrNull(yearIndex + 1)?.let(onSelectYear) }
        }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(48)))
        ui.add(body, yearNavigation, bottom = 16)

        val months = RoutineArchivePresentation.months(catalog, selectedYear)
        val principalDocuments = months.mapNotNull { it.primary ?: it.routines.firstOrNull() }
        val sessions = principalDocuments.sumOf { document ->
            document.workouts.count { it.finishedAt.isNotBlank() }
        }
        val average = principalDocuments.map { RoutinePresentation.progress(it.plan, it.progress).percent }
            .takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        val summary = ui.row()
        summary.addView(ui.metric(sessions.toString(), "sesiones en $selectedYear", ui.red),
            LinearLayout.LayoutParams(0, ui.dp(92), 1f).apply { marginEnd = ui.dp(7) })
        summary.addView(ui.metric("$average%", "completado", ui.green),
            LinearLayout.LayoutParams(0, ui.dp(92), 1f))
        ui.add(body, summary, bottom = 19)

        ui.add(body, ui.text("Calendario anual", 19f, bold = true), bottom = 4)
        ui.add(body, ui.text("Selecciona un mes para consultar sus rutinas.", 13f, ui.muted), bottom = 12)
        RoutineArchivePresentation.yearCalendar(catalog, selectedYear).chunked(3).forEach { quarter ->
            val row = ui.row()
            quarter.forEachIndexed { index, group ->
                val document = group.primary ?: group.routines.firstOrNull()
                val status = document?.let(catalog::status)
                val sessionsInMonth = document?.workouts?.count { it.finishedAt.isNotBlank() } ?: 0
                val panel = ui.col(12).apply { gravity = Gravity.CENTER }
                val monthName = group.month.month.getDisplayName(TextStyle.SHORT, Locale("es", "ES"))
                    .removeSuffix(".").replaceFirstChar { it.titlecase(Locale("es", "ES")) }
                ui.add(panel, ui.text(monthName, 16f, if (document == null) ui.muted else ui.ink, true).apply {
                    gravity = Gravity.CENTER
                })
                ui.add(panel, ui.text(if (document == null) "Sin rutina" else "$sessionsInMonth sesiones",
                    11f, ui.muted).apply { gravity = Gravity.CENTER }, 7)
                if (group.routines.size > 1) ui.add(panel, ui.text("${group.routines.size} rutinas", 10f, ui.red, true)
                    .apply { gravity = Gravity.CENTER }, 5)
                val fill = when (status) {
                    MonthlyRoutineStatus.ACTIVE -> ui.getColor(R.color.selected_surface)
                    MonthlyRoutineStatus.PROGRAMMED -> ui.getColor(R.color.orange_surface)
                    MonthlyRoutineStatus.HISTORICAL -> ui.getColor(R.color.completed_surface)
                    MonthlyRoutineStatus.ALTERNATIVE, null -> ui.cardSurface
                }
                val border = when (status) {
                    MonthlyRoutineStatus.ACTIVE -> ui.red
                    MonthlyRoutineStatus.PROGRAMMED -> ui.orange
                    MonthlyRoutineStatus.HISTORICAL -> ui.green
                    else -> ui.line
                }
                row.addView(ui.card(panel, fill, border).apply {
                    isEnabled = document != null
                    alpha = if (document == null) 0.58f else 1f
                    contentDescription = "$monthName, ${if (document == null) "sin rutina" else "$sessionsInMonth sesiones"}"
                    setOnClickListener { if (document != null) onOpenMonth(group.month) }
                }, LinearLayout.LayoutParams(0, ui.dp(105), 1f).apply {
                    if (index > 0) marginStart = ui.dp(7)
                })
            }
            ui.add(body, row, bottom = 8)
        }
    }

    fun month(body: LinearLayout, catalog: MonthlyRoutineSnapshot, period: YearMonth,
        onOpen: (MonthlyRoutine) -> Unit) {
        val group = RoutineArchivePresentation.months(catalog, period.year)
            .firstOrNull { it.month == period } ?: return
        val primary = group.primary
        ui.add(body, ui.sectionTitle("RUTINA PRINCIPAL"), bottom = 9)
        if (primary == null) {
            ui.add(body, ui.card(ui.col(16).apply {
                ui.add(this, ui.text("Este mes no tiene una rutina principal", 17f, bold = true))
                ui.add(this, ui.text("Puedes consultar las importaciones conservadas y elegir una como principal.",
                    13f, ui.muted), 7)
            }), bottom = 16)
        } else {
            val progress = RoutinePresentation.progress(primary.plan, primary.progress)
            val panel = ui.col(17)
            val heading = ui.row()
            heading.addView(ui.text(primary.plan.calendar.values.firstOrNull()?.title
                ?.takeIf(String::isNotBlank) ?: "Rutina mensual", 20f, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(ui.chip("Principal", ui.red, ui.getColor(R.color.selected_surface)))
            ui.add(panel, heading)
            ui.add(panel, ui.text("${progress.completedSessions} sesiones completadas", 13f, ui.muted), 9)
            ui.add(panel, ui.progressBar(progress.percent), 10)
            val stats = ui.row()
            listOf("${progress.percent}%" to "avance", primary.workouts.size.toString() to "registros",
                primary.plan.exercises.size.toString() to "ejercicios").forEachIndexed { index, (value, label) ->
                stats.addView(ui.metric(value, label, if (index == 0) ui.green else ui.ink),
                    LinearLayout.LayoutParams(0, ui.dp(73), 1f).apply { if (index > 0) marginStart = ui.dp(6) })
            }
            ui.add(panel, stats, 13)
            ui.add(panel, ui.button("Consultar rutina").apply { setOnClickListener { onOpen(primary) } }, 14)
            ui.add(body, ui.card(panel), bottom = 20)
        }

        ui.add(body, ui.text("Otras rutinas", 19f, bold = true), bottom = 9)
        if (group.alternatives.isEmpty()) {
            ui.add(body, ui.text("No hay otras importaciones para este mes.", 14f, ui.muted))
        } else group.alternatives.forEach { document ->
            ui.add(body, documentRow(document, catalog.status(document), onOpen), bottom = 8)
        }
    }

    private fun documentRow(document: MonthlyRoutine, status: MonthlyRoutineStatus,
        onOpen: (MonthlyRoutine) -> Unit) = ui.card(ui.row().apply {
        setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12))
        addView(ui.text("●", 23f, if (status == MonthlyRoutineStatus.ALTERNATIVE) ui.orange else ui.muted),
            LinearLayout.LayoutParams(ui.dp(37), -2))
        val description = ui.col()
        ui.add(description, ui.text(document.plan.calendar.values.firstOrNull()?.title
            ?.takeIf(String::isNotBlank) ?: "Rutina mensual", 15f, bold = true))
        ui.add(description, ui.text("${document.workouts.size} sesiones · ${RoutinePresentation.progress(document.plan, document.progress).percent}%",
            12f, ui.muted), 4)
        addView(description, LinearLayout.LayoutParams(0, -2, 1f))
        addView(statusChip(status))
        addView(ui.text("›", 23f, ui.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ui.dp(26), -1))
        setOnClickListener { onOpen(document) }
    })

    private fun documentCard(document: MonthlyRoutine, status: MonthlyRoutineStatus,
        onOpen: (MonthlyRoutine) -> Unit) = ui.card(ui.col(16).apply {
        val heading = ui.row()
        heading.addView(ui.text(document.plan.calendar.values.firstOrNull()?.title
            ?.takeIf(String::isNotBlank) ?: "Rutina mensual", 18f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(statusChip(status))
        ui.add(this, heading)
        val progress = RoutinePresentation.progress(document.plan, document.progress)
        ui.add(this, ui.text("${document.workouts.size} sesiones registradas · ${progress.percent}% de avance",
            13f, ui.muted), 8)
        ui.add(this, ui.button("Consultar rutina", false).apply {
            setOnClickListener { onOpen(document) }
        }, 12)
    })

    private fun statusChip(status: MonthlyRoutineStatus) = when (status) {
        MonthlyRoutineStatus.ACTIVE -> ui.chip("Activa", ui.red, ui.getColor(R.color.selected_surface))
        MonthlyRoutineStatus.PROGRAMMED -> ui.chip("Programada", ui.orange, ui.getColor(R.color.orange_surface))
        MonthlyRoutineStatus.HISTORICAL -> ui.chip("Finalizada", ui.green, ui.getColor(R.color.completed_surface))
        MonthlyRoutineStatus.ALTERNATIVE -> ui.chip("Alternativa", ui.red, ui.getColor(R.color.selected_surface))
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
        ui.add(body, ui.text("Calendario de planificación", 18f, bold = true), bottom = 4)
        ui.add(body, ui.text("Selecciona un día para consultar el entrenamiento previsto.", 13f, ui.muted),
            bottom = 12)
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
        if (items.isNotEmpty()) ui.add(panel, ui.button("▶  Iniciar sesión extra").apply {
            setOnClickListener { onExtraSession(day) }
        }, 15)
        if (items.isNotEmpty()) ui.add(panel, ui.text("No cambiará tu rutina principal", 12f, ui.muted).apply {
            gravity = Gravity.CENTER
        }, 8)
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

    fun history(body: LinearLayout, document: MonthlyRoutine, selectedDay: Int?,
        onSelectDay: (Int) -> Unit, onOpenWorkout: (WorkoutRecord) -> Unit) {
        if (document.workouts.isEmpty()) {
            ui.add(body, ui.text("Aún no hay entrenamientos registrados.", 14f, ui.muted)); return
        }
        val historyDays = RoutinePresentation.historyDays(document)
        if (historyDays.isNotEmpty()) {
            val chosenDay = selectedDay?.takeIf { day -> historyDays.any { it.day == day } }
                ?: RoutinePresentation.initialHistoryDay(document) ?: historyDays.last().day
            val byDay = historyDays.associateBy { it.day }
            ui.add(body, ui.text("Sesiones realizadas", 18f, bold = true), bottom = 4)
            ui.add(body, ui.text("Selecciona un día para consultar sus realizaciones.", 13f, ui.muted), bottom = 13)
            val weekHeader = ui.row()
            listOf("L", "M", "X", "J", "V", "S", "D").forEach { name ->
                weekHeader.addView(ui.text(name, 12f, ui.muted, true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, ui.dp(28), 1f))
            }
            ui.add(body, weekHeader, bottom = 3)
            RoutinePresentation.calendarRows(document.month).forEach { week ->
                val line = ui.row()
                week.forEach { day ->
                    if (day == null) line.addView(View(ui), LinearLayout.LayoutParams(0, ui.dp(50), 1f))
                    else {
                        val history = byDay[day]
                        val selected = day == chosenDay
                        val pending = history?.pending?.let { it > 0 } == true
                        val cell = FrameLayout(ui).apply {
                            val fill = when {
                                selected -> ui.red
                                pending -> ui.getColor(R.color.orange_surface)
                                history != null -> ui.getColor(R.color.completed_surface)
                                else -> ui.cardSurface
                            }
                            val border = when {
                                selected -> ui.red
                                pending -> ui.orange
                                history != null -> ui.green
                                else -> ui.line
                            }
                            background = ui.box(fill, 14, border)
                            addView(ui.text(day.toString(), 13f,
                                if (selected) ui.getColor(R.color.on_accent) else ui.ink, selected).apply {
                                gravity = Gravity.CENTER
                            }, FrameLayout.LayoutParams(-1, -1))
                            if ((history?.workouts?.size ?: 0) > 1) addView(ui.text("×${history!!.workouts.size}",
                                9f, if (selected) ui.getColor(R.color.on_accent) else ui.red, true).apply {
                                gravity = Gravity.CENTER
                            }, FrameLayout.LayoutParams(ui.dp(24), ui.dp(17), Gravity.TOP or Gravity.END))
                            isEnabled = history != null
                            alpha = if (history == null) 0.55f else 1f
                            contentDescription = when {
                                history == null -> "$day, sin sesiones"
                                pending -> "$day, ${history.workouts.size} sesiones, pendiente"
                                else -> "$day, ${history.workouts.size} sesiones completadas"
                            }
                            setOnClickListener { if (history != null) onSelectDay(day) }
                        }
                        line.addView(cell, LinearLayout.LayoutParams(0, ui.dp(47), 1f).apply {
                            marginEnd = ui.dp(3); bottomMargin = ui.dp(3)
                        })
                    }
                }
                ui.add(body, line)
            }
            val legend = ui.row()
            listOf("●" to "Finalizada" to ui.green, "●" to "Pendiente" to ui.orange,
                "×2" to "Repeticiones" to ui.red).forEachIndexed { index, pair ->
                val (symbolAndLabel, color) = pair
                val (symbol, label) = symbolAndLabel
                legend.addView(ui.row().apply {
                    gravity = Gravity.CENTER
                    addView(ui.text(symbol, 12f, color, true))
                    addView(ui.text(label, 10f, ui.muted), LinearLayout.LayoutParams(-2, -2).apply {
                        marginStart = ui.dp(4)
                    })
                }, LinearLayout.LayoutParams(0, ui.dp(36), 1f).apply { if (index > 0) marginStart = ui.dp(3) })
            }
            ui.add(body, legend, top = 6, bottom = 12)

            val selected = byDay.getValue(chosenDay)
            ui.add(body, ui.text(RoutinePresentation.fullDate(document.month.atDay(chosenDay)), 19f, bold = true),
                bottom = 9)
            selected.workouts.forEach { record ->
                val completed = record.results.values.count { it.done }
                val timing = RoutinePresentation.workoutTime(record)
                val panel = ui.col(14)
                val heading = ui.row()
                val repetition = selected.workouts.sortedBy { it.startedAt }.indexOfFirst { it.id == record.id }
                val label = when {
                    record.kind == "extra" -> "Sesión extra"
                    repetition > 0 -> "Repetición $repetition"
                    else -> "Sesión principal"
                }
                heading.addView(ui.text(label, 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(ui.chip(if (record.finishedAt.isBlank()) "Pendiente" else "Finalizada",
                    if (record.finishedAt.isBlank()) ui.orange else ui.green,
                    if (record.finishedAt.isBlank()) ui.getColor(R.color.orange_surface)
                    else ui.getColor(R.color.completed_surface)))
                ui.add(panel, heading)
                ui.add(panel, ui.text(listOfNotNull(timing.range, timing.duration).joinToString(" · "),
                    12f, ui.muted), 7)
                ui.add(panel, ui.text("$completed de ${record.results.size} ejercicios completados  ›", 13f,
                    if (record.finishedAt.isNotBlank()) ui.green else ui.orange, true), 8)
                ui.add(body, ui.card(panel).apply { setOnClickListener { onOpenWorkout(record) } }, bottom = 8)
            }
        }

        val legacy = document.workouts.filter { it.dayOfMonth == null }
        if (legacy.isNotEmpty()) ui.add(body, ui.text("Sesiones anteriores sin fecha", 17f, bold = true),
            top = if (historyDays.isEmpty()) 0 else 15, bottom = 9)
        legacy.asReversed().forEach { record ->
            val panel = ui.col(14)
            ui.add(panel, ui.text(record.day, 15f, bold = true))
            ui.add(panel, ui.text(record.startedAt.ifBlank { "Fecha desconocida" }, 12f, ui.muted), 5)
            val completed = record.results.values.count { it.done }
            ui.add(panel, ui.text("$completed de ${record.results.size} ejercicios completados", 13f,
                if (record.finishedAt.isNotBlank()) ui.green else ui.muted), 6)
            ui.add(body, ui.card(panel).apply { setOnClickListener { onOpenWorkout(record) } }, bottom = 8)
        }
    }

    companion object {
        fun label(status: MonthlyRoutineStatus): String = when (status) {
            MonthlyRoutineStatus.ACTIVE -> "ACTIVA"
            MonthlyRoutineStatus.PROGRAMMED -> "PROGRAMADA"
            MonthlyRoutineStatus.HISTORICAL -> "FINALIZADA"
            MonthlyRoutineStatus.ALTERNATIVE -> "ALTERNATIVA"
        }

    }
}
