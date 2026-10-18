package ke.mahn.vincent.forecast

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Drives refreshes with a chain of exact allow-while-idle alarms aligned to the server's
 * 5-minute render boundaries. On Android 12+ apps holding the exact-alarm permission may fire
 * 72 of these per hour even in Doze, so a 5-minute cadence (12/hour) stays within quota.
 * A 15-minute periodic job re-arms the chain should it ever break (e.g. OEM task killers).
 */
object RefreshScheduler {
    private const val REFRESH_WORK = "refresh"
    private const val SAFETY_NET_WORK = "safety-net"
    const val ACTION_REFRESH = "ke.mahn.vincent.forecast.action.REFRESH"

    fun canScheduleExact(context: Context) = alarmManager(context).canScheduleExactAlarms()

    fun ensureScheduled(context: Context) {
        if (WidgetRenderer.widgetIds(context).isEmpty()) return
        val prefs = Prefs(context)
        val alarmMissing = alarmIntent(context, PendingIntent.FLAG_NO_CREATE) == null
        if (alarmMissing || prefs.nextAlarmAt < System.currentTimeMillis()) scheduleNextAlarm(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SAFETY_NET_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SafetyNetWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun scheduleNextAlarm(context: Context) {
        val at = ForecastConfig.nextRefreshAt(System.currentTimeMillis())
        val manager = alarmManager(context)
        val intent = alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            // Degraded mode: inexact alarms may be deferred by Doze (≈7/hour quota).
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        Prefs(context).nextAlarmAt = at
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        alarmIntent(context, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager(context).cancel(it)
            it.cancel()
        }
        Prefs(context).nextAlarmAt = 0
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(SAFETY_NET_WORK)
            cancelUniqueWork(REFRESH_WORK)
        }
    }

    private fun alarmIntent(context: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, RefreshAlarmReceiver::class.java).setAction(ACTION_REFRESH),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
