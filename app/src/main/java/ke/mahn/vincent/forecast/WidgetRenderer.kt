package ke.mahn.vincent.forecast

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

object WidgetRenderer {

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ForecastWidgetProvider::class.java))

    fun zoomFor(context: Context, widgetId: Int): Int {
        Prefs(context).zoomOverride?.let { return it }
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        // Portrait size: min width × max height.
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (width <= 0 || height <= 0) return ForecastConfig.zoomForCells(2)
        return ForecastConfig.zoomForWidgetSize(width, height, context.resources.configuration.smallestScreenWidthDp)
    }

    fun activeZooms(context: Context): Set<Int> = widgetIds(context).map { zoomFor(context, it) }.toSet()

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        widgetIds(context).forEach { manager.updateAppWidget(it, build(context, it)) }
    }

    fun render(context: Context, widgetId: Int) {
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, build(context, widgetId))
    }

    private fun build(context: Context, widgetId: Int): RemoteViews {
        val prefs = Prefs(context)
        val zoom = zoomFor(context, widgetId)
        val frames = FrameStore(context).frameUris(zoom)
        val views = RemoteViews(context.packageName, R.layout.widget_forecast)

        views.removeAllViews(R.id.flipper)
        frames.forEach { uri ->
            val frame = RemoteViews(context.packageName, R.layout.widget_frame)
            frame.setImageViewUri(R.id.frame, uri)
            views.addView(R.id.flipper, frame)
        }
        views.setViewVisibility(R.id.flipper, if (frames.isEmpty()) View.GONE else View.VISIBLE)

        val message = when {
            frames.isNotEmpty() -> null
            else -> context.getString(R.string.widget_loading)
        }
        views.setTextViewText(R.id.message, message ?: "")
        views.setViewVisibility(R.id.message, if (message == null) View.GONE else View.VISIBLE)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.root, open)
        return views
    }
}
