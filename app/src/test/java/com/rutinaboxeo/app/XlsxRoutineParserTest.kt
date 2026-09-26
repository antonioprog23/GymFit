package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Comprueba que el lector XLSX mantiene el contrato de la plantilla incluida. */
class XlsxRoutineParserTest {
    /** Rechaza declaraciones de entidades antes de interpretar XML procedente del libro. */
    @Test(expected = IllegalArgumentException::class)
    fun rejectsDocumentTypeDeclarations() {
        val xml = "<!DOCTYPE s [<!ENTITY secret SYSTEM 'file:///unavailable'>]><s>&secret;</s>"
        XlsxRoutineParser.parse(archive("xl/sharedStrings.xml", xml.toByteArray()).inputStream())
    }

    /** Aplica la misma protección a documentos XML codificados en UTF-16. */
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUtf16DocumentTypeDeclarations() {
        val xml = "<?xml version='1.0' encoding='UTF-16'?><!DOCTYPE s><s/>"
        XlsxRoutineParser.parse(archive("xl/sharedStrings.xml", xml.toByteArray(Charsets.UTF_16)).inputStream())
    }

    /** Rechaza entradas que superan el límite de descompresión aunque el ZIP sea pequeño. */
    @Test(expected = IllegalStateException::class)
    fun rejectsOversizedArchiveEntry() {
        XlsxRoutineParser.parse(archive("oversized.xml", ByteArray(5 * 1024 * 1024 + 1)).inputStream())
    }

    /** Verifica semanas, sesiones, recuperación, alternativas y rutina matinal. */
    @Test fun bundledTemplateHasExpectedStructure() {
        val plan = File("src/main/assets/rutina_plantilla.xlsx").inputStream().use(XlsxRoutineParser::parse)
        assertEquals(listOf(1, 2, 3, 4), plan.weeks())
        assertEquals("Piernas + potencia", plan.session("Lunes").title)
        assertTrue(plan.forDay(1, "Lunes").isNotEmpty())
        assertTrue(plan.forDay(1, "Viernes").any { it.block == "Recuperación" })
        assertTrue(plan.exercises.any { it.alternatives.isNotEmpty() })
        assertTrue(plan.exercises.flatMap { it.alternatives }.all { it.title.isNotBlank() })
        assertEquals(6, plan.morningSteps.size)
        assertEquals("Estiramiento suave", plan.morningSteps.last().title)
    }

    /** Construye un ZIP mínimo en memoria para probar entradas no confiables. */
    private fun archive(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
