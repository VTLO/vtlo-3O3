// TB-303 AudioWorkletProcessor
// DSP based on Open303 by Robin Schmidt (circuit-modeled TB-303 emulation)

const TWO_PI = 2 * Math.PI;
const PI = Math.PI;
const SQRT2 = Math.SQRT2;
const ONE_OVER_SQRT2 = 1 / SQRT2;
const EXP_NEG3 = Math.exp(-3);
const ONE_OVER_ONE_MINUS_EXP_NEG3 = 1 / (1 - EXP_NEG3);

class TB303Processor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      { name: 'cutoff', defaultValue: 400, minValue: 60, maxValue: 22000, automationRate: 'a-rate' },
      { name: 'resonance', defaultValue: 0.5, minValue: 0, maxValue: 1, automationRate: 'a-rate' },
      { name: 'envMod', defaultValue: 0.5, minValue: 0, maxValue: 1, automationRate: 'a-rate' },
      { name: 'decay', defaultValue: 0.3, minValue: 0, maxValue: 1, automationRate: 'k-rate' },
      { name: 'accentLevel', defaultValue: 0.5, minValue: 0, maxValue: 1, automationRate: 'k-rate' },
      { name: 'volume', defaultValue: 0.5, minValue: 0, maxValue: 1, automationRate: 'a-rate' },
      { name: 'tuning', defaultValue: 0, minValue: -1200, maxValue: 1200, automationRate: 'k-rate' },
      { name: 'waveform', defaultValue: 0, minValue: 0, maxValue: 1, automationRate: 'k-rate' },
    ];
  }

  constructor(options) {
    super(options);
    const sr = sampleRate;
    this.sr = sr;
    this.invSr = 1 / sr;

    // --- Oscillator ---
    this.phase = 0;
    this.phaseInc = 0;

    // --- Pitch ---
    this.currentFreq = 0;
    this.targetFreq = 0;
    this.slideActive = false;
    this.slideCoeff = Math.exp(-1 / (sr * 0.012));

    // --- Filter state (TB-303 4-pole ladder) ---
    this.fY1 = 0;
    this.fY2 = 0;
    this.fY3 = 0;
    this.fY4 = 0;

    // Feedback highpass in ladder (150 Hz one-pole HP)
    this.fbHpCoeff = Math.exp(-TWO_PI * 150 / sr);
    this.fbHpX1 = 0;
    this.fbHpY1 = 0;

    // Pre-filter highpass (44.486 Hz)
    this.preHpCoeff = Math.exp(-TWO_PI * 44.486 / sr);
    this.preHpX1 = 0;
    this.preHpY1 = 0;

    // Post-filter highpass (24.167 Hz)
    this.postHpCoeff = Math.exp(-TWO_PI * 24.167 / sr);
    this.postHpX1 = 0;
    this.postHpY1 = 0;

    // --- Filter envelope ---
    this.filterEnv = 0;
    this.filterEnvDecayCoeff = 0.9999;

    // Envelope RC smoothing (creates ~3ms attack shape)
    this.envRC1 = 0;
    this.envRC1Coeff = Math.exp(-1 / (sr * 0.003));
    this.envRC2 = 0;
    this.envRC2Coeff = Math.exp(-1 / (sr * 0.003));

    // --- Amp envelope ---
    this.ampEnv = 0;
    this.ampGate = false;
    this.ampAttackCoeff = 1 - Math.exp(-1 / (sr * 0.003));
    this.ampReleaseCoeffNormal = Math.exp(-1 / (sr * 0.003));
    this.ampReleaseCoeffAccent = Math.exp(-1 / (sr * 0.060));

    // --- Accent & note state ---
    this.noteActive = false;
    this.currentAccent = false;

    // --- Event queue ---
    this.events = [];

    this.port.onmessage = (e) => {
      const msg = e.data;
      if (msg.type === 'allNotesOff') {
        this.doNoteOff();
        this.filterEnv = 0;
        this.ampEnv = 0;
        this.envRC1 = 0;
        this.envRC2 = 0;
        this.events.length = 0;
        return;
      }
      let i = this.events.length;
      while (i > 0 && this.events[i - 1].time > msg.time) i--;
      this.events.splice(i, 0, msg);
    };
  }

  // --- PolyBLEP anti-aliasing correction ---
  polyBLEP(t, dt) {
    if (t < dt) {
      t /= dt;
      return t + t - t * t - 1;
    }
    if (t > 1 - dt) {
      t = (t - 1) / dt;
      return t * t + t + t + 1;
    }
    return 0;
  }

  midiToFreq(note) {
    return 440 * Math.pow(2, (note - 69) / 12);
  }

  doNoteOn(note, accent, slide) {
    this.targetFreq = this.midiToFreq(note);

    if (slide && this.noteActive) {
      this.slideActive = true;
    } else {
      this.currentFreq = this.targetFreq;
      this.slideActive = false;
      this.filterEnv = 1;
      this.envRC1 = 0;
      this.envRC2 = 0;
      this.ampGate = true;
    }

    this.noteActive = true;
    this.currentAccent = accent;
  }

  doNoteOff() {
    this.ampGate = false;
    this.noteActive = false;
  }

  process(inputs, outputs, parameters) {
    const output = outputs[0];
    if (!output || !output[0]) return true;
    const outL = output[0];
    const outR = output.length > 1 ? output[1] : null;
    const N = outL.length;

    const cutoffArr = parameters.cutoff;
    const resArr = parameters.resonance;
    const envModArr = parameters.envMod;
    const volArr = parameters.volume;
    const cutoffK = cutoffArr.length === 1;
    const resK = resArr.length === 1;
    const envModK = envModArr.length === 1;
    const volK = volArr.length === 1;

    const decay = parameters.decay[0];
    const accentKnob = parameters.accentLevel[0];
    const tuning = parameters.tuning[0];
    const wf = parameters.waveform[0];

    const blockTime = currentTime;

    for (let i = 0; i < N; i++) {
      const t = blockTime + i * this.invSr;

      // --- Process queued events ---
      while (this.events.length > 0 && this.events[0].time <= t) {
        const ev = this.events.shift();
        if (ev.type === 'noteOn') {
          this.doNoteOn(ev.note, ev.accent, ev.slide);
        } else if (ev.type === 'noteOff') {
          this.doNoteOff();
        }
      }

      // --- Read parameters ---
      const baseCutoff = cutoffK ? cutoffArr[0] : cutoffArr[i];
      const resonance = resK ? resArr[0] : resArr[i];
      const envMod = envModK ? envModArr[0] : envModArr[i];
      const volume = volK ? volArr[0] : volArr[i];

      // --- Pitch slide ---
      if (this.slideActive) {
        this.currentFreq = this.targetFreq + this.slideCoeff * (this.currentFreq - this.targetFreq);
      }

      const freq = this.currentFreq * Math.pow(2, tuning / 1200);

      // --- Filter envelope ---
      const decayMs = this.currentAccent ? 200 : (200 + decay * 1800);
      const envDecayCoeff = Math.exp(-1 / (0.001 * decayMs * this.sr));
      this.filterEnv *= envDecayCoeff;

      // RC1: normal envelope path (attack smoothing)
      this.envRC1 = this.filterEnv + this.envRC1Coeff * (this.envRC1 - this.filterEnv);
      // RC2: accent envelope path
      const accentInput = this.currentAccent ? this.filterEnv : 0;
      this.envRC2 = accentInput + this.envRC2Coeff * (this.envRC2 - accentInput);

      // --- Modulated cutoff ---
      const envRange = 4.5;
      const normalContrib = envMod * envRange * this.envRC1;
      const accentContrib = accentKnob * 2.5 * this.envRC2;
      let modCutoff = baseCutoff * Math.pow(2, normalContrib + accentContrib);
      if (modCutoff < 60) modCutoff = 60;
      if (modCutoff > 22000) modCutoff = 22000;

      // --- Amp envelope ---
      if (this.ampGate) {
        this.ampEnv += this.ampAttackCoeff * (1 - this.ampEnv);
      } else {
        const rc = this.currentAccent ? this.ampReleaseCoeffAccent : this.ampReleaseCoeffNormal;
        this.ampEnv *= rc;
      }

      let ampOut = this.ampEnv;
      ampOut += 0.45 * this.envRC1;
      if (this.currentAccent) {
        ampOut += accentKnob * 4 * this.envRC2;
      }
      if (ampOut > 3) ampOut = 3;

      // --- Oscillator (PolyBLEP) ---
      this.phaseInc = freq * this.invSr;
      let osc;

      if (wf < 0.5) {
        // Sawtooth
        osc = 2 * this.phase - 1;
        osc -= this.polyBLEP(this.phase, this.phaseInc);
      } else {
        // Square
        osc = this.phase < 0.5 ? 1 : -1;
        osc += this.polyBLEP(this.phase, this.phaseInc);
        osc -= this.polyBLEP((this.phase + 0.5) % 1, this.phaseInc);
        osc *= 0.6;
      }

      this.phase += this.phaseInc;
      if (this.phase >= 1) this.phase -= 1;

      // Negate (Open303 convention)
      osc = -osc;

      // --- Pre-filter highpass (44.486 Hz) ---
      let sig = this.preHpCoeff * (this.preHpY1 + osc - this.preHpX1);
      this.preHpX1 = osc;
      this.preHpY1 = sig;

      // --- TB-303 Ladder Filter ---
      const resSkew = (1 - Math.exp(-3 * resonance)) * ONE_OVER_ONE_MINUS_EXP_NEG3;
      const fx = modCutoff * this.invSr * ONE_OVER_SQRT2;
      const b0 = (0.00045522346 + 6.1922189 * fx) / (1 + 12.358354 * fx + 4.4156345 * fx * fx);
      let k = fx * (fx * (fx * (fx * (fx * (fx + 7198.6997) - 5837.7917) - 476.47308) + 614.95611) + 213.87126) + 16.998792;
      let g = k * 0.058823529411764706;
      g = (g - 1) * resSkew + 1;
      g = g * (1 + resSkew);
      k = k * resSkew;

      // Feedback highpass
      const fbIn = k * this.fY4;
      const fbHp = this.fbHpCoeff * (this.fbHpY1 + fbIn - this.fbHpX1);
      this.fbHpX1 = fbIn;
      this.fbHpY1 = fbHp;

      // 4-pole ladder stages
      const y0 = sig - fbHp;
      this.fY1 += 2 * b0 * (y0 - this.fY1 + this.fY2);
      this.fY2 += b0 * (this.fY1 - 2 * this.fY2 + this.fY3);
      this.fY3 += b0 * (this.fY2 - 2 * this.fY3 + this.fY4);
      this.fY4 += b0 * (this.fY3 - 2 * this.fY4);

      sig = 2 * g * this.fY4;

      // --- Post-filter highpass (24.167 Hz) ---
      const postHp = this.postHpCoeff * (this.postHpY1 + sig - this.postHpX1);
      this.postHpX1 = sig;
      this.postHpY1 = postHp;
      sig = postHp;

      // --- Output ---
      sig *= ampOut * volume;

      // Soft-clip
      if (sig > 1) sig = 1 - 2 / (1 + Math.exp(2 * sig));
      else if (sig < -1) sig = -1 + 2 / (1 + Math.exp(-2 * sig));

      outL[i] = sig;
      if (outR) outR[i] = sig;
    }

    return true;
  }
}

registerProcessor('tb303-processor', TB303Processor);
