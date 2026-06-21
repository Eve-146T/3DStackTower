# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Stack Tower 3D — an Android-only stacking game built with libGDX 1.13.1. A
block slides over the tower, you tap to drop it, overhang gets sliced off and
tumbles away as physics-driven rubble. Landing within one frame of travel of
dead center is a "frame perfect" (full size kept, double score).

## Build & run

Requires JDK 11+ and an Android SDK (`sdk.dir` in `local.properties` or
`ANDROID_HOME`).

```sh
./gradlew :android:assembleDebug      # APK -> android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:installDebug       # build + install on connected device
```

There is **no test suite, no lint config, and no CI** — this is a single-file
game. There is also **no desktop/LWJGL launcher**; the only runnable target is
the Android app, so changes are verified by building and running the APK on a
device/emulator.

## Architecture

Two Gradle modules (`settings.gradle`):

- **`core/`** — platform-independent game, entirely in
  `StackTowerGame.java`. It is a single libGDX `ApplicationAdapter`; essentially
  all logic, rendering, and UI live here.
- **`android/`** — thin launcher (`AndroidLauncher.java`), manifest, resources.
  Sets immersive mode, locks portrait, declares the `VIBRATE` permission.

Key things that span the one big file and are easy to miss:

- **Game loop is a 3-state machine** (`State.READY/PLAYING/GAME_OVER`) driven
  from `update()`/`render()`. Input is a single `justTouched()` tap routed by
  state in `handleTap()`. There are no libGDX `Screen`s.
- **Stacking math** lives in `placeBlock()`: `overlap = size - |Δcenter|`.
  `overlap <= 0` ends the game; otherwise the placed block is shrunk to the
  overlap and the offcut spawns a `Rubble` with velocity/spin. The slide axis
  alternates x/z each level (`axis` = 0 or 2), handled via `axisPos`/`setAxisPos`
  so one code path serves both directions.
- **Frame-perfect threshold** is `speed * (1/60)` — i.e. literally one 60fps
  frame of travel, so it tightens as `speed` ramps with level.
- **Almost every asset is generated, not shipped.** 3D boxes come from
  `ModelBuilder` at runtime; the starfield, the 1×1 white pixel (used for all
  UI rects/flashes), and colors (`hsvToColor`, hue from level) are computed in
  code. Block/rubble `Model`s are manually `dispose()`d everywhere they leave
  scope — preserve this; libGDX models are not GC'd.
- **Fonts are regenerated on every resize** (`buildFonts`) at the exact pixel
  size they render at, loaded from a **system TTF** found by probing
  `/system/fonts` (`newSystemFontGenerator`). Text is uppercase/numeric only
  (`FONT_CHARS` has no lowercase). There's a built-in-font fallback for
  environments with no system TTF.
- **Persistence** is libGDX `Preferences` named `"stacktower3d"`: keys `best`,
  `sound`, `haptics`.

## Native libraries

`android/libs/` (the `.so` JNI files) is **gitignored and generated**. The
`copyAndroidNatives` task in `android/build.gradle` extracts them from the
`gdx-platform` natives jars and is wired to run before the JNI merge step, so a
clean checkout builds without manual steps. If natives go missing, run
`./gradlew :android:copyAndroidNatives`.

## Sound effects

The four `assets/sfx/*.wav` files are **procedurally synthesized** by
`tools/gen_sfx.py` (pure Python stdlib, deterministic). To change a sound, edit
the synth function in that script and regenerate:

```sh
python3 tools/gen_sfx.py
```

Everything under `assets/` is shared with Android via
`assets.srcDirs = ['../assets']` in `android/build.gradle`.
