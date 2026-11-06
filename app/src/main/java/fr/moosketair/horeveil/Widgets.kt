package fr.moosketair.horeveil

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WidgetUpdates {
    fun refresh(context:Context) {
        val manager=AppWidgetManager.getInstance(context)
        val alarmIds=manager.getAppWidgetIds(ComponentName(context,AlarmWidget::class.java))
        val napIds=manager.getAppWidgetIds(ComponentName(context,NapWidget::class.java))
        if(alarmIds.isNotEmpty()) AlarmWidget.update(context,manager,alarmIds)
        if(napIds.isNotEmpty()) NapWidget.update(context,manager,napIds)
    }
}
class AlarmWidget: AppWidgetProvider() {
    companion object {
        fun update(context:Context,manager:AppWidgetManager,ids:IntArray) {
            val next=PlanningEngine.next(AppStore.get(context).state)
            val date=next?.let { PlanningEngine.formatDate(it.targetAt) } ?: "Aucun réveil programmé"
            for(id in ids) {
                val views=RemoteViews(context.packageName,R.layout.widget_alarm)
                views.setTextViewText(R.id.widget_time,next?.let {
                    DateTimeFormatter.ofPattern("HH:mm",Locale.FRANCE).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(it.targetAt))
                } ?: "—:—")
                views.setTextViewText(R.id.widget_label,next?.title?.let { it+" · "+date } ?: date)
                views.setOnClickPendingIntent(R.id.widget_delay,AlarmScheduler.action(context,AlarmScheduler.NEXT_DELAY,710))
                views.setOnClickPendingIntent(R.id.widget_skip,AlarmScheduler.action(context,AlarmScheduler.NEXT_SKIP,711))
                manager.updateAppWidget(id,views)
            }
        }
    }
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) = update(context,manager,ids)
}
class NapWidget: AppWidgetProvider() {
    companion object {
        fun update(context:Context,manager:AppWidgetManager,ids:IntArray) {
            for(id in ids) {
                val views=RemoteViews(context.packageName,R.layout.widget_nap)
                views.setOnClickPendingIntent(R.id.nap20,AlarmScheduler.action(context,AlarmScheduler.NAP20,720))
                views.setOnClickPendingIntent(R.id.nap30,AlarmScheduler.action(context,AlarmScheduler.NAP30,721))
                manager.updateAppWidget(id,views)
            }
        }
    }
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) = update(context,manager,ids)
}
