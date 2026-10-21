package ke.mahn.vincent.forecast

import android.Manifest
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * One-time setup checklist. Once every item is green the widget refreshes on its own;
 * this screen is only needed again to inspect status.
 */
class SetupActivity : ComponentActivity() {

    private val foregroundLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updateStatus()
        RefreshScheduler.refreshNow(this)
    }

    private val backgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<Button>(R.id.location_button).setOnClickListener {
            foregroundLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        findViewById<Button>(R.id.background_location_button).setOnClickListener { showBackgroundDisclosure() }
        findViewById<Button>(R.id.exact_alarm_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
        }
        findViewById<Button>(R.id.battery_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.widget_button).setOnClickListener {
            AppWidgetManager.getInstance(this)
                .requestPinAppWidget(ComponentName(this, ForecastWidgetProvider::class.java), null, null)
        }
        findViewById<Button>(R.id.refresh_button).setOnClickListener {
            RefreshScheduler.refreshNow(this)
            findViewById<TextView>(R.id.status_text).text = getString(R.string.status_refreshing)
        }
        setupZoomSelector()
    }

    override fun onResume() {
        super.onResume()
        RefreshScheduler.ensureScheduled(this)
        // Theme color changes don't notify widgets, so re-render once the user comes back here.
        val zooms = WidgetRenderer.activeZooms(this)
        if (FrameStore(this).hasStalePalette(RainPalette.fromTheme(this), zooms)) RefreshScheduler.refreshNow(this)
        updateStatus()
    }

    private fun showBackgroundDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                Prefs(this).backgroundLocationDisclosureAccepted = true
                backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.disclosure_decline, null)
            .show()
    }

    private fun setupZoomSelector() {
        val spinner = findViewById<Spinner>(R.id.zoom_selector)
        val labels = listOf(getString(R.string.zoom_auto)) +
            ForecastConfig.ZOOM_LEVELS.map { getString(R.string.zoom_level, it) }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val currentZoom = Prefs(this).zoomOverride
        spinner.setSelection(currentZoom?.let { ForecastConfig.ZOOM_LEVELS.indexOf(it) + 1 } ?: 0)

        var initialSelection = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initialSelection) {
                    initialSelection = false
                    return
                }
                val selectedZoom = ForecastConfig.ZOOM_LEVELS.getOrNull(position - 1)
                val prefs = Prefs(this@SetupActivity)
                if (prefs.zoomOverride != selectedZoom) {
                    prefs.zoomOverride = selectedZoom
                    WidgetRenderer.renderAll(this@SetupActivity)
                    RefreshScheduler.refreshNow(this@SetupActivity)
                }
            }
        }
    }

    private fun updateStatus() {
        val location = LocationResolver(this)
        val foregroundOk = location.hasForegroundPermission()
        val backgroundOk = location.hasBackgroundPermission()
        val exactOk = RefreshScheduler.canScheduleExact(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val widgetCount = WidgetRenderer.widgetIds(this).size

        setStep(R.id.location_state, R.id.location_button, foregroundOk)
        setStep(R.id.background_location_state, R.id.background_location_button, backgroundOk, enabled = foregroundOk)
        setStep(R.id.exact_alarm_state, R.id.exact_alarm_button, exactOk)
        setStep(R.id.battery_state, R.id.battery_button, batteryOk)
        setStep(
            R.id.widget_state, R.id.widget_button, widgetCount > 0,
            hideButtonWhenDone = false,
            enabled = AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported,
        )

        val prefs = Prefs(this)
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        fun format(ms: Long) = if (ms > 0) time.format(Date(ms)) else getString(R.string.status_never)
        findViewById<TextView>(R.id.status_text).text = getString(
            R.string.status_template,
            widgetCount,
            format(prefs.lastSuccessAt),
            if (widgetCount > 0) format(prefs.nextAlarmAt) else getString(R.string.status_never),
            if (prefs.latitude != null) "%.4f, %.4f".format(prefs.latitude, prefs.longitude) else getString(R.string.status_never),
        )
        updateUsage()
    }

    private fun updateUsage() {
        val usage = DataUsageLog.forContext(this)
        fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)
        val dateTime = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss")
        val hour = DateTimeFormatter.ofPattern("HH:mm")
        val day = DateTimeFormatter.ofPattern("EEE dd.MM.")
        val zone = ZoneId.systemDefault()
        val recent = usage.recent()
        val hourly = usage.hourly()
        val daily = usage.daily()
        findViewById<TextView>(R.id.usage_text).text = buildString {
            appendLine(getString(R.string.usage_recent))
            if (recent.isEmpty()) appendLine("  " + getString(R.string.usage_none))
            recent.forEach {
                val at = Instant.ofEpochMilli(it.at).atZone(zone)
                val status = if (it.notModified) getString(R.string.usage_not_modified) else ""
                appendLine("  %s  %9s  z%d %s".format(dateTime.format(at), size(it.bytes), it.zoom, status).trimEnd())
            }
            appendLine()
            appendLine(getString(R.string.usage_hourly, size(hourly.sumOf { it.bytes })))
            hourly.asReversed().forEach {
                appendLine("  %s  %9s".format(hour.format(Instant.ofEpochMilli(it.start).atZone(zone)), size(it.bytes)))
            }
            appendLine()
            appendLine(getString(R.string.usage_daily, size(daily.sumOf { it.bytes })))
            daily.asReversed().forEach { appendLine("  %s  %9s".format(day.format(it.date), size(it.bytes))) }
        }.trimEnd()
    }

    private fun setStep(stateId: Int, buttonId: Int, done: Boolean, hideButtonWhenDone: Boolean = true, enabled: Boolean = true) {
        findViewById<TextView>(stateId).text = getString(if (done) R.string.step_done else R.string.step_todo)
        findViewById<Button>(buttonId).apply {
            visibility = if (done && hideButtonWhenDone) View.GONE else View.VISIBLE
            isEnabled = enabled
        }
    }
}
