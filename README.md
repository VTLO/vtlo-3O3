# TB-303 Bass Line — Web Synthesizer

A browser-based TB-303 style bassline synthesizer with a full 16-step sequencer, built entirely with vanilla HTML/CSS/JavaScript and the Web Audio API.

## Quick Start

### Local Development

Any static file server works:

```bash
# Option 1: Node.js
npx serve .

# Option 2: Python
python3 -m http.server 8000
```

Then open `http://localhost:8000` (or the port shown) in Chrome, Firefox, or Safari.

### Cloudflare Pages Deployment

**Via GitHub integration:**

1. Push this repository to GitHub
2. Go to Cloudflare Dashboard → Pages → Create a project
3. Connect to your GitHub repository
4. Configure:
   - **Framework preset:** None
   - **Build command:** (leave empty)
   - **Build output directory:** `/`
5. Click Deploy

**Via Wrangler CLI:**

```bash
npx wrangler pages deploy . --project-name=tb303
```

**Node version:** Any (no build step required)

## Architecture

```
303/
├── index.html              Main page
├── css/style.css           All styles (303 hardware aesthetic)
├── js/
│   ├── main.js             App bootstrap, AudioContext, wiring
│   ├── ui.js               Knob/switch/button UI components
│   ├── sequencer.js        16-step sequencer + lookahead scheduler
│   ├── synth-worklet.js    AudioWorkletProcessor (all DSP)
│   ├── presets.js           8 built-in acid patterns + persistence
│   └── midi.js             WebMIDI input (optional)
├── assets/                 Reference image
└── README.md
```

**Three layers:**
- **UI layer** (`ui.js`, `style.css`): Rotary knobs, step buttons, keyboard — all touch/mouse compatible
- **Sequencer layer** (`sequencer.js`): Pattern data, lookahead timer scheduling, transport
- **Audio engine** (`synth-worklet.js`): Runs in AudioWorklet thread, no main-thread audio processing

## Sound Engine

The synth voice runs entirely in an `AudioWorkletProcessor` to ensure glitch-free audio.

### Oscillator
- PolyBLEP antialiased sawtooth and square waveforms
- Switchable via the WAVEFORM toggle

### Filter (TB-303 Ladder)
- 4-pole cascaded ladder filter based on the Open303 project's TB_303 mode
- Coefficient formulas derived from circuit analysis of the original TB-303
- 150 Hz highpass in the feedback path (characteristic of the 303's filter)
- Resonance skew: `r = (1 - exp(-3·res)) / (1 - exp(-3))` for authentic Q curve
- Self-oscillates at high resonance settings

### Envelopes
- **Filter envelope:** Exponential decay with RC smoothing (~3ms attack). Decay time: 200–2000ms (knob controlled), forced to 200ms on accent steps
- **Amp envelope:** RC-style attack (3ms), release 3ms (normal) or 60ms (accent)
- **Env Mod** knob controls filter envelope depth (up to ~4.5 octaves of sweep)

### Accent
When a step has accent enabled:
- Filter decay shortened to 200ms (punchier)
- Additional filter cutoff boost via separate RC path
- Amplitude boosted (up to 4× envelope contribution)
- Amp release extended to 60ms (characteristic accent tail)

### Slide (Glide)
- One-pole lowpass on pitch frequency: `y = target + coeff·(y - target)`
- Time constant ~12ms (derived from 60ms slide time × 0.2)
- On slide steps, envelopes are NOT retriggered (legato behavior)

### Signal Flow
1. PolyBLEP oscillator (saw or square)
2. Pre-filter highpass (44.486 Hz)
3. TB-303 ladder filter with modulated cutoff
4. Post-filter highpass (24.167 Hz)
5. Amp envelope × volume
6. Soft-clip output

### FX Chain (optional, behind FX toggle)
- **Distortion:** WaveShaperNode with tanh curve
- **Delay:** DelayNode with feedback loop
- **Reverb:** ConvolverNode with generated impulse response

## Sequencer

- 16-step pattern sequencer with per-step note, gate, accent, and slide
- 8 pattern slots (expandable to 16)
- Lookahead scheduler: `setInterval(25ms)` checks 100ms ahead, schedules via `AudioContext.currentTime`
- Swing control (offsets even steps)
- BPM: 60–300

## Controls

### Knobs (drag up/down)
| Knob | Parameter | Range |
|------|-----------|-------|
| TUNING | Pitch offset | ±1200 cents |
| CUT OFF FREQ | Filter cutoff | 60–22000 Hz (log) |
| RESONANCE | Filter Q | 0–100% |
| ENV MOD | Filter envelope depth | 0–100% |
| DECAY | Filter envelope decay | 200–2000ms |
| ACCENT | Accent intensity | 0–100% |
| TEMPO | BPM | 60–300 |
| VOLUME | Output level | 0–100% |

### Keyboard Shortcuts
| Key | Action |
|-----|--------|
| Space | Start/Stop |
| ← → | Select step |
| ↑ ↓ | Transpose selected step |
| A | Toggle accent on selected step |
| S | Toggle slide on selected step |
| Delete | Clear selected step |

### Workflow
1. Click **POWER ON** to initialize audio
2. Click step buttons to toggle notes on/off
3. Click keyboard keys to set the pitch for the selected step
4. Use ACCENT/SLIDE buttons for per-step modifiers
5. Click **RUN/STOP** to play (or press Space)
6. Twist knobs to shape the sound while playing
7. Switch patterns with buttons 1–8

## Persistence

- All patterns auto-save to `localStorage` on every edit
- Knob positions saved to `localStorage`
- **EXPORT:** Downloads all patterns as a `.json` file
- **IMPORT:** Loads patterns from a `.json` file

## MIDI

WebMIDI is requested on startup (if available). Connected MIDI keyboards can:
- Play notes through the synth in real-time
- Velocity > 100 triggers accent

## Verification Checklist

- [ ] Audio only starts after clicking POWER ON (browser autoplay policy)
- [ ] Sawtooth and square waveforms sound distinct (saw is brighter/buzzier)
- [ ] Filter sweeps smoothly when turning CUT OFF FREQ — no zipper noise
- [ ] High RESONANCE (>80%) produces squealing/self-oscillation
- [ ] Accent steps are noticeably louder with a sharper filter "zap"
- [ ] Slide steps produce smooth pitch glide between notes
- [ ] Sequencer timing is tight at 120–160 BPM with no drift
- [ ] Knob tweaks during playback produce no clicks or crackles
- [ ] Works on mobile browsers (touch controls, audio plays)
- [ ] Patterns persist across page refresh

## Browser Support

- Chrome/Edge 66+ (AudioWorklet)
- Firefox 76+ (AudioWorklet)
- Safari 14.1+ (AudioWorklet)
- Mobile Chrome/Safari supported

## Credits

- DSP approach based on [Open303](https://sourceforge.net/projects/open303/) by Robin Schmidt
- Inspired by the [JC-303](https://midilab.co/jc303/) VST plugin by midilab
- Roland and TB-303 are registered trademarks of Roland Corporation. This project is not affiliated with or endorsed by Roland.
