// Audius upload integration: OAuth, offline audio rendering, track upload
// OAuth handled via @audius/sdk CDN; file uploads via tus-js-client; track creation via REST API

const API_KEY = '2cc593fc814461263d282a84286fd4f72c79562e';
const API_BASE = 'https://api.audius.co/v1';
const STORAGE_NODES = [
  'https://creatornode.audius.co',
  'https://creatornode2.audius.co',
  'https://creatornode3.audius.co',
  'https://blockdaemon-audius-content-01.bdnodes.net',
  'https://audius-content-1.figment.io',
];
const POLL_INTERVAL = 3000;
const MAX_POLL_TIME = 600000;

let sdk = null;
let user = null;
let encodedJwt = null;

// ─── SDK lifecycle (read-only + OAuth) ──────────────

export function ensureSDK() {
  if (sdk) return sdk;
  if (!window.audiusSdk) throw new Error('Audius SDK not loaded');
  sdk = window.audiusSdk({ apiKey: API_KEY });
  return sdk;
}

export function getUser() { return user; }
export function isLoggedIn() { return !!user; }

export function setupOAuth(onSuccess, onError) {
  ensureSDK();
  sdk.oauth.init({
    successCallback: (profile, jwt) => {
      user = profile;
      encodedJwt = jwt;
      if (onSuccess) onSuccess(profile);
    },
    errorCallback: (err) => {
      if (onError) onError(err);
    },
  });
}

export function triggerLogin() {
  ensureSDK();
  sdk.oauth.login({ scope: 'write' });
}

// ─── Storage node selection ─────────────────────────

async function getHealthyStorageNode() {
  for (const node of STORAGE_NODES) {
    try {
      const resp = await fetch(`${node}/health_check`, { signal: AbortSignal.timeout(5000) });
      if (resp.ok) return node;
    } catch { /* try next */ }
  }
  throw new Error('No healthy Audius storage node found');
}

// ─── tus file upload ────────────────────────────────

function tusUpload(storageNode, file, template, onProgress) {
  return new Promise((resolve, reject) => {
    if (!window.tus || !window.tus.Upload) {
      reject(new Error('tus-js-client not loaded'));
      return;
    }
    const upload = new window.tus.Upload(file, {
      endpoint: `${storageNode}/files/`,
      retryDelays: [0, 3000, 5000, 10000],
      chunkSize: 100_000_000,
      metadata: {
        filename: file.name || 'file',
        filetype: file.type || 'application/octet-stream',
        template: template,
      },
      onError: (err) => reject(err),
      onProgress: (loaded, total) => {
        if (onProgress) onProgress(loaded, total);
      },
      onSuccess: () => {
        const uploadId = upload.url?.split('/').pop();
        if (!uploadId) {
          reject(new Error('No upload ID returned from tus'));
          return;
        }
        resolve(uploadId);
      },
    });
    upload.start();
  });
}

// ─── Poll for processing completion ─────────────────

async function pollUploadStatus(storageNode, uploadId, template) {
  const start = Date.now();
  while (Date.now() - start < MAX_POLL_TIME) {
    try {
      const resp = await fetch(`${storageNode}/uploads/${uploadId}`);
      if (resp.ok) {
        const data = await resp.json();
        if (data.status === 'done') return data;
        if (data.status === 'error') throw new Error(`Processing failed: ${JSON.stringify(data)}`);
      }
    } catch (e) {
      if (e.message?.startsWith('Processing failed')) throw e;
    }
    await new Promise(r => setTimeout(r, POLL_INTERVAL));
  }
  throw new Error(`Upload processing timed out after ${MAX_POLL_TIME / 1000}s`);
}

// ─── Offline audio rendering ─────────────────────────

export async function renderPatternToWAV(pattern, bpm, paramValues, loopCount = 8) {
  const sr = 44100;
  const stepDur = 60 / bpm / 4;
  const totalDur = 16 * stepDur * loopCount + 1.5;
  const totalSamples = Math.ceil(sr * totalDur);

  const offCtx = new OfflineAudioContext(2, totalSamples, sr);
  await offCtx.audioWorklet.addModule('js/synth-worklet.js');

  const node = new AudioWorkletNode(offCtx, 'tb303-processor', {
    outputChannelCount: [2],
  });
  node.connect(offCtx.destination);

  const paramNames = [
    'cutoff', 'resonance', 'envMod', 'decay',
    'accentLevel', 'volume', 'tuning', 'waveform',
  ];
  for (const name of paramNames) {
    if (paramValues[name] !== undefined) {
      const p = node.parameters.get(name);
      if (p) p.setValueAtTime(paramValues[name], 0);
    }
  }

  for (let loop = 0; loop < loopCount; loop++) {
    for (let step = 0; step < 16; step++) {
      const time = (loop * 16 + step) * stepDur;
      const s = pattern.steps[step];
      const next = pattern.steps[(step + 1) % 16];

      if (s.gate) {
        node.port.postMessage({
          type: 'noteOn', note: s.note,
          accent: s.accent, slide: s.slide, time,
        });
        if (!(next.gate && next.slide)) {
          node.port.postMessage({ type: 'noteOff', time: time + stepDur * 0.75 });
        }
      } else {
        node.port.postMessage({ type: 'noteOff', time });
      }
    }
  }

  await new Promise((r) => setTimeout(r, 100));

  const buffer = await offCtx.startRendering();
  return encodeWAV(buffer);
}

// ─── Upload to Audius (direct tus + REST) ────────────

export async function uploadTrack(title, audioBlob, pattern, bpm, onProgress) {
  if (!user || !encodedJwt) throw new Error('Not authenticated with Audius');

  if (onProgress) onProgress('Finding storage node...');
  const storageNode = await getHealthyStorageNode();

  if (onProgress) onProgress('Uploading audio...');
  const audioFile = new File([audioBlob], 'pattern.wav', { type: 'audio/wav' });
  const audioUploadId = await tusUpload(storageNode, audioFile, 'audio');
  if (onProgress) onProgress('Processing audio...');
  const audioResult = await pollUploadStatus(storageNode, audioUploadId, 'audio');

  const trackCid = audioResult.results?.['320'];
  const origFileCid = audioResult.orig_file_cid;
  const origFilename = audioResult.orig_filename;
  const duration = parseInt(audioResult?.probe?.format?.duration ?? '0', 10);

  if (!trackCid) throw new Error('Audio processing did not return a track CID');

  if (onProgress) onProgress('Uploading cover art...');
  const coverResp = await fetch('assets/cover-art.png');
  const coverBlob = await coverResp.blob();
  const coverFile = new File([coverBlob], 'cover.png', { type: 'image/png' });
  const coverUploadId = await tusUpload(storageNode, coverFile, 'img_square');
  if (onProgress) onProgress('Processing cover art...');
  const coverResult = await pollUploadStatus(storageNode, coverUploadId, 'img_square');
  const coverArtCid = coverResult.orig_file_cid;

  if (onProgress) onProgress('Creating track...');

  const description = 'Uploaded from 303.audius.co';

  const trackBody = {
    title,
    genre: 'Electronic',
    description,
    bpm,
    track_cid: trackCid,
    orig_file_cid: origFileCid,
    orig_filename: origFilename || 'pattern.wav',
    duration,
  };
  if (coverArtCid) trackBody.cover_art_sizes = coverArtCid;

  const createResp = await fetch(`${API_BASE}/tracks?user_id=${encodeURIComponent(user.userId)}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${encodedJwt}`,
    },
    body: JSON.stringify(trackBody),
  });

  if (!createResp.ok) {
    const errText = await createResp.text();
    throw new Error(`Track creation failed (${createResp.status}): ${errText}`);
  }

  return await createResp.json();
}

// ─── WAV encoder ─────────────────────────────────────

function encodeWAV(buffer) {
  const numCh = buffer.numberOfChannels;
  const sr = buffer.sampleRate;
  const bps = 16;
  const len = buffer.length;
  const blockAlign = numCh * (bps / 8);
  const dataSize = len * blockAlign;
  const arrBuf = new ArrayBuffer(44 + dataSize);
  const dv = new DataView(arrBuf);

  const ws = (off, str) => {
    for (let i = 0; i < str.length; i++) dv.setUint8(off + i, str.charCodeAt(i));
  };

  ws(0, 'RIFF');
  dv.setUint32(4, 36 + dataSize, true);
  ws(8, 'WAVE');
  ws(12, 'fmt ');
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true);
  dv.setUint16(22, numCh, true);
  dv.setUint32(24, sr, true);
  dv.setUint32(28, sr * blockAlign, true);
  dv.setUint16(32, blockAlign, true);
  dv.setUint16(34, bps, true);
  ws(36, 'data');
  dv.setUint32(40, dataSize, true);

  const channels = [];
  for (let i = 0; i < numCh; i++) channels.push(buffer.getChannelData(i));

  let off = 44;
  for (let i = 0; i < len; i++) {
    for (let ch = 0; ch < numCh; ch++) {
      const s = Math.max(-1, Math.min(1, channels[ch][i]));
      dv.setInt16(off, s * 0x7FFF, true);
      off += 2;
    }
  }

  return new Blob([arrBuf], { type: 'audio/wav' });
}
