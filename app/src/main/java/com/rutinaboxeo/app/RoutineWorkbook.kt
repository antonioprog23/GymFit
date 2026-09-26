package com.rutinaboxeo.app

import java.io.OutputStream
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Genera XLSX nativos en el móvil con las columnas compatibles con la plantilla existente. */
object RoutineWorkbook {
    /** Exporta una sola rutina, o una plantilla sin resultados cuando no se facilita documento. */
    fun write(output: OutputStream, plan: RoutinePlan, month: YearMonth, document: MonthlyRoutine? = null) {
        val sheets = linkedMapOf<String, List<List<Any>>>()
        sheets["Periodo"] = listOf(listOf("Campo", "Valor"), listOf("Mes", month.monthValue),
            listOf("Año", month.year), listOf("Importación", document?.id ?: "Plantilla"))
        sheets["Instrucciones"] = listOf(listOf("Tema", "Cómo rellenar la plantilla"),
            listOf("Período", "Edita Mes y Año en Periodo antes de importar. Cada importación crea una rutina nueva."),
            listOf("Semanas", "Duplica una hoja Semana 1 y cambia su nombre a Semana 2, Semana 3… según necesites."),
            listOf("Días", "Usa Lunes, Martes, Miércoles, Jueves, Viernes, Sábado o Domingo."),
            listOf("Ejercicios", "Rellena Día, Bloque y Ejercicio. Añade filas debajo de la cabecera sin cambiar las columnas."),
            listOf("Series", "Ejemplo: 3. Repeticiones: 8–10. Trabajo por tiempo: 30 s o 5 min."),
            listOf("Descanso", "Ejemplos: 60 s, 2 min o - para no descansar."),
            listOf("Mañana", "Duración en minutos, por ejemplo 0,5 para 30 segundos. Indicaciones obligatorias."),
            listOf("Resultados", "Peso, Reps reales, RIR, Nota personal y Hecho son resultados; se ignoran al importar."),
            listOf("Historial", "Cada fila identifica una realización fechada. Nunca se importa como progreso."),
            listOf("Ejemplo", "La planificación incluida ilustra el formato; adapta sus ejercicios y cargas a tus necesidades."))
        sheets["Inicio"] = listOf(listOf("Día", "Sesión", "Objetivo", "Notas")) +
            plan.sessions.map { (day, info) -> listOf(day, info.title, info.focus, info.note) }
        if (plan.morningSteps.isNotEmpty()) sheets["Mañana"] = listOf(listOf("Orden", "Ejercicio", "Duración (min)", "Indicaciones", "Vídeo")) +
            plan.morningSteps.mapIndexed { index, step -> listOf(index + 1, step.title, step.seconds / 60.0, step.instruction, step.videoUrl) }
        plan.weeks().forEach { week ->
            sheets["Semana $week"] = listOf(listOf("Día", "Bloque", "Ejercicio", "Series", "Reps/tiempo", "Descanso",
                "Peso (kg)", "Reps reales", "RIR", "Nota personal", "Indicaciones", "Vídeo", "Nota planificación", "Hecho")) +
                plan.forWeek(week).map { item ->
                    val result = document?.progress?.get(item.key()) ?: ExerciseProgress()
                    listOf(item.day, item.block, item.exercise, item.series, item.reps, item.rest,
                        result.weight, result.actualReps, result.rir, result.userNote, item.instruction,
                        item.videoUrl, item.note, if (result.done) "Sí" else "")
                }
        }
        sheets["Alternativas"] = listOf(listOf("Ejercicio principal", "Alternativa 1", "Alternativa 2", "Nota",
            "Indicaciones 1", "Vídeo 1", "Indicaciones 2", "Vídeo 2")) +
            plan.exercises.filter { it.alternatives.isNotEmpty() }.distinctBy { it.exercise }.map { item ->
                val first = item.alternatives.first()
                val second = item.alternatives.getOrNull(1)
                listOf(item.exercise, first.title, second?.title.orEmpty(), "", first.instruction, first.videoUrl,
                    second?.instruction.orEmpty(), second?.videoUrl.orEmpty())
            }
        if (document != null) {
            val history = mutableListOf<List<Any>>(listOf("Realización", "Inicio", "Fin", "Tipo", "Semana", "Día",
                "Ejercicio", "Peso (kg)", "Reps reales", "RIR", "Nota personal", "Hecho"))
            document.workouts.forEach { record ->
                val prefix = listOf(record.id, record.startedAt.ifBlank { "Fecha desconocida" }, record.finishedAt,
                    record.kind, record.week, record.day)
                if (record.results.isEmpty()) history += prefix + listOf("Rutina matinal", "", "", "", "", if (record.finishedAt.isNotBlank()) "Sí" else "No")
                record.results.forEach { (key, result) ->
                    val title = plan.exercises.firstOrNull { it.key() == key }?.exercise ?: key
                    history += prefix + listOf(title, result.weight, result.actualReps, result.rir,
                        result.userNote, if (result.done) "Sí" else "No")
                }
            }
            sheets["Historial"] = history
        }
        ZipOutputStream(output).use { zip ->
            entry(zip, "[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.keys.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            entry(zip, "_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry(zip, "xl/workbook.xml", """<workbook xmlns="$NS" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.keys.mapIndexed { index, name -> """<sheet name="${escape(name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            entry(zip, "xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.keys.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry(zip, "xl/styles.xml", """<styleSheet xmlns="$NS"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1B2937"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf fontId="1" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.entries.forEachIndexed { index, (name, rows) ->
                val columns = rows.maxOfOrNull { it.size } ?: 1
                val widths = (0 until columns).joinToString("") { col ->
                    val width = if (name == "Instrucciones" && col == 1) 100 else if (col in listOf(2, 9, 10, 12)) 38 else 22
                    """<col min="${col + 1}" max="${col + 1}" width="$width" customWidth="1"/>"""
                }
                entry(zip, "xl/worksheets/sheet${index + 1}.xml", """<worksheet xmlns="$NS"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>$widths</cols><sheetData>""" +
                    rows.mapIndexed { rowIndex, cells ->
                        """<row r="${rowIndex + 1}" ht="${if (rowIndex == 0) 32 else 60}" customHeight="1">""" + cells.mapIndexed { col, value ->
                            val address = "${column(col)}${rowIndex + 1}"
                            val style = if (rowIndex == 0) 1 else 0
                            if (value is Number) """<c r="$address" s="$style"><v>$value</v></c>"""
                            else """<c r="$address" s="$style" t="inlineStr"><is><t xml:space="preserve">${escape(value.toString())}</t></is></c>"""
                        }.joinToString("") + "</row>"
                    }.joinToString("") + "</sheetData></worksheet>")
            }
        }
    }

    /** Escribe un componente XML UTF-8 dentro del libro XLSX. */
    private fun entry(zip: ZipOutputStream, path: String, xml: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Escapa texto literal y descarta caracteres de control no admitidos por XML. */
    private fun escape(value: String): String = value.filter { it >= ' ' || it in "\n\r\t" }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Convierte un índice de columna en la notación A, B, …, AA de Excel. */
    private fun column(index: Int): String {
        var number = index + 1
        var result = ""
        while (number > 0) { result = ('A' + (number - 1) % 26) + result; number = (number - 1) / 26 }
        return result
    }

    /** Espacio de nombres SpreadsheetML compartido por los componentes del libro. */
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
}
