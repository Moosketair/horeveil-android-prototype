package fr.moosketair.horeveil

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.WeekFields
import java.util.Locale

/** One app-owned JSON snapshot; mutations are atomic at the state level. */
data class ShiftConfig(
    val start: String,
    val end: String,
    val leadMinutes: Int = 60,
    val enabled: Boolean = true
)
data class PersonalAlarm(
    val id: String,
    val label: String,
    val time: String,
    val days: Set<Int> = setOf(1,2,3,4,5,6,7),
    val enabled: Boolean = true,
    val parity: String = "ALL"
)
data class DayEdit(
    val type: String,
    val start: String = "",
    val end: String = "",
    val leadMinutes: Int = -1
)
data class Leave(val id: String, val start: String, val end: String, val label: String = "Congé")
data class AppState(
    val shifts: Map<String, ShiftConfig> = mapOf(
        "MATIN" to ShiftConfig("06:00", "14:00", 60),
        "APRES_MIDI" to ShiftConfig("14:00", "22:00", 90),
        "NUIT" to ShiftConfig("22:00", "06:00", 90)
    ),
    val weeks: Map<String, String> = emptyMap(),
    val edits: Map<String, DayEdit> = emptyMap(),
    val leaves: List<Leave> = emptyList(),
    val personal: List<PersonalAlarm> = emptyList(),
    val skipped: Set<String> = emptySet(),
    val offsets: Map<String, Int> = emptyMap(),
    val fired: Set<String> = emptySet(),
    val napAt: Long = 0,
    val snoozeAt: Long = 0,
    val snoozeMinutes: Int = 10,
    val rampMinutes: Int = 5,
    val remind: Boolean = true,
    val exportEnabled: Boolean = false,
    val calendarId: Long = -1,
    val exportedIds: Map<String, Long> = emptyMap()
)

fun LocalDate.weekMonday(): LocalDate = with(DayOfWeek.MONDAY)
fun LocalDate.isoWeek(): Int = get(WeekFields.ISO.weekOfWeekBasedYear())
fun AppState.isLeave(date: LocalDate): Boolean = leaves.any {
    runCatching { !date.isBefore(LocalDate.parse(it.start)) && !date.isAfter(LocalDate.parse(it.end)) }.getOrDefault(false)
}
fun AppState.shiftOn(date: LocalDate): String {
    val override = edits[date.toString()]
    if (override != null) return override.type
    if (date.dayOfWeek.value > 5) return "REPOS"
    return weeks[date.weekMonday().toString()] ?: "NON_DEFINI"
}
fun shiftLabel(key: String): String = when(key) {
    "MATIN" -> "Matin"
    "APRES_MIDI" -> "Après-midi"
    "NUIT" -> "Nuit"
    "REPOS" -> "Repos"
    "CONGE" -> "Congé"
    else -> "Non défini"
}
fun hhmm(s: String): LocalTime = LocalTime.parse(s)

class AppStore private constructor(private val app: Context) {
    companion object {
        @Volatile private var singleton: AppStore? = null
        fun get(context: Context): AppStore = singleton ?: synchronized(this) {
            singleton ?: AppStore(context.applicationContext).also { singleton = it }
        }
    }
    private val prefs = app.getSharedPreferences("horeveil", Context.MODE_PRIVATE)
    var state by mutableStateOf(read())
        private set

    @Synchronized fun update(sync: Boolean = true, transform: (AppState) -> AppState) {
        state = transform(state)
        prefs.edit().putString("state", encode(state).toString()).apply()
        AlarmScheduler.refresh(app)
        WidgetUpdates.refresh(app)
        if (sync && state.exportEnabled && state.calendarId >= 0) {
            Thread { CalendarExport.sync(app) }.start()
        }
    }

    @Synchronized fun saveExported(ids: Map<String, Long>) {
        state = state.copy(exportedIds = ids)
        prefs.edit().putString("state", encode(state).toString()).apply()
    }

    private fun read(): AppState = try {
        val raw = prefs.getString("state", null)
        if (raw.isNullOrBlank()) AppState() else decode(JSONObject(raw))
    } catch (_: Exception) { AppState() }
}

private fun JSONObject.string(key: String, fallback: String = "") = optString(key, fallback)
private fun JSONObject.strMap(key: String): Map<String, String> {
    val obj = optJSONObject(key) ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.optString(it) }
}
private fun JSONObject.intMap(key: String): Map<String, Int> {
    val obj = optJSONObject(key) ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.optInt(it) }
}
private fun JSONObject.longMap(key: String): Map<String, Long> {
    val obj = optJSONObject(key) ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.optLong(it) }
}
private fun JSONArray.strings(): Set<String> = (0 until length()).map { optString(it) }.toSet()
private fun <T> JSONArray.readObjects(mapper: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it)?.let(mapper) }
private fun <T> Iterable<T>.jsonArray(mapper: (T) -> Any): JSONArray =
    JSONArray().also { a -> forEach { a.put(mapper(it)) } }

private fun encode(s: AppState) = JSONObject().apply {
    put("shifts", JSONObject().apply { s.shifts.forEach { (k,v) ->
        put(k, JSONObject().put("start",v.start).put("end",v.end).put("lead",v.leadMinutes).put("enabled",v.enabled))
    } })
    put("weeks", JSONObject(s.weeks))
    put("edits", JSONObject().apply { s.edits.forEach { (k,v) ->
        put(k,JSONObject().put("type",v.type).put("start",v.start).put("end",v.end).put("lead",v.leadMinutes))
    } })
    put("leaves",s.leaves.jsonArray { JSONObject().put("id",it.id).put("start",it.start).put("end",it.end).put("label",it.label) })
    put("personal",s.personal.jsonArray { JSONObject().put("id",it.id).put("label",it.label)
        .put("time",it.time).put("days",JSONArray(it.days.toList())).put("enabled",it.enabled).put("parity",it.parity) })
    put("skipped",JSONArray(s.skipped.toList()))
    put("offsets",JSONObject(s.offsets))
    put("fired",JSONArray(s.fired.toList()))
    put("napAt",s.napAt)
    put("snoozeAt",s.snoozeAt)
    put("snoozeMinutes",s.snoozeMinutes)
    put("rampMinutes",s.rampMinutes)
    put("remind",s.remind)
    put("exportEnabled",s.exportEnabled)
    put("calendarId",s.calendarId)
    put("exportedIds",JSONObject(s.exportedIds))
}
private fun decode(o: JSONObject): AppState {
    val defaults = AppState()
    val shifts = defaults.shifts.toMutableMap()
    o.optJSONObject("shifts")?.let { obj ->
        obj.keys().forEach { key -> obj.optJSONObject(key)?.let { v ->
            shifts[key] = ShiftConfig(v.string("start"),v.string("end"),v.optInt("lead",60),v.optBoolean("enabled",true))
        } }
    }
    val edits = mutableMapOf<String,DayEdit>()
    o.optJSONObject("edits")?.let { obj -> obj.keys().forEach { key ->
        obj.optJSONObject(key)?.let { v -> edits[key] = DayEdit(v.string("type"),v.string("start"),v.string("end"),v.optInt("lead",-1)) }
    } }
    return AppState(
        shifts = shifts, weeks = o.strMap("weeks"), edits = edits,
        leaves = (o.optJSONArray("leaves") ?: JSONArray()).readObjects { Leave(it.string("id"),it.string("start"),it.string("end"),it.string("label","Congé")) },
        personal = (o.optJSONArray("personal") ?: JSONArray()).readObjects {
            val a = it.optJSONArray("days") ?: JSONArray()
            PersonalAlarm(it.string("id"),it.string("label"),it.string("time"),
                (0 until a.length()).map { i -> a.optInt(i) }.toSet(),it.optBoolean("enabled",true),it.string("parity","ALL"))
        },
        skipped = (o.optJSONArray("skipped") ?: JSONArray()).strings(),
        offsets = o.intMap("offsets"), fired = (o.optJSONArray("fired") ?: JSONArray()).strings(),
        napAt = o.optLong("napAt"), snoozeAt = o.optLong("snoozeAt"),
        snoozeMinutes = o.optInt("snoozeMinutes",10), rampMinutes = o.optInt("rampMinutes",5),
        remind = o.optBoolean("remind",true), exportEnabled = o.optBoolean("exportEnabled"),
        calendarId = o.optLong("calendarId",-1), exportedIds = o.longMap("exportedIds")
    )
}
