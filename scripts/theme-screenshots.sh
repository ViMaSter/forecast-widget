#!/usr/bin/env bash
# Switches the device's Material You seed color through red/yellow/green/blue at four seed
# brightnesses, waits until the widget is re-rendered with the new accent1 palette and stores a
# screenshot of the widget per theme plus a 4×4 contact sheet (grid.png).
#
# Prerequisites: a debug build is installed, a forecast widget is pinned on the first home screen
# and its location has rain. Usage: scripts/theme-screenshots.sh [output-dir]
# Env: ANDROID_SERIAL (device), ADB (adb binary), THEME_STYLE (default TONAL_SPOT),
#      RESTORE_THEME=0 to keep the last theme instead of restoring the original setting.
set -euo pipefail

OUT=${1:-theme-screenshots}
ADB=${ADB:-adb}
STYLE=${THEME_STYLE:-TONAL_SPOT}
PACKAGE=ke.mahn.vincent.forecast
SETTING=theme_customization_overlay_packages
TIMEOUT_S=180

HUES="red yellow green blue"
BRIGHTNESSES="100 75 50 25"

# Seed = hue at the given HSV brightness (value) in percent.
seed_for() {
    local channel
    case $2 in
        100) channel=FF ;; 75) channel=BF ;; 50) channel=80 ;; 25) channel=40 ;;
        *) echo "unknown brightness $2" >&2; return 1 ;;
    esac
    case $1 in
        red) echo "${channel}0000" ;;
        yellow) echo "${channel}${channel}00" ;;
        green) echo "00${channel}00" ;;
        blue) echo "0000${channel}" ;;
        *) echo "unknown hue $1" >&2; return 1 ;;
    esac
}

adb_shell() { "$ADB" shell "$@" | tr -d '\r'; }

# adb occasionally drops the emulator for a moment; retry until a complete PNG arrives.
screencap() {
    local attempt
    for attempt in 1 2 3 4 5; do
        "$ADB" wait-for-device
        if "$ADB" exec-out screencap -p >"$1" && python3 -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).load()' "$1" 2>/dev/null; then
            return 0
        fi
        sleep 2
    done
    echo "Failed to take screenshot $1" >&2
    return 1
}

accent_key() {
    local key="" tone color
    for tone in 100 200 300 400 500 600 700 800; do
        color=$(adb_shell cmd overlay lookup android "android:color/system_accent1_$tone")
        key="$key${key:+,}$(printf '%s' "${color: -6}" | tr '[:lower:]' '[:upper:]')"
    done
    printf '%s' "$key"
}

# Render key of the newest frame directory of every zoom: "<start>,<end>|<palette>".
render_keys() {
    adb_shell "run-as $PACKAGE sh -c 'for z in files/frames/z*; do d=\$(ls -d \$z/[0-9]* 2>/dev/null | grep -v tmp | sort | tail -n 1); [ -n \"\$d\" ] && [ -f \$d/render-key ] && cat \$d/render-key && echo; done'"
}

# The animated widget keeps uiautomator from ever becoming idle, so the widget is located by the
# pixels that change between screenshots (status and navigation bars excluded).
detect_widget_bounds() {
    local dir
    dir=$(mktemp -d)
    # HOME from an activity returns to the launcher page that was last shown (HOME on the launcher
    # itself would jump to the default page), so leave the launcher on the widget's page.
    adb_shell am start -W -n "$PACKAGE/.SetupActivity" >/dev/null
    adb_shell input keyevent HOME
    sleep 3
    for i in 1 2 3 4 5 6; do
        screencap "$dir/$i.png"
        sleep 1
    done
    python3 - "$dir" <<'EOF'
import sys
from PIL import Image, ImageChops
shots = [Image.open(f"{sys.argv[1]}/{i}.png").convert("RGB") for i in range(1, 7)]
width, height = shots[0].size
top, bottom = height // 20, height - height // 10
box = None
for before, after in zip(shots, shots[1:]):
    diff = ImageChops.difference(before, after).convert("L").point(lambda v: 255 if v > 12 else 0)
    found = diff.crop((0, top, width, bottom)).getbbox()
    if found:
        found = (found[0], found[1] + top, found[2], found[3] + top)
        box = found if box is None else (min(box[0], found[0]), min(box[1], found[1]),
                                         max(box[2], found[2]), max(box[3], found[3]))
if box is None:
    sys.exit("Could not find the animated widget; set WIDGET_BOUNDS='left top right bottom'")
print(*box)
EOF
    rm -rf "$dir"
}

apply_theme() {
    adb_shell settings put secure "$SETTING" \
        "'{\"android.theme.customization.system_palette\":\"$1\",\"android.theme.customization.accent_color\":\"$1\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.theme_style\":\"$STYLE\",\"_applied_timestamp\":$(date +%s)000}'"
}

wait_for_palette() {
    local expected=$1 deadline=$((SECONDS + TIMEOUT_S)) keys
    while :; do
        keys=$(render_keys)
        if [ -n "$keys" ] && ! grep -qv "|$expected\$" <<<"$keys"; then return 0; fi
        if [ $SECONDS -ge $deadline ]; then
            echo "Timed out waiting for palette $expected; render keys: $keys" >&2
            return 1
        fi
        sleep 2
    done
}

mkdir -p "$OUT"
original=$(adb_shell settings get secure "$SETTING")
restore() {
    [ "${RESTORE_THEME:-1}" = 1 ] || return 0
    if [ "$original" = null ]; then adb_shell settings delete secure "$SETTING" >/dev/null
    else adb_shell settings put secure "$SETTING" "'$original'"; fi
}
trap restore EXIT

bounds=${WIDGET_BOUNDS:-$(detect_widget_bounds)} || exit 1
read -r left top right bottom <<<"$bounds"
[ -n "$bottom" ] || { echo "Invalid widget bounds: $bounds" >&2; exit 1; }
echo "Widget bounds: $left $top $right $bottom"

: >"$OUT/palettes.tsv"
for hue in $HUES; do
    for brightness in $BRIGHTNESSES; do
        name="$hue-$brightness"
        seed=$(seed_for "$hue" "$brightness")
        echo "== $name: seed #$seed ($STYLE)"
        previous=$(accent_key)
        apply_theme "$seed"
        # The overlay is applied asynchronously; similar seeds may legitimately yield the same palette.
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            sleep 1
            [ "$(accent_key)" != "$previous" ] && break
        done
        sleep 2
        palette=$(accent_key)
        printf '%s\t%s\t%s\n' "$name" "$seed" "$palette" >>"$OUT/palettes.tsv"
        echo "   accent1 100..800: $palette"
        # SetupActivity re-renders cached forecasts whose palette is outdated.
        adb_shell am start -W -n "$PACKAGE/.SetupActivity" >/dev/null
        wait_for_palette "$palette"
        adb_shell input keyevent HOME
        sleep 3
        screencap "$OUT/$name-full.png"
        python3 - "$OUT/$name-full.png" "$OUT/$name.png" "$left" "$top" "$right" "$bottom" <<'EOF'
import sys
from PIL import Image
source, target, *box = sys.argv[1:]
Image.open(source).crop(tuple(map(int, box))).save(target)
EOF
        echo "   saved $OUT/$name.png"
    done
done

python3 - "$OUT" "$HUES" "$BRIGHTNESSES" <<'EOF'
import sys
from PIL import Image, ImageDraw, ImageFont
out, hues, brightnesses = sys.argv[1], sys.argv[2].split(), sys.argv[3].split()
tiles = {(h, b): Image.open(f"{out}/{h}-{b}.png").convert("RGB") for h in hues for b in brightnesses}
width = max(t.width for t in tiles.values())
height = max(t.height for t in tiles.values())
label, gap = 140, 16
try:
    font = ImageFont.load_default(size=40)
except TypeError:
    font = ImageFont.load_default()
sheet = Image.new("RGB", (label + len(hues) * (width + gap), label + len(brightnesses) * (height + gap)), "white")
draw = ImageDraw.Draw(sheet)
for column, hue in enumerate(hues):
    draw.text((label + column * (width + gap) + width // 2, label // 2), hue, fill="black", font=font, anchor="mm")
for row, brightness in enumerate(brightnesses):
    top = label + row * (height + gap)
    draw.text((label // 2, top + height // 2), f"{brightness}%", fill="black", font=font, anchor="mm")
    for column, hue in enumerate(hues):
        sheet.paste(tiles[hue, brightness], (label + column * (width + gap), top))
sheet.save(f"{out}/grid.png")
print(f"saved {out}/grid.png")
EOF
