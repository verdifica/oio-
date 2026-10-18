// Personal keyword spotter for OIOIOI.
// It learns one short phrase ("oi oi oi") from a few recordings of the user's own voice and then
// listens silently, comparing each burst of sound to those recordings with dynamic time warping (DTW).
// No speech service and no downloaded model: everything runs on the phone.
(function (root) {
  'use strict';
  const N_MEL = 24, N_CEP = 12, FMIN = 150, FMAX = 5000;

  let filterCache = null;
  // Triangular mel filters over the analyser's frequency bins.
  function melFilters(nBins, sampleRate) {
    const key = nBins + ':' + sampleRate;
    if (filterCache && filterCache.key === key) return filterCache.filters;
    const mel = (f) => 2595 * Math.log10(1 + f / 700), inv = (m) => 700 * (10 ** (m / 2595) - 1);
    const hzPerBin = sampleRate / 2 / nBins;
    const pts = [];
    for (let i = 0; i < N_MEL + 2; i++) pts.push(inv(mel(FMIN) + (mel(FMAX) - mel(FMIN)) * i / (N_MEL + 1)) / hzPerBin);
    const filters = [];
    for (let m = 1; m <= N_MEL; m++) {
      const w = [];
      for (let b = Math.floor(pts[m - 1]); b <= Math.ceil(pts[m + 1]); b++) {
        const v = b < pts[m] ? (b - pts[m - 1]) / (pts[m] - pts[m - 1]) : (pts[m + 1] - b) / (pts[m + 1] - pts[m]);
        if (v > 0 && b >= 0 && b < nBins) w.push([b, v]);
      }
      filters.push(w);
    }
    filterCache = { key, filters };
    return filters;
  }

  // One frame from a dB spectrum (AnalyserNode.getFloatFrequencyData): energy in dB and power per mel band.
  function features(db, sampleRate) {
    const filters = melFilters(db.length, sampleRate);
    let total = 0;
    const mel = filters.map((w) => {
      let s = 0;
      for (const [b, v] of w) s += v * 10 ** (db[b] / 10);
      total += s;
      return s;
    });
    return { e: 10 * Math.log10(total + 1e-12), mel };
  }

  // Average mel power of quiet frames: the car's background sound.
  function noiseProfile(frames) {
    const out = new Array(N_MEL).fill(0);
    for (const f of frames) for (let m = 0; m < N_MEL; m++) out[m] += f.mel[m] / frames.length;
    return out;
  }

  // Turns frames into 12 cepstral coefficients each, which describe the shape of the voice and not its volume.
  // The background sound is subtracted first, and the average shape removed, so engine noise,
  // the microphone and the car's acoustics matter less.
  function normalize(frames, noise) {
    const cep = frames.map((f) => {
      const logMel = f.mel.map((p, m) => Math.log10(Math.max(p - (noise ? noise[m] : 0), p * 0.05) + 1e-12));
      const c = new Array(N_CEP);
      for (let k = 1; k <= N_CEP; k++) {
        let s = 0;
        for (let m = 0; m < N_MEL; m++) s += logMel[m] * Math.cos(Math.PI * k * (m + 0.5) / N_MEL);
        c[k - 1] = s;
      }
      return c;
    });
    const mean = new Array(N_CEP).fill(0);
    for (const c of cep) for (let k = 0; k < N_CEP; k++) mean[k] += c[k] / cep.length;
    return cep.map((c) => c.map((x, k) => +(x - mean[k]).toFixed(3)));
  }

  // DTW distance between two sequences of cepstral frames, averaged over the alignment path.
  function dtw(a, b) {
    const n = a.length, m = b.length, band = Math.max(4, Math.ceil(Math.max(n, m) * 0.35));
    const INF = 1e9;
    let prev = new Float64Array(m + 1).fill(INF), cur = new Float64Array(m + 1);
    let prevLen = new Float64Array(m + 1), curLen = new Float64Array(m + 1);
    prev[0] = 0;
    for (let i = 1; i <= n; i++) {
      cur.fill(INF); cur[0] = INF;
      const center = Math.round(i * m / n);
      for (let j = Math.max(1, center - band); j <= Math.min(m, center + band); j++) {
        let d = 0;
        for (let k = 0; k < a[i - 1].length; k++) { const x = a[i - 1][k] - b[j - 1][k]; d += x * x; }
        d = Math.sqrt(d);
        let best = prev[j - 1], len = prevLen[j - 1];
        if (prev[j] < best) { best = prev[j]; len = prevLen[j]; }
        if (cur[j - 1] < best) { best = cur[j - 1]; len = curLen[j - 1]; }
        cur[j] = best + d; curLen[j] = len + 1;
      }
      [prev, cur] = [cur, prev]; [prevLen, curLen] = [curLen, prevLen];
    }
    return prev[m] >= INF ? INF : prev[m] / prevLen[m];
  }

  // Cut a recording down to the phrase: the longest stretch of frames clearly above the background.
  function trim(frames) {
    const es = frames.map((f) => f.e).sort((x, y) => x - y);
    const noise = es[Math.floor(es.length * 0.2)], peak = es[es.length - 1];
    const on = Math.max(noise + 10, peak - 30);
    let best = null, start = -1, quiet = 0;
    for (let i = 0; i <= frames.length; i++) {
      const loud = i < frames.length && frames[i].e > on;
      if (loud) { if (start < 0) start = i; quiet = 0; }
      else if (start >= 0 && (++quiet > 14 || i === frames.length)) { // 14 frames ≈ 0.3 s of silence ends the phrase
        const end = i - quiet + 1;
        if (!best || end - start > best[1] - best[0]) best = [start, end];
        start = -1; quiet = 0;
      }
    }
    if (!best || peak - noise < 10) return null;
    const calm = frames.filter((f) => f.e <= noise + 3);
    return { frames: frames.slice(Math.max(0, best[0] - 2), Math.min(frames.length, best[1] + 2)), noise: noiseProfile(calm.length ? calm : frames.slice(0, 5)) };
  }

  // Splits live audio into bursts of sound separated by silence, following the background level.
  function Segmenter(opts) {
    const o = Object.assign({ startDb: 6, keepDb: 3, hangFrames: 14, maxFrames: 150 }, opts);
    let noise = null, noiseMel = null, seg = null, quiet = 0;
    const pre = [];
    this.noise = () => noise;
    this.push = (f) => {
      if (noise == null) noise = f.e;
      if (!seg) {
        // Background follows quiet moments, quickly down and slowly up.
        if (f.e < noise) noise = noise * 0.7 + f.e * 0.3; else if (f.e < noise + o.startDb) noise = noise * 0.98 + f.e * 0.02;
        if (f.e < noise + 3) noiseMel = noiseMel ? noiseMel.map((x, m) => x * 0.95 + f.mel[m] * 0.05) : f.mel.slice();
        pre.push(f); if (pre.length > 3) pre.shift();
        if (f.e > noise + o.startDb) { seg = pre.slice(); quiet = 0; }
        return null;
      }
      seg.push(f);
      quiet = f.e > noise + o.keepDb ? 0 : quiet + 1;
      if (seg.length > o.maxFrames) {
        // A sound this long is the background getting louder (engine, road, fan), not a phrase:
        // learn it as the new background from its quieter part.
        const calm = seg.slice().sort((x, y) => x.e - y.e).slice(0, Math.ceil(seg.length * 0.3));
        noise = calm[calm.length - 1].e;
        noiseMel = noiseProfile(calm);
        const done = seg; seg = null; pre.length = 0;
        return { frames: done, noise: noiseMel };
      }
      if (quiet > o.hangFrames) {
        const done = seg.slice(0, seg.length - Math.max(0, quiet - 2));
        seg = null; pre.length = 0;
        return { frames: done, noise: noiseMel };
      }
      return null;
    };
  }

  // Builds the matcher from the user's recordings (each already normalized).
  // The pass mark comes from how much the recordings differ from each other.
  const SENS = { low: 1.15, medium: 1.35, high: 1.55 };
  function model(templates, sensitivity) {
    if (!templates.length) return null;
    const lens = templates.map((t) => t.length);
    let spread = 0, pairs = 0;
    for (let i = 0; i < templates.length; i++) for (let j = i + 1; j < templates.length; j++) { spread += dtw(templates[i], templates[j]); pairs++; }
    // With one recording, use a typical value. Recordings that are almost identical would give a pass mark
    // too strict for the road, so it never goes below 5.
    const base = Math.max(5, pairs ? spread / pairs : 7);
    return {
      templates, threshold: base * (SENS[sensitivity] || SENS.medium),
      minLen: Math.min(...lens) * 0.6, maxLen: Math.max(...lens) * 1.7,
    };
  }
  function score(mdl, seg) {
    const frames = seg.frames;
    if (frames.length < mdl.minLen || frames.length > mdl.maxLen) return { dist: Infinity, ok: false, reason: 'length', len: frames.length };
    const seq = normalize(frames, seg.noise);
    const dist = Math.min(...mdl.templates.map((t) => dtw(seq, t)));
    return { dist, ok: dist < mdl.threshold, len: frames.length };
  }

  const api = { features, normalize, dtw, trim, Segmenter, model, score };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.KWS = api;
})(this);
