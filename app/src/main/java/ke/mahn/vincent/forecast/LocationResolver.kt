package ke.mahn.vincent.forecast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationResolver(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val prefs = Prefs(appContext)

    fun hasForegroundPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundPermission() = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /**
     * Returns the freshest available position and persists it. Background fixes are throttled by
     * Android to a few per hour, so cached fixes and the last persisted position are fallbacks.
     */
    @SuppressLint("MissingPermission")
    suspend fun resolve(): Pair<Double, Double>? {
        if (hasForegroundPermission()) {
            val location = runCatching {
                currentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    ?: client.lastLocation.awaitOrNull()
                    // Without any stored position, pay for one GPS fix rather than show nothing.
                    ?: (if (prefs.latitude == null) currentLocation(Priority.PRIORITY_HIGH_ACCURACY) else null)
                    ?: platformLastKnownLocation()
            }.getOrNull()
            if (location != null) {
                prefs.latitude = location.latitude
                prefs.longitude = location.longitude
            }
        }
        val lat = prefs.latitude ?: return null
        val lon = prefs.longitude ?: return null
        return lat to lon
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(priority: Int): Location? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(ForecastConfig.REFRESH_INTERVAL_MS)
            .setDurationMillis(20_000)
            .build()
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(25_000) { client.getCurrentLocation(request, cancellation.token).awaitOrNull() }
        } finally {
            cancellation.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private fun platformLastKnownLocation(): Location? {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
}
