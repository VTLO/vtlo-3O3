// TB-303 Step Sequencer with lookahead scheduling
import { createEmptyPattern, clonePattern, PRESETS } from './presets.js';

export class Sequencer {
  constructor() {
    this.patterns = [];
    this.currentPatternIndex = 0;
    this.currentStep = 0;
    this.bpm = 130;
    this.swing = 0;
    this.playing = false;
    this.selectedStep = 0;

    this._nextStepTime = 0;
    this._timerID = null;
    this._audioCtx = null;
    this._workletNode = null;
    this._onStep = null;
    this._onStop = null;
    this._lookahead = 0.1;
    this._scheduleInterval = 25;

    for (let i = 0; i < 16; i++) {
      if (i < PRESETS.length) {
        this.patterns.push({ steps: PRESETS[i].steps.map(s => ({ ...s })) });
      } else {
        this.patterns.push(createEmptyPattern());
      }
    }
  }

  get pattern() {
    return this.patterns[this.currentPatternIndex];
  }

  get stepDuration() {
    return 60 / this.bpm / 4;
  }

  connect(audioCtx, workletNode) {
    this._audioCtx = audioCtx;
    this._workletNode = workletNode;
  }

  set onStep(fn) { this._onStep = fn; }
  set onStop(fn) { this._onStop = fn; }

  start() {
    if (this.playing) return;
    if (!this._audioCtx) return;

    if (this._audioCtx.state === 'suspended') {
      this._audioCtx.resume();
    }

    this.playing = true;
    this.currentStep = 0;
    this._nextStepTime = this._audioCtx.currentTime + 0.05;
    this._schedule();
    this._timerID = setInterval(() => this._schedule(), this._scheduleInterval);
  }

  stop() {
    if (!this.playing) return;
    this.playing = false;
    if (this._timerID !== null) {
      clearInterval(this._timerID);
      this._timerID = null;
    }
    if (this._workletNode) {
      this._workletNode.port.postMessage({ type: 'allNotesOff' });
    }
    if (this._onStop) this._onStop();
  }

  toggle() {
    if (this.playing) this.stop();
    else this.start();
  }

  _schedule() {
    if (!this._audioCtx || !this.playing) return;
    const now = this._audioCtx.currentTime;

    while (this._nextStepTime < now + this._lookahead) {
      this._scheduleStep(this.currentStep, this._nextStepTime);

      // Advance step
      const prevStep = this.currentStep;
      this.currentStep = (this.currentStep + 1) % 16;

      // Compute next step time with swing
      let dur = this.stepDuration;
      if (this.swing > 0 && prevStep % 2 === 0) {
        dur *= 1 + this.swing * 0.5;
      } else if (this.swing > 0 && prevStep % 2 === 1) {
        dur *= 1 - this.swing * 0.5;
      }
      this._nextStepTime += dur;
    }
  }

  _scheduleStep(stepIdx, time) {
    const step = this.pattern.steps[stepIdx];
    const nextIdx = (stepIdx + 1) % 16;
    const nextStep = this.pattern.steps[nextIdx];
    const port = this._workletNode?.port;
    if (!port) return;

    if (step.gate) {
      port.postMessage({
        type: 'noteOn',
        note: step.note,
        accent: step.accent,
        slide: step.slide,
        time
      });

      // Gate-off timing: if next step has slide+gate, keep gate open (legato)
      if (!(nextStep.gate && nextStep.slide)) {
        const gateLen = this.stepDuration * 0.75;
        port.postMessage({ type: 'noteOff', time: time + gateLen });
      }
    } else {
      port.postMessage({ type: 'noteOff', time });
    }

    // Fire UI callback (will be picked up by rAF on main thread)
    if (this._onStep) this._onStep(stepIdx, time);
  }

  // --- Pattern editing ---

  toggleGate(stepIdx) {
    const s = this.pattern.steps[stepIdx];
    s.gate = !s.gate;
    if (s.gate && s.note < 24) s.note = 36;
  }

  setNote(stepIdx, note) {
    this.pattern.steps[stepIdx].note = note;
  }

  toggleAccent(stepIdx) {
    const s = this.pattern.steps[stepIdx];
    s.accent = !s.accent;
  }

  toggleSlide(stepIdx) {
    const s = this.pattern.steps[stepIdx];
    s.slide = !s.slide;
  }

  clearPattern() {
    const empty = createEmptyPattern();
    this.patterns[this.currentPatternIndex] = empty;
  }

  selectPattern(idx) {
    if (idx < 0 || idx >= this.patterns.length) return;
    this.currentPatternIndex = idx;
  }

  loadPreset(presetIdx) {
    if (presetIdx < 0 || presetIdx >= PRESETS.length) return;
    const preset = PRESETS[presetIdx];
    this.patterns[this.currentPatternIndex] = {
      steps: preset.steps.map(s => ({ ...s }))
    };
    if (preset.bpm) this.bpm = preset.bpm;
  }

  getAllPatterns() {
    return this.patterns.map(p => ({ steps: p.steps.map(s => ({ ...s })) }));
  }

  setAllPatterns(data) {
    if (!Array.isArray(data)) return;
    for (let i = 0; i < Math.min(data.length, 16); i++) {
      if (data[i]?.steps) {
        this.patterns[i] = { steps: data[i].steps.map(s => ({ ...s })) };
      }
    }
  }

  transposeStep(stepIdx, semitones) {
    const s = this.pattern.steps[stepIdx];
    const newNote = s.note + semitones;
    if (newNote >= 24 && newNote <= 72) s.note = newNote;
  }

  randomizePattern() {
    const notes = [36, 36, 36, 38, 39, 41, 43, 46, 48, 48];
    for (let i = 0; i < 16; i++) {
      const s = this.pattern.steps[i];
      s.gate = Math.random() > 0.3;
      s.note = notes[Math.floor(Math.random() * notes.length)];
      s.accent = Math.random() > 0.7;
      s.slide = Math.random() > 0.75;
    }
  }
}
