package fr.moosketair.horeveil

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val Teal=Color(0xFF008D98)
private val Navy=Color(0xFF12435F)
private val SoftBlue=Color(0xFFE5F1FA)
private val French=Locale.FRANCE
private val dateFmt=DateTimeFormatter.ofPattern("EEE d MMM",French)
private val tabs=listOf("Réveils","Planning","Sieste","Réglages")

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        RingingService.channels(this)
        AlarmScheduler.refresh(this)
        setContent { HoreveilApp() }
    }
    override fun onResume() {
        super.onResume()
        AlarmScheduler.refresh(this)
        WidgetUpdates.refresh(this)
    }
}

@Composable private fun HoreveilApp() {
    val context=LocalContext.current
    val store=remember { AppStore.get(context) }
    val state=store.state
    var tab by remember { mutableIntStateOf(0) }
    var notificationsGranted by remember { mutableStateOf(
        Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    ) }
    var calendarsGranted by remember { mutableStateOf(
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED
    ) }
    val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted=it
    }
    val calendarLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendarsGranted=context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED
    }
    val dark=isSystemInDarkTheme()
    val colors=if(Build.VERSION.SDK_INT>=31) {
        if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if(dark) darkColorScheme(primary=Color(0xFF70D5DE),secondary=Color(0xFFA2CADA))
        else lightColorScheme(primary=Teal,secondary=Navy,primaryContainer=SoftBlue)
    MaterialTheme(colorScheme=colors) {
        Scaffold(
            topBar={ Surface(tonalElevation=2.dp) { Row(
                Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Alarm,contentDescription=null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Horéveil",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick={tab=3}) { Icon(Icons.Default.MoreVert,contentDescription="Réglages") }
            } } },
            bottomBar={ NavigationBar {
                tabs.forEachIndexed { i,name ->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},label={Text(name)},
                        icon={ Icon(when(i) {
                            0->Icons.Default.Alarm
                            1->Icons.Default.CalendarMonth
                            2->Icons.Default.Bedtime
                            else->Icons.Default.Settings
                        },contentDescription=name) })
                }
            } }
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                when(tab) {
                    0->AlarmsPage(state,store)
                    1->PlanningPage(state,store)
                    2->NapPage(state,store)
                    else->SettingsPage(state,store,notificationsGranted,calendarsGranted,
                        onNotifications={ if(Build.VERSION.SDK_INT>=33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onCalendars={ calendarLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) })
                }
            }
        }
    }
}
@Composable private fun PageColumn(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
}
@Composable private fun Pane(title:String,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(title.isNotEmpty()) Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            content()
        }
    }
}
@Composable private fun PrimaryButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier,shape=RoundedCornerShape(15.dp)) { Text(text) }
}
private fun nextDate(millis:Long)=runCatching {
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFmt)
}.getOrDefault("")

@Composable private fun AlarmsPage(s:AppState,store:AppStore) {
    val next=PlanningEngine.next(s)
    var delay by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf<String?>(null) }
    var addPersonal by remember { mutableStateOf(false) }
    PageColumn {
        Text("Prochain réveil",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=Navy)) {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Navy,Teal))).padding(22.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(next?.let { java.time.Instant.ofEpochMilli(it.targetAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—:—",
                    color=Color.White,fontSize=48.sp,fontWeight=FontWeight.Bold)
                Text(next?.let { it.title+" · "+nextDate(it.targetAt) } ?: "Aucune alarme programmée",
                    color=Color.White.copy(alpha=.9f))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={delay=true},enabled=next!=null,
                        colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text("Décaler") }
                    OutlinedButton(onClick={confirmSkip=true},enabled=next!=null,
                        colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text("Sauter") }
                }
            }
        }
        Text("Mes réveils de travail",style=MaterialTheme.typography.titleLarge)
        listOf("MATIN","APRES_MIDI","NUIT").forEach { type ->
            val config=s.shifts[type] ?: return@forEach
            Pane("") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(type=="NUIT") Icons.Default.NightsStay else Icons.Default.Work,
                        contentDescription=null,tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { editKey=type }) {
                        Text("Travail · "+shiftLabel(type),fontWeight=FontWeight.Bold)
                        Text("Selon planning · "+config.start+" à "+config.end,
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Réveil "+config.leadMinutes+" min avant",style=MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked=config.enabled,onCheckedChange={ on ->
                        store.update { it.copy(shifts=it.shifts+(type to config.copy(enabled=on))) }
                    })
                }
            }
        }
        Text("Mes alarmes personnelles",style=MaterialTheme.typography.titleLarge)
        s.personal.forEach { a ->
            Pane("") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Alarm,contentDescription=null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).clickable {editKey=a.id}) {
                        Text(a.label,fontWeight=FontWeight.Bold)
                        Text(a.time+" · "+(if(a.days.size==7) "Tous les jours" else "Jours sélectionnés"),
                            color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked=a.enabled,onCheckedChange={ value ->
                        store.update { it.copy(personal=it.personal.map { item -> if(item.id==a.id) item.copy(enabled=value) else item }) }
                    })
                }
            }
        }
        PrimaryButton("+ Ajouter une alarme",onClick={addPersonal=true},Modifier.fillMaxWidth())
        Text("Seules les alarmes Travail suivent le planning et les congés.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(delay && next!=null) AlertDialog(onDismissRequest={delay=false},title={Text("Décaler uniquement ce réveil")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(10,30,60,120,180).forEach { minutes ->
                TextButton(onClick={
                    store.update { s -> when(next.key) {
                        "nap"->s.copy(napAt=s.napAt+minutes*60_000L)
                        "snooze"->s.copy(snoozeAt=s.snoozeAt+minutes*60_000L)
                        else->s.copy(offsets=s.offsets+(next.key to ((s.offsets[next.key]?:0)+minutes)))
                    } }
                    delay=false
                }) { Text("+"+minutes+" min") }
            }
        }},confirmButton={TextButton(onClick={delay=false}){Text("Fermer")}})
    if(confirmSkip && next!=null) AlertDialog(onDismissRequest={confirmSkip=false},
        title={Text("Sauter ce réveil ?")},text={Text("La programmation habituelle sera conservée.")},
        confirmButton={TextButton(onClick={
            store.update { s -> when(next.key) {
                "nap"->s.copy(napAt=0)
                "snooze"->s.copy(snoozeAt=0)
                else->s.copy(skipped=s.skipped+next.key)
            } }
            confirmSkip=false
        }){Text("Sauter")}},dismissButton={TextButton(onClick={confirmSkip=false}){Text("Annuler")}})
    if(editKey!=null) {
        val type=editKey!!
        if(type in listOf("MATIN","APRES_MIDI","NUIT")) {
            s.shifts[type]?.let { cfg -> ShiftDialog(type,cfg,
                onClose={editKey=null},onSave={value->
                    store.update { it.copy(shifts=it.shifts+(type to value)) }
                    editKey=null
                })
            }
        } else {
            s.personal.find{it.id==type}?.let { a ->
                PersonalDialog(a,onClose={editKey=null},onSave={updated ->
                    store.update { it.copy(personal=it.personal.map { old -> if(old.id==a.id) updated else old }) }
                    editKey=null
                },onDelete={
                    store.update { it.copy(personal=it.personal.filterNot { p -> p.id==a.id }) }
                    editKey=null
                })
            }
        }
    }
    if(addPersonal) PersonalDialog(null,onClose={addPersonal=false},onSave={a->
        store.update { it.copy(personal=it.personal+a) }; addPersonal=false
    })
}

@Composable private fun ShiftDialog(type:String,cfg:ShiftConfig,onClose:()->Unit,onSave:(ShiftConfig)->Unit) {
    var start by remember(type){mutableStateOf(cfg.start)}
    var end by remember(type){mutableStateOf(cfg.end)}
    var lead by remember(type){mutableStateOf(cfg.leadMinutes.toString())}
    val valid=runCatching { hhmm(start);hhmm(end);lead.toInt() in 0..720 }.getOrDefault(false)
    AlertDialog(onDismissRequest=onClose,title={Text("Poste "+shiftLabel(type))},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(start,{start=it},label={Text("Début (HH:mm)")},singleLine=true)
            OutlinedTextField(end,{end=it},label={Text("Fin (HH:mm)")},singleLine=true)
            OutlinedTextField(lead,{lead=it},label={Text("Minutes avant le poste")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Text("L'heure de réveil s'adaptera automatiquement aux changements de poste.",
                style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(enabled=valid,onClick={
            onSave(cfg.copy(start=start,end=end,leadMinutes=lead.toInt()))
        }){Text("Enregistrer")}},dismissButton={TextButton(onClick=onClose){Text("Annuler")}})
}

@Composable private fun PersonalDialog(a:PersonalAlarm?,onClose:()->Unit,onSave:(PersonalAlarm)->Unit,onDelete:(()->Unit)?=null) {
    var label by remember(a?.id){mutableStateOf(a?.label ?: "")}
    var time by remember(a?.id){mutableStateOf(a?.time ?: "07:00")}
    var days by remember(a?.id){mutableStateOf(a?.days ?: setOf(1,2,3,4,5,6,7))}
    var parity by remember(a?.id){mutableStateOf(a?.parity ?: "ALL")}
    val valid=label.isNotBlank() && days.isNotEmpty() && runCatching{hhmm(time)}.isSuccess
    AlertDialog(onDismissRequest=onClose,title={Text(if(a==null)"Nouvelle alarme" else "Modifier l'alarme")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label,{label=it},label={Text("Nom")},singleLine=true)
            OutlinedTextField(time,{time=it},label={Text("Heure HH:mm")},singleLine=true)
            Text("Jours de répétition")
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("L","M","M","J","V","S","D").forEachIndexed { i,v ->
                    FilterChip(selected=i+1 in days,onClick={ days=if(i+1 in days) days-(i+1) else days+(i+1) },
                        label={Text(v)},modifier=Modifier.weight(1f))
                }
            }
            Text("Semaines")
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("ALL" to "Toutes","PAIR" to "Paires","IMPAIR" to "Impaires").forEach { (key,value) ->
                    FilterChip(selected=parity==key,onClick={parity=key},label={Text(value)})
                }
            }
            if(onDelete!=null) TextButton(onClick=onDelete){Text("Supprimer l'alarme",color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=valid,onClick={
            onSave(PersonalAlarm(a?.id ?: UUID.randomUUID().toString(),label,time,days,a?.enabled?:true,parity))
        }){Text("Enregistrer")}},dismissButton={TextButton(onClick=onClose){Text("Annuler")}})
}

@Composable private fun PlanningPage(s:AppState,store:AppStore) {
    var monday by remember {mutableStateOf(LocalDate.now().weekMonday())}
    var monthMode by remember {mutableStateOf(false)}
    var selectedDay by remember {mutableStateOf<LocalDate?>(null)}
    var leaveDialog by remember {mutableStateOf(false)}
    var selectedShift by remember(monday,s.weeks){mutableStateOf(s.weeks[monday.toString()] ?: "NON_DEFINI")}
    PageColumn {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Planning intelligent",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            TextButton(onClick={monthMode=!monthMode}) { Text(if(monthMode)"Semaine" else "Mois") }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={monday=if(monthMode) monday.minusWeeks(4) else monday.minusWeeks(1)}) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription="Précédent")
            }
            Text(monday.format(DateTimeFormatter.ofPattern("MMMM yyyy",French)).replaceFirstChar{it.uppercase()},
                style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            IconButton(onClick={monday=if(monthMode) monday.plusWeeks(4) else monday.plusWeeks(1)}) {
                Icon(Icons.Default.ChevronRight,contentDescription="Suivant")
            }
        }
        Pane("Affecter un poste à la semaine") {
            Text("Semaine du "+monday.format(dateFmt)+" · n°"+monday.isoWeek(),
                style=MaterialTheme.typography.bodyMedium)
            ShiftChoices(selectedShift){ choice ->
                selectedShift=choice
                store.update { it.copy(weeks=it.weeks+(monday.toString() to choice)) }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick={
                    store.update { s -> s.copy(weeks=s.weeks+ (0L..5L).associate { i ->
                        monday.plusWeeks(i).toString() to selectedShift
                    }) }
                },enabled=selectedShift!="NON_DEFINI",modifier=Modifier.weight(1f)) { Text("6 semaines") }
                OutlinedButton(onClick={
                    store.update { s -> s.copy(weeks=s.weeks+ (0L..11L).associate { i ->
                        monday.plusWeeks(i).toString() to selectedShift
                    }) }
                },enabled=selectedShift!="NON_DEFINI",modifier=Modifier.weight(1f)) { Text("12 semaines") }
            }
            TextButton(onClick={
                val prev=s.weeks[monday.minusWeeks(1).toString()] ?: return@TextButton
                store.update { it.copy(weeks=it.weeks+(monday.toString() to prev)) }
            }) { Text("Copier la semaine précédente") }
        }
        if(monthMode) {
            Pane("Vue mensuelle · toucher un jour") {
                val month=monday.withDayOfMonth(1)
                val offset=month.dayOfWeek.value-1
                val length=month.lengthOfMonth()
                val cells=(offset+length+6)/7*7
                (0 until cells step 7).forEach { start ->
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        (0..6).forEach { col ->
                            val day=start+col-offset+1
                            if(day in 1..length) {
                                val date=month.withDayOfMonth(day)
                                val type=if(s.isLeave(date)) "CONGE" else s.shiftOn(date)
                                Box(Modifier.weight(1f)) {
                                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable{selectedDay=date}.padding(vertical=10.dp),
                                        horizontalAlignment=Alignment.CenterHorizontally) {
                                        Text(day.toString(),fontWeight=FontWeight.Medium)
                                        Text(when(type){"MATIN"->"M";"APRES_MIDI"->"A";"NUIT"->"N";"REPOS"->"R";"CONGE"->"C";else->"·"},
                                            color=shiftColor(type),fontWeight=FontWeight.Bold)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Pane("Journées et alarmes") {
                (0L..6L).forEach { i ->
                    val day=monday.plusDays(i)
                    val type=if(s.isLeave(day))"CONGE" else s.shiftOn(day)
                    val preview=PlanningEngine.upcoming(s,
                        day.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),4)
                        .firstOrNull { it.key=="work:"+day }
                    Row(Modifier.fillMaxWidth().clickable{selectedDay=day}.padding(vertical=8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.width(64.dp)) {
                            Text(day.format(DateTimeFormatter.ofPattern("EEE",French)).replaceFirstChar{it.uppercase()},
                                style=MaterialTheme.typography.labelLarge)
                            Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(shiftLabel(type),color=shiftColor(type),fontWeight=FontWeight.Bold)
                            Text(preview?.let { "Réveil "+java.time.Instant.ofEpochMilli(it.targetAt)
                                .atZone(ZoneId.systemDefault()).toLocalTime().toString() }
                                ?: if(type=="NON_DEFINI")"Poste à renseigner" else "Pas de réveil de travail",
                                style=MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ChevronRight,contentDescription="Modifier")
                    }
                    if(i<6) HorizontalDivider()
                }
            }
        }
        PrimaryButton("+ Ajouter des congés ou vacances",onClick={leaveDialog=true},Modifier.fillMaxWidth())
        if(s.leaves.isNotEmpty()) Pane("Congés enregistrés") {
            s.leaves.sortedBy { it.start }.forEach { leave ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(leave.label,fontWeight=FontWeight.Medium)
                        Text(leave.start+" → "+leave.end,style=MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick={ store.update { it.copy(leaves=it.leaves.filterNot { v -> v.id==leave.id }) } }) {
                        Icon(Icons.Default.Delete,contentDescription="Supprimer")
                    }
                }
            }
        }
        val nextWeek=monday.plusWeeks(1)
        if(s.weeks[nextWeek.toString()]==null) {
            Pane("Planning à compléter") {
                Text("La semaine du "+nextWeek.format(dateFmt)+" n'a pas de poste global configuré.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Un rappel peut être envoyé chaque vendredi à 18h.",
                    style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={monday=nextWeek}) { Text("Configurer la semaine") }
            }
        }
    }
    if(selectedDay!=null) DayDialog(selectedDay!!,s,onClose={selectedDay=null},
        onSave={ edit ->
            val key=selectedDay!!.toString()
            store.update { it.copy(edits=it.edits+(key to edit)) }
            selectedDay=null
        },onClear={
            val key=selectedDay!!.toString()
            store.update { it.copy(edits=it.edits-key) };selectedDay=null
        })
    if(leaveDialog) LeaveDialog(onClose={leaveDialog=false},onSave={ leave ->
        store.update { it.copy(leaves=it.leaves+leave) };leaveDialog=false
    })
}
@Composable private fun ShiftChoices(value:String,onChoose:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf(listOf("MATIN","APRES_MIDI","NUIT"),listOf("REPOS","NON_DEFINI")).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    FilterChip(selected=value==key,onClick={onChoose(key)},label={Text(shiftLabel(key))})
                }
            }
        }
    }
}
private fun shiftColor(type:String):Color = when(type) {
    "MATIN"->Color(0xFF3266BE)
    "APRES_MIDI"->Color(0xFFD18B20)
    "NUIT"->Color(0xFF9268D2)
    "REPOS"->Color(0xFF228A50)
    "CONGE"->Color(0xFF008C94)
    else->Color.Gray
}
@Composable private fun DayDialog(day:LocalDate,s:AppState,onClose:()->Unit,onSave:(DayEdit)->Unit,onClear:()->Unit) {
    val existing=s.edits[day.toString()]
    var type by remember(day){mutableStateOf(existing?.type ?: s.shiftOn(day))}
    val cfg=s.shifts[type]
    var start by remember(day){mutableStateOf(existing?.start?.takeIf{it.isNotBlank()} ?: cfg?.start ?: "")}
    var end by remember(day){mutableStateOf(existing?.end?.takeIf{it.isNotBlank()} ?: cfg?.end ?: "")}
    var lead by remember(day){mutableStateOf((existing?.leadMinutes?.takeIf{it>=0} ?: cfg?.leadMinutes ?: 60).toString())}
    val work=type in listOf("MATIN","APRES_MIDI","NUIT")
    val valid=!work || (runCatching {hhmm(start);hhmm(end);lead.toInt() in 0..720}.getOrDefault(false))
    AlertDialog(onDismissRequest=onClose,title={Text(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM",French)))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Modifier uniquement cette journée")
            ShiftChoices(type) { value ->
                type=value
                s.shifts[value]?.let { shift ->
                    start=shift.start;end=shift.end;lead=shift.leadMinutes.toString()
                }
            }
            if(work) {
                OutlinedTextField(start,{start=it},label={Text("Début HH:mm")},singleLine=true)
                OutlinedTextField(end,{end=it},label={Text("Fin HH:mm")},singleLine=true)
                OutlinedTextField(lead,{lead=it},label={Text("Minutes avant le poste")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            if(s.isLeave(day)) Text("Ce jour est couvert par un congé : aucune alarme Travail ne sonnera.",
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(existing!=null) TextButton(onClick=onClear){Text("Supprimer l'exception")}
        }},confirmButton={TextButton(enabled=valid,onClick={
            onSave(DayEdit(type,if(work)start else "",if(work)end else "",if(work)lead.toInt() else -1))
        }){Text("Enregistrer")}},dismissButton={TextButton(onClick=onClose){Text("Annuler")}})
}
@Composable private fun LeaveDialog(onClose:()->Unit,onSave:(Leave)->Unit) {
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var title by remember { mutableStateOf("Congé") }
    val valid=runCatching { !LocalDate.parse(end).isBefore(LocalDate.parse(start)) }.getOrDefault(false)
    AlertDialog(onDismissRequest=onClose,title={Text("Ajouter une absence")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title,{title=it},label={Text("Motif (vacances, RTT, repos...)")},singleLine=true)
            OutlinedTextField(start,{start=it},label={Text("Du (AAAA-MM-JJ)")},singleLine=true)
            OutlinedTextField(end,{end=it},label={Text("Au (AAAA-MM-JJ)")},singleLine=true)
            Text("Les réveils Travail seront suspendus pendant cette période.",
                style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(enabled=valid,onClick={
            onSave(Leave(UUID.randomUUID().toString(),start,end,title.ifBlank{"Congé"}))
        }){Text("Ajouter")}},dismissButton={TextButton(onClick=onClose){Text("Annuler")}})
}

@Composable private fun NapPage(s:AppState,store:AppStore) {
    var duration by remember { mutableIntStateOf(20) }
    PageColumn {
        Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=Navy)) {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Navy,Teal))).padding(22.dp)) {
                Text("Mode sieste",style=MaterialTheme.typography.headlineMedium,
                    fontWeight=FontWeight.Bold,color=Color.White)
                Text("Un réveil rapide sans toucher à tes alarmes habituelles.",
                    color=Color.White.copy(alpha=.88f))
            }
        }
        Pane("Durée de la sieste") {
            listOf(listOf(10,15,20,30),listOf(45,60,90)).forEach { row ->
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    row.forEach { n -> FilterChip(selected=duration==n,onClick={duration=n},label={Text("$n min")}) }
                }
            }
        }
        Pane("Réveil prévu") {
            if(s.napAt>System.currentTimeMillis()) {
                Text("Sieste en cours",color=MaterialTheme.colorScheme.primary)
                Text(PlanningEngine.formatDate(s.napAt),style=MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick={store.update { it.copy(napAt=0) }}) { Text("Annuler la sieste") }
            } else {
                Text("Dans "+duration+" minutes",style=MaterialTheme.typography.titleLarge)
                Text("Sonnera à "+PlanningEngine.formatDate(System.currentTimeMillis()+duration*60_000L))
                PrimaryButton("Démarrer la sieste",onClick={
                    store.update {it.copy(napAt=System.currentTimeMillis()+duration*60_000L)}
                },Modifier.fillMaxWidth())
            }
        }
        Text("Les widgets permettent aussi de lancer directement une sieste de 20 ou 30 minutes.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsPage(s:AppState,store:AppStore,
    notificationsGranted:Boolean,calendarsGranted:Boolean,onNotifications:()->Unit,onCalendars:()->Unit) {
    val context=LocalContext.current
    val am=remember { context.getSystemService(AlarmManager::class.java) }
    val exact=Build.VERSION.SDK_INT<31 || am.canScheduleExactAlarms()
    val calendars=remember(calendarsGranted,s.calendarId) { if(calendarsGranted) CalendarExport.calendars(context) else emptyList() }
    PageColumn {
        Text("Réglages",style=MaterialTheme.typography.headlineSmall)
        Pane("Réveil progressif") {
            Text("Durée de montée du volume : "+s.rampMinutes+" min")
            Slider(value=s.rampMinutes.toFloat(),onValueChange={ value ->
                store.update(sync=false) { it.copy(rampMinutes=value.roundToInt()) }
            },valueRange=0f..30f,steps=5)
            Text("La sonnerie commence doucement avant l'heure affichée.",
                style=MaterialTheme.typography.bodySmall)
        }
        Pane("Répétition et notifications") {
            Text("Répéter l'alarme après : "+s.snoozeMinutes+" min")
            Slider(value=s.snoozeMinutes.toFloat(),onValueChange={ value ->
                store.update(sync=false) { it.copy(snoozeMinutes=value.roundToInt().coerceAtLeast(1)) }
            },valueRange=1f..30f,steps=28)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Rappel de planning vendredi 18h",modifier=Modifier.weight(1f))
                Switch(checked=s.remind,onCheckedChange={ value -> store.update {it.copy(remind=value)} })
            }
            if(!exact) {
                Text("Alarmes exactes non autorisées : les alarmes ne pourront pas être garanties !",
                    color=MaterialTheme.colorScheme.error)
                Button(onClick={
                    if(Build.VERSION.SDK_INT>=31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }) {Text("Autoriser les alarmes exactes")}
            } else Text("✓ Alarmes exactes autorisées",color=Color(0xFF218A5C))
            if(!notificationsGranted && Build.VERSION.SDK_INT>=33) {
                Button(onClick=onNotifications){Text("Autoriser les notifications")}
            }
        }
        Pane("Calendrier Android / Google") {
            Text("Exporter automatiquement les congés et postes de travail vers l'agenda de ton choix.",
                style=MaterialTheme.typography.bodyMedium)
            if(!calendarsGranted) {
                OutlinedButton(onClick=onCalendars){Text("Autoriser l'accès aux calendriers")}
            } else {
                if(calendars.isEmpty()) Text("Aucun calendrier accessible en écriture.")
                calendars.forEach { calendar ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if(s.calendarId!=calendar.id) {
                            CalendarExport.deleteExports(context)
                            store.update(sync=false) { it.copy(calendarId=calendar.id,exportedIds=emptyMap()) }
                        }
                    }.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=s.calendarId==calendar.id,onClick={
                            if(s.calendarId!=calendar.id) {
                                CalendarExport.deleteExports(context)
                                store.update(sync=false){ it.copy(calendarId=calendar.id,exportedIds=emptyMap()) }
                            }
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(calendar.displayName)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Synchronisation automatique",modifier=Modifier.weight(1f))
                    Switch(checked=s.exportEnabled,onCheckedChange={ enabled ->
                        if(!enabled) CalendarExport.deleteExports(context)
                        store.update(sync=false){it.copy(exportEnabled=enabled)}
                        if(enabled) Thread { CalendarExport.sync(context) }.start()
                    },enabled=s.calendarId>=0)
                }
                OutlinedButton(onClick={Thread{CalendarExport.sync(context)}.start()},enabled=s.calendarId>=0 && s.exportEnabled) {
                    Text("Synchroniser maintenant")
                }
                Text("L'application ne modifie que les événements qu'elle a créés. La synchronisation dépend du fournisseur du calendrier.",
                    style=MaterialTheme.typography.bodySmall)
            }
        }
        Pane("À propos") {
            Text("Horéveil 0.1.0 · Version initiale")
            Text("Le planning et les alarmes restent enregistrés sur ce téléphone.",
                style=MaterialTheme.typography.bodySmall)
        }
    }
}
