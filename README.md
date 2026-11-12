# TB-303 Bass Line

![TB-303 synthesizer interface](assets/screenshot.png)

A browser-based TB-303 style bassline synthesizer with a 16-step sequencer. Vanilla HTML/CSS/JavaScript and the Web Audio API.

**Run it:**
```bash
npx serve .
```
Then open http://localhost:3000

## Android app (`android/`)

<img src="assets/vtlo-coin.gif" width="96" align="right" alt="VTLO two-faced coin emblem">

**VTLO 3O3** is a standalone, fully offline studio for Android: a TB-303 bass
line, a 606/808/909 drum machine and a sampler on three screens you swipe
between (or pick with the tabs at the top). Native Kotlin, no WebView, no
third-party libraries; it targets **Android 17 (API 37)** and runs on
Android 8.0 (API 26) and newer. Permissions: foreground service and
notifications (background playback) and the microphone, which is only asked
for the first time you press REC.

**Studio**
- One transport: RUN on any screen starts all three instruments on the same
  sample, and they share tempo and swing, so they stay locked. BASS, DRUMS and
  SAMPLER ON/OFF take a part out of the mix without stopping it.
- Orientation option in the top bar: LANDSCAPE (default), PORTRAIT or AUTO
  ROTATE, remembered between launches. Landscape fits every control of a
  screen on one display, scaled to the window; portrait scrolls. Large screens
  (where Android 16+ ignores orientation requests) follow the window's shape.
- Background playback in a `mediaPlayback` foreground service with a
  MediaSession: stop from the notification, lock screen, headset or Bluetooth
  buttons, or by swiping the app away. Audio focus is respected and playback
  stops when headphones are unplugged.
- WAV export (24-bit, 48 kHz) of either part through the system file picker.
- Edge-to-edge, predictive back, resizable windows; TalkBack can operate every
  control.

**Bass line**
- TB-303 voice modelled on Open303's measurements: PolyBLEP saw/square, 44 Hz
  pre-HP, 4-pole diode-ladder model with 150 Hz feedback HP and soft-clipping
  feedback, post 24 Hz HP / 14 Hz all-pass / 7.5 Hz notch, calibrated
  cutoff/env-mod curves, 200 ms accent decay, 60 ms RC slide.
- Accent "sweep" circuit: consecutive accents stack, resonance sets the leak.
- Oscillator and filter run 4× oversampled with a 12th-order anti-aliasing
  decimator (aliasing below −110 dB in tests).
- Slide uses hardware semantics (slide on a step glides into the next one).
- 16 patterns (2 banks × 8) with per-pattern length, presets, random,
  copy/paste, undo; pattern changes are queued to the end of the loop.

**Drum machine**
- Three bundled kits (606, 808, 909), eight tracks each: kick, snare, tom,
  clap (606: high tom), rim, closed hat, open hat, cymbal (909: ride).
  Switch the whole machine with the kit buttons, or mix kits per track with
  the TRACK panel's KIT knob.
- Samples are converted once to the device's native rate with a
  Kaiser-windowed sinc (≈100 dB SNR) and cached; retuned tracks use 4-point
  Hermite interpolation.
- 8×16 step grid with per-step accent (long-press), per-track level, tune
  (±12 semitones), decay, pan and mute, closed-hat choke of the open hat, and
  click-free retriggering. 16 patterns, presets, random, copy/paste, undo.

**Sampler**
- Eight pads. Record from the microphone (unprocessed source where the device
  offers it, up to 30 s, live waveform and level meter; silence is trimmed and
  the take normalized), or load any audio file the device can decode (WAV, MP3,
  AAC/M4A, OGG, FLAC…). On first run the pads hold sounds from the bundled kits.
- Per-pad effects: level, tune (±24 semitones), start/end trim (knobs or drag
  the markers on the waveform), attack, decay, reverse, one-knob low-pass /
  high-pass resonant filter, bit-crusher (16 → 3 bits with sample-rate
  reduction), drive, pan and an effects send into the master delay and reverb.
  NORMAL normalizes a pad, ERASE clears it, MUTE silences it in the sequence.
- 16-step sequencer per pad with accents, 16 patterns; pad audio is stored in
  app storage as 32-bit float WAV, settings and patterns in preferences.
- Recording stops whenever the app leaves the foreground.

**Shared**
- Sequencers are clocked by the audio sample counter (sample-accurate).
- Overdrive, tempo-synced ping-pong delay, FDN reverb and soft limiter per
  part, plus a limiter on the mix.
- Low-latency float `AudioTrack` at the device's native rate, real-time
  thread, buffer auto-grows on underruns.

**Modules**: `engine` (plain Kotlin/JVM: all three sound engines, sequencers,
effects, WAV I/O, resampler and the studio mixer, shared with the desktop
app), `core` (Android audio output, transport, service, widgets, pager),
`drums` (kits, drum screen), `sampler` (recorder, file decoder, sampler
screen), `app` (bass line screen, the activity), `desktop` (Windows app).

**Build**
```bash
cd android
./gradlew testDebugUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```
Requires JDK 17+ and the Android SDK with `platforms;android-37.0` and
`build-tools;37.0.0`. Release builds are minified and signed with
`keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
if present, otherwise with the local debug key. CI builds the APK on every push
(see the "Android APK" workflow artifact `vtlo-3o3-apk`).

### Emblem

Both the Android studio and the Windows app show the two-faced VTLO coin
(robot on one side, winking smiley on the other) flipping continuously in the
upper-right corner of every screen. It is purely decorative: it takes no
taps, clicks or focus, is hidden from screen readers, and on Android it holds
still when system animations are turned off.

## Windows app (`android/desktop`)

The same studio for the computer: the three instruments on three screens (tabs
at the top), running the very same `engine` code as the Android app, so they
sound identical. Swing UI drawn to scale like the Android landscape layout
(resize the window freely), Java Sound for output and the microphone, no
third-party libraries.

- **Download**: the "Android APK and Windows app" workflow publishes
  `vtlo-3o3-windows`, a folder with `VTLO 3O3.exe` and its own Java runtime
  (built with `jpackage`). Unzip anywhere and run; nothing to install.
- **Keys**: Space run/stop, Tab next screen (F1–F3 jump), Esc panic; bass line
  keyboard on Z S X D C V G B H N J M , ; sampler pads on Q W E R / A S D F.
  Right-click a step for gate (bass) or accent (drums, sampler); knobs take
  drag, mouse wheel, and double-click to reset.
- Sampler loads WAV, AIFF and AU and records from the default microphone.
  State and pad audio are saved in `%APPDATA%\VTLO 3O3` (`~/.vtlo-3o3`
  elsewhere).

**Build**
```bash
cd android
./gradlew -PdesktopOnly :desktop:run          # run it (any OS with JDK 17+)
./gradlew -PdesktopOnly :desktop:installDist  # jars in desktop/build/install
desktop/packaging/build-portable.sh           # portable Windows zip from Linux (needs mingw-w64)
```
`-PdesktopOnly` skips the Android modules, so no Android SDK is needed.

