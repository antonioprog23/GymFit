package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/** Comprueba la agrupación cronológica usada por el histórico mensual. */
class RoutineArchivePresentationTest {
    @Test fun groupsImportsByYearAndMonthKeepingPrimaryFirst() {
        val september = YearMonth.of(2026, 9)
        val primary = routine("09_2026", september, "2026-09-01")
        val alternative = routine("09_2026_1", september, "2026-09-12")
        val august = routine("08_2025", YearMonth.of(2025, 8), "2025-08-01")
        val snapshot = MonthlyRoutineSnapshot(listOf(alternative, august, primary),
            mapOf(september to primary.id), YearMonth.of(2026, 10))

        assertEquals(listOf(2026, 2025), RoutineArchivePresentation.years(snapshot))
        val group = RoutineArchivePresentation.months(snapshot, 2026).single()
        assertEquals(primary.id, group.primary?.id)
        assertEquals(listOf(primary.id, alternative.id), group.routines.map { it.id })
        assertEquals(listOf(alternative.id), group.alternatives.map { it.id })
    }

    @Test fun preservesAGroupWithoutSelectedPrimary() {
        val month = YearMonth.of(2026, 7)
        val document = routine("07_2026", month, "2026-07-01")
        val snapshot = MonthlyRoutineSnapshot(listOf(document), emptyMap(), YearMonth.of(2026, 10))

        val group = RoutineArchivePresentation.months(snapshot, 2026).single()
        assertNull(group.primary)
        assertEquals(listOf(document.id), group.alternatives.map { it.id })
    }

    private fun routine(id: String, month: YearMonth, importedAt: String) =
        MonthlyRoutine(id, month, importedAt, RoutinePlan(emptyList(), month = month))
}
