package fr.moosketair.horeveil

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class WakeEvent(
    val key: String,
    val title: String,
    val targetAt: Long,
    val rampMinutes: Int,
    val category: String
) {
    val startsAt: Long get() = targetAt - rampMinutes * 60_000L
}
object PlanningEngine {
    fun upcoming(s: AppState, now: Long = System.currentTimeMillis(), days: Long = 100): List<WakeEvent> {
        val zone = ZoneId.systemDefault()
        val first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
        val results = mutableListOf<WakeEvent>()
        for (n in 0..days) {
            val date = first.plusDays(n)
            val shift = s.shiftOn(date)
            if (!s.isLeave(date) && shift in listOf("MATIN","APRES_MIDI","NUIT")) {
                val cfg = s.shifts[shift]
                if (cfg != null && cfg.enabled) {
                    val edit = s.edits[date.toString()]
                    val begin = edit?.start?.takeIf { it.isNotBlank() } ?: cfg.start
                    val lead = edit?.leadMinutes?.takeIf { it >= 0 } ?: cfg.leadMinutes
                    val alarm = runCatching {
                        date.atTime(hhmm(begin)).minusMinutes(lead.toLong()).atZone(zone).toInstant().toEpochMilli()
                    }.getOrNull()
                    if (alarm != null) {
                        val key = "work:" + date
                        val target = alarm + (s.offsets[key] ?: 0) * 60_000L
                        if (target > now && key !in s.skipped && key !in s.fired) {
                            results.add(WakeEvent(key,"Travail · " + shiftLabel(shift),target,s.rampMinutes,"Travail"))
                        }
                    }
                }
            }
            for (a in s.personal) {
                if (!a.enabled || date.dayOfWeek.value !in a.days) continue
                if (a.parity == "PAIR" && date.isoWeek() % 2 != 0) continue
                if (a.parity == "IMPAIR" && date.isoWeek() % 2 == 0) continue
                val nominal = runCatching {
                    date.atTime(hhmm(a.time)).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull() ?: continue
                val key = "personal:" + a.id + ":" + date
                val target = nominal + (s.offsets[key] ?: 0) * 60_000L
                if (target > now && key !in s.skipped && key !in s.fired) {
                    results.add(WakeEvent(key,a.label,target,s.rampMinutes,"Personnel"))
                }
            }
        }
        if (s.napAt > now) results.add(WakeEvent("nap","Sieste",s.napAt,0,"Sieste"))
        if (s.snoozeAt > now) results.add(WakeEvent("snooze","Rappel du réveil",s.snoozeAt,0,"Rappel"))
        return results.sortedWith(compareBy<WakeEvent> { it.startsAt }.thenBy { it.targetAt })
    }
    fun next(s: AppState, now: Long = System.currentTimeMillis()): WakeEvent? =
        upcoming(s,now).firstOrNull()
    fun formatDate(millis: Long): String = java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM · HH:mm")
        .withLocale(java.util.Locale.FRANCE).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

object AlarmScheduler {
    const val FIRE = "fr.moosketair.horeveil.FIRE"
    const val REMIND = "fr.moosketair.horeveil.REMIND"
    const val NEXT_DELAY = "fr.moosketair.horeveil.DELAY"
    const val NEXT_SKIP = "fr.moosketair.horeveil.SKIP"
    const val NAP20 = "fr.moosketair.horeveil.NAP20"
    const val NAP30 = "fr.moosketair.horeveil.NAP30"
    const val SNOOZE = "fr.moosketair.horeveil.SNOOZE"
    const val DISMISS = "fr.moosketair.horeveil.DISMISS"

    fun action(context: Context, action: String, code: Int, extras: (Intent.() -> Unit)? = null): PendingIntent {
        val intent = Intent(context,AlarmReceiver::class.java).setAction(action)
        extras?.invoke(intent)
        return PendingIntent.getBroadcast(context,code,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun refresh(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = action(context,FIRE,100)
        am.cancel(fire)
        val store = AppStore.get(context).state
        val next = PlanningEngine.next(store)
        if (next != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())) {
            val intent = Intent(context,AlarmReceiver::class.java).setAction(FIRE)
                .putExtra("key",next.key).putExtra("title",next.title)
                .putExtra("target",next.targetAt).putExtra("ramp",next.rampMinutes)
            val pending = PendingIntent.getBroadcast(context,100,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val show = PendingIntent.getActivity(context,101,Intent(context,MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(next.startsAt,System.currentTimeMillis()+1000),show),pending)
            } catch (_: SecurityException) { /* Settings screen explains missing capability */ }
        }
        scheduleReminder(context,am,store)
    }
    private fun scheduleReminder(context: Context,am: AlarmManager,s: AppState) {
        val pi = action(context,REMIND,200)
        am.cancel(pi)
        if (!s.remind) return
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var fri = now.with(java.time.DayOfWeek.FRIDAY).withHour(18).withMinute(0).withSecond(0).withNano(0)
        if (!fri.isAfter(now)) fri = fri.plusWeeks(1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,fri.toInstant().toEpochMilli(),pi)
    }
}
