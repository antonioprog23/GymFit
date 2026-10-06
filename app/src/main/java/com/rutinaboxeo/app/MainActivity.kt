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
    /** Identificador principal cargado para detectar el cambio natural de mes. */
    private var activeDocumentId: String? = null
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
    /** Día seleccionado en los planes organizados por fechas mensuales. */
    private var selectedDateDay = 1
    /** Documento consultado en Otras rutinas. */
    private var selectedRoutineId: String? = null
    /** Documento dueño de la sesión visible, incluso cuando no es el principal. */
    private var sessionDocumentId: String? = null
    /** Distingue una realización alternativa de la sesión principal mensual. */
    private var sessionExtra = false
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
    /** Fecha mensual de la sesión abierta; vacía para planes antiguos por semanas. */
    private var sessionDateDay: Int? = null
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

    private enum class DetailTab { PLAN, PROGRESS, HISTORY }
    private var detailTab = DetailTab.PLAN

    /** Secciones navegables de la actividad. */
    private enum class Page {
        /** Resumen del día. */
        TODAY,
        /** Rutina guiada de mañana. */
        MORNING,
        /** Calendario completo de la rutina. */
        ROUTINE,
        /** Biblioteca de documentos programados, históricos y alternativos. */
        OTHER_ROUTINES,
        /** Consulta completa de un documento de la biblioteca. */
        ROUTINE_DETAIL,
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
            val period = requireNotNull(parsed.month) { "La plantilla no contiene un período mensual" }
            saveSessionFields()
            importMonthlyPlan(parsed, period)
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
        activeDocumentId = monthly.active()?.id
        morningRemaining = plan.morningSteps.firstOrNull()?.seconds ?: 0
        selectedDateDay = RoutinePresentation.initialDay(plan)
        if (savedInstanceState != null) {
            templateExport = savedInstanceState.getBoolean("templateExport")
            selectedDateDay = savedInstanceState.getInt("selectedDateDay", selectedDateDay)
                .coerceIn(1, plan.month?.lengthOfMonth() ?: 1)
            if (plan.morningSteps.isNotEmpty()) {
                morningIndex = savedInstanceState.getInt("morningIndex", 0)
                    .coerceIn(plan.morningSteps.indices)
                morningRemaining = savedInstanceState.getInt("morningRemaining", plan.morningSteps[morningIndex].seconds)
                    .coerceIn(1, plan.morningSteps[morningIndex].seconds)
                morningStarted = savedInstanceState.getBoolean("morningStarted", false)
            }
            sessionDateDay = savedInstanceState.getInt("sessionDateDay", 0).takeIf { it > 0 }
            sessionIndex = savedInstanceState.getInt("sessionIndex", 0)
            selectedRoutineId = savedInstanceState.getString("selectedRoutineId")
            sessionDocumentId = savedInstanceState.getString("sessionDocumentId")
            sessionExtra = savedInstanceState.getBoolean("sessionExtra")
            detailTab = runCatching { DetailTab.valueOf(savedInstanceState.getString("detailTab").orEmpty()) }
                .getOrDefault(DetailTab.PLAN)
            sessionDocumentId?.let { id -> runCatching { monthly.read(id) }.getOrNull()?.let { document ->
                plan = document.plan
                progress.clear()
                progress.putAll(document.progress)
            } }
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
            Page.SESSION.name -> when {
                sessionDateDay != null && plan.forDate(sessionDateDay!!).isNotEmpty() ->
                    session(sessionDateDay!!, sessionDocumentId ?: monthly.active()?.id, sessionExtra)
                else -> showPage(Page.TODAY)
            }
            Page.ROUTINE.name -> showPage(Page.ROUTINE)
            Page.OTHER_ROUTINES.name -> showPage(Page.OTHER_ROUTINES)
            Page.ROUTINE_DETAIL.name -> showPage(Page.ROUTINE_DETAIL)
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
        outState.putInt("selectedDateDay", selectedDateDay)
        outState.putInt("morningIndex", morningIndex)
        outState.putInt("morningRemaining", morningRemaining)
        outState.putBoolean("morningStarted", morningStarted)
        outState.putInt("sessionDateDay", sessionDateDay ?: 0)
        outState.putInt("sessionIndex", sessionIndex)
        outState.putString("selectedRoutineId", selectedRoutineId)
        outState.putString("sessionDocumentId", sessionDocumentId)
        outState.putBoolean("sessionExtra", sessionExtra)
        outState.putString("detailTab", detailTab.name)
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
            restoreActiveDocument()
        }
        page = next
        when (next) {
            Page.TODAY -> today()
            Page.MORNING -> if (plan.morningSteps.isEmpty()) today() else morningPage()
            Page.ROUTINE -> routine()
            Page.OTHER_ROUTINES -> otherRoutinesPage()
            Page.ROUTINE_DETAIL -> routineDetailPage()
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
            val active = page == target || (page in setOf(Page.SESSION, Page.OTHER_ROUTINES, Page.ROUTINE_DETAIL) &&
                target == Page.ROUTINE) ||
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
        if (!plan.isMonthlyCalendar()) {
            showLegacyArchive(body)
            return
        }
        val monthlyDay = plan.month?.takeIf { it == YearMonth.now() }
            ?.let { LocalDate.now().dayOfMonth }
        if (monthlyDay == null) {
            val period = plan.month
            val panel = col(18)
            add(panel, text("Esta rutina no corresponde al mes actual", 20f, bold = true))
            add(panel, text("Abre Rutina para consultar ${period?.month?.getDisplayName(java.time.format.TextStyle.FULL, locale)} ${period?.year} o importa el mes actual.", 14f, muted), 9)
            add(panel, button("Abrir calendario").apply { setOnClickListener { showPage(Page.ROUTINE) } }, 15)
            add(body, card(panel))
            return
        }
        val day = todayName()
        val info = plan.calendarDay(monthlyDay).let { SessionInfo(it.title, it.focus, it.note) }
        val items = plan.forDate(monthlyDay)
        val isRestDay = plan.calendarDay(monthlyDay).type.equals("Descanso", true)
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
        add(overlay, text("$day · $monthlyDay de ${plan.month?.month?.getDisplayName(java.time.format.TextStyle.FULL, locale)}", 15f, Color.WHITE))
        overlay.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        add(overlay, text("✦  ${items.size} ejercicios", 14f, Color.WHITE), bottom = 6)
        if (info.focus.isNotBlank()) add(overlay, text("◎  ${info.focus}", 13f, Color.WHITE), bottom = 14)
        if (isRestDay) add(overlay, text("Día de descanso programado", 15f, Color.WHITE, true))
        else add(overlay, button("▶  Empezar entrenamiento").apply {
            setOnClickListener { session(monthlyDay) }
        })
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
        header(body, "Rutina", "Selecciona un día del mes")
        monthly.active()?.let { add(body, text("${it.label()} · ACTIVA", 14f, muted), bottom = 12) }
        add(body, button("Otras rutinas · programadas, históricas y alternativas", false).apply {
            setOnClickListener { showPage(Page.OTHER_ROUTINES) }
        }, bottom = 14)
        if (plan.exercises.isEmpty()) {
            showRoutineEmpty(body)
            return
        }
        if (!plan.isMonthlyCalendar()) {
            showLegacyArchive(body)
            return
        }
        routineCalendar(body)
    }

    /** Explica que un plan semanal heredado solo permanece disponible como histórico exportable. */
    private fun showLegacyArchive(body: LinearLayout) {
        val panel = col(18)
        add(panel, text("Rutina de una versión anterior", 20f, bold = true))
        add(panel, text("Sus resultados se conservan para consulta y exportación, pero el formato semanal ya no inicia entrenamientos. Importa la nueva plantilla mensual para continuar.", 14f, muted), 9)
        add(panel, button("Exportar histórico").apply { setOnClickListener { showMonthlyRoutines(exportOnly = true) } }, 15)
        add(panel, button("Ir a Plantilla", false).apply { setOnClickListener { showPage(Page.TEMPLATE) } }, 9)
        add(body, card(panel, pale, pale))
    }

    /** Dibuja el mes real y el resumen de la fecha seleccionada. */
    private fun routineCalendar(body: LinearLayout) {
        val period = plan.month ?: return
        val locale = Locale("es", "ES")
        selectedDateDay = selectedDateDay.coerceIn(1, period.lengthOfMonth())
        add(body, text(RoutinePresentation.monthTitle(period), 21f, bold = true), bottom = 12)

        val weekHeader = row()
        listOf("L", "M", "X", "J", "V", "S", "D").forEach { label ->
            weekHeader.addView(text(label, 12f, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, dp(28), 1f))
        }
        add(body, weekHeader, bottom = 4)

        RoutinePresentation.calendarRows(period).forEach { week ->
            val calendarRow = row()
            week.forEach { day ->
                if (day == null) {
                    calendarRow.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f))
                } else {
                    val items = plan.forDate(day)
                    val completed = items.isNotEmpty() && items.all { progress[it.key()]?.done == true }
                    val selected = day == selectedDateDay
                    val dayButton = button(day.toString(), selected).apply {
                        textSize = 13f
                        minHeight = dp(44)
                        if (completed && !selected) {
                            backgroundTintList = ColorStateList.valueOf(getColor(R.color.completed_surface))
                            setTextColor(green)
                        }
                        setOnClickListener { selectedDateDay = day; routine() }
                    }
                    calendarRow.addView(dayButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginEnd = dp(3); bottomMargin = dp(3)
                    })
                }
            }
            add(body, calendarRow)
        }

        val day = selectedDateDay
        val date = period.atDay(day)
        val info = plan.calendarDay(day)
        val items = plan.forDate(day)
        val done = items.count { progress[it.key()]?.done == true }
        val details = col(18)
        val dateLabel = RoutinePresentation.fullDate(date)
        add(details, text(dateLabel, 20f, bold = true))
        add(details, text(info.type.uppercase(locale), 12f, if (info.type.equals("Descanso", true)) muted else red, true), 7)
        add(details, text(info.title, 18f, bold = true), 8)
        if (info.focus.isNotBlank()) add(details, text(info.focus, 14f, muted), 5)
        if (info.phase.isNotBlank()) add(details, text("${info.phase}${info.intensity.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", 13f, muted), 5)
        add(details, text("${items.size} ejercicios · $done completados", 13f, muted), 8)
        if (info.note.isNotBlank()) add(details, text(info.note, 13f, muted), 5)
        if (items.isNotEmpty() && !info.type.equals("Descanso", true)) {
            add(details, button("▶  Empezar entrenamiento").apply { setOnClickListener { session(day) } }, 15)
        } else {
            add(details, text("No hay entrenamiento que iniciar en esta fecha.", 13f, muted), 12)
        }
        add(body, card(details, if (date == LocalDate.now()) getColor(R.color.selected_surface) else cardSurface,
            if (date == LocalDate.now()) red else line), top = 13)
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

    /** Abre una sesión ligada a un día real del mes. */
    private fun session(dayOfMonth: Int, documentId: String? = monthly.active()?.id, extra: Boolean = false) {
        saveSessionFields()
        val target = documentId?.let(monthly::read) ?: return
        if (sessionDocumentId != target.id) {
            plan = target.plan
            progress.clear()
            progress.putAll(target.progress)
            sessionDocumentId = target.id
            sessionExtra = extra
            sessionDateDay = null
            exerciseRuns.clear()
        }
        val kind = if (extra) "extra" else "tarde"
        val previous = target.workouts.lastOrNull {
            it.dayOfMonth == dayOfMonth && it.kind == kind
        }
        sessionReadOnly = previous != null && previous.finishedAt.isNotBlank()
        if (!sessionReadOnly) monthly.beginDate(target.id, dayOfMonth, extra = extra)
        if (sessionDateDay != dayOfMonth) {
            saveSessionFields()
            pauseSessionRest()
            sessionDateDay = dayOfMonth
            val items = plan.forDate(dayOfMonth)
            sessionIndex = items.indexOfFirst { progress[it.key()]?.done != true }.takeIf { it >= 0 } ?: 0
        }
        page = Page.SESSION
        drawNavigation()
        renderSession()
    }

    /** Devuelve los ejercicios de la fecha mensual abierta. */
    private fun currentSessionItems(): List<RoutineExercise> = sessionDateDay?.let(plan::forDate).orEmpty()

    /** Dibuja el ejercicio actual, su registro y el estado del resto de la sesión. */
    private fun renderSession() {
        saveSessionFields()
        val items = currentSessionItems()
        sessionIndex = sessionIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val body = scrollPage()
        sessionFields = emptyList()
        sessionFieldKey = null
        val monthlyInfo = sessionDateDay?.let(plan::calendarDay)
        val title = monthlyInfo?.title ?: "Entrenamiento"
        val subtitle = sessionDateDay?.let { selected -> plan.month?.atDay(selected)?.let(RoutinePresentation::fullDate) }.orEmpty()
        header(body, title, subtitle, backTo = Page.ROUTINE)
        if (items.isEmpty()) {
            add(body, text("No hay ejercicios para esta sesión.", 15f, muted))
            return
        }
        val item = items[sessionIndex]
        exerciseRuns.getOrPut(item.key()) { ExerciseRun() }
        val completed = items.count { progress[it.key()]?.done == true }
        val summary = col(14)
        add(summary, text("$completed de ${items.size} ejercicios hechos", 15f, bold = true))
        val focus = monthlyInfo?.focus.orEmpty()
        if (focus.isNotBlank()) add(summary, text(focus, 13f, muted), 5)
        add(body, card(summary, pale, pale), bottom = 13)
        if (sessionReadOnly) {
            add(body, text("Entrenamiento finalizado. Sus resultados se conservan en el historial.", 14f, green), bottom = 10)
            add(body, button("Repetir entrenamiento").apply { setOnClickListener {
                sessionFields = emptyList()
                sessionFieldKey = null
                sessionDateDay?.let { day -> sessionDocumentId?.let { id ->
                    monthly.beginDate(id, day, repeat = true, extra = sessionExtra)
                } }
                progress.clear()
                sessionDocumentId?.let { progress.putAll(monthly.read(it).progress) }
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
                    saveVisibleProgress()
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
        saveVisibleProgress()
    }

    /** Persiste en el documento dueño de la sesión y nunca en otra rutina por accidente. */
    private fun saveVisibleProgress() {
        val owner = sessionDocumentId
        if (owner != null) monthly.updateProgress(owner, progress) else RoutineStore.saveProgress(this, progress)
    }

    /** Recupera la principal del mes después de consultar o ejecutar una sesión extra. */
    private fun restoreActiveDocument() {
        val active = monthly.active()
        activeDocumentId = active?.id
        plan = active?.plan ?: RoutinePlan(emptyList())
        progress.clear()
        progress.putAll(active?.progress.orEmpty())
        sessionDocumentId = null
        sessionExtra = false
        sessionDateDay = null
        sessionIndex = 0
        exerciseRuns.clear()
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
            saveVisibleProgress()
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
        if (page == Page.SESSION) currentSessionItems().getOrNull(sessionIndex)?.let {
            updateSessionControls(it)
        }
    }
    /** Finaliza una sesión y solicita confirmación si quedan ejercicios pendientes. */
    private fun finish(items: List<RoutineExercise>) {
        saveSessionFields()
        pauseSessionRest()
        val pending = items.count { progress[it.key()]?.done != true }
        if (pending == 0 || items.isEmpty()) {
            finishCurrentSession()
            showPage(if (sessionExtra) Page.ROUTINE_DETAIL else Page.PROGRESS)
            return
        }
        AlertDialog.Builder(this).setTitle("Finalizar entrenamiento")
            .setMessage("Quedan $pending ejercicios sin marcar. ¿Quieres marcarlos como completados?")
            .setNegativeButton("Seguir entrenando", null)
            .setNeutralButton("Finalizar sin marcarlos") { _, _ ->
                finishCurrentSession()
                showPage(if (sessionExtra) Page.ROUTINE_DETAIL else Page.PROGRESS)
            }
            .setPositiveButton("Marcar y finalizar") { _, _ ->
                items.forEach { progress.getOrPut(it.key()) { ExerciseProgress() }.done = true }
                saveVisibleProgress()
                finishCurrentSession()
                showPage(if (sessionExtra) Page.ROUTINE_DETAIL else Page.PROGRESS)
            }.show()
    }

    /** Cierra la sesión visible conservando su identidad mensual o heredada. */
    private fun finishCurrentSession() {
        val id = sessionDocumentId ?: return
        sessionDateDay?.let { monthly.finishDate(id, it, sessionExtra) }
    }

    /** Dibuja estadísticas agregadas, avance e historial con un modelo ya calculado. */
    private fun progressPage() {
        val body = scrollPage()
        header(body, "Progreso", if (plan.isMonthlyCalendar()) "Tu evolución durante el mes" else "Tu evolución semana a semana")
        if (plan.exercises.isEmpty()) {
            showRoutineEmpty(body)
            return
        }
        val summary = RoutinePresentation.progress(plan, progress)
        val stats = row()
        listOf(summary.completedSessions.toString() to "Sesiones", summary.completedExercises.toString() to "Ejercicios",
            "${summary.percent}%" to "Avance")
            .forEachIndexed { index, (number, caption) ->
                val box = col(9).apply { gravity = Gravity.CENTER }
                add(box, text(number, 24f, bold = true).apply { gravity = Gravity.CENTER })
                add(box, text(caption, 12f, muted).apply { gravity = Gravity.CENTER }, 4)
                stats.addView(card(box), LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                    if (index < 2) marginEnd = dp(7)
                })
            }
        add(body, stats, bottom = 14)
        val weights = runCatching { RoutinePresentation.weightHistory(monthly.list()) }.getOrDefault(emptyList())
        if (weights.isNotEmpty()) {
            val weightPanel = col(15)
            add(weightPanel, text("Pesos por ejercicio", 17f, bold = true), bottom = 10)
            weights.distinctBy { it.exercise.lowercase(Locale.ROOT) }.take(8).forEach { entry ->
                val line = row().apply { setPadding(0, dp(7), 0, dp(7)) }
                line.addView(text(entry.exercise, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(text("${entry.weight} kg  ›", 14f, red, true))
                line.setOnClickListener { showWeightHistory(entry.exercise, weights) }
                weightPanel.addView(line)
            }
            add(body, card(weightPanel), bottom = 14)
        }
        val chart = col(15)
        add(chart, text(if (plan.isMonthlyCalendar()) "Ejercicios completados por tramo del mes"
            else "Ejercicios completados por semana", 17f, bold = true), bottom = 12)
        summary.groups.forEach { group ->
            val entry = row()
            entry.addView(text(group.label, 13f, muted, true), LinearLayout.LayoutParams(dp(45), -2))
            val track = FrameLayout(this).apply { background = box(pale, 5) }
            val bar = View(this).apply { background = box(red, 5) }
            track.addView(bar, FrameLayout.LayoutParams(0, dp(13)))
            track.post { bar.layoutParams = FrameLayout.LayoutParams(track.width * group.completed / group.total.coerceAtLeast(1), dp(13)) }
            entry.addView(track, LinearLayout.LayoutParams(0, dp(13), 1f))
            entry.addView(text("${group.completed}/${group.total}", 12f, muted).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(57), -2))
            add(chart, entry, bottom = 11)
        }
        add(body, card(chart), bottom = 14)
        val history = col(15)
        add(history, text("Historial de entrenamientos", 17f, bold = true), bottom = 11)
        if (summary.history.isEmpty()) add(history, text("Aún no hay entrenamientos registrados.", 13f, muted))
        summary.history.forEach { item ->
            val entry = row()
            entry.addView(text(item.label, 13f), LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(text("${item.completed}/${item.total}  ${if (item.isComplete) "✓" else "◌"}",
                13f, if (item.isComplete) green else muted))
            add(history, entry, bottom = 11)
        }
        add(body, card(history))
    }

    /** Consulta rápida de la evolución de un ejercicio entre documentos y sesiones extra. */
    private fun showWeightHistory(exercise: String, entries: List<ExerciseWeightEntry>) {
        val body = col(15)
        entries.filter { it.exercise.equals(exercise, true) }.forEach { entry ->
            val source = "${RoutinePresentation.monthTitle(entry.month)} · ${if (entry.extra) "Sesión extra" else entry.routineId}"
            add(body, text("${entry.weight} kg${entry.reps.takeIf(String::isNotBlank)?.let { " · $it reps" }.orEmpty()}${entry.rir.takeIf(String::isNotBlank)?.let { " · RIR $it" }.orEmpty()}", 15f, bold = true))
            add(body, text("${entry.dateLabel}\n$source", 12f, muted), 4, bottom = 12)
        }
        AlertDialog.Builder(this).setTitle(exercise)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Cerrar", null).show()
    }

    /** Dibuja el centro de importación o exportación de la plantilla. */
    private fun templatePage() {
        val body = scrollPage()
        header(body, "Plantilla", "Importa o guarda tu rutina de Excel")
        add(body, button("Otras rutinas · consultar, realizar o exportar", false).apply {
            setOnClickListener { showPage(Page.OTHER_ROUTINES) }
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
        listOf("✓  Archivo .xlsx mensual", "✓  Hojas Periodo, Calendario y Rutina",
            "✓  Un día entero entre 1 y el último día del mes",
            "✓  Al menos un ejercicio por fecha; recuperación incluida").forEach { add(format, text(it, 13f, muted), bottom = 8) }
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
        listOf("✓  Mes elegido con 28, 29, 30 o 31 fechas", "✓  Rutina asociada a cada día",
            "✓  Ejercicios, series, repeticiones y descansos",
            "✓  Movilidad, estiramientos y alternativas").forEach { add(includes, text(it, 13f, muted), bottom = 8) }
        add(body, card(includes, pale, pale))
    }
    /** Solicita el mes exacto antes de preparar una plantilla para compartir. */
    private fun shareTemplate() = chooseMonth("Mes de la plantilla", YearMonth.now(), confirmText = "Compartir") { period ->
        shareTemplate(period)
    }

    /** Copia la plantilla del período elegido a caché y abre el panel nativo para compartirla. */
    private fun shareTemplate(period: YearMonth) = try {
        val folder = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(folder, "%02d_%04d.xlsx".format(Locale.ROOT, period.monthValue, period.year))
        val template = MonthlyPlanTemplate.forMonth(embeddedPlan(), period)
        file.outputStream().use { RoutineWorkbook.write(it, template, period) }
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
    private fun chooseMonth(title: String, initial: YearMonth, migration: Boolean = false,
        confirmText: String = "Guardar rutina", action: (YearMonth) -> Unit) {
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
            .setCancelable(!migration).setPositiveButton(confirmText, null)
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

    /** Importa un período nuevo o permite decidir cuál será principal cuando ya existe. */
    private fun importMonthlyPlan(importedPlan: RoutinePlan, period: YearMonth) {
        val current = monthly.primary(period)
        if (current == null) {
            completeImport(importedPlan, period, makePrimary = true)
            return
        }
        val currentExercises = current.plan.exercises.size
        val newExercises = importedPlan.exercises.size
        AlertDialog.Builder(this)
            .setTitle("Ya existe una rutina para ${RoutinePresentation.monthTitle(period)}")
            .setMessage("Actual: $currentExercises ejercicios · ${current.workouts.size} entrenamientos\n" +
                "Nueva: $newExercises ejercicios · progreso inicial 0 %\n\n" +
                "¿Cuál quieres mantener como principal? No se eliminará ninguna ni se mezclarán sus resultados.")
            .setNegativeButton("Mantener la actual") { _, _ ->
                completeImport(importedPlan, period, makePrimary = false)
            }
            .setPositiveButton("Usar la nueva") { _, _ ->
                completeImport(importedPlan, period, makePrimary = true)
            }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    /** Completa la elección de importación y refresca solo la principal del mes actual. */
    private fun completeImport(importedPlan: RoutinePlan, period: YearMonth, makePrimary: Boolean) {
        try {
            check(period != YearMonth.now() || !makePrimary || monthly.active()?.workouts.orEmpty().none {
                it.kind != "anterior" && it.finishedAt.isBlank()
            }) { "Finaliza el entrenamiento pendiente antes de cambiar la rutina activa." }
            monthly.create(importedPlan, period, makePrimary = makePrimary)
            RoutineStore.setImported(this, true)
            activateImportedPlan()
            val message = when {
                period == YearMonth.now() && makePrimary -> "Rutina importada y activada"
                makePrimary -> "Rutina programada para ${RoutinePresentation.monthTitle(period)}"
                else -> "Rutina guardada en Otras rutinas"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "No se pudo importar: ${error.message}", Toast.LENGTH_LONG).show()
        }
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
        sessionDocumentId = null
        sessionExtra = false
        exerciseRuns.clear()
        sessionDateDay = null
        sessionIndex = 0
        activeDocumentId = monthly.active()?.id
        plan = monthly.active()?.plan ?: RoutinePlan(emptyList())
        progress.clear()
        progress.putAll(monthly.active()?.progress.orEmpty())
        resetMorning()
        selectedDateDay = RoutinePresentation.initialDay(plan)
        page = Page.TODAY
        showPage(Page.TODAY)
    }

    /** Genera una instantánea de un único documento o la plantilla del mes actual. */
    private fun downloadWorkbook(document: MonthlyRoutine? = null) {
        if (document == null) {
            chooseMonth("Mes de la plantilla", YearMonth.now(), confirmText = "Crear Excel") { period ->
                prepareWorkbook(null, period)
            }
            return
        }
        prepareWorkbook(document, document.month)
    }

    /** Genera el archivo mensual o el informe histórico que espera el selector de destino. */
    private fun prepareWorkbook(document: MonthlyRoutine?, period: YearMonth) {
        try {
            saveSessionFields()
            val selected = document?.let { monthly.read(it.id) }
            val source = selected?.plan ?: embeddedPlan()
            val exportPlan = if (source.isMonthlyCalendar()) MonthlyPlanTemplate.forMonth(source, period) else source
            pendingWorkbook.outputStream().use { RoutineWorkbook.write(it, exportPlan, period, selected) }
            val filename = selected?.id ?: "%02d_%04d".format(Locale.ROOT, period.monthValue, period.year)
            saveTemplate.launch("$filename.xlsx")
        } catch (error: Exception) { Toast.makeText(this, "No se pudo preparar el Excel: ${error.message}", Toast.LENGTH_LONG).show() }
    }

    /** Muestra programadas, históricas y alternativas sin incluir la principal activa. */
    private fun otherRoutinesPage() {
        val body = scrollPage()
        header(body, "Otras rutinas", "Programadas, históricas y alternativas", backTo = Page.ROUTINE)
        val activeId = monthly.active()?.id
        val documents = try { monthly.list().filter { it.id != activeId } } catch (error: Exception) {
            add(body, text("No se pudieron leer las rutinas: ${error.message}", 14f, muted)); return
        }
        if (documents.isEmpty()) {
            val empty = col(18)
            add(empty, text("Todavía no hay otras rutinas", 20f, bold = true))
            add(empty, text("Las rutinas programadas, históricas y alternativas aparecerán aquí.", 14f, muted), 9)
            add(empty, button("Importar Excel").apply { setOnClickListener { openRoutinePicker() } }, 16)
            add(body, card(empty))
            return
        }
        listOf(
            MonthlyRoutineStatus.PROGRAMMED to "PROGRAMADAS",
            MonthlyRoutineStatus.HISTORICAL to "HISTÓRICAS",
            MonthlyRoutineStatus.ALTERNATIVE to "ALTERNATIVAS"
        ).forEach { (status, title) ->
            val group = documents.filter { monthly.status(it) == status }
            if (group.isEmpty()) return@forEach
            add(body, text(title, 13f, if (status == MonthlyRoutineStatus.ALTERNATIVE) red else muted, true),
                top = 10, bottom = 8)
            group.forEach { document ->
                val panel = col(15)
                val heading = row()
                heading.addView(text(RoutinePresentation.monthTitle(document.month), 18f, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(text(statusLabel(status), 12f, if (status == MonthlyRoutineStatus.ALTERNATIVE) red else muted, true))
                add(panel, heading)
                add(panel, text("${document.plan.exercises.size} ejercicios · ${document.workouts.size} entrenamientos", 13f, muted), 7)
                add(panel, button("Consultar rutina", false).apply { setOnClickListener {
                    selectedRoutineId = document.id
                    detailTab = DetailTab.PLAN
                    selectedDateDay = RoutinePresentation.initialDay(document.plan)
                    showPage(Page.ROUTINE_DETAIL)
                } }, 12)
                add(body, card(panel), bottom = 9)
            }
        }
    }

    /** Dibuja una rutina secundaria mediante Planificación, Progreso e Historial. */
    private fun routineDetailPage() {
        val document = selectedRoutineId?.let { runCatching { monthly.read(it) }.getOrNull() }
        if (document == null) { showPage(Page.OTHER_ROUTINES); return }
        val body = scrollPage()
        header(body, RoutinePresentation.monthTitle(document.month), statusLabel(monthly.status(document)),
            backTo = Page.OTHER_ROUTINES)
        add(body, text("${document.id} · ${document.workouts.size} entrenamientos registrados", 13f, muted), bottom = 12)
        val tabs = row()
        listOf(DetailTab.PLAN to "Planificación", DetailTab.PROGRESS to "Progreso", DetailTab.HISTORY to "Historial")
            .forEachIndexed { index, (tab, label) ->
                tabs.addView(button(label, detailTab == tab).apply { setOnClickListener {
                    detailTab = tab; routineDetailPage()
                } }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { if (index > 0) marginStart = dp(5) })
            }
        add(body, tabs, bottom = 14)
        when (detailTab) {
            DetailTab.PLAN -> detailPlan(body, document)
            DetailTab.PROGRESS -> detailProgress(body, document)
            DetailTab.HISTORY -> detailHistory(body, document)
        }
        if (monthly.primary(document.month)?.id != document.id) {
            add(body, button("Marcar como principal de este mes", false).apply { setOnClickListener {
                try {
                    monthly.setPrimary(document.id)
                    activateImportedPlan()
                    selectedRoutineId = document.id
                    showPage(Page.ROUTINE_DETAIL)
                } catch (error: Exception) {
                    Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show()
                }
            } }, top = 15)
        }
        add(body, button("Exportar esta rutina", false).apply { setOnClickListener { downloadWorkbook(document) } }, top = 8)
        add(body, button("Eliminar rutina", false).apply { setOnClickListener { confirmDeleteRoutine(document.id) } }, top = 8)
    }

    /** Calendario y ejercicios de un documento consultado. */
    private fun detailPlan(body: LinearLayout, document: MonthlyRoutine) {
        if (!document.plan.isMonthlyCalendar()) {
            add(body, text("Plan anterior de consulta", 18f, bold = true), bottom = 9)
            document.plan.exercises.forEach { add(body, text("${it.exercise} · ${it.series} × ${it.reps}", 14f), bottom = 7) }
            return
        }
        val period = document.month
        selectedDateDay = selectedDateDay.coerceIn(1, period.lengthOfMonth())
        val weekHeader = row()
        listOf("L", "M", "X", "J", "V", "S", "D").forEach { label ->
            weekHeader.addView(text(label, 12f, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, dp(28), 1f))
        }
        add(body, weekHeader, bottom = 3)
        RoutinePresentation.calendarRows(period).forEach { week ->
            val line = row()
            week.forEach { day ->
                if (day == null) line.addView(View(this), LinearLayout.LayoutParams(0, dp(45), 1f))
                else line.addView(button(day.toString(), day == selectedDateDay).apply {
                    textSize = 12f
                    setOnClickListener { selectedDateDay = day; routineDetailPage() }
                }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { marginEnd = dp(3); bottomMargin = dp(3) })
            }
            add(body, line)
        }
        val day = selectedDateDay
        val info = document.plan.calendarDay(day)
        val items = document.plan.forDate(day)
        val panel = col(16)
        add(panel, text(RoutinePresentation.fullDate(period.atDay(day)), 19f, bold = true))
        add(panel, text("${info.type.uppercase()} · ${info.title}", 13f, red, true), 7)
        if (info.focus.isNotBlank()) add(panel, text(info.focus, 13f, muted), 6)
        items.forEach { exercise ->
            val result = document.progress[exercise.key()]
            val metrics = listOf(exercise.series + " series", exercise.reps,
                result?.weight?.takeIf(String::isNotBlank)?.let { "$it kg" }).filterNotNull().joinToString(" · ")
            add(panel, text("${exercise.exercise}\n$metrics", 14f), 10)
        }
        if (items.isNotEmpty()) add(panel, button("Realizar como sesión extra").apply { setOnClickListener {
            session(day, document.id, extra = true)
        } }, 15)
        add(body, card(panel), top = 10)
    }

    /** Resumen y últimos valores registrados exclusivamente en el documento elegido. */
    private fun detailProgress(body: LinearLayout, document: MonthlyRoutine) {
        val summary = RoutinePresentation.progress(document.plan, document.progress)
        val stats = row()
        listOf("${summary.percent}%" to "Avance", summary.completedSessions.toString() to "Sesiones",
            summary.completedExercises.toString() to "Ejercicios").forEachIndexed { index, (number, label) ->
            val box = col(9).apply { gravity = Gravity.CENTER }
            add(box, text(number, 22f, bold = true).apply { gravity = Gravity.CENTER })
            add(box, text(label, 12f, muted).apply { gravity = Gravity.CENTER }, 4)
            stats.addView(card(box), LinearLayout.LayoutParams(0, dp(75), 1f).apply { if (index > 0) marginStart = dp(5) })
        }
        add(body, stats, bottom = 14)
        add(body, text("Últimos resultados", 18f, bold = true), bottom = 9)
        val weighted = RoutinePresentation.weightHistory(listOf(document)).take(12)
        if (weighted.isEmpty()) add(body, text("Todavía no hay pesos registrados.", 13f, muted))
        weighted.forEach { result ->
            add(body, card(col(13).apply {
                add(this, text(result.exercise, 15f, bold = true))
                add(this, text("${result.weight} kg${result.reps.takeIf(String::isNotBlank)?.let { " · $it reps" }.orEmpty()}${result.rir.takeIf(String::isNotBlank)?.let { " · RIR $it" }.orEmpty()}", 13f, muted), 5)
                add(this, text(result.dateLabel, 12f, muted), 4)
            }), bottom = 7)
        }
        add(body, text("Este progreso pertenece únicamente a esta rutina.", 13f, muted), top = 10)
    }

    /** Historial completo con identificación explícita de las sesiones extra. */
    private fun detailHistory(body: LinearLayout, document: MonthlyRoutine) {
        if (document.workouts.isEmpty()) {
            add(body, text("Aún no hay entrenamientos registrados.", 14f, muted)); return
        }
        document.workouts.asReversed().forEach { record ->
            val panel = col(14)
            val title = if (record.kind == "extra") "SESIÓN EXTRA · ${record.day}" else record.day
            add(panel, text(title, 15f, bold = true))
            add(panel, text(record.startedAt.ifBlank { "Fecha desconocida" }, 12f, muted), 5)
            val completed = record.results.values.count { it.done }
            add(panel, text("$completed de ${record.results.size} ejercicios completados", 13f,
                if (record.finishedAt.isNotBlank()) green else muted), 6)
            record.results.filterValues { it.weight.isNotBlank() }.forEach { (key, result) ->
                val name = document.plan.exercises.firstOrNull { it.key() == key }?.exercise ?: key
                add(panel, text("$name · ${result.weight} kg · ${result.actualReps} reps · RIR ${result.rir}", 12f, muted), 5)
            }
            add(body, card(panel), bottom = 8)
        }
    }

    private fun statusLabel(status: MonthlyRoutineStatus): String = when (status) {
        MonthlyRoutineStatus.ACTIVE -> "ACTIVA"
        MonthlyRoutineStatus.PROGRAMMED -> "PROGRAMADA"
        MonthlyRoutineStatus.HISTORICAL -> "HISTÓRICA"
        MonthlyRoutineStatus.ALTERNATIVE -> "ALTERNATIVA"
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
            val labels = documents.map { "${it.label()} · ${statusLabel(monthly.status(it))}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle(if (exportOnly) "Elige una rutina para exportar" else "Otras rutinas")
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
            val location = RoutinePresentation.exerciseLocation(document.month, item)
            add(body, text("$location\n${item.exercise}: ${item.series} series · ${item.reps} · descanso ${item.rest}\n${item.instruction}", 14f), bottom = 10)
        }
        add(body, text("Entrenamientos realizados", 19f, bold = true), top = 12, bottom = 10)
        if (document.workouts.isEmpty()) add(body, text("Sin entrenamientos registrados.", 14f))
        document.workouts.forEach { record ->
            val location = RoutinePresentation.workoutLocation(document.month, record)
            add(body, text("$location\n${record.startedAt.ifBlank { "Fecha desconocida" }} · ${if (record.finishedAt.isBlank() && record.kind != "anterior") "En curso" else "Guardado"}", 15f, bold = true), top = 12)
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
                        selectedRoutineId = null
                        showPage(Page.OTHER_ROUTINES)
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
    /** Activa el nuevo período al volver a la app, sin interrumpir una sesión abierta. */
    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && page !in setOf(Page.SESSION, Page.MORNING) && sessionDocumentId == null) {
            val currentId = monthly.active()?.id
            if (currentId != activeDocumentId) {
                restoreActiveDocument()
                showPage(page)
            }
        }
    }
    /** Pausa contadores y persiste ediciones cuando la actividad deja de estar visible. */
    override fun onPause() {
        pauseMorning()
        pauseSessionRest()
        saveSessionFields()
        super.onPause()
        saveVisibleProgress()
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
