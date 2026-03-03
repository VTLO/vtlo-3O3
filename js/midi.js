// WebMIDI input for TB-303 (optional, graceful fallback)

export class MidiInput {
  constructor() {
    this.enabled = false;
    this.access = null;
    this._onNoteOn = null;
    this._onNoteOff = null;
    this._onCC = null;
    this.inputName = '(none)';
  }

  set onNoteOn(fn) { this._onNoteOn = fn; }
  set onNoteOff(fn) { this._onNoteOff = fn; }
  set onCC(fn) { this._onCC = fn; }

  async init() {
    if (!navigator.requestMIDIAccess) return false;
    try {
      this.access = await navigator.requestMIDIAccess();
      this.enabled = true;
      this._listenAll();
      this.access.onstatechange = () => this._listenAll();
      return true;
    } catch {
      return false;
    }
  }

  _listenAll() {
    if (!this.access) return;
    for (const input of this.access.inputs.values()) {
      input.onmidimessage = (e) => this._handleMessage(e);
      this.inputName = input.name;
    }
  }

  _handleMessage(e) {
    const [status, data1, data2] = e.data;
    const cmd = status & 0xf0;
    if (cmd === 0x90 && data2 > 0) {
      if (this._onNoteOn) this._onNoteOn(data1, data2);
    } else if (cmd === 0x80 || (cmd === 0x90 && data2 === 0)) {
      if (this._onNoteOff) this._onNoteOff(data1);
    } else if (cmd === 0xb0) {
      if (this._onCC) this._onCC(data1, data2);
    }
  }
}
