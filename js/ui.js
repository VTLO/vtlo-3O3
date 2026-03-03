// TB-303 UI Components: RotaryKnob, ToggleSwitch, StepButton, KeyboardButton

const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];

// ─── RotaryKnob ──────────────────────────────────────
export class RotaryKnob {
  constructor(el, opts = {}) {
    this.el = el;
    this.min = opts.min ?? 0;
    this.max = opts.max ?? 1;
    this.value = opts.default ?? (this.min + this.max) / 2;
    this.step = opts.step ?? 0;
    this.label = opts.label ?? '';
    this.log = opts.log ?? false;
    this.startAngle = -135;
    this.endAngle = 135;
    this._onChange = null;
    this._dragging = false;
    this._startY = 0;
    this._startVal = 0;

    this._build();
    this._bind();
    this._render();
  }

  _build() {
    this.el.classList.add('knob-widget');
    this.el.setAttribute('role', 'slider');
    this.el.setAttribute('aria-valuemin', this.min);
    this.el.setAttribute('aria-valuemax', this.max);
    this.el.setAttribute('aria-valuenow', this.value);
    this.el.setAttribute('aria-label', this.label);
    this.el.tabIndex = 0;

    this.knobBody = document.createElement('div');
    this.knobBody.className = 'knob-body';
    this.indicator = document.createElement('div');
    this.indicator.className = 'knob-indicator';
    this.knobBody.appendChild(this.indicator);
    this.el.appendChild(this.knobBody);
  }

  _bind() {
    const onDown = (e) => {
      e.preventDefault();
      this._dragging = true;
      this._startY = e.clientY ?? e.touches?.[0]?.clientY ?? 0;
      this._startVal = this._normalize(this.value);
      this.el.classList.add('active');
      document.addEventListener('pointermove', onMove);
      document.addEventListener('pointerup', onUp);
      document.addEventListener('pointercancel', onUp);
    };

    const onMove = (e) => {
      if (!this._dragging) return;
      const y = e.clientY ?? 0;
      const sensitivity = this.step > 0
        ? Math.max(60, ((this.max - this.min) / this.step) * 30)
        : 200;
      const delta = (this._startY - y) / sensitivity;
      let norm = this._startVal + delta;
      norm = Math.max(0, Math.min(1, norm));
      this.value = this._denormalize(norm);
      if (this.step > 0) {
        this.value = Math.round(this.value / this.step) * this.step;
      }
      this._render();
      this._fire();
    };

    const onUp = () => {
      this._dragging = false;
      this.el.classList.remove('active');
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.removeEventListener('pointercancel', onUp);
    };

    this.el.addEventListener('pointerdown', onDown);

    this.el.addEventListener('dblclick', () => {
      const def = parseFloat(this.el.dataset.default ?? ((this.min + this.max) / 2));
      this.value = def;
      this._render();
      this._fire();
    });

    this.el.addEventListener('keydown', (e) => {
      const range = this.max - this.min;
      const inc = range / 100;
      if (e.key === 'ArrowUp' || e.key === 'ArrowRight') {
        this.value = Math.min(this.max, this.value + inc);
        this._render(); this._fire();
      } else if (e.key === 'ArrowDown' || e.key === 'ArrowLeft') {
        this.value = Math.max(this.min, this.value - inc);
        this._render(); this._fire();
      }
    });

    this.el.addEventListener('wheel', (e) => {
      e.preventDefault();
      const range = this.max - this.min;
      const inc = range / 200;
      const delta = e.deltaY > 0 ? -inc : inc;
      this.value = Math.max(this.min, Math.min(this.max, this.value + delta));
      this._render(); this._fire();
    }, { passive: false });
  }

  _normalize(v) {
    if (this.log) {
      const minLog = Math.log(Math.max(this.min, 1));
      const maxLog = Math.log(this.max);
      return (Math.log(Math.max(v, 1)) - minLog) / (maxLog - minLog);
    }
    return (v - this.min) / (this.max - this.min);
  }

  _denormalize(n) {
    if (this.log) {
      const minLog = Math.log(Math.max(this.min, 1));
      const maxLog = Math.log(this.max);
      return Math.exp(minLog + n * (maxLog - minLog));
    }
    return this.min + n * (this.max - this.min);
  }

  _render() {
    const norm = this._normalize(this.value);
    const angle = this.startAngle + norm * (this.endAngle - this.startAngle);
    this.knobBody.style.transform = `rotate(${angle}deg)`;
    this.el.setAttribute('aria-valuenow', this.value.toFixed(2));
  }

  _fire() {
    if (this._onChange) this._onChange(this.value);
    this.el.dispatchEvent(new CustomEvent('input', { detail: this.value }));
  }

  set onChange(fn) { this._onChange = fn; }

  setValue(v, silent = false) {
    this.value = Math.max(this.min, Math.min(this.max, v));
    this._render();
    if (!silent) this._fire();
  }
}


// ─── ToggleSwitch ────────────────────────────────────
export class ToggleSwitch {
  constructor(el, opts = {}) {
    this.el = el;
    this.states = opts.states ?? ['off', 'on'];
    this.value = opts.default ?? 0;
    this._onChange = null;
    this._build();
    this._bind();
    this._render();
  }

  _build() {
    this.el.classList.add('toggle-switch');
    this.el.setAttribute('role', 'switch');
    this.el.setAttribute('aria-checked', this.value ? 'true' : 'false');
    this.el.setAttribute('aria-label', this.el.dataset.label ?? 'switch');
    this.el.tabIndex = 0;

    this.lever = document.createElement('div');
    this.lever.className = 'switch-lever';
    this.el.appendChild(this.lever);

    this.labelL = this.el.querySelector('.switch-label-l');
    this.labelR = this.el.querySelector('.switch-label-r');
  }

  _bind() {
    this.el.addEventListener('click', () => this.toggle());
    this.el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        this.toggle();
      }
    });
  }

  toggle() {
    this.value = this.value ? 0 : 1;
    this._render();
    if (this._onChange) this._onChange(this.value);
    this.el.dispatchEvent(new CustomEvent('change', { detail: this.value }));
  }

  _render() {
    this.el.setAttribute('aria-checked', this.value ? 'true' : 'false');
    this.el.classList.toggle('on', this.value === 1);
    this.lever.style.transform = this.value ? 'translateX(100%)' : 'translateX(0)';
  }

  set onChange(fn) { this._onChange = fn; }

  setValue(v, silent = false) {
    this.value = v ? 1 : 0;
    this._render();
    if (!silent && this._onChange) this._onChange(this.value);
  }
}


// ─── StepButton ──────────────────────────────────────
export class StepButton {
  constructor(el, index) {
    this.el = el;
    this.index = index;
    this.gate = false;
    this.accent = false;
    this.slide = false;
    this.note = 36;
    this.active = false;
    this.selected = false;
    this._onClick = null;

    this._build();
    this._bind();
  }

  _build() {
    this.el.classList.add('step-btn');
    this.el.setAttribute('role', 'checkbox');
    this.el.setAttribute('aria-checked', 'false');
    this.el.setAttribute('aria-label', `Step ${this.index + 1}`);
    this.el.tabIndex = 0;
    this.el.dataset.step = this.index;

    this.led = document.createElement('div');
    this.led.className = 'step-led';
    this.el.appendChild(this.led);

    this.noteLabel = document.createElement('span');
    this.noteLabel.className = 'step-note-label';
    this.el.appendChild(this.noteLabel);

    this.numLabel = document.createElement('span');
    this.numLabel.className = 'step-num';
    this.numLabel.textContent = this.index + 1;
    this.el.appendChild(this.numLabel);
  }

  _bind() {
    this.el.addEventListener('click', () => {
      if (this._onClick) this._onClick(this.index);
    });
    this.el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        if (this._onClick) this._onClick(this.index);
      }
    });
  }

  set onClick(fn) { this._onClick = fn; }

  setData(data) {
    this.gate = data.gate;
    this.accent = data.accent;
    this.slide = data.slide;
    this.note = data.note;
    this.render();
  }

  render() {
    this.el.classList.toggle('gate-on', this.gate);
    this.el.classList.toggle('accent-on', this.accent);
    this.el.classList.toggle('slide-on', this.slide);
    this.el.setAttribute('aria-checked', this.gate ? 'true' : 'false');
    this.led.classList.toggle('lit', this.gate);
    this.led.classList.toggle('accent', this.accent);

    if (this.gate) {
      const noteName = NOTE_NAMES[this.note % 12];
      const octave = Math.floor(this.note / 12) - 1;
      this.noteLabel.textContent = noteName + octave;
    } else {
      this.noteLabel.textContent = '';
    }
  }

  setActive(v) {
    this.active = v;
    this.el.classList.toggle('playing', v);
  }

  setSelected(v) {
    this.selected = v;
    this.el.classList.toggle('selected', v);
  }
}


// ─── KeyboardButton ──────────────────────────────────
export class KeyboardButton {
  constructor(el, noteOffset) {
    this.el = el;
    this.noteOffset = noteOffset;
    this.isBlack = [1, 3, 6, 8, 10].includes(noteOffset % 12);
    this._onClick = null;
    this._build();
    this._bind();
  }

  _build() {
    this.el.classList.add('key-btn');
    if (this.isBlack) this.el.classList.add('black-key');
    else this.el.classList.add('white-key');
    this.el.setAttribute('role', 'button');
    this.el.setAttribute('aria-label', `${NOTE_NAMES[this.noteOffset % 12]}`);
    this.el.tabIndex = 0;
  }

  _bind() {
    const fire = () => {
      if (this._onClick) this._onClick(this.noteOffset);
    };
    this.el.addEventListener('pointerdown', fire);
    this.el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fire(); }
    });
  }

  set onClick(fn) { this._onClick = fn; }

  setHighlight(v) {
    this.el.classList.toggle('highlight', v);
  }
}


// ─── Helper: format note name ────────────────────────
export function noteName(midi) {
  return NOTE_NAMES[midi % 12] + (Math.floor(midi / 12) - 1);
}
