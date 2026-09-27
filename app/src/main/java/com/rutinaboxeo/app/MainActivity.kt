package com.rutinaboxeo.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.NumberPicker
import androidx.core.widget.doAfterTextChanged
import java.time.YearMonth
import java.time.LocalDate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.rutinaboxeo.app.ui.GymFitActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Pantalla única de GymFit; coordina navegación, temporizadores y renderizado de sesiones. */
class MainActivity : GymFitActivity() {
    /** Contenedor donde se dibuja la página activa. */
    private lateinit var content: FrameLayout
    /** Barra inferior que permite cambiar de sección. */
    private lateinit var navigation: LinearLayout
    /** Plan importado actualmente visible. */
    private var plan = RoutinePlan(emptyList())
    /** Progreso local cargado bajo demanda. */
    private val progress by lazy { RoutineStore.loadProgress(this) }
    /** Repositorio de documentos mensuales en almacenamiento privado. */
    private val monthly by lazy { RoutineStore.monthly(this) }
    /** Instantánea pendiente de descarga, conservada en caché ante recreaciones. */
    private val pendingWorkbook: File get() = File(cacheDir, "pending-export.xlsx")
    /** Impide editar sesiones cerradas hasta iniciar explícitamente una repetición. */
    private var sessionReadOnly = false
    /** Agrupa pulsaciones próximas para evitar escribir un archivo por cada carácter. */
    private val autosaveHandler = Handler(Looper.getMainLooper())
    /** Guarda los campos editados tras una pausa breve de escritura. */
    private val autosave = Runnable { saveSessionFields() }
    /** Semana elegida en las vistas de rutina y hoy. */
    private var selectedWeek = 1
    /** Página que controla el contenido y el estado de la navegación. */
    private var page = Page.TODAY
    /** Indica si la pestaña Plantilla muestra exportación. */
    private var templateExport = false
    /** Posición actual dentro de la rutina matinal. */
    private var morningIndex = 0
    /** Segundos pendientes del paso matinal activo. */
    private var morningRemaining = 0
    /** Indica si la rutina matinal ya se inició. */
    private var morningStarted = false
    /** Indica si el contador matinal está avanzando. */
    private var morningRunning = false
    /** Temporizador activo de la rutina matinal. */
    private var morningTimer: CountDownTimer? = null
    /** Referencia al reloj matinal visible. */
    private var morningClock: TextView? = null
    /** Referencia al tiempo total pendiente de la rutina matinal. */
    private var morningTotal: TextView? = null
    /** Botón que inicia o pausa el contador matinal. */
    private var morningPlay: MaterialButton? = null
    /** Semana de la sesión de tarde abierta. */
    private var sessionWeek = 1
    /** Día de la sesión de tarde abierta. */
    private var sessionDay = ""
    /** Índice del ejercicio visible en la sesión. */
    private var sessionIndex = 0
    /** Estados de ejecución conservados para cada ejercicio. */
    private val exerciseRuns = mutableMapOf<String, ExerciseRun>()
    /** Temporizador activo de trabajo o descanso. */
    private var sessionTimer: CountDownTimer? = null
    /** Indica si el temporizador de sesión está avanzando. */
    private var sessionRunning = false
    /** Avisos configurables para los últimos segundos de cada descanso. */
    private val restSound by lazy { RestCountdownSound(this) }
    /** Referencia al reloj visible del ejercicio. */
    private var sessionClock: TextView? = null
    /** Referencia a la descripción de fase del ejercicio. */
    private var sessionStatus: TextView? = null
    /** Referencia al resumen de descanso entre series. */
    private var sessionRestOverview: TextView? = null
    /** Acción principal de la fase actual del ejercicio. */
    private var sessionAction: MaterialButton? = null
    /** Clave del ejercicio cuyos campos se están editando. */
    private var sessionFieldKey: String? = null
    /** Campos visibles que deben guardarse al cambiar de ejercicio. */
    private var sessionFields: List<EditText> = emptyList()

    /** Secciones navegables de la actividad. */
    private enum class Page {
        /** Resumen del día. */
        TODAY,
        /** Rutina guiada de mañana. */
        MORNING,
        /** Calendario completo de la rutina. */
        ROUTINE,
        /** Sesión guiada de tarde. */
        SESSION,
        /** Estadísticas y sesiones registradas. */
        PROGRESS,
        /** Importación y exportación de Excel. */
        TEMPLATE
    }

    /** Selector de documentos que importa y activa una rutina XLSX válida. */
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val parsed = contentResolver.openInputStream(uri)?.use { XlsxRoutineParser.parse(it) }
                ?: error("No se pudo abrir el archivo")
            chooseMonth("Mes de la nueva rutina", parsed.month ?: YearMonth.now()) { period ->
                saveSessionFields()
                monthly.create(parsed.copy(month = period), period)
                RoutineStore.setImported(this, true)
                activateImportedPlan()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo importar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Selector de destino que copia la plantilla incluida al almacenamiento elegido. */
    private val saveTemplate = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) try {
            val output = contentResolver.openOutputStream(uri) ?: error("No se pudo guardar el archivo")
            output.use { target -> pendingWorkbook.inputStream().use { it.copyTo(target) } }
            Toast.makeText(this, "Excel guardado", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Inicializa la interfaz, migra preferencias antiguas y restaura el estado visible. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkTheme()
            isAppearanceLightNavigationBars = !isDarkTheme()
        }
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
            templateExport = savedInstanceState.getBoolean("templateExport")
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
            savedInstanceState.getBundle("exerciseRuns")?.let { runs ->
                for (key in runs.keySet()) {
                    val state = runs.getBundle(key) ?: continue
                    exerciseRuns[key] = ExerciseRun(
                        phase = runCatching { ExercisePhase.valueOf(state.getString("phase").orEmpty()) }
                            .getOrDefault(ExercisePhase.READY),
                        round = state.getInt("round", 1),
                        remainingSeconds = state.getInt("remaining", 0)
                    )
                }
            }
        }
        when (savedInstanceState?.getString("page")) {
            Page.MORNING.name -> showPage(if (plan.morningSteps.isEmpty()) Page.TODAY else Page.MORNING)
            Page.SESSION.name -> if (sessionDay.isNotBlank() && plan.forDay(sessionWeek, sessionDay).isNotEmpty())
                session(sessionWeek, sessionDay) else showPage(Page.TODAY)
            Page.ROUTINE.name -> showPage(Page.ROUTINE)
            Page.PROGRESS.name -> showPage(Page.PROGRESS)
            Page.TEMPLATE.name -> showPage(Page.TEMPLATE)
            else -> showPage(Page.TODAY)
        }
        if (monthly.active() == null && imported && stored != null) {
            chooseMonth("Asigna mes y año a tu rutina anterior", YearMonth.now(), migration = true) { period ->
                monthly.create(stored.copy(month = period), period, progress)
                activateImportedPlan()
            }
        }
    }

    /** Conserva la posición de ambas sesiones antes de recrear la actividad. */
    override fun onSaveInstanceState(outState: Bundle) {
        saveSessionFields()
        outState.putString("page", page.name)
        outState.putBoolean("templateExport", templateExport)
        outState.putInt("morningIndex", morningIndex)
        outState.putInt("morningRemaining", morningRemaining)
        outState.putBoolean("morningStarted", morningStarted)
        outState.putInt("sessionWeek", sessionWeek)
        outState.putString("sessionDay", sessionDay)
        outState.putInt("sessionIndex", sessionIndex)
        outState.putBundle("exerciseRuns", Bundle().apply {
            exerciseRuns.forEach { (key, run) ->
                putBundle(key, Bundle().apply {
                    putString("phase", run.phase.name)
                    putInt("round", run.round)
                    putInt("remaining", run.remainingSeconds)
                })
            }
        })
        super.onSaveInstanceState(outState)
    }

    /** Analiza la plantilla incluida para reconocer datos heredados de versiones anteriores. */
    private fun embeddedPlan() = assets.open(TEMPLATE).use { XlsxRoutineParser.parse(it) }

    /** Sustituye el contenido por una página desplazable y devuelve su columna raíz. */
    private fun scrollPage(): LinearLayout {
        content.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = col().apply { setPadding(dp(18), dp(20), dp(18), dp(24)) }
        scroll.addView(body); content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return body
    }
    /** Dibuja la cabecera común con título, retorno opcional y acceso a ajustes. */
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
        line.addView(AppCompatImageButton(this).apply {
            val dark = isDarkTheme()
            setImageResource(if (dark) R.drawable.ic_theme_sun else R.drawable.ic_theme_moon)
            imageTintList = ColorStateList.valueOf(muted)
            contentDescription = getString(if (dark) R.string.activate_light_theme else R.string.activate_dark_theme)
            val background = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                saveSessionFields()
                pauseMorning()
                pauseSessionRest()
                ThemePreferences.setDark(this@MainActivity, !dark)
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        line.addView(text("⚙", 25f, muted).apply {
            gravity = Gravity.CENTER; contentDescription = "Ajustes"; setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        add(body, line, bottom = 19)
    }

    /** Consulta el tema efectivo, incluyendo la preferencia inicial del dispositivo. */
    private fun isDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Cambia de página, conserva datos editados y pausa temporizadores que dejan de ser visibles. */
    private fun showPage(next: Page) {
        if (page == Page.MORNING && next != Page.MORNING) pauseMorning()
        if (page == Page.SESSION && next != Page.SESSION) {
            saveSessionFields()
            pauseSessionRest()
            sessionFields = emptyList()
            sessionFieldKey = null
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
    /** Reconstruye la barra inferior y resalta la sección activa. */
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
    /** Devuelve en español el día de la semana actual. */
    private fun todayName() = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "Lunes"; Calendar.TUESDAY -> "Martes"
        Calendar.WEDNESDAY -> "Miércoles"; Calendar.THURSDAY -> "Jueves"
        Calendar.FRIDAY -> "Viernes"; Calendar.SATURDAY -> "Sábado"; else -> "Domingo"
    }

    /** Dibuja el resumen diario y los accesos a las sesiones de mañana y tarde. */
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
        add(body, card(morning), bottom = 18)
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
                setOnClickListener { downloadWorkbook() }
            }, 8)
            add(body, card(empty))
            return
        }
        val day = todayName()
        val info = plan.session(day)
        val items = plan.forDay(selectedWeek, day)
        val hero = FrameLayout(this).apply { background = box(getColor(R.color.navy), 18); clipToOutline = true }
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

    /** Indica si la rutina matinal ya se completó en la fecha local actual. */
    private fun morningDoneToday(): Boolean = monthly.active()?.let { document ->
        document.workouts.any { it.kind == "mañana" && it.finishedAt.take(10) == LocalDate.now().toString() }
    } ?: (getPreferences(MODE_PRIVATE).getString(MORNING_DONE, null) == todayKey())

    /** Reinicia por completo el estado y el contador de la rutina matinal. */
    private fun resetMorning() {
        morningTimer?.cancel()
        morningTimer = null
        morningRunning = false
        morningIndex = 0
        morningRemaining = plan.morningSteps.firstOrNull()?.seconds ?: 0
        morningStarted = false
    }

    /** Dibuja el paso activo, los controles y el listado de la rutina matinal. */
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
        addVideoButton(panel, step.videoUrl, step.title)
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
            add(body, card(entry, if (index == morningIndex) getColor(R.color.selected_surface) else cardSurface,
                if (index == morningIndex) red else line), bottom = 7)
        }
        add(body, text("Muévete sin dolor y a tu propio ritmo. Si notas mareo o malestar, detén la rutina.",
            12f, muted), top = 7)
    }

    /** Suma el tiempo del paso actual y de todos los pasos posteriores. */
    private fun morningSecondsLeft(): Int = morningRemaining +
        plan.morningSteps.drop(morningIndex + 1).sumOf { it.seconds }

    /** Formatea una duración en el formato fijo minutos:segundos. */
    private fun formatTime(seconds: Int) = "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)

    /** Sincroniza los textos y la acción principal del temporizador matinal. */
    private fun updateMorningClock() {
        morningClock?.text = formatTime(morningRemaining)
        morningTotal?.text = "Quedan ${formatTime(morningSecondsLeft())} de ${formatTime(plan.morningSteps.sumOf { it.seconds })}"
        morningPlay?.text = if (morningRunning) "Ⅱ  Pausar" else
            "▶  ${if (morningRemaining == plan.morningSteps[morningIndex].seconds) "Empezar" else "Continuar"}"
    }

    /** Inicia o reanuda el contador del paso matinal actual. */
    private fun startMorning() {
        if (morningRunning) return
        monthly.beginMorning()
        morningStarted = true
        morningRunning = true
        updateMorningClock()
        morningTimer = object : CountDownTimer(morningRemaining * 1000L, 1000L) {
            /** Actualiza el tiempo pendiente una vez por segundo. */
            override fun onTick(millisUntilFinished: Long) {
                morningRemaining = ((millisUntilFinished + 999) / 1000).toInt()
                updateMorningClock()
            }
            /** Avanza automáticamente cuando finaliza el paso activo. */
            override fun onFinish() {
                morningRemaining = 0
                morningRunning = false
                morningTimer = null
                advanceMorning()
            }
        }.start()
    }

    /** Detiene el contador matinal conservando los segundos restantes. */
    private fun pauseMorning() {
        morningTimer?.cancel()
        morningTimer = null
        morningRunning = false
        if (page == Page.MORNING && morningIndex in plan.morningSteps.indices) updateMorningClock()
    }

    /** Avanza al siguiente paso o registra la rutina como completada hoy. */
    private fun advanceMorning() {
        pauseMorning()
        if (morningIndex == plan.morningSteps.lastIndex) {
            monthly.finishMorning()
            getPreferences(MODE_PRIVATE).edit().putString(MORNING_DONE, todayKey()).apply()
            resetMorning()
            Toast.makeText(this, "¡Rutina de mañana completada!", Toast.LENGTH_LONG).show()
            showPage(Page.TODAY)
        } else {
            morningIndex++
            morningRemaining = plan.morningSteps[morningIndex].seconds
            morningPage()
        }
    }

    /** Dibuja las semanas y sesiones disponibles con su progreso. */
    private fun routine() {
        val body = scrollPage()
        header(body, "Rutina", "Selecciona una semana y un día")
        monthly.active()?.let { add(body, text(it.label(), 14f, muted), bottom = 12) }
        add(body, button("Mis rutinas", false).apply { setOnClickListener { showMonthlyRoutines() } }, bottom = 14)
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
                gravity = Gravity.CENTER; background = box(getColor(R.color.badge_surface), 25)
            }, LinearLayout.LayoutParams(dp(43), dp(43)))
            val details = col().apply { setPadding(dp(11), 0, 0, 0) }
            add(details, text(day, 16f, bold = true))
            add(details, text(info.title, 14f), 3)
            add(details, text("${items.size} ejercicios · $done completados", 12f, muted), 3)
            panel.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val complete = items.isNotEmpty() && done == items.size
            panel.addView(text(if (complete) "✓" else "›", 22f, if (complete) green else red, true))
            add(body, card(panel, if (day == todayName()) getColor(R.color.selected_surface) else cardSurface,
                if (day == todayName()) red else line).apply {
                setOnClickListener { session(selectedWeek, day) }
            }, bottom = 9)
        }
    }

    /** Muestra el estado vacío común cuando todavía no existe una rutina. */
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

    /** Abre el selector del sistema limitado a libros XLSX. */
    private fun openRoutinePicker() {
        importPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    /** Añade acceso al vídeo válido y conserva el entrenamiento antes de abrirlo. */
    private fun addVideoButton(panel: LinearLayout, url: String, title: String) {
        if (VideoLinks.youtubeId(url) == null) return
        add(panel, button("Ver vídeo", false).apply {
            setOnClickListener {
                saveSessionFields()
                pauseMorning()
                pauseSessionRest()
                startActivity(Intent(this@MainActivity, ExerciseVideoActivity::class.java)
                    .putExtra("video_url", url).putExtra("exercise_title", title))
            }
        }, 12)
    }

    /** Abre una sesión y selecciona el primer ejercicio pendiente. */
    private fun session(week: Int, day: String) {
        saveSessionFields()
        val previous = monthly.active()?.workouts?.lastOrNull { it.week == week && it.day == day && it.kind == "tarde" }
        sessionReadOnly = previous != null && previous.finishedAt.isNotBlank()
        if (!sessionReadOnly) monthly.begin(week, day)
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

    /** Dibuja el ejercicio actual, su registro y el estado del resto de la sesión. */
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
        if (sessionReadOnly) {
            add(body, text("Entrenamiento finalizado. Sus resultados se conservan en el historial.", 14f, green), bottom = 10)
            add(body, button("Repetir entrenamiento").apply { setOnClickListener {
                sessionFields = emptyList()
                sessionFieldKey = null
                monthly.begin(week, day, repeat = true)
                progress.clear()
                progress.putAll(RoutineStore.loadProgress(this@MainActivity))
                exerciseRuns.clear()
                sessionReadOnly = false
                renderSession()
            } }, bottom = 14)
        }

        val panel = col(18)
        add(panel, text("EJERCICIO ${sessionIndex + 1} DE ${items.size} · ${item.block.uppercase()}", 13f, red, true))
        add(panel, text(item.exercise, 24f, bold = true), 10)
        add(panel, text(ExerciseGuide.forExercise(item), 15f, muted), 11)
        addVideoButton(panel, item.videoUrl, item.exercise)
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
        if (sessionReadOnly) sessionAction?.isEnabled = false

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

        val trackNumbers = item.block.lowercase(Locale.ROOT) in TRACKABLE_BLOCKS
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
        editors.forEach { editor ->
            editor.isEnabled = !sessionReadOnly
            editor.doAfterTextChanged {
                if (!sessionReadOnly) {
                    autosaveHandler.removeCallbacks(autosave)
                    autosaveHandler.postDelayed(autosave, 400L)
                }
            }
        }
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
                isEnabled = !sessionReadOnly
                setOnCheckedChangeListener { _, checked ->
                    pauseSessionRest()
                    progress.getOrPut(listed.key()) { ExerciseProgress() }.done = checked
                    exerciseRuns[listed.key()] = ExerciseRun()
                    RoutineStore.saveProgress(this@MainActivity, progress)
                    renderSession()
                }
            })
            add(body, card(entry, if (done) getColor(R.color.completed_surface) else if (index == sessionIndex)
                getColor(R.color.selected_surface) else cardSurface,
                if (done) green else if (index == sessionIndex) red else line).apply {
                setOnClickListener { selectSessionExercise(index) }
            }, bottom = 7)
        }
        add(body, button("Finalizar entrenamiento", false).apply { setOnClickListener { finish(items) } }, top = 12)
    }

    /** Crea un campo compacto para registrar métricas o notas de una serie. */
    private fun sessionEdit(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 13f
        setSingleLine(true)
        setText(value)
        setPadding(dp(10), 0, dp(10), 0)
        background = box(cardSurface, 9, line)
        setTextColor(ink)
        setHintTextColor(muted)
    }

    /** Copia los campos visibles al progreso y los persiste antes de abandonar el ejercicio. */
    private fun saveSessionFields() {
        autosaveHandler.removeCallbacks(autosave)
        if (sessionReadOnly) return
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

    /** Guarda el ejercicio actual y abre otro índice de la misma sesión. */
    private fun selectSessionExercise(index: Int) {
        saveSessionFields()
        pauseSessionRest()
        sessionIndex = index
        renderSession()
    }

    /** Sincroniza reloj, estado, descanso y botón con la máquina de estados del ejercicio. */
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
            isEnabled = !done && !sessionReadOnly
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

    /** Ejecuta la transición solicitada por el botón principal de la sesión. */
    private fun handleSessionAction(item: RoutineExercise) {
        if (sessionReadOnly) return
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

    /** Completa trabajo o descanso, guarda el resultado y arranca la fase siguiente. */
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

    /** Inicia el contador de la fase activa cuando dispone de una duración válida. */
    private fun startSessionTimer(item: RoutineExercise) {
        val run = exerciseRuns[item.key()] ?: return
        if (run.phase !in listOf(ExercisePhase.WORK, ExercisePhase.REST) || run.remainingSeconds <= 0 || sessionRunning) return
        sessionRunning = true
        updateSessionControls(item)
        if (run.phase == ExercisePhase.REST) restSound.start(run.remainingSeconds)
        sessionTimer = object : CountDownTimer(run.remainingSeconds * 1000L, 1000L) {
            /** Actualiza el estado inmutable y los controles una vez por segundo. */
            override fun onTick(ms: Long) {
                exerciseRuns[item.key()] = run.copy(remainingSeconds = ((ms + 999) / 1000).toInt())
                if (run.phase == ExercisePhase.REST) restSound.tick(((ms + 999) / 1000).toInt())
                updateSessionControls(item)
            }
            /** Completa la fase automáticamente cuando el contador llega a cero. */
            override fun onFinish() {
                sessionTimer = null
                sessionRunning = false
                completeSessionPhase(item)
                if (run.phase == ExercisePhase.REST) restSound.finish()
            }
        }.start()
    }

    /** Detiene el contador de trabajo o descanso conservando su tiempo pendiente. */
    private fun pauseSessionRest() {
        restSound.stop()
        sessionTimer?.cancel()
        sessionTimer = null
        sessionRunning = false
        if (page == Page.SESSION) plan.forDay(sessionWeek, sessionDay).getOrNull(sessionIndex)?.let {
            updateSessionControls(it)
        }
    }
    /** Finaliza una sesión y solicita confirmación si quedan ejercicios pendientes. */
    private fun finish(items: List<RoutineExercise>) {
        saveSessionFields()
        pauseSessionRest()
        val pending = items.count { progress[it.key()]?.done != true }
        if (pending == 0 || items.isEmpty()) {
            monthly.finish(sessionWeek, sessionDay)
            showPage(Page.PROGRESS)
            return
        }
        AlertDialog.Builder(this).setTitle("Finalizar entrenamiento")
            .setMessage("Quedan $pending ejercicios sin marcar. ¿Quieres marcarlos como completados?")
            .setNegativeButton("Seguir entrenando", null)
            .setNeutralButton("Finalizar sin marcarlos") { _, _ ->
                monthly.finish(sessionWeek, sessionDay)
                showPage(Page.PROGRESS)
            }
            .setPositiveButton("Marcar y finalizar") { _, _ ->
                items.forEach { progress.getOrPut(it.key()) { ExerciseProgress() }.done = true }
                RoutineStore.saveProgress(this, progress)
                monthly.finish(sessionWeek, sessionDay)
                showPage(Page.PROGRESS)
            }.show()
    }

    /** Dibuja estadísticas agregadas, avance semanal e historial de sesiones. */
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
            val items = plan.forWeek(week)
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

    /** Dibuja el centro de importación o exportación de la plantilla. */
    private fun templatePage() {
        val body = scrollPage()
        header(body, "Plantilla", "Importa o guarda tu rutina de Excel")
        add(body, button("Mis rutinas · consultar o exportar", false).apply {
            setOnClickListener { showMonthlyRoutines() }
        }, bottom = 12)
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
    /** Dibuja instrucciones y acciones para importar una rutina XLSX. */
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
        add(body, card(panel, cardSurface, getColor(R.color.strong_border)), bottom = 14)
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
    /** Dibuja las acciones para guardar o compartir la plantilla incluida. */
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
            setOnClickListener { downloadWorkbook() }
        }, top = 22)
        add(panel, button("♧  Compartir plantilla", false).apply {
            setOnClickListener { shareTemplate() }
        }, top = 9, bottom = 10)
        add(body, card(panel), bottom = 14)
        add(body, button("Exportar rutina y resultados").apply {
            setOnClickListener { showMonthlyRoutines(exportOnly = true) }
        }, bottom = 14)
        val includes = col(15)
        add(includes, text("Incluye", 17f, bold = true), bottom = 10)
        listOf("✓  Mañana editable con duración e indicaciones", "✓  Cuatro semanas de entrenamiento",
            "✓  Ejercicios, series, repeticiones y descansos",
            "✓  Alternativas y recuperación").forEach { add(includes, text(it, 13f, muted), bottom = 8) }
        add(body, card(includes, pale, pale))
    }
    /** Copia la plantilla a caché y abre el panel nativo para compartirla. */
    private fun shareTemplate() = try {
        val folder = File(cacheDir, "shared").apply { mkdirs() }
        val period = YearMonth.now()
        val file = File(folder, "%02d_%04d.xlsx".format(Locale.ROOT, period.monthValue, period.year))
        file.outputStream().use { RoutineWorkbook.write(it, embeddedPlan(), period) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir plantilla GymFit"))
    } catch (e: Exception) {
        Toast.makeText(this, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
    /** Solicita el período del documento sin deducir fechas de entrenamientos antiguos. */
    private fun chooseMonth(title: String, initial: YearMonth, migration: Boolean = false, action: (YearMonth) -> Unit) {
        val panel = col(16)
        add(panel, text(if (migration) "Conservaremos tus resultados; las fechas antiguas seguirán como desconocidas."
            else "La rutina actual pasará al histórico. La nueva empezará con progreso a cero.", 14f), bottom = 12)
        val selectors = row()
        val month = NumberPicker(this).apply { minValue = 1; maxValue = 12; value = initial.monthValue }
        val year = NumberPicker(this).apply { minValue = 1900; maxValue = 9999; value = initial.year }
        selectors.addView(month, LinearLayout.LayoutParams(0, -2, 1f))
        selectors.addView(year, LinearLayout.LayoutParams(0, -2, 1f))
        add(panel, text("Mes                         Año", 14f))
        add(panel, selectors)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(panel)
            .setCancelable(!migration).setPositiveButton("Guardar rutina", null)
        if (!migration) dialog.setNegativeButton("Cancelar", null)
        val shown = dialog.create()
        shown.setOnShowListener { shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                month.clearFocus()
                year.clearFocus()
                action(YearMonth.of(year.value, month.value))
                shown.dismiss()
            } catch (error: Exception) { Toast.makeText(this, "No se pudo guardar: ${error.message}", Toast.LENGTH_LONG).show() }
        } }
        shown.show()
    }

    /** Sustituye el estado visible tras importar o migrar sin escribir el progreso anterior en el nuevo mes. */
    private fun activateImportedPlan() {
        morningTimer?.cancel()
        sessionTimer?.cancel()
        sessionTimer = null
        sessionRunning = false
        sessionFields = emptyList()
        sessionFieldKey = null
        sessionReadOnly = false
        exerciseRuns.clear()
        sessionDay = ""
        sessionIndex = 0
        plan = monthly.active()?.plan ?: RoutinePlan(emptyList())
        progress.clear()
        progress.putAll(monthly.active()?.progress.orEmpty())
        resetMorning()
        selectedWeek = plan.weeks().firstOrNull() ?: 1
        RoutineStore.saveActiveWeek(this, selectedWeek)
        page = Page.TODAY
        showPage(Page.TODAY)
    }

    /** Genera una instantánea de un único documento o la plantilla del mes actual. */
    private fun downloadWorkbook(document: MonthlyRoutine? = null) {
        try {
            saveSessionFields()
            val selected = document?.let { monthly.read(it.id) }
            val period = selected?.month ?: YearMonth.now()
            pendingWorkbook.outputStream().use { RoutineWorkbook.write(it, selected?.plan ?: embeddedPlan(), period, selected) }
            val filename = selected?.id ?: "%02d_%04d".format(Locale.ROOT, period.monthValue, period.year)
            saveTemplate.launch("$filename.xlsx")
        } catch (error: Exception) { Toast.makeText(this, "No se pudo preparar el Excel: ${error.message}", Toast.LENGTH_LONG).show() }
    }

    /** Ofrece el documento activo y los históricos sin combinar resultados de distintas importaciones. */
    private fun showMonthlyRoutines(exportOnly: Boolean = false) {
        saveSessionFields()
        pauseMorning()
        pauseSessionRest()
        try {
            val documents = monthly.list()
            if (documents.isEmpty()) {
                Toast.makeText(this, "Todavía no hay rutinas mensuales", Toast.LENGTH_LONG).show()
                return
            }
            val activeId = monthly.active()?.id
            val labels = documents.map { "${it.label()} · ${if (it.id == activeId) "Activa" else "Histórica"}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle(if (exportOnly) "Elige una rutina para exportar" else "Mis rutinas")
                .setItems(labels) { _, index ->
                    if (exportOnly) downloadWorkbook(documents[index]) else showMonthlyDetail(documents[index])
                }.setNegativeButton("Cerrar", null).show()
        } catch (error: Exception) { Toast.makeText(this, "No se pudo leer el histórico: ${error.message}", Toast.LENGTH_LONG).show() }
    }

    /** Muestra planificación y realizaciones de un mes en modo consulta y permite exportarlo. */
    private fun showMonthlyDetail(document: MonthlyRoutine) {
        val body = col(16)
        add(body, text("Importada: ${document.importedAt}\n${document.workouts.size} registros de entrenamiento", 14f), bottom = 12)
        add(body, text("Planificación", 19f, bold = true), bottom = 10)
        document.plan.morningSteps.forEach { step -> add(body, text("Mañana · ${step.title} · ${formatTime(step.seconds)}\n${step.instruction}", 14f), bottom = 8) }
        document.plan.exercises.forEach { item ->
            add(body, text("Semana ${item.week} · ${item.day}\n${item.exercise}: ${item.series} series · ${item.reps} · descanso ${item.rest}\n${item.instruction}", 14f), bottom = 10)
        }
        add(body, text("Entrenamientos realizados", 19f, bold = true), top = 12, bottom = 10)
        if (document.workouts.isEmpty()) add(body, text("Sin entrenamientos registrados.", 14f))
        document.workouts.forEach { record ->
            add(body, text("${record.day} · Semana ${record.week}\n${record.startedAt.ifBlank { "Fecha desconocida" }} · ${if (record.finishedAt.isBlank() && record.kind != "anterior") "En curso" else "Guardado"}", 15f, bold = true), top = 12)
            record.results.forEach { (key, value) ->
                val name = document.plan.exercises.firstOrNull { it.key() == key }?.exercise ?: key
                add(body, text("$name · ${if (value.done) "Hecho" else "Pendiente"}\nPeso: ${value.weight} · Reps: ${value.actualReps} · RIR: ${value.rir}\n${value.userNote}", 14f), top = 6)
            }
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this).setTitle(document.label()).setView(scroll)
            .setPositiveButton("Exportar este mes") { _, _ -> downloadWorkbook(document) }
            .setNeutralButton("Eliminar rutina") { _, _ -> confirmDeleteRoutine(document.id) }
            .setNegativeButton("Cerrar", null).show()
    }

    /** Confirma el alcance del borrado y ofrece exportar sin eliminar automáticamente después. */
    private fun confirmDeleteRoutine(id: String) {
        try {
            val document = monthly.read(id)
            val active = monthly.active()?.id == id
            if (active && (morningRunning || sessionRunning || document.workouts.any {
                    it.kind != "anterior" && it.finishedAt.isBlank()
                })) {
                AlertDialog.Builder(this).setTitle("Entrenamiento pendiente")
                    .setMessage("Finaliza los entrenamientos pendientes de esta rutina antes de eliminarla. Pausar el temporizador no finaliza el entrenamiento.")
                    .setPositiveButton("Entendido", null).show()
                return
            }
            AlertDialog.Builder(this).setTitle("Eliminar ${document.label()}")
                .setMessage("Importación: ${document.id}\n\nSe eliminarán esta rutina y todos sus entrenamientos y resultados. No se puede deshacer. Las demás rutinas no se modificarán.\n\n" +
                    (if (active) "La aplicación quedará sin rutina activa hasta que importes otra.\n\n" else "") +
                    "Puedes exportar antes. La Excel permite consultar los resultados, pero al reimportarla no recupera el historial.")
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Exportar antes") { _, _ -> downloadWorkbook(document) }
                .setPositiveButton("Eliminar definitivamente") { _, _ ->
                    try {
                        saveSessionFields()
                        val deletedActive = monthly.delete(id)
                        if (deletedActive) {
                            autosaveHandler.removeCallbacks(autosave)
                            restSound.stop()
                            activateImportedPlan()
                        }
                        Toast.makeText(this, "Rutina eliminada. No se puede deshacer.", Toast.LENGTH_LONG).show()
                        showMonthlyRoutines()
                    } catch (error: Exception) {
                        Toast.makeText(this, "No se pudo eliminar: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }.show()
        } catch (error: Exception) {
            Toast.makeText(this, "No se pudo leer la rutina: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Muestra información de la aplicación y el acceso al borrado de progreso. */
    private fun settings() {
        val options = col(20)
        add(options, text("Tu rutina y tu progreso se guardan en este móvil.", 14f, muted))
        add(options, com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "Sonido de descanso"
            setTextColor(ink)
            isChecked = restSound.enabled
            setOnCheckedChangeListener { _, enabled -> restSound.enabled = enabled }
        }, 12)
        add(options, text("Pitidos a los 5, 4, 3, 2 y 1 segundos; un tono distinto al terminar. Usa el volumen multimedia.", 13f, muted), 8)
        AlertDialog.Builder(this).setTitle("GymFit")
            .setView(options)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Borrar progreso") { _, _ -> confirmReset() }.show()
    }
    /** Solicita confirmación antes de eliminar todos los registros de entrenamiento. */
    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Borrar progreso")
            .setMessage("Se borrarán pesos, repeticiones, RIR, notas y ejercicios completados. La rutina importada se conserva.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                pauseSessionRest()
                sessionFields = emptyList()
                sessionFieldKey = null
                progress.clear()
                exerciseRuns.clear()
                RoutineStore.clearProgress(this)
                showPage(Page.PROGRESS)
            }.show()
    }
    /** Pausa contadores y persiste ediciones cuando la actividad deja de estar visible. */
    override fun onPause() {
        pauseMorning()
        pauseSessionRest()
        saveSessionFields()
        super.onPause()
        RoutineStore.saveProgress(this, progress)
    }
    /** Libera ambos temporizadores antes de destruir la actividad. */
    override fun onDestroy() {
        autosaveHandler.removeCallbacks(autosave)
        morningTimer?.cancel()
        sessionTimer?.cancel()
        restSound.release()
        super.onDestroy()
    }

    /** Produce la clave ISO de la fecha local para el registro matinal. */
    private fun todayKey(): String = SimpleDateFormat(DATE_KEY_PATTERN, Locale.ROOT)
        .format(Calendar.getInstance().time)

    private companion object {
        /** Nombre del libro de ejemplo incluido en los recursos. */
        const val TEMPLATE = "rutina_plantilla.xlsx"

        /** Clave privada que guarda la última rutina matinal completada. */
        const val MORNING_DONE = "morningDone"

        /** Patrón estable usado para comparar fechas sin depender del idioma. */
        const val DATE_KEY_PATTERN = "yyyy-MM-dd"

        /** Bloques cuyos ejercicios admiten peso, repeticiones reales y RIR. */
        val TRACKABLE_BLOCKS = setOf("fuerza", "potencia", "accesorio", "core", "estabilidad", "agarre")
    }
}
