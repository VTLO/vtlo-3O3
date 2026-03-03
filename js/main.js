// TB-303 Bass Line — Main application wiring
import { RotaryKnob, ToggleSwitch, StepButton, KeyboardButton, noteName } from './ui.js';
import { Sequencer } from './sequencer.js';
import { MidiInput } from './midi.js';
import {
  savePatterns, loadPatterns, saveKnobState, loadKnobState,
  exportPatternsJSON, importPatternsJSON, PRESETS
} from './presets.js';

let audioCtx = null;
let workletNode = null;
let sequencer = new Sequencer();
let midi = null;

// FX nodes
let distortionNode = null;
let delayNode = null;
let delayFeedback = null;
let delayDry = null;
let delayWet = null;
let reverbNode = null;
let reverbDry = null;
let reverbWet = null;

// UI state
let knobs = {};
let stepButtons = [];
let keyboardButtons = [];
let selectedStep = 0;
let currentOctave = 2;
let fxOpen = false;

// ─── Audio setup ─────────────────────────────────────
async function initAudio() {
  audioCtx = new (window.AudioContext || window.webkitAudioContext)();

  await audioCtx.audioWorklet.addModule('js/synth-worklet.js');

  workletNode = new AudioWorkletNode(audioCtx, 'tb303-processor', {
    outputChannelCount: [2]
  });

  // FX chain: worklet -> distortion -> delay -> reverb -> destination
  buildFXChain();

  sequencer.connect(audioCtx, workletNode);

  // Load saved knob state
  const knobState = loadKnobState();
  if (knobState) {
    for (const [id, val] of Object.entries(knobState)) {
      if (knobs[id]) knobs[id].setValue(val, true);
    }
    applyAllKnobs();
  }

  sequencer.onStep = (stepIdx, time) => {
    scheduledStep = { idx: stepIdx, time };
  };

  sequencer.onStop = () => {
    stepButtons.forEach(b => b.setActive(false));
    document.getElementById('led-run').classList.remove('on');
  };

  // MIDI
  midi = new MidiInput();
  const midiOk = await midi.init();
  document.getElementById('midi-status').textContent =
    midiOk ? `MIDI: ${midi.inputName}` : 'MIDI: unavailable';

  midi.onNoteOn = (note, vel) => {
    workletNode.port.postMessage({
      type: 'noteOn', note, accent: vel > 100, slide: false,
      time: audioCtx.currentTime
    });
  };
  midi.onNoteOff = () => {
    workletNode.port.postMessage({ type: 'noteOff', time: audioCtx.currentTime });
  };

  updatePatternUI();
}

function buildFXChain() {
  // Distortion (WaveShaperNode)
  distortionNode = audioCtx.createWaveShaper();
  distortionNode.oversample = '2x';
  setDistortionCurve(0);

  // Delay
  delayNode = audioCtx.createDelay(1);
  delayNode.delayTime.value = 0.3;
  delayFeedback = audioCtx.createGain();
  delayFeedback.gain.value = 0.3;
  delayDry = audioCtx.createGain();
  delayDry.gain.value = 1;
  delayWet = audioCtx.createGain();
  delayWet.gain.value = 0;

  delayNode.connect(delayFeedback);
  delayFeedback.connect(delayNode);
  delayNode.connect(delayWet);

  // Reverb (simple convolution)
  reverbNode = audioCtx.createConvolver();
  reverbNode.buffer = generateReverbIR(audioCtx, 1.5, 2);
  reverbDry = audioCtx.createGain();
  reverbDry.gain.value = 1;
  reverbWet = audioCtx.createGain();
  reverbWet.gain.value = 0;

  const masterGain = audioCtx.createGain();
  masterGain.gain.value = 1;

  // Routing: worklet -> distortion -> [dry + delay] -> [dry + reverb] -> dest
  workletNode.connect(distortionNode);
  distortionNode.connect(delayDry);
  distortionNode.connect(delayNode);
  delayDry.connect(reverbDry);
  delayWet.connect(reverbDry);
  delayDry.connect(reverbNode);
  delayWet.connect(reverbNode);
  reverbDry.connect(masterGain);
  reverbWet.connect(masterGain);
  reverbNode.connect(reverbWet);
  masterGain.connect(audioCtx.destination);
}

function setDistortionCurve(amount) {
  const k = amount * 50;
  const samples = 256;
  const curve = new Float32Array(samples);
  for (let i = 0; i < samples; i++) {
    const x = (i * 2) / samples - 1;
    curve[i] = k > 0 ? Math.tanh(x * k) / Math.tanh(k) : x;
  }
  distortionNode.curve = curve;
}

function generateReverbIR(ctx, duration, decay) {
  const len = ctx.sampleRate * duration;
  const buf = ctx.createBuffer(2, len, ctx.sampleRate);
  for (let ch = 0; ch < 2; ch++) {
    const data = buf.getChannelData(ch);
    for (let i = 0; i < len; i++) {
      data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, decay);
    }
  }
  return buf;
}

// ─── Step playback tracking ──────────────────────────
let scheduledStep = null;
let lastActiveStep = -1;

function animationLoop() {
  requestAnimationFrame(animationLoop);

  if (!sequencer || !sequencer.playing) return;

  if (scheduledStep && audioCtx) {
    const now = audioCtx.currentTime;
    if (now >= scheduledStep.time - 0.02) {
      if (lastActiveStep >= 0) stepButtons[lastActiveStep].setActive(false);
      stepButtons[scheduledStep.idx].setActive(true);
      lastActiveStep = scheduledStep.idx;
      scheduledStep = null;
    }
  }
}
requestAnimationFrame(animationLoop);

// ─── UI initialization ──────────────────────────────

function initUI() {
  // Load saved patterns early so UI renders correctly before audio init
  const saved = loadPatterns();
  if (saved) sequencer.setAllPatterns(saved);

  // Knobs
  const knobConfigs = [
    { id: 'knob-tuning', param: 'tuning' },
    { id: 'knob-cutoff', param: 'cutoff', log: true },
    { id: 'knob-resonance', param: 'resonance' },
    { id: 'knob-envmod', param: 'envMod' },
    { id: 'knob-decay', param: 'decay' },
    { id: 'knob-accent', param: 'accentLevel' },
    { id: 'knob-tempo', param: null },
    { id: 'knob-volume', param: 'volume' },
  ];

  for (const cfg of knobConfigs) {
    const el = document.getElementById(cfg.id);
    if (!el) continue;
    const k = new RotaryKnob(el, {
      min: parseFloat(el.dataset.min),
      max: parseFloat(el.dataset.max),
      default: parseFloat(el.dataset.default),
      label: cfg.id,
      log: cfg.log || el.dataset.log === '1',
    });

    k.onChange = (val) => {
      if (cfg.param && workletNode) {
        const p = workletNode.parameters.get(cfg.param);
        if (p) p.setTargetAtTime(val, audioCtx.currentTime, 0.01);
      }
      if (cfg.id === 'knob-tempo') {
        if (sequencer) sequencer.bpm = val;
        document.getElementById('bpm-display').textContent = Math.round(val);
      }
      saveCurrentKnobState();
    };

    knobs[cfg.id] = k;
  }

  // FX knobs
  const fxKnobConfigs = [
    { id: 'knob-dist-drive', handler: (v) => setDistortionCurve(v) },
    { id: 'knob-delay-time', handler: (v) => { if (delayNode) delayNode.delayTime.setTargetAtTime(v, audioCtx.currentTime, 0.05); } },
    { id: 'knob-delay-feedback', handler: (v) => { if (delayFeedback) delayFeedback.gain.setTargetAtTime(v, audioCtx.currentTime, 0.05); } },
    { id: 'knob-delay-mix', handler: (v) => { if (delayWet) { delayWet.gain.setTargetAtTime(v, audioCtx.currentTime, 0.05); delayDry.gain.setTargetAtTime(1 - v * 0.5, audioCtx.currentTime, 0.05); } } },
    { id: 'knob-reverb-mix', handler: (v) => { if (reverbWet) { reverbWet.gain.setTargetAtTime(v, audioCtx.currentTime, 0.05); reverbDry.gain.setTargetAtTime(1 - v * 0.3, audioCtx.currentTime, 0.05); } } },
  ];

  for (const cfg of fxKnobConfigs) {
    const el = document.getElementById(cfg.id);
    if (!el) continue;
    const k = new RotaryKnob(el, {
      min: parseFloat(el.dataset.min),
      max: parseFloat(el.dataset.max),
      default: parseFloat(el.dataset.default),
      label: cfg.id,
    });
    k.onChange = cfg.handler;
    knobs[cfg.id] = k;
  }

  // Selector knobs (patt.group, mode)
  const pattGroupEl = document.getElementById('knob-patt-group');
  if (pattGroupEl) {
    const k = new RotaryKnob(pattGroupEl, {
      min: parseFloat(pattGroupEl.dataset.min),
      max: parseFloat(pattGroupEl.dataset.max),
      default: parseFloat(pattGroupEl.dataset.default),
      step: parseFloat(pattGroupEl.dataset.step || 0),
      label: 'knob-patt-group',
    });
    k.onChange = (val) => {
      const group = Math.round(val);
      sequencer.setGroup(group);
      document.querySelectorAll('.pat-btn').forEach(b => {
        b.classList.toggle('selected', parseInt(b.dataset.pat) === (sequencer.currentPatternIndex % 8));
      });
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
    };
    knobs['knob-patt-group'] = k;
  }

  const modeEl = document.getElementById('knob-mode');
  if (modeEl) {
    const k = new RotaryKnob(modeEl, {
      min: parseFloat(modeEl.dataset.min),
      max: parseFloat(modeEl.dataset.max),
      default: parseFloat(modeEl.dataset.default),
      step: parseFloat(modeEl.dataset.step || 0),
      label: 'knob-mode',
    });
    knobs['knob-mode'] = k;
  }

  // Waveform switch
  const wfEl = document.getElementById('waveform-switch');
  const wfSwitch = new ToggleSwitch(wfEl, { states: ['saw', 'square'], default: 0 });
  wfSwitch.onChange = (val) => {
    if (workletNode) {
      workletNode.parameters.get('waveform').setTargetAtTime(val, audioCtx.currentTime, 0.01);
    }
    saveCurrentKnobState();
  };
  knobs['waveform'] = wfSwitch;

  // Step buttons (16)
  const stepContainer = document.getElementById('step-buttons');
  for (let i = 0; i < 16; i++) {
    const el = document.createElement('div');
    stepContainer.appendChild(el);
    const btn = new StepButton(el, i);
    btn.onClick = (idx) => handleStepClick(idx);
    stepButtons.push(btn);
  }

  // Keyboard buttons
  const keyContainer = document.getElementById('keyboard');
  const keyEls = keyContainer.querySelectorAll('.key-btn');
  keyEls.forEach((el) => {
    const noteOffset = parseInt(el.dataset.note);
    const kb = new KeyboardButton(el, noteOffset);
    kb.onClick = (offset) => handleKeyClick(offset);
    keyboardButtons.push(kb);
  });

  // Transport
  document.getElementById('btn-run-stop').addEventListener('click', () => {
    if (!audioCtx) return;
    sequencer.toggle();
    document.getElementById('led-run').classList.toggle('on', sequencer.playing);
    if (!sequencer.playing) {
      lastActiveStep = -1;
      stepButtons.forEach(b => b.setActive(false));
    }
  });

  // Pattern buttons
  document.querySelectorAll('.pat-btn').forEach((el) => {
    el.addEventListener('click', () => {
      const idx = parseInt(el.dataset.pat);
      sequencer.selectPattern(idx);
      document.querySelectorAll('.pat-btn').forEach(b => b.classList.remove('selected'));
      el.classList.add('selected');
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
    });
  });

  // Accent / Slide toggles
  document.getElementById('btn-accent-toggle').addEventListener('click', () => {
    sequencer.toggleAccent(selectedStep);
    updatePatternUI();
    savePatterns(sequencer.getAllPatterns());
    updateEditToggles();
  });

  document.getElementById('btn-slide-toggle').addEventListener('click', () => {
    sequencer.toggleSlide(selectedStep);
    updatePatternUI();
    savePatterns(sequencer.getAllPatterns());
    updateEditToggles();
  });

  // Transpose
  document.getElementById('btn-transpose-down').addEventListener('click', () => {
    sequencer.transposeStep(selectedStep, -1);
    updatePatternUI();
    savePatterns(sequencer.getAllPatterns());
  });

  document.getElementById('btn-transpose-up').addEventListener('click', () => {
    sequencer.transposeStep(selectedStep, 1);
    updatePatternUI();
    savePatterns(sequencer.getAllPatterns());
  });

  // Clear pattern
  document.getElementById('btn-clear').addEventListener('click', () => {
    sequencer.clearPattern();
    updatePatternUI();
    savePatterns(sequencer.getAllPatterns());
  });

  // Randomize
  document.getElementById('btn-randomize').addEventListener('click', () => {
    sequencer.randomizePattern();
    updatePatternUI();
    savePatterns(sequencer.getAllPatterns());
  });

  // Octave buttons
  document.getElementById('btn-oct-down').addEventListener('click', () => {
    if (currentOctave > 0) {
      currentOctave--;
      document.getElementById('octave-display').textContent = currentOctave;
    }
  });

  document.getElementById('btn-oct-up').addEventListener('click', () => {
    if (currentOctave < 5) {
      currentOctave++;
      document.getElementById('octave-display').textContent = currentOctave;
    }
  });

  // FX toggle
  document.getElementById('btn-fx-toggle').addEventListener('click', () => {
    fxOpen = !fxOpen;
    document.getElementById('fx-panel').classList.toggle('open', fxOpen);
  });

  // Export / Import
  document.getElementById('btn-export').addEventListener('click', () => {
    exportPatternsJSON(sequencer.getAllPatterns());
  });

  document.getElementById('file-import').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const data = await importPatternsJSON(file);
      sequencer.setAllPatterns(data);
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
    } catch (err) {
      console.error('Import failed:', err);
    }
    e.target.value = '';
  });

  // Swing toggle
  const swingBtn = document.getElementById('btn-swing');
  swingBtn.addEventListener('click', () => {
    if (sequencer.swing === 0) {
      sequencer.swing = 0.33;
      swingBtn.textContent = 'SWING: ON';
    } else {
      sequencer.swing = 0;
      swingBtn.textContent = 'SWING: OFF';
    }
  });

  // Keyboard shortcuts
  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

    if (e.code === 'Space') {
      e.preventDefault();
      if (audioCtx) {
        sequencer.toggle();
        document.getElementById('led-run').classList.toggle('on', sequencer.playing);
        if (!sequencer.playing) {
          lastActiveStep = -1;
          stepButtons.forEach(b => b.setActive(false));
        }
      }
    } else if (e.code === 'ArrowLeft') {
      e.preventDefault();
      selectStep((selectedStep - 1 + 16) % 16);
    } else if (e.code === 'ArrowRight') {
      e.preventDefault();
      selectStep((selectedStep + 1) % 16);
    } else if (e.code === 'ArrowUp') {
      e.preventDefault();
      sequencer.transposeStep(selectedStep, 1);
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
    } else if (e.code === 'ArrowDown') {
      e.preventDefault();
      sequencer.transposeStep(selectedStep, -1);
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
    } else if (e.key === 'a' || e.key === 'A') {
      sequencer.toggleAccent(selectedStep);
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
      updateEditToggles();
    } else if (e.key === 's' || e.key === 'S') {
      sequencer.toggleSlide(selectedStep);
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
      updateEditToggles();
    } else if (e.key === 'Delete' || e.key === 'Backspace') {
      const step = sequencer.pattern.steps[selectedStep];
      step.gate = false;
      step.accent = false;
      step.slide = false;
      updatePatternUI();
      savePatterns(sequencer.getAllPatterns());
      updateEditToggles();
    }
  });

  selectStep(0);
}

// ─── Interaction handlers ────────────────────────────

function handleStepClick(idx) {
  if (selectedStep === idx && sequencer.pattern.steps[idx].gate) {
    sequencer.toggleGate(idx);
  } else if (selectedStep !== idx) {
    selectStep(idx);
  } else {
    sequencer.toggleGate(idx);
  }
  updatePatternUI();
  savePatterns(sequencer.getAllPatterns());
  updateEditToggles();
}

function handleKeyClick(noteOffset) {
  const midiNote = noteOffset + (currentOctave + 1) * 12;
  sequencer.setNote(selectedStep, midiNote);
  if (!sequencer.pattern.steps[selectedStep].gate) {
    sequencer.toggleGate(selectedStep);
  }
  updatePatternUI();
  savePatterns(sequencer.getAllPatterns());

  // Audition the note
  if (workletNode && audioCtx) {
    workletNode.port.postMessage({
      type: 'noteOn', note: midiNote, accent: false, slide: false,
      time: audioCtx.currentTime
    });
    workletNode.port.postMessage({
      type: 'noteOff',
      time: audioCtx.currentTime + 0.2
    });
  }
}

function selectStep(idx) {
  selectedStep = idx;
  stepButtons.forEach((b, i) => b.setSelected(i === idx));
  updateEditToggles();
  highlightKeyboard();
}

function updateEditToggles() {
  const step = sequencer.pattern.steps[selectedStep];
  const accentBtn = document.getElementById('btn-accent-toggle');
  const slideBtn = document.getElementById('btn-slide-toggle');
  accentBtn.classList.toggle('active', step.accent);
  slideBtn.classList.toggle('active', step.slide);
}

function highlightKeyboard() {
  const step = sequencer.pattern.steps[selectedStep];
  if (!step.gate) {
    keyboardButtons.forEach(kb => kb.setHighlight(false));
    return;
  }
  const noteInOctave = step.note % 12;
  keyboardButtons.forEach(kb => {
    kb.setHighlight(kb.noteOffset % 12 === noteInOctave);
  });
}

function updatePatternUI() {
  const pat = sequencer.pattern;
  for (let i = 0; i < 16; i++) {
    stepButtons[i].setData(pat.steps[i]);
  }
  highlightKeyboard();
}

function applyAllKnobs() {
  if (!workletNode || !audioCtx) return;
  const paramMap = {
    'knob-cutoff': 'cutoff',
    'knob-resonance': 'resonance',
    'knob-envmod': 'envMod',
    'knob-decay': 'decay',
    'knob-accent': 'accentLevel',
    'knob-volume': 'volume',
    'knob-tuning': 'tuning',
  };
  for (const [knobId, paramName] of Object.entries(paramMap)) {
    if (knobs[knobId]) {
      const p = workletNode.parameters.get(paramName);
      if (p) p.setValueAtTime(knobs[knobId].value, audioCtx.currentTime);
    }
  }
  if (knobs['knob-tempo'] && sequencer) {
    sequencer.bpm = knobs['knob-tempo'].value;
    document.getElementById('bpm-display').textContent = Math.round(knobs['knob-tempo'].value);
  }
  if (knobs['waveform']) {
    const p = workletNode.parameters.get('waveform');
    if (p) p.setValueAtTime(knobs['waveform'].value, audioCtx.currentTime);
  }
}

function saveCurrentKnobState() {
  const state = {};
  for (const [id, knob] of Object.entries(knobs)) {
    state[id] = knob.value;
  }
  saveKnobState(state);
}

// ─── Power on ────────────────────────────────────────

function powerOn() {
  document.getElementById('power-overlay').classList.add('hidden');
  initAudio().then(() => {
    applyAllKnobs();
    updatePatternUI();
  }).catch(err => {
    console.error('Audio init failed:', err);
  });
}

// ─── Boot ────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  initUI();
  document.getElementById('btn-power-on').addEventListener('click', powerOn);
});
