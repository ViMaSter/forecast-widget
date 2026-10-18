package ke.mahn.vincent.forecast

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RefreshAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RefreshScheduler.ACTION_REFRESH) return
        if (WidgetRenderer.widgetIds(context).isEmpty()) {
            RefreshScheduler.cancel(context)
            return
        }
        RefreshScheduler.scheduleNextAlarm(context)
        RefreshScheduler.refreshNow(context)
    }
}

/** Re-arms the alarm chain after events that clear or shift alarms. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                if (WidgetRenderer.widgetIds(context).isEmpty()) return
                RefreshScheduler.scheduleNextAlarm(context)
                RefreshScheduler.ensureScheduled(context)
                RefreshScheduler.refreshNow(context)
            }
        }
    }
}
