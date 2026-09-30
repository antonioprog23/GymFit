package com.rutinaboxeo.app

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/** Importa la plantilla XLSX sin depender de una biblioteca ofimática pesada. */
object XlsxRoutineParser {
    /** Datos auxiliares que enriquecen un ejercicio principal. */
    private data class AlternativeData(
        /** Observación aplicable al ejercicio principal. */
        val note: String,
        /** Sustituciones permitidas para el ejercicio. */
        val exercises: List<ExerciseAlternative>
    )

    /** Fila de ejercicio mensual pendiente de validar cuando se conozca el período completo. */
    private data class MonthlyExerciseData(val dayOfMonth: Int, val row: Map<Int, String>)

    /** Lee un archivo XLSX y construye un plan validado y ordenado. */
    fun parse(input: InputStream): RoutinePlan {
        val files = readArchive(input)

        val shared = parseSharedStrings(files["xl/sharedStrings.xml"])
        val rels = parseWorkbookRels(files["xl/_rels/workbook.xml.rels"] ?: error("Excel no válido: faltan relaciones"))
        val sheets = parseWorkbook(files["xl/workbook.xml"] ?: error("Excel no válido: falta workbook"))

        val result = mutableListOf<RoutineExercise>()
        val alternatives = mutableMapOf<String, AlternativeData>()
        val morning = mutableListOf<Pair<Int, MorningStep>>()
        val monthlyExercises = mutableListOf<MonthlyExerciseData>()
        val calendar = mutableMapOf<Int, CalendarDay>()
        var morningSheetFound = false
        var month: YearMonth? = null
        var calendarSheetFound = false
        var routineSheetFound = false

        sheets.forEach { (name, rId) ->
            val target = rels[rId] ?: return@forEach
            val normalized = if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("../")}".replace("xl/xl/", "xl/")
            val xml = files[normalized] ?: files["xl/worksheets/${target.substringAfterLast('/')}"] ?: return@forEach
            val rows = parseSheet(xml, shared)

            when {
                name.equals("Periodo", true) -> {
                    val monthValue = wholeNumber(rows.firstOrNull { it[0] == "Mes" }?.get(1))
                    val yearValue = wholeNumber(rows.firstOrNull { it[0] == "Año" }?.get(1))
                    require(monthValue != null && monthValue in 1..12 && yearValue != null && yearValue in 1900..9999) {
                        "La hoja Periodo necesita Mes (1–12) y Año (1900–9999)"
                    }
                    month = YearMonth.of(yearValue, monthValue)
                }
                name.equals("Calendario", true) -> {
                    calendarSheetFound = true
                    rows.forEach calendarRow@{ row ->
                        val rawDay = row[0]?.trim().orEmpty()
                        if (rawDay.isBlank() || rawDay.equals("Día", true) || isDecorativeRow(row, rawDay)) return@calendarRow
                        val day = requireNotNull(wholeNumber(rawDay)) { "Calendario contiene un día no entero: $rawDay" }
                        require(day !in calendar) { "El día $day está repetido en Calendario" }
                        calendar[day] = CalendarDay(
                            dayOfMonth = day,
                            type = row[3].orEmpty().ifBlank { "Entrenamiento" },
                            title = row[4].orEmpty().ifBlank { "Día $day" },
                            focus = row[5].orEmpty(), note = row[6].orEmpty(), phase = row[7].orEmpty(),
                            intensity = row[8].orEmpty(), rule = row[9].orEmpty()
                        )
                    }
                }
                name.equals("Rutina", true) -> {
                    routineSheetFound = true
                    rows.forEach monthlyRow@{ row ->
                        val rawDay = row[0]?.trim().orEmpty()
                        if (rawDay.isBlank() || rawDay.equals("Día", true) || isDecorativeRow(row, rawDay)) return@monthlyRow
                        val day = requireNotNull(wholeNumber(rawDay)) { "Rutina contiene un día no entero: $rawDay" }
                        val exercise = row[2]?.trim().orEmpty()
                        if (exercise.isNotBlank()) monthlyExercises += MonthlyExerciseData(day, row)
                    }
                }
                name.equals("Mañana", true) || name.equals("Manana", true) -> {
                    morningSheetFound = true
                    rows.forEach morningRow@{ row ->
                        val order = row[0]?.trim()?.toIntOrNull() ?: return@morningRow
                        val title = row[1]?.trim().orEmpty()
                        val minutes = row[2]?.trim()?.replace(',', '.')?.toDoubleOrNull()
                        val instruction = row[3]?.trim().orEmpty()
                        if (title.isBlank() || minutes == null || !minutes.isFinite() || minutes <= 0 || minutes > 120 || instruction.isBlank()) {
                            error("La hoja Mañana necesita ejercicio, duración en minutos e indicaciones en la fila de orden $order")
                        }
                        morning += order to MorningStep(title, (minutes * 60).roundToInt().coerceAtLeast(1), instruction,
                            VideoLinks.clean(row[4].orEmpty()))
                    }
                }
                name.equals("Alternativas", true) -> {
                    rows.forEach alternativeRow@{ row ->
                        val main = row[0]?.trim().orEmpty()
                        val alt1 = row[1]?.trim().orEmpty()
                        val alt2 = row[2]?.trim().orEmpty()
                        val note = row[3]?.trim().orEmpty()
                        if (main.isNotBlank() && alt1.isNotBlank() && !main.contains("Ejercicio principal", true)) {
                            val options = buildList {
                                add(ExerciseAlternative(alt1, row[4].orEmpty(), VideoLinks.clean(row[5].orEmpty())))
                                if (alt2.isNotBlank() && !alt2.startsWith("Eliminar", true)) {
                                    add(ExerciseAlternative(alt2, row[6].orEmpty(), VideoLinks.clean(row[7].orEmpty())))
                                }
                            }
                            val extraNote = listOf(note, alt2.takeIf { it.startsWith("Eliminar", true) }.orEmpty())
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            alternatives[main] = AlternativeData(extraNote, options)
                        }
                    }
                }
            }
        }

        require(calendarSheetFound && routineSheetFound) {
            "Esta plantilla semanal está obsoleta. Usa la plantilla mensual con las hojas Periodo, Calendario y Rutina."
        }
        val period = requireNotNull(month) { "La plantilla necesita una hoja Periodo con Mes y Año" }
        val validDays = (1..period.lengthOfMonth()).toSet()
        require(calendar.keys == validDays) {
            val missing = (validDays - calendar.keys).sorted()
            val invalid = (calendar.keys - validDays).sorted()
            listOfNotNull(
                missing.takeIf { it.isNotEmpty() }?.let { "faltan ${it.joinToString()}" },
                invalid.takeIf { it.isNotEmpty() }?.let { "sobran ${it.joinToString()}" }
            ).joinToString("; ", prefix = "Calendario no coincide con $period: ")
        }
        require(monthlyExercises.isNotEmpty()) { "La hoja Rutina no contiene ejercicios" }
        val routineDays = monthlyExercises.map { it.dayOfMonth }.toSet()
        require(routineDays.all { it in calendar }) { "Rutina contiene días que no existen en Calendario" }
        require(routineDays == validDays) {
            "Cada día del mes debe tener al menos un ejercicio; faltan ${(validDays - routineDays).sorted().joinToString()}"
        }
        val orders = mutableMapOf<Int, Int>()
        result.clear()
        monthlyExercises.forEach { data ->
            val row = data.row
            val order = orders.getOrDefault(data.dayOfMonth, 0)
            orders[data.dayOfMonth] = order + 1
            result += RoutineExercise(
                week = (data.dayOfMonth - 1) / 7 + 1,
                day = dayName(period, data.dayOfMonth),
                block = row[1].orEmpty(), exercise = row[2].orEmpty(), series = row[3].orEmpty(),
                reps = row[4].orEmpty(), rest = row[5].orEmpty(), note = row[12].orEmpty(), order = order,
                instruction = row[10].orEmpty(), videoUrl = VideoLinks.clean(row[11].orEmpty()),
                dayOfMonth = data.dayOfMonth
            )
        }

        if (morningSheetFound && morning.isEmpty()) error("La hoja Mañana no contiene ejercicios")

        val enriched = result.map { e ->
            val extra = alternatives[e.exercise] ?: return@map e
            e.copy(
                note = listOf(e.note, extra.note).filter { it.isNotBlank() }.joinToString(" · "),
                alternatives = extra.exercises
            )
        }

        return RoutinePlan(
            enriched.sortedWith(compareBy<RoutineExercise> { it.dayOfMonth }.thenBy { it.order }),
            emptyMap(),
            morning.sortedBy { it.first }.map { it.second }, month, calendar.toSortedMap()
        )
    }

    /** Convierte solo números enteros, aunque Excel los serialice con decimal cero. */
    private fun wholeNumber(value: String?): Int? {
        val number = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (!number.isFinite() || number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) return null
        return number.toInt()
    }

    /** Reconoce títulos y subtítulos combinados que Excel replica en varias celdas. */
    private fun isDecorativeRow(row: Map<Int, String>, firstValue: String): Boolean =
        wholeNumber(firstValue) == null && row.values.filter { it.isNotBlank() }.distinct().size == 1

    /** Calcula el nombre español del día de la semana correspondiente a una fecha mensual. */
    private fun dayName(month: YearMonth, dayOfMonth: Int): String = when (month.atDay(dayOfMonth).dayOfWeek) {
        DayOfWeek.MONDAY -> "Lunes"
        DayOfWeek.TUESDAY -> "Martes"
        DayOfWeek.WEDNESDAY -> "Miércoles"
        DayOfWeek.THURSDAY -> "Jueves"
        DayOfWeek.FRIDAY -> "Viernes"
        DayOfWeek.SATURDAY -> "Sábado"
        DayOfWeek.SUNDAY -> "Domingo"
    }

    /** Lee el contenedor ZIP con límites para evitar archivos descomprimidos desproporcionados. */
    private fun readArchive(input: InputStream): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        var totalBytes = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = zip.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            totalBytes += read
                            if (output.size() + read > MAX_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
                                error("El archivo Excel es demasiado grande")
                            }
                            output.write(buffer, 0, read)
                        }
                        read = zip.read(buffer)
                    }
                    files[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return files
    }

    /** Extrae la tabla de textos compartidos utilizada por las celdas XLSX. */
    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val doc = document(bytes)
        val nodes = doc.getElementsByTagNameNS("*", "si")
        return (0 until nodes.length).map { i ->
            val si = nodes.item(i) as Element
            val ts = si.getElementsByTagNameNS("*", "t")
            buildString { for (j in 0 until ts.length) append(ts.item(j).textContent) }
        }
    }

    /** Relaciona los identificadores internos del libro con sus archivos de hoja. */
    private fun parseWorkbookRels(bytes: ByteArray): Map<String, String> {
        val doc = document(bytes)
        val nodes = doc.getElementsByTagNameNS("*", "Relationship")
        val map = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            map[e.getAttribute("Id")] = e.getAttribute("Target")
        }
        return map
    }

    /** Obtiene los nombres de hoja y sus identificadores de relación. */
    private fun parseWorkbook(bytes: ByteArray): List<Pair<String, String>> {
        val doc = document(bytes)
        val nodes = doc.getElementsByTagNameNS("*", "sheet")
        val list = mutableListOf<Pair<String,String>>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val rid = e.getAttribute("r:id").ifBlank { e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") }
            list += e.getAttribute("name") to rid
        }
        return list
    }

    /** Convierte las celdas de una hoja en filas indexadas por número de columna. */
    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<Map<Int, String>> {
        val doc = document(bytes)
        val rowNodes = doc.getElementsByTagNameNS("*", "row")
        val rows = mutableListOf<Map<Int,String>>()
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as Element
            val cells = row.getElementsByTagNameNS("*", "c")
            val map = mutableMapOf<Int,String>()
            for (j in 0 until cells.length) {
                val c = cells.item(j) as Element
                val ref = c.getAttribute("r")
                val col = columnIndex(ref.takeWhile { it.isLetter() })
                val type = c.getAttribute("t")
                val value = when (type) {
                    "s" -> c.getElementsByTagNameNS("*", "v").item(0)?.textContent?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> c.getElementsByTagNameNS("*", "t").item(0)?.textContent.orEmpty()
                    else -> c.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                }
                map[col] = value
            }
            rows += map
        }
        return rows
    }

    /** Convierte una referencia alfabética de columna a un índice basado en cero. */
    private fun columnIndex(letters: String): Int {
        var n = 0
        letters.uppercase().forEach { n = n * 26 + (it - 'A' + 1) }
        return (n - 1).coerceAtLeast(0)
    }

    /** Crea un documento XML con entidades externas y declaraciones DOCTYPE deshabilitadas. */
    private fun document(bytes: ByteArray): org.w3c.dom.Document {
        // Reconoce también UTF-16/32 sin depender de opciones XML ausentes en Android.
        val declarationText = bytes.toString(Charsets.UTF_8).replace("\u0000", "")
        require(!declarationText.contains("<!DOCTYPE", ignoreCase = true)) {
            "El archivo Excel contiene una declaración XML no permitida"
        }
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("Entidad externa no permitida") }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    /** Tamaño del bloque empleado al descomprimir el libro. */
    private const val BUFFER_SIZE = 8 * 1024

    /** Máximo descomprimido aceptado para una entrada individual. */
    private const val MAX_ENTRY_BYTES = 5 * 1024 * 1024

    /** Máximo descomprimido aceptado para el conjunto del libro. */
    private const val MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
}
