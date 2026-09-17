package com.rutinaboxeo.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val ink = Color.rgb(27, 41, 55)
    private val muted = Color.rgb(89, 106, 124)
    private val red = Color.rgb(190, 58, 65)
    private val pale = Color.rgb(235, 243, 249)
    private val line = Color.rgb(226, 235, 242)
    private val green = Color.rgb(24, 132, 92)
    private lateinit var content: FrameLayout
    private lateinit var navigation: LinearLayout
    private var plan = RoutinePlan(emptyList())
    private val progress by lazy { RoutineStore.loadProgress(this) }
    private var selectedWeek = 1
    private var page = Page.TODAY
    private var templateExport = false
    private var morningIndex = 0
    private var morningRemaining = 0
    private var morningStarted = false
    private var morningRunning = false
    private var morningTimer: CountDownTimer? = null
    private var morningClock: TextView? = null
    private var morningTotal: TextView? = null
    private var morningPlay: MaterialButton? = null
    private var sessionWeek = 1
    private var sessionDay = ""
    private var sessionIndex = 0
    private val exerciseRuns = mutableMapOf<String, ExerciseRun>()
    private var sessionTimer: CountDownTimer? = null
    private var sessionRunning = false
    private var sessionClock: TextView? = null
    private var sessionStatus: TextView? = null
    private var sessionRestOverview: TextView? = null
    private var sessionAction: MaterialButton? = null
    private var sessionFieldKey: String? = null
    private var sessionFields: List<EditText> = emptyList()

    private enum class Page { TODAY, MORNING, ROUTINE, SESSION, PROGRESS, TEMPLATE }

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val parsed = contentResolver.openInputStream(uri)?.use { XlsxRoutineParser.parse(it) }
                ?: error("No se pudo abrir el archivo")
            pauseMorning()
            pauseSessionRest()
            plan = parsed
            resetMorning()
            getPreferences(MODE_PRIVATE).edit().remove("morningDone").apply()
            exerciseRuns.clear()
            RoutineStore.saveRoutine(this, parsed)
            selectedWeek = parsed.weeks().firstOrNull() ?: 1
            RoutineStore.saveActiveWeek(this, selectedWeek)
            Toast.makeText(this, "Rutina importada: ${parsed.exercises.size} ejercicios de tarde y ${parsed.morningSteps.size} de mañana", Toast.LENGTH_LONG).show()
            showPage(Page.TODAY)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo importar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val saveTemplate = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) try {
            val output = contentResolver.openOutputStream(uri) ?: error("No se pudo guardar el archivo")
            output.use { target -> assets.open(TEMPLATE).use { it.copyTo(target) } }
            Toast.makeText(this, "Plantilla Excel guardada", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(248, 251, 254)
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.screenContent)
        navigation = findViewById(R.id.bottomNavigation)
        val stored = RoutineStore.loadRoutine(this)
        val importStatus = RoutineStore.importedStatus(this)
        // Antes se guardaba la plantilla incluida como rutina. En esa versión solo
        // recuperamos una rutina antigua cuando su estructura difiere de la plantilla.
        val bundled = if (importStatus == null && stored != null) try {
            embeddedPlan()
        } catch (_: Exception) { null } else null
        val imported = RoutineImportPolicy.shouldShowRoutine(importStatus, stored, bundled)
        if (importStatus == null) RoutineStore.setImported(this, imported)
        plan = if (imported) stored ?: RoutinePlan(emptyList()) else RoutinePlan(emptyList())
        morningRemaining = plan.morningSteps.firstOrNull()?.seconds ?: 0
        selectedWeek = RoutineStore.activeWeek(this).takeIf { it in plan.weeks() }
            ?: plan.weeks().firstOrNull() ?: 1
        if (savedInstanceState != null) {
            if (plan.morningSteps.isNotEmpty()) {
                morningIndex = savedInstanceState.getInt("morningIndex", 0)
                    .coerceIn(plan.morningSteps.indices)
                morningRemaining = savedInstanceState.getInt("morningRemaining", plan.morningSteps[morningIndex].seconds)
                    .coerceIn(1, plan.morningSteps[morningIndex].seconds)
                morningStarted = savedInstanceState.getBoolean("morningStarted", false)
            }
            sessionWeek = savedInstanceState.getInt("sessionWeek", selectedWeek)
            sessionDay = savedInstanceState.getString("sessionDay").orEmpty()
            sessionIndex = savedInstanceState.getInt("sessionIndex", 0)
            if (sessionDay.isNotBlank()) {
                plan.forDay(sessionWeek, sessionDay).getOrNull(sessionIndex)?.let { item ->
                    exerciseRuns[item.key()] = ExerciseRun(
                        phase = runCatching { ExercisePhase.valueOf(savedInstanceState.getString("sessionPhase", "READY")!!) }
                            .getOrDefault(ExercisePhase.READY),
                        round = savedInstanceState.getInt("sessionRound", 1),
                        remainingSeconds = savedInstanceState.getInt("sessionRemaining", 0)
                    )
                }
            }
        }
        when (savedInstanceState?.getString("page")) {
            Page.MORNING.name -> showPage(if (plan.morningSteps.isEmpty()) Page.TODAY else Page.MORNING)
            Page.SESSION.name -> if (sessionDay.isNotBlank() && plan.forDay(sessionWeek, sessionDay).isNotEmpty())
                session(sessionWeek, sessionDay) else showPage(Page.TODAY)
            else -> showPage(Page.TODAY)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveSessionFields()
        outState.putString("page", page.name)
        outState.putInt("morningIndex", morningIndex)
        outState.putInt("morningRemaining", morningRemaining)
        outState.putBoolean("morningStarted", morningStarted)
        outState.putInt("sessionWeek", sessionWeek)
        outState.putString("sessionDay", sessionDay)
        outState.putInt("sessionIndex", sessionIndex)
        plan.forDay(sessionWeek, sessionDay).getOrNull(sessionIndex)?.let { item ->
            exerciseRuns[item.key()]?.let { run ->
                outState.putString("sessionPhase", run.phase.name)
                outState.putInt("sessionRound", run.round)
                outState.putInt("sessionRemaining", run.remainingSeconds)
            }
        }
        super.onSaveInstanceState(outState)
    }

    private fun embeddedPlan() = assets.open(TEMPLATE).use { XlsxRoutineParser.parse(it) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun box(fill: Int, radius: Int = 16, border: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private fun col(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        })
    }
    private fun card(child: View, fill: Int = Color.WHITE, border: Int = line) = MaterialCardView(this).apply {
        setCardBackgroundColor(fill); radius = dp(16).toFloat(); cardElevation = dp(1).toFloat()
        strokeColor = border; strokeWidth = dp(1); addView(child)
    }
    private fun button(value: String, filled: Boolean = true) = MaterialButton(this).apply {
        text = value; isAllCaps = false; textSize = 15f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        cornerRadius = dp(10); minHeight = dp(48); insetTop = 0; insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(if (filled) red else Color.WHITE)
        setTextColor(if (filled) Color.WHITE else red)
        if (!filled) { strokeColor = ColorStateList.valueOf(red); strokeWidth = dp(1) }
    }
    private fun scrollPage(): LinearLayout {
        content.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = col().apply { setPadding(dp(18), dp(20), dp(18), dp(24)) }
        scroll.addView(body); content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return body
    }
    private fun header(body: LinearLayout, title: String, subtitle: String? = null, backTo: Page? = null) {
        val line = row()
        if (backTo != null) line.addView(text("‹", 34f).apply {
            gravity = Gravity.CENTER; contentDescription = "Volver"
            setOnClickListener { showPage(backTo) }
        }, LinearLayout.LayoutParams(dp(32), dp(44)))
        val titles = col()
        add(titles, text(title, 26f, bold = true))
        if (subtitle != null) add(titles, text(subtitle, 13f, muted), 4)
        line.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text("⚙", 25f, muted).apply {
            gravity = Gravity.CENTER; contentDescription = "Ajustes"; setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        add(body, line, bottom = 19)
    }

    private fun showPage(next: Page) {
        if (page == Page.MORNING && next != Page.MORNING) pauseMorning()
        if (page == Page.SESSION && next != Page.SESSION) {
            saveSessionFields()
            pauseSessionRest()
        }
        page = next
        when (next) {
            Page.TODAY -> today()
            Page.MORNING -> if (plan.morningSteps.isEmpty()) today() else morningPage()
            Page.ROUTINE -> routine()
            Page.PROGRESS -> progressPage()
            Page.TEMPLATE -> templatePage()
            Page.SESSION -> Unit
        }
        drawNavigation()
    }
    private fun drawNavigation() {
        navigation.removeAllViews()
        listOf(
            Triple(Page.TODAY, "Hoy", R.drawable.nav_home),
            Triple(Page.ROUTINE, "Rutina", R.drawable.nav_calendar),
            Triple(Page.PROGRESS, "Progreso", R.drawable.nav_chart),
            Triple(Page.TEMPLATE, "Plantilla", R.drawable.nav_file)
        ).forEach { (target, name, icon) ->
            val active = page == target || (page == Page.SESSION && target == Page.ROUTINE) ||
                (page == Page.MORNING && target == Page.TODAY)
            val tab = col().apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(8)) }
            tab.addView(ImageView(this).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(if (active) red else muted)
                contentDescription = name
            }, LinearLayout.LayoutParams(dp(23), dp(23)))
            add(tab, text(name, 11f, if (active) red else muted, active).apply { gravity = Gravity.CENTER }, 4)
            tab.setOnClickListener { showPage(target) }
            navigation.addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }
    private fun todayName() = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "Lunes"; Calendar.TUESDAY -> "Martes"
        Calendar.WEDNESDAY -> "Miércoles"; Calendar.THURSDAY -> "Jueves"
        Calendar.FRIDAY -> "Viernes"; Calendar.SATURDAY -> "Sábado"; else -> "Domingo"
    }

    private fun today() {
        val body = scrollPage()
        val locale = Locale("es", "ES")
        val date = SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(Calendar.getInstance().time)
        header(body, "Hoy", date.replaceFirstChar { it.titlecase(locale) })
        add(body, card(col(15).apply {
            add(this, text("“La disciplina de hoy construye el boxeador de mañana.”", 15f).apply {
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            })
        }, pale, pale), bottom = 14)
        add(body, text("MAÑANA", 13f, red, true), bottom = 8)
        val morning = col(17)
        val completed = morningDoneToday()
        add(morning, text("Movilidad y elasticidad", 21f, bold = true))
        if (plan.morningSteps.isEmpty()) {
            add(morning, text("Importa una plantilla con la hoja Mañana para cargar esta sesión.", 14f, muted), 10)
            add(morning, button("Importar archivo Excel").apply { setOnClickListener { openRoutinePicker() } }, 16)
        } else {
            val total = plan.morningSteps.sumOf { it.seconds }
            add(morning, text("${total / 60} minutos · ${plan.morningSteps.size} ejercicios", 13f, muted), 6)
            add(morning, text("Sigue los pasos y las indicaciones que hayas escrito en la plantilla.", 14f), 13)
            if (completed) add(morning, text("✓  Completada hoy", 14f, green, true), 13)
            add(morning, button(when {
                completed -> "↻  Repetir rutina"
                morningStarted -> "▶  Continuar rutina"
                else -> "▶  Empezar rutina"
            }).apply {
                setOnClickListener {
                    if (completed) resetMorning()
                    morningStarted = true
                    showPage(Page.MORNING)
                }
            }, 16)
        }
        add(body, card(morning, Color.WHITE, line), bottom = 18)
        add(body, text("TARDE", 13f, red, true), bottom = 8)
        if (plan.exercises.isEmpty()) {
            val empty = col(18)
            add(empty, text("Importa tu rutina de tarde", 21f, bold = true))
            add(empty, text("Los ejercicios, series, repeticiones y descansos aparecerán aquí después de importar tu Excel.",
                14f, muted), 10)
            add(empty, button("Importar archivo Excel").apply {
                setOnClickListener { openRoutinePicker() }
            }, 17)
            add(empty, button("Descargar plantilla", false).apply {
                setOnClickListener { saveTemplate.launch("GymFit_plantilla.xlsx") }
            }, 8)
            add(body, card(empty))
            return
        }
        val day = todayName()
        val info = plan.session(day)
        val items = plan.forDay(selectedWeek, day)
        val hero = FrameLayout(this).apply { background = box(ink, 18); clipToOutline = true }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.boxing_hero); scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(16, 25, 35), 0xD91C2A38.toInt(), 0x451C2A38))
        }, FrameLayout.LayoutParams(-1, -1))
        val overlay = col().apply { setPadding(dp(19), dp(18), dp(19), dp(17)) }
        add(overlay, text("ENTRENAMIENTO DE TARDE", 12f, Color.WHITE, true))
        add(overlay, text(info.title, 26f, Color.WHITE, true), 10)
        add(overlay, text("$day · Semana $selectedWeek", 15f, Color.WHITE))
        overlay.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        add(overlay, text("✦  ${items.size} ejercicios", 14f, Color.WHITE), bottom = 6)
        if (info.focus.isNotBlank()) add(overlay, text("◎  ${info.focus}", 13f, Color.WHITE), bottom = 14)
        add(overlay, button("▶  Empezar entrenamiento").apply { setOnClickListener { session(selectedWeek, day) } })
        hero.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        body.addView(hero, LinearLayout.LayoutParams(-1, dp(322)))
        val tip = row().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        tip.addView(text("♧", 26f), LinearLayout.LayoutParams(dp(38), -2))
        val tipText = col()
        add(tipText, text("Consejo del día", 15f, bold = true))
        add(tipText, text(info.note.ifBlank { "La técnica gana peleas, pero la constancia las mantiene." }, 13f, muted), 5)
        tip.addView(tipText, LinearLayout.LayoutParams(0, -2, 1f))
        add(body, card(tip, pale, pale), top = 16)
    }

    private fun morningDoneToday(): Boolean = getPreferences(MODE_PRIVATE).getString("morningDone", null) ==
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().time)

    private fun resetMorning() {
        morningTimer?.cancel()
        morningTimer = null
        morningRunning = false
        morningIndex = 0
        morningRemaining = plan.morningSteps.firstOrNull()?.seconds ?: 0
        morningStarted = false
    }

    private fun morningPage() {
        val body = scrollPage()
        val steps = plan.morningSteps
        val step = steps[morningIndex]
        val total = steps.sumOf { it.seconds }
        header(body, "Movilidad matinal", "${total / 60} minutos para empezar el día", backTo = Page.TODAY)

        val panel = col(18)
        add(panel, text("PASO ${morningIndex + 1} DE ${steps.size}", 13f, red, true))
        add(panel, text(step.title, 24f, bold = true), 9)
        add(panel, text(step.instruction, 15f, muted), 11)
        morningClock = text(formatTime(morningRemaining), 43f, ink, true).apply { gravity = Gravity.CENTER }
        add(panel, morningClock!!, 23)
        morningTotal = text("Quedan ${formatTime(morningSecondsLeft())} de ${formatTime(total)}", 13f, muted).apply {
            gravity = Gravity.CENTER
        }
        add(panel, morningTotal!!, 4)
        morningPlay = button(if (morningRunning) "Ⅱ  Pausar" else "▶  ${if (morningRemaining == step.seconds) "Empezar" else "Continuar"}").apply {
            setOnClickListener { if (morningRunning) pauseMorning() else startMorning() }
        }
        add(panel, morningPlay!!, 19)
        add(body, card(panel, pale, pale), bottom = 12)

        val controls = row()
        controls.addView(button("‹  Anterior", false).apply {
            isEnabled = morningIndex > 0
            setOnClickListener {
                pauseMorning()
                morningIndex--
                morningRemaining = steps[morningIndex].seconds
                morningPage()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(7) })
        controls.addView(button(if (morningIndex == steps.lastIndex) "✓  Terminar" else "Siguiente  ›", false).apply {
            setOnClickListener { advanceMorning() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        add(body, controls, bottom = 18)

        add(body, text("Todos los ejercicios", 18f, bold = true), bottom = 10)
        steps.forEachIndexed { index, item ->
            val entry = row().apply { setPadding(dp(13), dp(12), dp(13), dp(12)) }
            entry.addView(text(if (index < morningIndex) "✓" else "${index + 1}", 15f,
                if (index <= morningIndex) red else muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(28), -2))
            entry.addView(text(item.title, 14f, ink, index == morningIndex),
                LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(text(formatTime(item.seconds), 13f, muted))
            add(body, card(entry, if (index == morningIndex) Color.rgb(255, 249, 249) else Color.WHITE,
                if (index == morningIndex) red else line), bottom = 7)
        }
        add(body, text("Muévete sin dolor y a tu propio ritmo. Si notas mareo o malestar, detén la rutina.",
            12f, muted), top = 7)
    }

    private fun morningSecondsLeft(): Int = morningRemaining +
        plan.morningSteps.drop(morningIndex + 1).sumOf { it.seconds }

    private fun formatTime(seconds: Int) = "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)

    private fun updateMorningClock() {
        morningClock?.text = formatTime(morningRemaining)
        morningTotal?.text = "Quedan ${formatTime(morningSecondsLeft())} de ${formatTime(plan.morningSteps.sumOf { it.seconds })}"
        morningPlay?.text = if (morningRunning) "Ⅱ  Pausar" else
            "▶  ${if (morningRemaining == plan.morningSteps[morningIndex].seconds) "Empezar" else "Continuar"}"
    }

    private fun startMorning() {
        if (morningRunning) return
        morningStarted = true
        morningRunning = true
        updateMorningClock()
        morningTimer = object : CountDownTimer(morningRemaining * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                morningRemaining = ((millisUntilFinished + 999) / 1000).toInt()
                updateMorningClock()
            }
            override fun onFinish() {
                morningRemaining = 0
                morningRunning = false
                morningTimer = null
                advanceMorning()
            }
        }.start()
    }

    private fun pauseMorning() {
        morningTimer?.cancel()
        morningTimer = null
        morningRunning = false
        if (page == Page.MORNING && morningIndex in plan.morningSteps.indices) updateMorningClock()
    }

    private fun advanceMorning() {
        pauseMorning()
        if (morningIndex == plan.morningSteps.lastIndex) {
            getPreferences(MODE_PRIVATE).edit().putString("morningDone",
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().time)).apply()
            resetMorning()
            Toast.makeText(this, "¡Rutina de mañana completada!", Toast.LENGTH_LONG).show()
            showPage(Page.TODAY)
        } else {
            morningIndex++
            morningRemaining = plan.morningSteps[morningIndex].seconds
            morningPage()
        }
    }

    private fun routine() {
        val body = scrollPage()
        header(body, "Rutina", "Selecciona una semana y un día")
        if (plan.exercises.isEmpty()) {
            showRoutineEmpty(body)
            return
        }
        val weeks = row()
        plan.weeks().forEach { week ->
            weeks.addView(button("Semana $week", week == selectedWeek).apply {
                textSize = 12f; minHeight = dp(40)
                setOnClickListener {
                    selectedWeek = week; RoutineStore.saveActiveWeek(this@MainActivity, week); routine()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
        }
        add(body, weeks, bottom = 13)
        plan.days(selectedWeek).forEach { day ->
            val info = plan.session(day)
            val items = plan.forDay(selectedWeek, day)
            val done = items.count { progress[it.key()]?.done == true }
            val panel = row().apply { setPadding(dp(13), dp(13), dp(13), dp(13)) }
            panel.addView(text(if (day == "Domingo") "◷" else "✦", 20f, red, true).apply {
                gravity = Gravity.CENTER; background = box(Color.rgb(250, 239, 241), 25)
            }, LinearLayout.LayoutParams(dp(43), dp(43)))
            val details = col().apply { setPadding(dp(11), 0, 0, 0) }
            add(details, text(day, 16f, bold = true))
            add(details, text(info.title, 14f), 3)
            add(details, text("${items.size} ejercicios · $done completados", 12f, muted), 3)
            panel.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val complete = items.isNotEmpty() && done == items.size
            panel.addView(text(if (complete) "✓" else "›", 22f, if (complete) green else red, true))
            add(body, card(panel, if (day == todayName()) Color.rgb(255, 249, 249) else Color.WHITE,
                if (day == todayName()) red else line).apply {
                setOnClickListener { session(selectedWeek, day) }
            }, bottom = 9)
        }
    }

    private fun showRoutineEmpty(body: LinearLayout) {
        val panel = col(18)
        add(panel, text("Aún no hay rutina importada", 21f, bold = true))
        add(panel, text("Selecciona tu archivo Excel para cargar los ejercicios y empezar a registrar tu progreso.",
            14f, muted), 10)
        add(panel, button("Importar archivo Excel").apply {
            setOnClickListener { openRoutinePicker() }
        }, 18)
        add(body, card(panel, pale, pale))
    }

    private fun openRoutinePicker() {
        importPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    private fun session(week: Int, day: String) {
        if (sessionWeek != week || sessionDay != day) {
            saveSessionFields()
            pauseSessionRest()
            sessionWeek = week
            sessionDay = day
            val items = plan.forDay(week, day)
            sessionIndex = items.indexOfFirst { progress[it.key()]?.done != true }.takeIf { it >= 0 } ?: 0
        }
        page = Page.SESSION
        drawNavigation()
        renderSession()
    }

    private fun renderSession() {
        saveSessionFields()
        val week = sessionWeek
        val day = sessionDay
        val items = plan.forDay(week, day)
        sessionIndex = sessionIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val body = scrollPage()
        sessionFields = emptyList()
        sessionFieldKey = null
        header(body, plan.session(day).title, "Semana $week · $day", backTo = Page.ROUTINE)
        if (items.isEmpty()) {
            add(body, text("No hay ejercicios para esta sesión.", 15f, muted))
            return
        }
        val item = items[sessionIndex]
        exerciseRuns.getOrPut(item.key()) { ExerciseRun() }
        val completed = items.count { progress[it.key()]?.done == true }
        val summary = col(14)
        add(summary, text("$completed de ${items.size} ejercicios hechos", 15f, bold = true))
        val focus = plan.session(day).focus
        if (focus.isNotBlank()) add(summary, text(focus, 13f, muted), 5)
        add(body, card(summary, pale, pale), bottom = 13)

        val panel = col(18)
        add(panel, text("EJERCICIO ${sessionIndex + 1} DE ${items.size} · ${item.block.uppercase()}", 13f, red, true))
        add(panel, text(item.exercise, 24f, bold = true), 10)
        add(panel, text(ExerciseGuide.forExercise(item), 15f, muted), 11)
        val sets = ExerciseTiming.seriesCount(item.series)
        val rest = ExerciseTiming.restSeconds(item.rest)
        add(panel, text("$sets ${if (sets == 1) "serie" else "series"} · ${item.reps} · ${if (rest > 0) "descanso ${item.rest} tras cada serie" else "sin descanso"}", 13f, ink, true), 13)
        if (item.note.isNotBlank()) add(panel, text("Nota de la plantilla: ${item.note}", 12f, muted), 8)
        sessionStatus = text("", 14f, if (progress[item.key()]?.done == true) green else ink, true).apply {
            gravity = Gravity.CENTER
        }
        add(panel, sessionStatus!!, 19)
        sessionClock = text("", 41f, ink, true).apply { gravity = Gravity.CENTER }
        add(panel, sessionClock!!, 9)
        sessionRestOverview = text("", 12f, muted).apply {
            gravity = Gravity.CENTER
        }
        add(panel, sessionRestOverview!!, 4)
        val timed = ExerciseTiming.workSeconds(item.reps) > 0
        add(panel, text(when {
            timed && rest > 0 -> "El contador empieza al pulsar Empezar. Tras cada serie tendrás el descanso indicado."
            timed -> "El contador empieza al pulsar Empezar y marca la serie al llegar a cero."
            rest > 0 -> "Completa las repeticiones y pulsa Serie terminada. Después comenzará el descanso."
            else -> "Completa las repeticiones y pulsa Serie terminada."
        }, 12f, muted).apply {
            gravity = Gravity.CENTER
        }, 7)
        sessionAction = button("").apply { setOnClickListener { handleSessionAction(item) } }
        add(panel, sessionAction!!, 19)
        add(body, card(panel, pale, pale), bottom = 12)
        updateSessionControls(item)

        val controls = row()
        controls.addView(button("‹  Anterior", false).apply {
            isEnabled = sessionIndex > 0
            setOnClickListener { selectSessionExercise(sessionIndex - 1) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(7) })
        controls.addView(button(if (sessionIndex == items.lastIndex) "✓  Finalizar" else "Siguiente  ›", false).apply {
            setOnClickListener {
                if (sessionIndex == items.lastIndex) finish(items)
                else selectSessionExercise(sessionIndex + 1)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        add(body, controls, bottom = 18)

        val trackNumbers = item.block.lowercase() in listOf("fuerza", "potencia", "accesorio", "core", "estabilidad", "agarre")
        val current = progress.getOrPut(item.key()) { ExerciseProgress() }
        val fields = col(14)
        add(fields, text("Tu registro", 17f, bold = true), bottom = 10)
        val editors = mutableListOf<EditText>()
        if (trackNumbers) {
            val numbers = row()
            listOf(
                Triple("Peso (kg)", current.weight, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
                Triple("Reps reales", current.actualReps, InputType.TYPE_CLASS_TEXT),
                Triple("RIR", current.rir, InputType.TYPE_CLASS_NUMBER)
            ).forEachIndexed { index, (hint, value, type) ->
                val editor = sessionEdit(hint, value, type)
                editors += editor
                numbers.addView(editor, LinearLayout.LayoutParams(0, dp(48), if (index == 1) 1.4f else 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
            add(fields, numbers, bottom = 8)
        }
        editors += sessionEdit("Nota personal", current.userNote, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        add(fields, editors.last())
        sessionFieldKey = item.key()
        sessionFields = editors
        add(body, card(fields), bottom = 17)

        add(body, text("Todos los ejercicios", 18f, bold = true), bottom = 10)
        items.forEachIndexed { index, listed ->
            val done = progress[listed.key()]?.done == true
            val entry = row().apply { setPadding(dp(12), dp(11), dp(12), dp(11)) }
            entry.addView(text(if (done) "✓" else "${index + 1}", 16f,
                if (done) green else if (index == sessionIndex) red else muted, true).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(30), -2))
            val description = col().apply { setPadding(dp(5), 0, 0, 0) }
            add(description, text(listed.exercise, 14f, if (done) green else ink, index == sessionIndex || done))
            add(description, text("${listed.block} · ${listed.series} series", 12f, muted), 4)
            entry.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(CheckBox(this).apply {
                text = "Hecho"
                textSize = 11f
                setTextColor(if (done) green else muted)
                buttonTintList = ColorStateList.valueOf(green)
                isChecked = done
                setOnCheckedChangeListener { _, checked ->
                    pauseSessionRest()
                    progress.getOrPut(listed.key()) { ExerciseProgress() }.done = checked
                    exerciseRuns[listed.key()] = ExerciseRun()
                    RoutineStore.saveProgress(this@MainActivity, progress)
                    renderSession()
                }
            })
            add(body, card(entry, if (done) Color.rgb(238, 249, 244) else if (index == sessionIndex)
                Color.rgb(255, 249, 249) else Color.WHITE,
                if (done) green else if (index == sessionIndex) red else line).apply {
                setOnClickListener { selectSessionExercise(index) }
            }, bottom = 7)
        }
        add(body, button("Finalizar entrenamiento", false).apply { setOnClickListener { finish(items) } }, top = 12)
    }

    private fun sessionEdit(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 13f
        setSingleLine(true)
        setText(value)
        setPadding(dp(10), 0, dp(10), 0)
        background = box(Color.WHITE, 9, line)
    }

    private fun saveSessionFields() {
        val key = sessionFieldKey ?: return
        val current = progress.getOrPut(key) { ExerciseProgress() }
        if (sessionFields.size == 4) {
            current.weight = sessionFields[0].text.toString().trim()
            current.actualReps = sessionFields[1].text.toString().trim()
            current.rir = sessionFields[2].text.toString().trim()
        }
        current.userNote = sessionFields.lastOrNull()?.text?.toString()?.trim().orEmpty()
        RoutineStore.saveProgress(this, progress)
    }

    private fun selectSessionExercise(index: Int) {
        saveSessionFields()
        pauseSessionRest()
        sessionIndex = index
        renderSession()
    }

    private fun updateSessionControls(item: RoutineExercise) {
        val run = exerciseRuns.getOrPut(item.key()) { ExerciseRun() }
        val done = progress[item.key()]?.done == true
        val sets = ExerciseTiming.seriesCount(item.series)
        val work = ExerciseTiming.workSeconds(item.reps)
        val rest = ExerciseTiming.restSeconds(item.rest)
        sessionStatus?.text = when {
            done -> "✓  Ejercicio hecho"
            run.phase == ExercisePhase.REST -> "Descanso tras serie ${run.round}/$sets"
            run.phase == ExercisePhase.WORK -> "Serie ${run.round}/$sets · ${if (work > 0) "tiempo de ejercicio" else "haz ${item.reps} repeticiones"}"
            else -> "Listo para la serie 1/$sets"
        }
        sessionClock?.text = when {
            done -> "✓"
            run.phase == ExercisePhase.REST || (run.phase == ExercisePhase.WORK && work > 0) -> formatTime(run.remainingSeconds)
            run.phase == ExercisePhase.READY && work > 0 -> formatTime(work)
            else -> "${run.round}/$sets"
        }
        sessionRestOverview?.text = if (rest > 0)
            "${formatTime(rest)} de descanso tras cada serie, incluida la última"
        else "Sin descanso programado"
        sessionAction?.apply {
            isEnabled = !done
            text = when {
                done -> "✓  Hecho"
                run.phase == ExercisePhase.REST && sessionRunning -> "Ⅱ  Pausar descanso"
                run.phase == ExercisePhase.REST -> "▶  Continuar descanso"
                run.phase == ExercisePhase.WORK && work > 0 && sessionRunning -> "Ⅱ  Pausar ejercicio"
                run.phase == ExercisePhase.WORK && work > 0 -> "▶  Continuar ejercicio"
                run.phase == ExercisePhase.WORK -> "✓  Serie ${run.round}/$sets terminada"
                else -> "▶  Empezar ejercicio"
            }
        }
    }

    private fun handleSessionAction(item: RoutineExercise) {
        if (progress[item.key()]?.done == true) return
        val run = exerciseRuns.getOrPut(item.key()) { ExerciseRun() }
        when (run.phase) {
            ExercisePhase.READY -> {
                exerciseRuns[item.key()] = ExerciseFlow.start(item)
                renderSession()
                if (ExerciseTiming.workSeconds(item.reps) > 0) startSessionTimer(item)
            }
            ExercisePhase.WORK -> if (ExerciseTiming.workSeconds(item.reps) > 0) {
                if (sessionRunning) pauseSessionRest() else startSessionTimer(item)
            } else completeSessionPhase(item)
            ExercisePhase.REST -> if (sessionRunning) pauseSessionRest() else startSessionTimer(item)
            ExercisePhase.DONE -> Unit
        }
    }

    private fun completeSessionPhase(item: RoutineExercise) {
        val run = exerciseRuns[item.key()] ?: return
        val next = when (run.phase) {
            ExercisePhase.WORK -> ExerciseFlow.completeWork(item, run)
            ExercisePhase.REST -> ExerciseFlow.completeRest(item, run)
            else -> return
        }
        exerciseRuns[item.key()] = next
        if (next.phase == ExercisePhase.DONE) {
            progress.getOrPut(item.key()) { ExerciseProgress() }.done = true
            RoutineStore.saveProgress(this, progress)
        }
        renderSession()
        if (next.phase == ExercisePhase.REST ||
            (next.phase == ExercisePhase.WORK && ExerciseTiming.workSeconds(item.reps) > 0)) {
            startSessionTimer(item)
        }
    }

    private fun startSessionTimer(item: RoutineExercise) {
        val run = exerciseRuns[item.key()] ?: return
        if (run.phase !in listOf(ExercisePhase.WORK, ExercisePhase.REST) || run.remainingSeconds <= 0 || sessionRunning) return
        sessionRunning = true
        updateSessionControls(item)
        sessionTimer = object : CountDownTimer(run.remainingSeconds * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                exerciseRuns[item.key()] = run.copy(remainingSeconds = ((ms + 999) / 1000).toInt())
                updateSessionControls(item)
            }
            override fun onFinish() {
                sessionTimer = null
                sessionRunning = false
                completeSessionPhase(item)
            }
        }.start()
    }

    private fun pauseSessionRest() {
        sessionTimer?.cancel()
        sessionTimer = null
        sessionRunning = false
        if (page == Page.SESSION) plan.forDay(sessionWeek, sessionDay).getOrNull(sessionIndex)?.let {
            updateSessionControls(it)
        }
    }
    private fun finish(items: List<RoutineExercise>) {
        saveSessionFields()
        pauseSessionRest()
        val pending = items.count { progress[it.key()]?.done != true }
        if (pending == 0 || items.isEmpty()) { showPage(Page.PROGRESS); return }
        AlertDialog.Builder(this).setTitle("Finalizar entrenamiento")
            .setMessage("Quedan $pending ejercicios sin marcar. ¿Quieres marcarlos como completados?")
            .setNegativeButton("Seguir entrenando", null)
            .setPositiveButton("Marcar y finalizar") { _, _ ->
                items.forEach { progress.getOrPut(it.key()) { ExerciseProgress() }.done = true }
                RoutineStore.saveProgress(this, progress); showPage(Page.PROGRESS)
            }.show()
    }

    private fun progressPage() {
        val body = scrollPage()
        header(body, "Progreso", "Tu evolución semana a semana")
        if (plan.exercises.isEmpty()) {
            showRoutineEmpty(body)
            return
        }
        val count = plan.exercises.count { progress[it.key()]?.done == true }
        val days = plan.weeks().flatMap { week -> plan.days(week).map { week to it } }
        val completedDays = days.count { (week, day) ->
            val items = plan.forDay(week, day)
            items.isNotEmpty() && items.all { progress[it.key()]?.done == true }
        }
        val stats = row()
        listOf(completedDays.toString() to "Sesiones", count.toString() to "Ejercicios",
            "${if (plan.exercises.isEmpty()) 0 else count * 100 / plan.exercises.size}%" to "Avance")
            .forEachIndexed { index, (number, caption) ->
                val box = col(9).apply { gravity = Gravity.CENTER }
                add(box, text(number, 24f, bold = true).apply { gravity = Gravity.CENTER })
                add(box, text(caption, 12f, muted).apply { gravity = Gravity.CENTER }, 4)
                stats.addView(card(box), LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                    if (index < 2) marginEnd = dp(7)
                })
            }
        add(body, stats, bottom = 14)
        val chart = col(15)
        add(chart, text("Ejercicios completados por semana", 17f, bold = true), bottom = 12)
        plan.weeks().forEach { week ->
            val items = plan.exercises.filter { it.week == week }
            val done = items.count { progress[it.key()]?.done == true }
            val entry = row()
            entry.addView(text("S$week", 13f, muted, true), LinearLayout.LayoutParams(dp(30), -2))
            val track = FrameLayout(this).apply { background = box(pale, 5) }
            val bar = View(this).apply { background = box(red, 5) }
            track.addView(bar, FrameLayout.LayoutParams(0, dp(13)))
            track.post { bar.layoutParams = FrameLayout.LayoutParams(track.width * done / items.size.coerceAtLeast(1), dp(13)) }
            entry.addView(track, LinearLayout.LayoutParams(0, dp(13), 1f))
            entry.addView(text("$done/${items.size}", 12f, muted).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(57), -2))
            add(chart, entry, bottom = 11)
        }
        add(body, card(chart), bottom = 14)
        val history = col(15)
        add(history, text("Historial de entrenamientos", 17f, bold = true), bottom = 11)
        val started = days.filter { (week, day) -> plan.forDay(week, day).any { progress[it.key()]?.done == true } }
        if (started.isEmpty()) add(history, text("Aún no hay entrenamientos registrados.", 13f, muted))
        started.takeLast(12).asReversed().forEach { (week, day) ->
            val items = plan.forDay(week, day)
            val done = items.count { progress[it.key()]?.done == true }
            val entry = row()
            entry.addView(text("$day · S$week", 13f), LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(text("$done/${items.size}  ${if (done == items.size) "✓" else "◌"}",
                13f, if (done == items.size) green else muted))
            add(history, entry, bottom = 11)
        }
        add(body, card(history))
    }

    private fun templatePage() {
        val body = scrollPage()
        header(body, "Plantilla", "Importa o guarda tu rutina de Excel")
        val tabs = row()
        tabs.addView(button("Importar", !templateExport).apply {
            setOnClickListener { templateExport = false; templatePage() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(7) })
        tabs.addView(button("Exportar", templateExport).apply {
            setOnClickListener { templateExport = true; templatePage() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        add(body, tabs, bottom = 17)
        if (templateExport) exportPanel(body) else importPanel(body)
    }
    private fun importPanel(body: LinearLayout) {
        val panel = col(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        add(panel, text("XLSX", 27f, muted, true).apply { gravity = Gravity.CENTER }, top = 12)
        add(panel, text("Importar plantilla", 21f, bold = true).apply { gravity = Gravity.CENTER }, top = 16)
        add(panel, text("Selecciona un archivo Excel (.xlsx) con tu rutina", 14f, muted).apply {
            gravity = Gravity.CENTER
        }, top = 8)
        add(panel, button("Seleccionar archivo").apply {
            setOnClickListener { openRoutinePicker() }
        }, top = 22, bottom = 10)
        add(body, card(panel, Color.WHITE, Color.rgb(173, 191, 207)), bottom = 14)
        val format = col(15)
        add(format, text("Formato esperado", 17f, bold = true), bottom = 10)
        listOf("✓  Archivo .xlsx", "✓  Hojas Mañana, Inicio y Semana 1–4",
            "✓  Columnas Día, Bloque, Ejercicio, Series, Reps y Descanso",
            "✓  Hojas Alternativas y Recuperación").forEach { add(format, text(it, 13f, muted), bottom = 8) }
        add(body, card(format, pale, pale), bottom = 12)
        add(body, button("Descargar plantilla de ejemplo", false).apply {
            setOnClickListener { templateExport = true; templatePage() }
        })
    }
    private fun exportPanel(body: LinearLayout) {
        val panel = col(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        add(panel, text("XLSX", 28f, muted, true).apply { gravity = Gravity.CENTER }, top = 16)
        add(panel, text("Descarga tu plantilla editable", 21f, bold = true).apply {
            gravity = Gravity.CENTER
        }, top = 18)
        add(panel, text("Personaliza tus rutinas en Excel o compártela con otros.", 14f, muted).apply {
            gravity = Gravity.CENTER
        }, top = 8)
        add(panel, button("↓  Descargar plantilla").apply {
            setOnClickListener { saveTemplate.launch("GymFit_plantilla.xlsx") }
        }, top = 22)
        add(panel, button("♧  Compartir plantilla", false).apply {
            setOnClickListener { shareTemplate() }
        }, top = 9, bottom = 10)
        add(body, card(panel), bottom = 14)
        val includes = col(15)
        add(includes, text("Incluye", 17f, bold = true), bottom = 10)
        listOf("✓  Mañana editable con duración e indicaciones", "✓  Cuatro semanas de entrenamiento",
            "✓  Ejercicios, series, repeticiones y descansos",
            "✓  Alternativas y recuperación").forEach { add(includes, text(it, 13f, muted), bottom = 8) }
        add(body, card(includes, pale, pale))
    }
    private fun shareTemplate() = try {
        val folder = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(folder, "GymFit_plantilla.xlsx")
        assets.open(TEMPLATE).use { source -> file.outputStream().use { source.copyTo(it) } }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir plantilla GymFit"))
    } catch (e: Exception) {
        Toast.makeText(this, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
    private fun settings() {
        AlertDialog.Builder(this).setTitle("GymFit")
            .setMessage("Entrena hoy. Un mejor mañana.\nTu rutina y tu progreso se guardan en este móvil.")
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Borrar progreso") { _, _ -> confirmReset() }.show()
    }
    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Borrar progreso")
            .setMessage("Se borrarán pesos, repeticiones, RIR, notas y ejercicios completados. La rutina importada se conserva.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                progress.clear(); exerciseRuns.clear(); RoutineStore.clearProgress(this); showPage(Page.PROGRESS)
            }.show()
    }
    override fun onPause() {
        pauseMorning()
        pauseSessionRest()
        saveSessionFields()
        super.onPause()
        RoutineStore.saveProgress(this, progress)
    }
    override fun onDestroy() { morningTimer?.cancel(); sessionTimer?.cancel(); super.onDestroy() }
    companion object { private const val TEMPLATE = "rutina_plantilla.xlsx" }
}
