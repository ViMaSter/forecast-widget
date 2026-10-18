package ke.mahn.vincent.forecast

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

class ForecastWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshScheduler.ensureScheduled(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetRenderer.render(context, it) }
        RefreshScheduler.ensureScheduled(context)
        RefreshScheduler.refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // A resize may change the zoom level: show what is cached, then fetch the new zoom.
        WidgetRenderer.render(context, appWidgetId)
        RefreshScheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
        FrameStore(context).retainZooms(emptySet())
    }
}
