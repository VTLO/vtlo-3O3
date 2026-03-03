// Built-in TB-303 acid patterns
// Notes are MIDI numbers. TB-303 range: roughly C1(36) to C3(60)

function step(note, gate = true, accent = false, slide = false) {
  return { note, gate, accent, slide };
}
const R = { note: 36, gate: false, accent: false, slide: false };

export const PRESETS = [
  {
    name: 'Acid Classic',
    bpm: 138,
    steps: [
      step(36, true, true),   step(36), R, step(48, true, false, true),
      step(39), step(36, true, true), R, step(36),
      step(48, true, true, true), step(36), R, step(41),
      step(36, true, true), R, step(36), step(43, true, false, true),
    ]
  },
  {
    name: 'Minimal Techno',
    bpm: 126,
    steps: [
      step(36, true, true), R, R, step(36),
      R, step(36), R, R,
      step(36, true, true), R, step(36), R,
      R, R, step(36), R,
    ]
  },
  {
    name: 'Accent Arp',
    bpm: 140,
    steps: [
      step(36, true, true), step(39), step(41), step(43, true, true),
      step(46), step(48, true, true), step(46, true, false, true), step(43),
      step(41, true, true), step(39), step(36), step(39, true, true),
      step(41, true, false, true), step(43), step(46, true, true), step(48),
    ]
  },
  {
    name: 'Deep House',
    bpm: 122,
    steps: [
      step(36, true, true), R, step(36), step(38, true, false, true),
      R, step(36), R, step(43),
      step(36, true, true), R, step(36), R,
      step(41, true, false, true), step(38), R, step(36),
    ]
  },
  {
    name: 'Acid House',
    bpm: 132,
    steps: [
      step(36, true, true), step(36), step(48, true, true, true), step(36),
      R, step(39, true, true), step(36, true, false, true), step(36),
      step(41, true, true), step(36), R, step(48, true, true),
      step(36, true, false, true), step(36), step(43, true, true), R,
    ]
  },
  {
    name: 'Slow Melodic',
    bpm: 108,
    steps: [
      step(48, true, false), step(46, true, false, true), step(43), step(41, true, false, true),
      step(39), R, step(36), step(38, true, false, true),
      step(41), step(43, true, false, true), step(46), step(48, true, true),
      R, step(46, true, false, true), step(43), R,
    ]
  },
  {
    name: 'Aggressive',
    bpm: 145,
    steps: [
      step(36, true, true), step(48, true, true), step(36, true, true), step(48, true, true),
      R, step(36, true, true), step(51, true, true, true), step(48),
      step(36, true, true), step(36), step(48, true, true), step(36, true, true),
      step(51, true, true, true), step(48, true, true), R, step(36, true, true),
    ]
  },
  {
    name: 'Experimental',
    bpm: 135,
    steps: [
      step(38), R, step(48, true, true, true), step(41),
      step(46, true, true), R, step(36, true, false, true), R,
      step(53, true, true), step(43, true, false, true), R, step(39),
      R, step(48, true, true), step(36, true, false, true), step(44),
    ]
  },
];

export function createEmptyPattern() {
  return {
    steps: Array.from({ length: 16 }, () => ({
      note: 36, gate: false, accent: false, slide: false
    }))
  };
}

export function clonePattern(pat) {
  return {
    steps: pat.steps.map(s => ({ ...s }))
  };
}

const STORAGE_KEY = 'tb303-patterns';
const KNOB_STORAGE_KEY = 'tb303-knobs';

export function savePatterns(patterns) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(patterns));
  } catch (e) { /* storage full or unavailable */ }
}

export function loadPatterns() {
  try {
    const data = localStorage.getItem(STORAGE_KEY);
    if (data) return JSON.parse(data);
  } catch (e) { /* corrupted */ }
  return null;
}

export function saveKnobState(state) {
  try {
    localStorage.setItem(KNOB_STORAGE_KEY, JSON.stringify(state));
  } catch (e) {}
}

export function loadKnobState() {
  try {
    const data = localStorage.getItem(KNOB_STORAGE_KEY);
    if (data) return JSON.parse(data);
  } catch (e) {}
  return null;
}

export function exportPatternsJSON(patterns) {
  const blob = new Blob([JSON.stringify(patterns, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'tb303-patterns.json';
  a.click();
  URL.revokeObjectURL(url);
}

export function importPatternsJSON(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        resolve(JSON.parse(reader.result));
      } catch (e) {
        reject(e);
      }
    };
    reader.onerror = reject;
    reader.readAsText(file);
  });
}
