# Shade

An original, privacy-first Android keyboard written from scratch in Kotlin. (MIT licensed).

## Building a release APK / AAB

See **[RELEASE_GUIDE.md](RELEASE_GUIDE.md)** for step-by-step instructions on
generating a release keystore and producing a signed, Play-Store-ready build.

Quick start:
```sh
cp keystore.properties.example keystore.properties  # then fill in your keystore paths
./gradlew :app:assembleRelease                       # produces app-release.apk
./gradlew :app:bundleRelease                         # produces app-release.aab (for Play Store)
```

## Features
**Typing**
- Layouts: QWERTY, QWERTZ, AZERTY, Dvorak, Colemak, plus a **custom layout** text format
- Symbols pages, number pad (auto for number/phone fields), optional number row, emoji panel
- Shift / caps-lock, auto-capitalize, double-space period, long-press accents, key previews
- Space-bar swipe to move the cursor; long-press space for the keyboard picker

**Text correction**
- Offline suggestions from one or more dictionaries (English, Hindi, Hinglish bundled)
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
