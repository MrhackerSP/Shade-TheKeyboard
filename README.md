# Shade

An original, privacy-first Android keyboard written from scratch in Kotlin. Its feature set is
modeled on open-source keyboards like HeliBoard, but all code here is original (MIT licensed).

## Features
**Typing**
- Layouts: QWERTY, QWERTZ, AZERTY, Dvorak, Colemak, plus a **custom layout** text format
- Symbols pages, number pad (auto for number/phone fields), optional number row, emoji panel
- Shift / caps-lock, auto-capitalize, double-space period, long-press accents, key previews
- Space-bar swipe to move the cursor; long-press space for the keyboard picker

**Text correction**
- Offline suggestions from one or more dictionaries (English, Español, Deutsch, Français bundled)
- **Multilingual typing**: enable several dictionaries at once; **import your own** word lists
- Autocorrect for single-letter typos (backspace undoes it), emoji suggestions
- Learned words stored on-device only; **incognito mode**

**Toolbar & clipboard**
- Toolbar (tap `›` on the suggestion bar): clipboard, emoji, number pad, cursor, select all,
  copy/cut/paste, undo, incognito, one-handed, settings — configurable
- **Clipboard history** with pinning

**Look & layout**
- Themes: system / light / dark / AMOLED / **dynamic (Material You)** / **custom colors**
- Key borders on/off, number hints, key height, **one-handed mode**, **split keyboard**
- Background image behind the keys

**Other**
- Backup & restore of settings and learned words (JSON file)
- **No INTERNET permission**

## Not included
- Glide (swipe) typing. HeliBoard itself needs a closed-source library for this.
- Full multi-language layouts/dictionaries (add more via custom layouts + dictionary import).

## Custom layout format
One row per line (2–4 rows). Keys separated by spaces. Long-press alternates follow a `|`.
```
q w e r t y u i o p
a|àáâä s d f g h j k l
z x c v b n m
```
The last row automatically gets shift and delete keys.

## Build
Open in Android Studio (Koala+), or `./gradlew assembleDebug`. JDK 17, Android SDK 35, minSdk 21.
APK: `app/build/outputs/apk/debug/app-debug.apk`. Then open the app → Enable → Switch.

## Source map
- `ShadeService.kt` – the IME (composition, autocorrect, toolbar, panels)
- `KeyboardView.kt` – canvas keyboard, touch, popups, split/one-handed
- `Layouts.kt` – layouts and custom-layout parser
- `WordDictionary.kt` – dictionaries, suggestions, correction, import
- `Clipboard.kt`, `EmojiPanel.kt`, `Theme.kt`, `Backup.kt`, `SettingsActivity.kt`, `MainActivity.kt`

## Build on GitHub
Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds a debug APK
on every push: open the **Actions** tab → latest run → download the `shade-debug-apk` artifact.
Tag a commit `v1.0` to also attach the APK to a GitHub Release.
