# forecast-app

Android home-screen widget showing the animated, transparent rain-radar forecast from
[forecast.by.vincent.mahn.ke](https://forecast.by.vincent.mahn.ke) for the device's current position.
Once set up, the widget refreshes every 5 minutes in the background, like a calendar widget. You
don't need to open the app again.

```
https://forecast.by.vincent.mahn.ke/forecast?x=<longitude>&y=<latitude>&zoom=<zoom>&theme=light
```

The app always requests `theme=light` and recolors the rain itself with the phone's Material You
primary palette (`system_accent1_100` … `system_accent1_800`). Each pixel is projected onto
WetterOnline's light rain ramp (light cyan → blue → purple → magenta hail) and mapped onto the
interpolated accent tones, so weak rain uses the lightest tone and heavy rain the darkest. The
opacity from `/alpha` is kept unchanged. Frames record the palette they were rendered with; after a
theme color change, the cached videos are recolored without downloading at the next 5-minute refresh,
or immediately when the app is opened.

The app requests the rain-only AV1 video and its matching `/alpha` opacity-mask video, then combines
them into transparent PNG frames. The widget itself has no background, so rain-free parts reveal the
home-screen wallpaper. If the server does not yet provide `/alpha` (HTTP 404), the app falls back to
the regular opaque forecast until the alpha endpoint is deployed. Launcher-specific widget
insets/backgrounds may still affect the edge.
Refresh errors are retained for internal diagnostics but are not shown as a widget warning.
The app checks the decoded frames for nonzero alpha. If any frame contains visible rain, every frame
gets the time/progress bar spanning the full forecast range, including dry frames. If the entire
forecast is transparent, the widget stays completely invisible. A `304` response with a shifted
time range redraws the bar using the cached videos.

## Zoom per widget size

By default, zoom continues to follow widget size:

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
The setup screen can override this with a fixed zoom (2, 7, 10, 25, 50, 75, or 100), or restore
**Auto (widget size)**. Zoom 2 is the widest view.

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
   - downloads synchronized rain-color and alpha-mask AV1 MP4s (each with its own `If-None-Match`
     ETag), without the basemap or footer.
   - decodes and combines their 91 frames (one per minute) into transparent PNGs with
     `MediaMetadataRetriever`, then updates all widgets.
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

With an emulator connected, `./gradlew connectedDebugAndroidTest` also checks decoded AV1 alpha
frames: an entirely dry forecast stays invisible, rain in only the last frame adds the time/progress
bar to every frame, shifted response times redraw the bar from the cached videos, and a different
theme palette recolors the rain while keeping its opacity.
The tiny three-frame videos in `app/src/androidTest/assets` are synthetic blue color, black opacity,
and black/black/white opacity fixtures.

`scripts/theme-screenshots.sh [output-dir]` switches the device's theme seed color through red,
yellow, green, and blue at 100/75/50/25 % brightness via the
`theme_customization_overlay_packages` secure setting. After each switch it waits until the widget is
re-rendered with the new `system_accent1` palette, then stores a widget screenshot, `palettes.tsv`,
and a 4×4 `grid.png`. The original theme is restored afterwards. It expects a pinned widget whose
location has rain; set `ANDROID_SERIAL`, `ADB`, `THEME_STYLE` (default `TONAL_SPOT`), or
`WIDGET_BOUNDS` as needed. With `TONAL_SPOT` the seed brightness does not change the palette, so the
rows of one hue match.

To test with a virtual location on an emulator, send it to Cologne and pick zoom 2 in the app's
**Forecast zoom** selector.
For local service development, the debug build can target a host server with
`./gradlew assembleDebug -PforecastServerUrl=http://10.0.2.2:3000`; release builds continue to use
the production server unless explicitly configured otherwise.

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
