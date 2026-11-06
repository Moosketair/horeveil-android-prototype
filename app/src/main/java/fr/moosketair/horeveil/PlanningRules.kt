package fr.moosketair.horeveil

import java.time.LocalDate

/** Pure helper for scheduling priorities, used by the UI and engine. */
object PlanningRules {
    fun isWorking(s: AppState, day: LocalDate): Boolean =
        !s.isLeave(day) && s.shiftOn(day) in setOf("MATIN","APRES_MIDI","NUIT")
    fun unresolvedWeek(s: AppState, monday: LocalDate): Boolean =
        (0L..4L).any { i ->
            val day = monday.plusDays(i)
            s.shiftOn(day) == "NON_DEFINI" && !s.isLeave(day)
        }
}
