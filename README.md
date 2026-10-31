# TB-303 Bass Line

![TB-303 synthesizer interface](assets/screenshot.png)

A browser-based TB-303 style bassline synthesizer with a 16-step sequencer. Vanilla HTML/CSS/JavaScript and the Web Audio API.

**Run it:**
```bash
npx serve .
```
Then open http://localhost:3000

## Android app (`android/`)

**VTLO 3O3** is a standalone, fully offline studio for Android: a TB-303 bass
line and a 606/808/909 drum machine on two screens you swipe between (or pick
with the tabs at the top). Native Kotlin, no WebView, no third-party
libraries; it targets **Android 17 (API 37)** and runs on Android 8.0 (API 26)
and newer. It only asks for the foreground-service and notification
permissions it needs for background playback.

**Studio**
- One transport: RUN on either screen starts both instruments on the same
  sample, and they share tempo and swing, so they stay locked. BASS ON/OFF and
  DRUMS ON/OFF take a part out of the mix without stopping it.
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

**Shared**
- Sequencers are clocked by the audio sample counter (sample-accurate).
- Overdrive, tempo-synced ping-pong delay, FDN reverb and soft limiter per
  part, plus a limiter on the mix.
- Low-latency float `AudioTrack` at the device's native rate, real-time
  thread, buffer auto-grows on underruns.

**Modules**: `core` (audio output, transport, service, effects, widgets,
pager), `drums` (drum engine, kits, drum screen), `app` (bass line, studio
engine, the activity).

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
