# forecast-app

Android home-screen widget showing the animated rain-radar forecast from
[forecast.by.vincent.mahn.ke](https://forecast.by.vincent.mahn.ke) for the device's current position.
Once set up, the widget refreshes every 5 minutes in the background, like a calendar widget. You
don't need to open the app again.

```
https://forecast.by.vincent.mahn.ke/forecast?x=<longitude>&y=<latitude>&zoom=<zoom>&theme=<light|dark>
```

`theme` follows the phone's dark-mode setting at each refresh. After you switch dark mode, the widget
updates at the next 5-minute refresh.

## Zoom per widget size

| Widget | Zoom |
|--------|------|
| 1×1, 2×2 | 100 |
| 3×3 | 75 |
| 4×4 | 50 |
| 5×5 | 25 |
| 6+ | 10 (floor) |

`zoom = clamp(150 − 25·cells, 10, 100)`. Non-square widgets use their larger side. Launchers report
sizes in dp rather than cells, so cells are estimated from a standard 4-column phone grid. Resizing a
widget fetches the matching zoom immediately. Several widgets at the same zoom share one download.

## One-time setup

Open the app and complete the checklist:

1. **Location**: centres the map on you.
2. **Location → "Allow all the time"**: lets the widget follow you while the app is closed.
3. **Alarms & reminders** (`SCHEDULE_EXACT_ALARM`): needed for an exact 5-minute refresh.
4. **Battery → unrestricted / "Don't optimize"**: allows network access while the phone is in Doze.
   On Samsung, Xiaomi, Huawei and similar phones, also allow autostart and keep the app out of
   "sleeping apps".
5. **Add widget**: pins it to the home screen. You can also add it from the launcher's widget picker.

## Why 5 minutes works, and how

| Mechanism | Minimum interval | Used? |
|-----------|------------------|-------|
| `updatePeriodMillis` | 30 min | no (`0`) |
| WorkManager periodic | 15 min | only as a safety net |
| `setAndAllowWhileIdle` (inexact) | ≈ 7 per hour in Doze | fallback if the exact-alarm permission is denied |
| **`setExactAndAllowWhileIdle` + exact-alarm permission** | **72 per hour in Doze** (Android 12+, AOSP `ALLOW_WHILE_IDLE_QUOTA`) | **yes** |
| `setAlarmClock` | unlimited | no: shows a permanent alarm icon and isn't needed |

The often-quoted "once per 9 minutes" rule only applies to apps targeting Android 11 or lower.

How a refresh happens:

1. An exact alarm fires at **:01:05, :06:05, :11:05, …**. The server renders a new video at each
   5-minute boundary, but during the first 60 s it may still serve the previous one. Fetching at
   +65 s always gets the fresh render.
2. The alarm receiver schedules the next alarm, then starts an **expedited** WorkManager job. Doze
   does not defer expedited jobs, and the exact alarm grants a short background-start allowlist.
3. The worker:
   - gets the location. Background location is throttled to a few fixes per hour, so it falls back
     to the last known and then the last stored position.
   - downloads the forecast for each zoom in use as an AV1 MP4 (roughly 5–40 KB), with an
     `If-None-Match` ETag. It requests `noFooter=1`, so the server leaves out the time box: frames then only
     change where rain moves, which roughly halves the download when there is little rain.
   - decodes the 91 frames (one per minute) to PNG with `MediaMetadataRetriever` and draws the time box
     onto each one. Frame `i` shows `start + i/90 × (end − start)` from the `X-Forecast-Start`/`-End`
     headers in the device's time zone, with a progress bar from 0% to 100%. Then it updates all widgets.
     The footerless video is kept, because without rain movement the next render is byte-identical: the
     server answers `304 Not Modified`, but its new `X-Forecast-Start`/`-End` still move the times 5 minutes
     on, so the app redraws the footer from the stored video without downloading again.
   - records the data used by every request: request and response headers plus the body, so
     `304 Not Modified` revalidations count their roughly 1 KB of headers (marked "304"). The
     app's **Data usage** section shows the last 5 requests, hourly totals for the past 24 hours and daily
     totals for the past 30 days. Header sizes are approximate, since HTTP/2 compresses them on the wire.
4. Safety nets:
   - A 15-minute periodic job re-arms the alarm chain if it broke.
   - The chain is re-armed after reboot, app update, time or timezone changes, and exact-alarm
     permission changes.

The widget animates the frames with a `ViewFlipper` at 100 ms per frame, the same timing as the server's animation.
Frames reach the launcher as `content://` URIs through a read-only `FrameProvider`. This avoids
the RemoteViews bitmap memory limit, and versioned paths stop the launcher from showing cached frames.

## Development

Requirements: a JDK 17+ (for example Android Studio's bundled JBR) and the Android SDK
(compileSdk 37).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Test Doze behaviour:

```sh
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
adb shell dumpsys alarm | grep -A4 ke.mahn.vincent.forecast   # expect exactAllowReason=permission
# wait for the next :x1:05 / :x6:05 and check the widget's timestamp
adb shell dumpsys deviceidle unforce && adb shell dumpsys battery reset
```

## Release (Google Play)

Pushing a `v*` tag builds a signed AAB in GitHub Actions. It needs these repository secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

Play Console requirements:

- **Background location declaration** with a demo video. Core feature: "The home-screen widget
  shows the rain radar for the user's current location and refreshes in the background." The app
  shows the required prominent disclosure before asking for the permission.
- **Exact alarms**: `SCHEDULE_EXACT_ALARM` is user-granted and needs no declaration.
  `USE_EXACT_ALARM` is deliberately not used, because Play restricts it to alarm and calendar apps.
- **Battery optimization**: the app opens the system settings list
  (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) and does not use the restricted
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission.
- **Privacy policy URL** (required because the app uses location). Coordinates are rounded to 4
  decimals and sent only to forecast.by.vincent.mahn.ke.
- No foreground service, so no foreground-service type declaration is needed.
