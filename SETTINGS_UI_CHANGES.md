# Shade pitch-black settings UI changes

Applied changes:

- Strict monochrome pitch-black theme (`#000000` background).
- Near-black cards with hairline strokes and uppercase section labels.
- Mature settings rows with icons, titles, subtitles, and RTL-safe arrows.
- Circular monochrome settings icons.
- Ripple feedback, focusability, clickability, and larger row touch targets.
- Dynamic ActionBar titles and back navigation for subscreens.
- Missing settings string resources added.
- Dialog theme aligned with the pitch-black settings UI.
- Placeholder rows now show a "Coming soon" toast instead of doing nothing.

Build check to run in Android Studio/Gradle:

```bash
./gradlew assembleDebug
```
