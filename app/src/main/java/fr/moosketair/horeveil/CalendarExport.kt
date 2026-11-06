package fr.moosketair.horeveil

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class DeviceCalendar(val id: Long,val displayName: String)
data class ExportEvent(val key:String,val title:String,val start:Long,val end:Long,val allDay:Boolean)

object CalendarExport {
    fun calendars(context: Context): List<DeviceCalendar> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED) return emptyList()
        val results=mutableListOf<DeviceCalendar>()
        val cols=arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        try {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,cols,null,null,null)?.use { c ->
                while(c.moveToNext()) if(c.getInt(2)>=CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    results.add(DeviceCalendar(c.getLong(0),c.getString(1) ?: "Agenda"))
                }
            }
        } catch (_:Exception) {}
        return results
    }
    private fun entries(s:AppState): List<ExportEvent> {
        val zone=ZoneId.systemDefault()
        val begin=LocalDate.now()
        val end=begin.plusDays(120)
        val events=mutableListOf<ExportEvent>()
        for (leave in s.leaves) {
            try {
                val from=LocalDate.parse(leave.start)
                val to=LocalDate.parse(leave.end)
                if (to.isBefore(begin) || from.isAfter(end)) continue
                val start=from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val finish=to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                events.add(ExportEvent("leave:"+leave.id,leave.label,start,finish,true))
            } catch (_:Exception) {}
        }
        for (i in 0..120) {
            val day=begin.plusDays(i.toLong())
            if (s.isLeave(day)) continue
            val type=s.shiftOn(day)
            if (type !in listOf("MATIN","APRES_MIDI","NUIT")) continue
            val config=s.shifts[type] ?: continue
            val edit=s.edits[day.toString()]
            val start=runCatching { day.atTime(hhmm(edit?.start?.takeIf { it.isNotBlank() } ?: config.start)).atZone(zone) }.getOrNull() ?: continue
            var finish=runCatching { day.atTime(hhmm(edit?.end?.takeIf { it.isNotBlank() } ?: config.end)).atZone(zone) }.getOrNull() ?: continue
            if (!finish.isAfter(start)) finish=finish.plusDays(1)
            events.add(ExportEvent("shift:"+day,"Travail · "+shiftLabel(type),
                start.toInstant().toEpochMilli(),finish.toInstant().toEpochMilli(),false))
        }
        return events
    }
    @Synchronized fun sync(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)!=PackageManager.PERMISSION_GRANTED) return
        val store=AppStore.get(context)
        val state=store.state
        if (!state.exportEnabled || state.calendarId<0) return
        val desired=entries(state).associateBy { it.key }
        val ids=state.exportedIds.toMutableMap()
        val resolver=context.contentResolver
        for ((key,id) in ids.toMap()) {
            if (!desired.containsKey(key)) {
                try { resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),null,null) } catch (_:Exception) {}
                ids.remove(key)
            }
        }
        for ((key,e) in desired) {
            val values=ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID,state.calendarId)
                put(CalendarContract.Events.TITLE,e.title)
                put(CalendarContract.Events.DESCRIPTION,"Créé par Horéveil · "+key)
                put(CalendarContract.Events.DTSTART,e.start)
                put(CalendarContract.Events.DTEND,e.end)
                put(CalendarContract.Events.EVENT_TIMEZONE,if(e.allDay) "UTC" else ZoneId.systemDefault().id)
                put(CalendarContract.Events.ALL_DAY,if(e.allDay) 1 else 0)
            }
            try {
                val existing=ids[key]
                val changed=if(existing!=null) resolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,existing),values,null,null) else 0
                if(changed==0) {
                    val uri=resolver.insert(CalendarContract.Events.CONTENT_URI,values)
                    if(uri!=null) ids[key]=ContentUris.parseId(uri)
                }
            } catch (_:Exception) {}
        }
        store.saveExported(ids)
    }
    @Synchronized fun deleteExports(context:Context) {
        val store=AppStore.get(context)
        if(context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)!=PackageManager.PERMISSION_GRANTED) return
        store.state.exportedIds.values.forEach { id ->
            try { context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),null,null) }
            catch (_: Exception) {}
        }
        store.saveExported(emptyMap())
    }
}
