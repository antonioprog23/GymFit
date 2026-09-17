package com.rutinaboxeo.app

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

object XlsxRoutineParser {
    private val dayNames = setOf("Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo")
    private data class AlternativeData(val note: String, val exercises: List<ExerciseAlternative>)

    fun parse(input: InputStream): RoutinePlan {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) files[entry.name] = zip.readBytes()
                zip.closeEntry(); entry = zip.nextEntry
            }
        }

        val shared = parseSharedStrings(files["xl/sharedStrings.xml"])
        val rels = parseWorkbookRels(files["xl/_rels/workbook.xml.rels"] ?: error("Excel no válido: faltan relaciones"))
        val sheets = parseWorkbook(files["xl/workbook.xml"] ?: error("Excel no válido: falta workbook"))

        val result = mutableListOf<RoutineExercise>()
        val recovery = mutableListOf<RoutineExercise>()
        val alternatives = mutableMapOf<String, AlternativeData>()
        val sessions = mutableMapOf<String, SessionInfo>()
        val morning = mutableListOf<Pair<Int, MorningStep>>()
        var morningSheetFound = false

        sheets.forEach { (name, rId) ->
            val target = rels[rId] ?: return@forEach
            val normalized = if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("../")}".replace("xl/xl/", "xl/")
            val xml = files[normalized] ?: files["xl/worksheets/${target.substringAfterLast('/')}"] ?: return@forEach
            val rows = parseSheet(xml, shared)

            val week = Regex("Semana\\s+(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
            when {
                name.equals("Mañana", true) || name.equals("Manana", true) -> {
                    morningSheetFound = true
                    rows.forEach { row ->
                        val order = row[0]?.trim()?.toIntOrNull() ?: return@forEach
                        val title = row[1]?.trim().orEmpty()
                        val minutes = row[2]?.trim()?.replace(',', '.')?.toDoubleOrNull()
                        val instruction = row[3]?.trim().orEmpty()
                        if (title.isBlank() || minutes == null || minutes <= 0 || minutes > 120 || instruction.isBlank()) {
                            error("La hoja Mañana necesita ejercicio, duración en minutos e indicaciones en la fila de orden $order")
                        }
                        morning += order to MorningStep(title, (minutes * 60).roundToInt(), instruction,
                            VideoLinks.clean(row[4].orEmpty()))
                    }
                }
                name.equals("Inicio", true) -> {
                    rows.forEach { row ->
                        val day = row[0]?.trim().orEmpty()
                        val title = row[1]?.trim().orEmpty()
                        if (day in dayNames && title.isNotBlank()) {
                            sessions[day] = SessionInfo(title, row[2].orEmpty(), row[3].orEmpty())
                        }
                    }
                }
                week != null -> {
                    var order = 0
                    rows.forEach { row ->
                        val day = row[0]?.trim().orEmpty()
                        val exercise = row[2]?.trim().orEmpty()
                        if (day in dayNames && exercise.isNotBlank()) {
                            result += RoutineExercise(
                                week = week, day = day, block = row[1].orEmpty(), exercise = exercise,
                                series = row[3].orEmpty(), reps = row[4].orEmpty(), rest = row[5].orEmpty(), order = order++,
                                instruction = row[10].orEmpty(), videoUrl = VideoLinks.clean(row[11].orEmpty())
                            )
                        }
                    }
                }
                name.equals("Recuperación", true) || name.equals("Recuperacion", true) -> {
                    var order = 0
                    rows.forEach { row ->
                        val exercise = row[1]?.trim().orEmpty()
                        if (exercise.isNotBlank() && row[0]?.trim()?.toIntOrNull() != null) {
                            recovery += RoutineExercise(
                                week = 0, day = "Viernes", block = "Recuperación", exercise = exercise,
                                series = row[2].orEmpty(), reps = row[3].orEmpty(), rest = row[4].orEmpty(),
                                order = order++, instruction = row[5].orEmpty(), videoUrl = VideoLinks.clean(row[6].orEmpty())
                            )
                        }
                    }
                }
                name.equals("Alternativas", true) -> {
                    rows.forEach { row ->
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

        if (result.isEmpty()) error("No se encontraron hojas 'Semana 1', 'Semana 2'... en la plantilla")
        if (morningSheetFound && morning.isEmpty()) error("La hoja Mañana no contiene ejercicios")
        if (recovery.isNotEmpty()) {
            val weeks = result.map { it.week }.distinct()
            result.removeAll { it.day == "Viernes" }
            weeks.forEach { w ->
                recovery.forEachIndexed { idx, r -> result += r.copy(week = w, order = 1000 + idx) }
            }
        }

        val enriched = result.map { e ->
            val extra = alternatives[e.exercise] ?: return@map e
            e.copy(
                note = listOf(e.note, extra.note).filter { it.isNotBlank() }.joinToString(" · "),
                alternatives = extra.exercises
            )
        }

        return RoutinePlan(
            enriched.sortedWith(compareBy<RoutineExercise> { it.week }.thenBy { dayIndex(it.day) }.thenBy { it.order }),
            sessions,
            morning.sortedBy { it.first }.map { it.second }
        )
    }

    private fun dayIndex(day: String) = listOf("Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo").indexOf(day)

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

    private fun columnIndex(letters: String): Int {
        var n = 0
        letters.uppercase().forEach { n = n * 26 + (it - 'A' + 1) }
        return (n - 1).coerceAtLeast(0)
    }

    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}
