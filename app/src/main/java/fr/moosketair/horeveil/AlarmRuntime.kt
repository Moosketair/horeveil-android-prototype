package fr.moosketair.horeveil

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.refresh(context)
        WidgetUpdates.refresh(context)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AppStore.get(context)
        when(intent.action) {
            AlarmScheduler.FIRE -> {
                val key = intent.getStringExtra("key") ?: return
                // Ignore a stale broadcast from an alarm that was edited or skipped.
                val expected = PlanningEngine.next(store.state)
                val target = intent.getLongExtra("target",0)
                if (expected?.key != key || expected.targetAt != target) return
                store.update { s ->
                    when(key) {
                        "nap" -> s.copy(napAt=0)
                        "snooze" -> s.copy(snoozeAt=0)
                        else -> s.copy(fired=(s.fired + key).toList().takeLast(250).toSet())
                    }
                }
                val ring = Intent(context,RingingService::class.java)
                    .putExtra("title",intent.getStringExtra("title") ?: "Horéveil")
                    .putExtra("target",target)
                    .putExtra("ramp",intent.getIntExtra("ramp",0))
                try {
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(ring)
                    else context.startService(ring)
                } catch (_: Exception) {
                    // Notification fallback if the OS denies a foreground-service launch.
                    RingingService.notifyFallback(context)
                }
            }
            AlarmScheduler.NEXT_DELAY -> {
                val next = PlanningEngine.next(store.state) ?: return
                if (next.key == "nap" || next.key == "snooze") {
                    store.update { s -> if (next.key == "nap") s.copy(napAt=s.napAt+30*60_000L)
                        else s.copy(snoozeAt=s.snoozeAt+30*60_000L) }
                } else store.update { s -> s.copy(offsets=s.offsets + (next.key to ((s.offsets[next.key] ?: 0)+30))) }
            }
            AlarmScheduler.NEXT_SKIP -> {
                val next = PlanningEngine.next(store.state) ?: return
                store.update { s -> when(next.key) {
                    "nap" -> s.copy(napAt=0)
                    "snooze" -> s.copy(snoozeAt=0)
                    else -> s.copy(skipped=s.skipped+next.key)
                } }
            }
            AlarmScheduler.NAP20,AlarmScheduler.NAP30 -> {
                val minutes = if (intent.action==AlarmScheduler.NAP20) 20 else 30
                store.update { it.copy(napAt=System.currentTimeMillis()+minutes*60_000L) }
            }
            AlarmScheduler.SNOOZE -> {
                store.update { s -> s.copy(snoozeAt=System.currentTimeMillis()+s.snoozeMinutes*60_000L) }
                context.stopService(Intent(context,RingingService::class.java))
            }
            AlarmScheduler.DISMISS -> context.stopService(Intent(context,RingingService::class.java))
            AlarmScheduler.REMIND -> {
                val monday = LocalDate.now().plusWeeks(1).weekMonday().toString()
                if (store.state.weeks[monday] == null &&
                    (1..5).any { d -> store.state.edits[LocalDate.parse(monday).plusDays((d-1).toLong()).toString()] == null }) {
                    RingingService.remind(context)
                }
                AlarmScheduler.refresh(context)
            }
        }
    }
}

class RingingService : Service() {
    companion object {
        const val ALARM_CHANNEL = "alarm_v1"
        const val REMIND_CHANNEL = "planning_v1"
        fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL,"Alarmes",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Réveils et siestes"; lockscreenVisibility=Notification.VISIBILITY_PUBLIC
                enableVibration(true); setSound(null,null)
            })
            manager.createNotificationChannel(NotificationChannel(REMIND_CHANNEL,"Rappels de planning",NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun notifyFallback(context: Context) {
            channels(context)
            val open = PendingIntent.getActivity(context,999,Intent(context,AlarmScreenActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(NotificationManager::class.java).notify(4401,
                Notification.Builder(context,ALARM_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Horéveil").setContentText("Votre réveil sonne")
                    .setPriority(Notification.PRIORITY_MAX).setFullScreenIntent(open,true).setContentIntent(open).build())
        }
        fun remind(context: Context) {
            channels(context)
            val open = PendingIntent.getActivity(context,199,Intent(context,MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(NotificationManager::class.java).notify(4402,
                Notification.Builder(context,REMIND_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Planning de la semaine prochaine")
                    .setContentText("Tes horaires ne sont pas encore configurés.").setAutoCancel(true)
                    .setContentIntent(open).build())
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var target: Long = 0
    private var ramp: Int = 0
    private val volumeLoop = object : Runnable {
        override fun run() {
            val start = target - ramp*60_000L
            val fraction = if (ramp<=0) 1f else ((System.currentTimeMillis()-start).toFloat()/(ramp*60_000f)).coerceIn(0f,1f)
            val volume = (0.05f + 0.95f*fraction).coerceIn(0f,1f)
            player?.setVolume(volume,volume)
            handler.postDelayed(this,1000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val title = intent?.getStringExtra("title") ?: "Horéveil"
        target = intent?.getLongExtra("target",System.currentTimeMillis()) ?: System.currentTimeMillis()
        ramp = intent?.getIntExtra("ramp",0) ?: 0
        val screen = PendingIntent.getActivity(this,300,Intent(this,AlarmScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = AlarmScheduler.action(this,AlarmScheduler.DISMISS,301)
        val snooze = AlarmScheduler.action(this,AlarmScheduler.SNOOZE,302)
        val notification = Notification.Builder(this,ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title)
            .setContentText("C'est l'heure de se réveiller !")
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(screen,true).setContentIntent(screen)
            .addAction(Notification.Action.Builder(null,"Répéter",snooze).build())
            .addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).build()
        startForeground(4401,notification)
        if (player != null) { handler.removeCallbacks(volumeLoop); player?.release() }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@RingingService,uri)
                isLooping = true
                prepare()
                start()
            }
            handler.post(volumeLoop)
        } catch (_: Exception) { player = null }
        try {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0,350,600),0))
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Horeveil:Alarm").apply { acquire(15*60_000L) }
        } catch (_: Exception) {}
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(volumeLoop)
        player?.stop()
        player?.release()
        player=null
        vibrator?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}

class AlarmScreenActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier=Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("Horéveil",style=MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(18.dp))
                        Text("Il est l'heure !",style=MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(60.dp))
                        Button(onClick={
                            sendBroadcast(Intent(this@AlarmScreenActivity,AlarmReceiver::class.java).setAction(AlarmScheduler.SNOOZE))
                            finish()
                        },modifier=Modifier.fillMaxWidth()) { Text("Répéter") }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick={
                            sendBroadcast(Intent(this@AlarmScreenActivity,AlarmReceiver::class.java).setAction(AlarmScheduler.DISMISS))
                            finish()
                        },modifier=Modifier.fillMaxWidth()) { Text("Arrêter") }
                    }
                }
            }
        }
    }
}
