#!/usr/bin/env python3
"""Paper Clash 2 - procedural sound effects and music generator.

Everything here is synthesised from scratch (no samples, no third-party audio):
Karplus-Strong plucks, FM bells/mallets, additive pads/brass/bass, filtered
noise for paper and scissor textures, a Freeverb-style reverb (8 lowpass-
feedback combs + 4 allpasses, rendered to an impulse response and applied by
FFT convolution), gentle RMS compression and a look-ahead limiter.

Deterministic: every random source is a numpy Generator seeded from the sound
name, and the OGG stream serial numbers are fixed, so re-running produces
byte-identical files.

Usage (from the project root):
    tools/.venv/bin/python tools/audio/gen_audio.py            # everything
    tools/.venv/bin/python tools/audio/gen_audio.py --only coin,snip
    tools/.venv/bin/python tools/audio/gen_audio.py --sfx-only | --music-only
    tools/.venv/bin/python tools/audio/gen_audio.py --wav      # 16-bit WAV fallback
"""
import argparse
import io
import itertools
import math
import re
import time
import wave
import zlib
from pathlib import Path

import numpy as np

try:
    import soundfile as sf
except Exception:  # pragma: no cover - fallback route
    sf = None

SR = 44100
BASE_SEED = 20261115
ROOT = Path(__file__).resolve().parents[2]
SFX_DIR = ROOT / "app" / "src" / "main" / "assets" / "sfx"
MUSIC_DIR = ROOT / "app" / "src" / "main" / "assets" / "music"

SFX_PEAK_DB = -1.0
MUSIC_PEAK_DB = -3.0
SFX_QUALITY = 0.6      # Vorbis quality (0..1); SFX are tiny, keep transients clean
MUSIC_QUALITY = 0.45   # Vorbis quality for the music loops (~110-130 kbps stereo)
TWO_PI = 2.0 * np.pi


# ----------------------------------------------------------------------------
# basics
# ----------------------------------------------------------------------------

def rng_for(name):
    return np.random.default_rng((zlib.crc32(name.encode()) ^ BASE_SEED) & 0xFFFFFFFF)


def nsamp(sec):
    return max(1, int(round(sec * SR)))


def tvec(n):
    return np.arange(n, dtype=np.float64) / SR


def db(x):
    return 20.0 * np.log10(np.maximum(np.abs(x), 1e-12))


def undb(d):
    return 10.0 ** (np.asarray(d, dtype=float) / 20.0)


def midi_hz(m):
    return 440.0 * 2.0 ** ((np.asarray(m, dtype=float) - 69.0) / 12.0)


_PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def note(s):
    """'C#5' -> 73 (MIDI)."""
    s = s.strip()
    pc = _PC[s[0].upper()]
    i = 1
    while i < len(s) and s[i] in "#b":
        pc += 1 if s[i] == "#" else -1
        i += 1
    return 12 * (int(s[i:]) + 1) + pc


def hz(s):
    return float(midi_hz(note(s)))


def peak(x):
    return float(np.max(np.abs(x))) if len(x) else 0.0


def mix_at(dst, src, start, gain=1.0):
    """Add src into dst at sample offset start (clipped to dst bounds)."""
    if start >= len(dst):
        return
    s0 = 0
    if start < 0:
        s0 = -start
        start = 0
    n = min(len(src) - s0, len(dst) - start)
    if n > 0:
        dst[start:start + n] += gain * src[s0:s0 + n]


def pad_to(x, n):
    if len(x) >= n:
        return x[:n]
    out = np.zeros(n)
    out[:len(x)] = x
    return out


# ----------------------------------------------------------------------------
# envelopes
# ----------------------------------------------------------------------------

def env_exp(n, tau, delay=0.0):
    t = tvec(n) - delay
    e = np.exp(-np.maximum(t, 0.0) / max(tau, 1e-6))
    e[t < 0] = 0.0
    return e


def env_ad(n, att, tau, delay=0.0, curve=1.0):
    """Raised-cosine attack of `att` s then exponential decay (time constant tau)."""
    t = tvec(n) - delay
    e = np.zeros(n)
    a = max(att, 1e-5)
    rise = (t >= 0) & (t < a)
    e[rise] = (0.5 - 0.5 * np.cos(np.pi * t[rise] / a)) ** curve
    fall = t >= a
    e[fall] = np.exp(-(t[fall] - a) / max(tau, 1e-6))
    return e


def env_adsr(n, att, dec, sus, rel, gate):
    """Classic ADSR; gate = note length in seconds before release starts."""
    t = tvec(n)
    e = np.zeros(n)
    a = max(att, 1e-4)
    m = t < a
    e[m] = 0.5 - 0.5 * np.cos(np.pi * t[m] / a)
    m = t >= a
    e[m] = sus + (1.0 - sus) * np.exp(-(t[m] - a) / max(dec, 1e-4))
    g = max(gate, a)
    lvl = sus + (1.0 - sus) * math.exp(-(g - a) / max(dec, 1e-4)) if g > a else 1.0
    m = t >= g
    e[m] = lvl * np.exp(-(t[m] - g) / max(rel, 1e-4))
    return e


def env_points(n, pts):
    """Piecewise-linear envelope from [(time_s, value), ...]."""
    ts = np.array([p[0] for p in pts], dtype=float)
    vs = np.array([p[1] for p in pts], dtype=float)
    return np.interp(tvec(n), ts, vs)


def fade(x, fin=0.0, fout=0.0):
    x = x.copy()
    n = len(x)
    a = min(n, int(round(fin * SR)))
    if a > 0:
        x[:a] *= 0.5 - 0.5 * np.cos(np.pi * np.arange(a) / a)
    b = min(n, int(round(fout * SR)))
    if b > 0:
        x[n - b:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(1, b + 1) / b)
    return x


# ----------------------------------------------------------------------------
# oscillators
# ----------------------------------------------------------------------------

def phase_of(freq, n):
    f = np.broadcast_to(np.asarray(freq, dtype=float), (n,))
    ph = np.cumsum(f) * (TWO_PI / SR)
    return ph - ph[0]


def sine(freq, n, ph0=0.0):
    if np.isscalar(freq):
        return np.sin(TWO_PI * freq * tvec(n) + ph0)
    return np.sin(phase_of(freq, n) + ph0)


def glide(f0, f1, n, tau=None, shape="exp"):
    """Frequency trajectory from f0 to f1 over n samples (exp = musical glide)."""
    x = np.linspace(0.0, 1.0, n)
    if tau is not None:  # exponential approach with time constant tau (s)
        k = 1.0 - np.exp(-tvec(n) / tau)
        return f0 * (f1 / f0) ** k if shape == "exp" else f0 + (f1 - f0) * k
    return f0 * (f1 / f0) ** x if shape == "exp" else f0 + (f1 - f0) * x


def additive(f0, n, partials, rng=None, vib=None):
    """Sum of decaying partials: [(ratio, amp, tau_s or None), ...]."""
    out = np.zeros(n)
    t = tvec(n)
    base_ph = phase_of(f0 * (1.0 if vib is None else vib), n) if vib is not None else None
    for k, (ratio, amp, tau) in enumerate(partials):
        if f0 * ratio >= 19500.0 or amp == 0.0:
            continue
        ph0 = rng.uniform(0, TWO_PI) if rng is not None else 0.0
        if base_ph is None:
            p = np.sin(TWO_PI * f0 * ratio * t + ph0)
        else:
            p = np.sin(base_ph * ratio + ph0)
        if tau is not None:
            p *= np.exp(-t / tau)
        out += amp * p
    return out


def fm(fc, n, ratio, index, amp_env=None, index_env=None, ph_mod=0.0, fc_traj=None):
    """2-operator FM; the modulation index is capped to keep sidebands < 19 kHz."""
    fmod = fc * ratio
    imax = max(0.0, (19000.0 - fc) / max(fmod, 1.0) - 1.0)
    idx = index if index_env is None else index * index_env
    idx = np.minimum(idx, imax)
    if fc_traj is None:
        t = tvec(n)
        y = np.sin(TWO_PI * fc * t + idx * np.sin(TWO_PI * fmod * t + ph_mod))
    else:
        pc = phase_of(fc_traj, n)
        y = np.sin(pc + idx * np.sin(pc * ratio + ph_mod))
    return y if amp_env is None else y * amp_env


def saw_additive(f0, n, cutoff=None, max_h=60, rng=None, tilt=1.0, odd_only=False,
                 reso=0.0, vib=None):
    """Band-limited saw/square by additive synthesis with a (possibly
    time-varying) 2-pole low-pass spectral envelope and optional resonance bump.
    cutoff: scalar or array (n,) in Hz."""
    out = np.zeros(n)
    if vib is None:
        base_ph = TWO_PI * f0 * tvec(n)
        fmax = f0
    else:
        base_ph = phase_of(f0 * vib, n)
        fmax = f0 * float(np.max(vib))
    cut = None if cutoff is None else np.asarray(cutoff, dtype=float)
    for h in range(1, max_h + 1):
        if odd_only and h % 2 == 0:
            continue
        fh = fmax * h
        if fh >= 18500.0:
            break
        amp = 1.0 / h ** tilt
        if cut is not None:
            r = (f0 * h) / np.maximum(cut, 1.0)
            g = 1.0 / np.sqrt(1.0 + r ** 4)
            if reso > 0.0:
                g = g * (1.0 + reso * np.exp(-((r - 1.0) / 0.25) ** 2))
            amp = amp * g
        ph0 = rng.uniform(0, TWO_PI) if rng is not None else 0.0
        out += amp * np.sin(base_ph * h + ph0)
    return out


# ----------------------------------------------------------------------------
# noise and FFT-domain filtering
# ----------------------------------------------------------------------------

def mag_lp(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (np.maximum(f, 0.0) / fc) ** (2 * order))


def mag_hp(f, fc, order=2):
    r = np.maximum(f, 1e-3) / fc
    return r ** order / np.sqrt(1.0 + r ** (2 * order))


def mag_bp(f, lo, hi, order=2):
    return mag_hp(f, lo, order) * mag_lp(f, hi, order)


def mag_peak(f, fc, q=4.0, gain_db=6.0):
    g = undb(gain_db) - 1.0
    return 1.0 + g / (1.0 + (q * (f / fc - fc / np.maximum(f, 1e-3))) ** 2)


def mag_shelf(f, fc, gain_db, high=False):
    """Smooth first-order-like shelf (gain in dB applied below/above fc)."""
    r = np.maximum(f, 1e-3) / fc
    w = 1.0 / (1.0 + r ** 2) if not high else r ** 2 / (1.0 + r ** 2)
    return undb(gain_db * w)


def fft_filter(x, magfun, pad=4096):
    """Zero-phase filtering with an arbitrary magnitude response magfun(freqs)."""
    n = len(x)
    nfft = 1 << (n + pad - 1).bit_length()
    X = np.fft.rfft(x, nfft)
    f = np.fft.rfftfreq(nfft, 1.0 / SR)
    return np.fft.irfft(X * magfun(f), nfft)[:n]


def noise(n, rng):
    return rng.standard_normal(n)


def band_noise(n, rng, lo, hi, order=2):
    y = fft_filter(noise(n, rng), lambda f: mag_bp(f, lo, hi, order))
    return y / (np.std(y) + 1e-12)


def lp_noise(n, rng, fc, order=2):
    y = fft_filter(noise(n, rng), lambda f: mag_lp(f, fc, order))
    return y / (np.std(y) + 1e-12)


def hp_noise(n, rng, fc, order=2):
    y = fft_filter(noise(n, rng), lambda f: mag_hp(f, fc, order))
    return y / (np.std(y) + 1e-12)


def tv_noise(n, rng, center, bw_oct=1.0, win=1024, hop=256, floor_hz=60.0):
    """Noise through a time-varying band-pass (log-Gaussian band) via STFT.
    center / bw_oct: scalar or per-sample arrays."""
    center = np.broadcast_to(np.asarray(center, dtype=float), (n,))
    bw = np.broadcast_to(np.asarray(bw_oct, dtype=float), (n,))
    x = rng.standard_normal(n + win)
    w = np.sqrt(np.hanning(win + 1)[:win])
    f = np.fft.rfftfreq(win, 1.0 / SR)
    lf = np.log2(np.maximum(f, floor_hz))
    y = np.zeros(n + win)
    for s in range(0, n, hop):
        c = center[min(s, n - 1)]
        sig = max(bw[min(s, n - 1)], 0.05) / 2.0
        m = np.exp(-0.5 * ((lf - math.log2(max(c, floor_hz))) / sig) ** 2)
        seg = np.fft.irfft(np.fft.rfft(x[s:s + win] * w) * m, win) * w
        y[s:s + win] += seg
    y = y[win // 2: win // 2 + n] / (win / (2.0 * hop))
    return y / (np.std(y) + 1e-12)


def crackle(n, rng, rate, lo=1800.0, hi=9000.0, amp_sigma=0.7, grain_ms=(0.15, 1.6)):
    """Paper crinkle: Poisson-distributed micro-grains, band-limited.
    rate: grains per second (scalar or per-sample array)."""
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    hits = rng.random(n) < rate / SR
    idx = np.nonzero(hits)[0]
    out = np.zeros(n)
    if len(idx) == 0:
        return out
    amps = rng.lognormal(0.0, amp_sigma, len(idx)) * rng.choice([-1.0, 1.0], len(idx))
    # three grain lengths for a less mechanical texture
    kinds = rng.integers(0, 3, len(idx))
    for k in range(3):
        sel = idx[kinds == k]
        if len(sel) == 0:
            continue
        imp = np.zeros(n)
        imp[sel] = amps[kinds == k]
        g_ms = grain_ms[0] + (grain_ms[1] - grain_ms[0]) * (k / 2.0)
        gl = max(8, int(g_ms * 1e-3 * SR * 4))
        kern = np.exp(-np.arange(gl) / (g_ms * 1e-3 * SR)) * rng.standard_normal(gl)
        out += np.convolve(imp, kern)[:n]
    out = fft_filter(out, lambda f: mag_bp(f, lo, hi, 2))
    return out / (np.percentile(np.abs(out), 99.5) + 1e-12)


# ----------------------------------------------------------------------------
# Karplus-Strong (block-vectorised: the loop delay is always >= block length)
# ----------------------------------------------------------------------------

def ks_pluck(f, n, rng, decay=1.2, bright=0.6, pick=0.18, exc=None):
    """Plucked string. decay = time (s) for the fundamental to fall 60 dB.
    bright 0..1 shapes the excitation spectrum; pick = pluck position 0..0.5."""
    L = SR / f
    M = int(math.floor(L - 0.5))
    M = max(M, 2)
    frac = (L - 0.5) - M
    t0, t1, t2 = 0.5 * (1.0 - frac), 0.5, 0.5 * frac
    g = 10.0 ** (-3.0 * (L / SR) / max(decay, 1e-3))
    P = M + 2
    if exc is None:
        e = rng.uniform(-1.0, 1.0, P)
        # brightness: repeated 3-tap smoothing of the noise burst
        for _ in range(int(round((1.0 - bright) * 6))):
            e = np.convolve(e, [0.25, 0.5, 0.25], mode="same")
        d = max(1, int(round(pick * M)))
        e = e - np.concatenate([np.zeros(d), e[:-d]])
        e -= e.mean()
        e /= peak(e) + 1e-12
    else:
        e = exc
    x = np.zeros(n)
    x[:min(n, len(e))] = e[:n]
    o = M + 2
    y = np.zeros(n + o)
    for s in range(0, n, M):
        en = min(s + M, n)
        y[o + s:o + en] = x[s:en] + g * (t0 * y[o + s - M:o + en - M]
                                         + t1 * y[o + s - M - 1:o + en - M - 1]
                                         + t2 * y[o + s - M - 2:o + en - M - 2])
    return y[o:]


# ----------------------------------------------------------------------------
# Freeverb-style reverb -> impulse response
# ----------------------------------------------------------------------------

COMB_TUNING = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617]
AP_TUNING = [556, 441, 341, 225]


def _comb_lp(x, D, g, d, K=48):
    """Freeverb lowpass-feedback comb: out[i] = w[i-D];
    w[i] = x[i] + g * lp[i];  lp[i] = (1-d) w[i-D] + d lp[i-1]."""
    h = (1.0 - d) * d ** np.arange(K) if d > 1e-9 else np.array([1.0])
    K = len(h)
    n = len(x)
    o = D + K
    w = np.zeros(n + o)
    for s in range(0, n, D):
        e = min(s + D, n)
        seg = w[o + s - D - (K - 1):o + e - D]
        lp = np.convolve(seg, h, mode="valid")
        w[o + s:o + e] = x[s:e] + g * lp
    out = np.zeros(n)
    out[D:] = w[o:o + n - D]
    return out


def _allpass(x, D, g=0.5):
    n = len(x)
    v = np.zeros(n + D)
    for s in range(0, n, D):
        e = min(s + D, n)
        v[D + s:D + e] = x[s:e] + g * v[s:e]
    return v[:n] - x


def freeverb_ir(seconds=2.5, room=0.7, damp=0.5, size=1.0, spread=23, predelay=0.012,
                stereo=True):
    """Impulse response of a Freeverb network (unit energy per channel).
    room 0..1 -> comb feedback 0.70..0.98, damp 0..1, size scales the delays."""
    n = nsamp(seconds)
    imp = np.zeros(n)
    imp[0] = 1.0
    g = room * 0.28 + 0.70
    d = damp * 0.4
    chans = []
    for sp in ((0, spread) if stereo else (0,)):
        acc = np.zeros(n)
        for c in COMB_TUNING:
            acc += _comb_lp(imp, max(8, int(round((c + sp) * size))), g, d)
        for a in AP_TUNING:
            acc = _allpass(acc, max(4, int(round((a + sp) * size))))
        tail = int(n * 0.25)
        acc[n - tail:] *= np.linspace(1.0, 0.0, tail) ** 2
        acc /= math.sqrt(np.sum(acc ** 2)) + 1e-12
        pre = np.zeros(nsamp(predelay)) if predelay > 0 else np.zeros(0)
        chans.append(np.concatenate([pre, acc]))
    return np.stack(chans, axis=1) if stereo else chans[0]


def fft_convolve(x, h):
    n = len(x) + len(h) - 1
    nfft = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, nfft) * np.fft.rfft(h, nfft), nfft)[:n]


_IR_CACHE = {}


def reverb_mono(x, wet=0.2, seconds=1.2, room=0.45, damp=0.55, size=0.7, predelay=0.006,
                tail=True):
    """Small room for SFX; returns dry + wet (mono), keeping the tail."""
    key = (seconds, room, damp, size, predelay)
    if key not in _IR_CACHE:
        _IR_CACHE[key] = freeverb_ir(seconds, room, damp, size, predelay=predelay, stereo=False)
    ir = _IR_CACHE[key]
    y = fft_convolve(x, ir) * wet
    if not tail:
        y = y[:len(x)]
    out = np.zeros(len(y))
    out[:len(x)] += x
    return out + y


def fold(x, L):
    """Wrap everything beyond L back onto the start (circular / steady-state loop)."""
    out = np.zeros((L,) + x.shape[1:])
    for s in range(0, len(x), L):
        seg = x[s:s + L]
        out[:len(seg)] += seg
    return out


# ----------------------------------------------------------------------------
# dynamics (vectorised, optionally circular for seamless loops)
# ----------------------------------------------------------------------------

def _pad(x, h, circular):
    if circular:
        return np.concatenate([x[-h:], x, x[:h]])
    return np.concatenate([np.full(h, x[0]), x, np.full(h, x[-1])])


def sliding_min(x, w, circular=False):
    """Centred running minimum over an odd window w (van Herk / Gil-Werman)."""
    w = int(w) | 1
    h = w // 2
    xp = _pad(x, h, circular)
    n = len(xp)
    nb = -(-n // w)
    buf = np.full(nb * w, np.inf)
    buf[:n] = xp
    B = buf.reshape(nb, w)
    pre = np.minimum.accumulate(B, axis=1).ravel()
    suf = np.minimum.accumulate(B[:, ::-1], axis=1)[:, ::-1].ravel()
    m = len(x)
    j = np.arange(m)
    return np.minimum(suf[j], pre[np.minimum(j + w - 1, len(pre) - 1)])


def moving_avg(x, w, circular=False):
    w = int(w) | 1
    h = w // 2
    xp = _pad(x, h, circular)
    c = np.concatenate([[0.0], np.cumsum(xp)])
    return (c[w:] - c[:-w])[:len(x)] / w


def limiter(x, ceiling, win_ms=6.0, circular=False):
    """Look-ahead brick-wall limiter: the smoothed gain never exceeds the
    instantaneous required gain, so no sample passes the ceiling."""
    a = np.max(np.abs(x), axis=1) if x.ndim == 2 else np.abs(x)
    g = np.minimum(1.0, ceiling / np.maximum(a, 1e-12))
    w = max(3, int(win_ms * 1e-3 * SR)) | 1
    gm = sliding_min(g, 2 * w + 1, circular)
    half = (w // 2) | 1
    gs = moving_avg(moving_avg(gm, half, circular), half, circular)
    gs = np.minimum(gs, g)  # numerical safety
    return x * (gs[:, None] if x.ndim == 2 else gs)


def compressor(x, thresh_db=-18.0, ratio=2.0, knee_db=8.0, rms_ms=40.0, smooth_ms=120.0,
               circular=False):
    p = np.mean(x ** 2, axis=1) if x.ndim == 2 else x ** 2
    env = moving_avg(p, max(3, int(rms_ms * 1e-3 * SR)), circular)
    lvl = 10.0 * np.log10(np.maximum(env, 1e-14))
    over = lvl - thresh_db
    k = knee_db / 2.0
    gd = np.where(over <= -k, 0.0,
                  np.where(over >= k, -(1.0 - 1.0 / ratio) * over,
                           -(1.0 - 1.0 / ratio) * (over + k) ** 2 / (2.0 * knee_db)))
    sw = max(3, int(smooth_ms * 1e-3 * SR))
    gd = moving_avg(moving_avg(gd, sw, circular), sw, circular)
    g = undb(gd)
    return x * (g[:, None] if x.ndim == 2 else g)


def soft_clip(x, drive=1.0):
    return np.tanh(drive * x) / math.tanh(drive)


# ----------------------------------------------------------------------------
# loudness (BS.1770 K-weighting, magnitude applied in the FFT domain)
# ----------------------------------------------------------------------------

def _kweight_mag(f):
    w = TWO_PI * f / SR
    z1 = np.exp(-1j * w)
    z2 = z1 * z1
    # stage 1: high shelf (+4 dB above ~1.7 kHz)
    f0, G, Q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    K = math.tan(math.pi * f0 / SR)
    Vh = 10 ** (G / 20.0)
    Vb = Vh ** 0.4996667741545416
    a0 = 1.0 + K / Q + K * K
    b = [(Vh + Vb * K / Q + K * K) / a0, 2.0 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0]
    a = [1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0]
    h1 = (b[0] + b[1] * z1 + b[2] * z2) / (a[0] + a[1] * z1 + a[2] * z2)
    # stage 2: RLB high-pass (~38 Hz)
    f0, Q = 38.13547087602444, 0.5003270373238773
    K = math.tan(math.pi * f0 / SR)
    a0 = 1.0 + K / Q + K * K
    a = [1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0]
    h2 = (1.0 - 2.0 * z1 + z2) / (a[0] + a[1] * z1 + a[2] * z2)
    return np.abs(h1 * h2)


def kweight(x):
    if x.ndim == 2:
        return np.stack([fft_filter(x[:, c], _kweight_mag) for c in range(x.shape[1])], 1)
    return fft_filter(x, _kweight_mag)


def loudness_momentary(x, win_ms=100.0):
    """Max short-window K-weighted loudness, LUFS-style (-0.691 offset)."""
    y = kweight(x)
    p = np.sum(y ** 2, axis=1) if y.ndim == 2 else y ** 2
    w = max(3, int(win_ms * 1e-3 * SR)) | 1
    pz = np.concatenate([np.zeros(w), p, np.zeros(w)])
    c = np.concatenate([[0.0], np.cumsum(pz)])
    ms = (c[w:] - c[:-w]) / w
    return -0.691 + 10.0 * math.log10(max(float(np.max(ms)), 1e-14))


def loudness_integrated(x):
    """Ungated programme loudness (fine for dense music loops)."""
    y = kweight(x)
    p = np.sum(y ** 2, axis=1) if y.ndim == 2 else y ** 2
    return -0.691 + 10.0 * math.log10(max(float(np.mean(p)), 1e-14))


def rms_db(x):
    return 10.0 * math.log10(max(float(np.mean(x ** 2)), 1e-14))


# ----------------------------------------------------------------------------
# encoding (OGG Vorbis with fixed stream serial -> byte-identical re-runs)
# ----------------------------------------------------------------------------

_REV8 = bytes(int("{:08b}".format(i)[::-1], 2) for i in range(256))


def ogg_crc(data):
    """Ogg page CRC (poly 0x04C11DB7, MSB-first, init 0) via zlib's reflected CRC."""
    r = zlib.crc32(data.translate(_REV8), 0xFFFFFFFF) ^ 0xFFFFFFFF
    return int("{:032b}".format(r)[::-1], 2)


def ogg_set_serial(data, serial):
    out = bytearray(data)
    pos = 0
    ser = (serial & 0x7FFFFFFF).to_bytes(4, "little")
    while pos < len(out):
        if out[pos:pos + 4] != b"OggS":
            raise ValueError("bad Ogg page at %d" % pos)
        nseg = out[pos + 26]
        size = 27 + nseg + sum(out[pos + 27:pos + 27 + nseg])
        out[pos + 14:pos + 18] = ser
        out[pos + 22:pos + 26] = b"\0\0\0\0"
        crc = ogg_crc(bytes(out[pos:pos + size]))
        out[pos + 22:pos + 26] = crc.to_bytes(4, "little")
        pos += size
    return bytes(out)


def encode_ogg(x, quality, serial):
    buf = io.BytesIO()
    sf.write(buf, np.asarray(x, dtype=np.float32), SR, format="OGG", subtype="VORBIS",
             compression_level=float(np.clip(1.0 - quality, 0.0, 1.0)))
    return ogg_set_serial(buf.getvalue(), serial)


def decode_bytes(data):
    y, _ = sf.read(io.BytesIO(data), dtype="float64", always_2d=False)
    return y


def encode_wav(x, sr):
    x = np.asarray(x, dtype=np.float64)
    pcm = np.clip(np.round(x * 32767.0), -32768, 32767).astype("<i2")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1 if x.ndim == 1 else x.shape[1])
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


def decode_wav(data):
    with wave.open(io.BytesIO(data), "rb") as w:
        ch = w.getnchannels()
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return raw.reshape(-1, ch) if ch > 1 else raw


def save_audio(path, x, peak_db, quality, wav=False, sr=SR, tol_db=0.05, max_tries=40):
    """Normalise so the *decoded* file peaks at peak_db, encode, write.
    Returns (decoded signal, bytes written)."""
    target = float(undb(peak_db))
    y0 = np.asarray(x, dtype=np.float64) * (target / max(peak(x), 1e-12))
    serial = zlib.crc32(path.name.encode()) & 0x7FFFFFFF

    def attempt(g):
        d_ = encode_wav(y0 * g, sr) if wav else encode_ogg(y0 * g, quality, serial)
        dec_ = decode_wav(d_) if wav else decode_bytes(d_)
        return d_, dec_, 20.0 * math.log10(peak(dec_) / target)

    data, dec, err = attempt(1.0)
    ok = lambda e: -tol_db <= e <= 0.005  # noqa: E731  (never above the target)
    if not wav and not ok(err):
        best = (abs(err) + (1.0 if err > 0.005 else 0.0), data, dec, err)
        g0 = float(undb(-err))
        steps = [0.0] + [s_ * k for k in range(1, max_tries) for s_ in (-0.03, 0.03)]
        for dlt in steps[:max_tries]:
            d_, dec_, e_ = attempt(g0 * float(undb(dlt)))
            score = abs(e_) + (1.0 if e_ > 0.005 else 0.0)
            if score < best[0]:
                best = (score, d_, dec_, e_)
            if ok(e_):
                break
        _, data, dec, err = best
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    return dec, len(data)


# ----------------------------------------------------------------------------
# tonal voices (mono; each returns the note plus its natural release tail)
# ----------------------------------------------------------------------------

def _att(n, sec=0.0015):
    return env_ad(n, sec, 1e9)


def v_kalimba(f, dur, vel, rng, ring=1.4):
    n = nsamp(min(dur, ring) + ring)
    t = tvec(n)
    tau = float(np.clip(1.6 * (440.0 / f) ** 0.4, 0.35, 2.6))
    y = np.sin(TWO_PI * f * t) * np.exp(-t / tau)
    y += 0.10 * np.sin(TWO_PI * 2.0 * f * t + 0.7) * np.exp(-t / (tau * 0.3))
    if f * 5.93 < 18000:
        y += (0.18 + 0.12 * vel) * np.sin(TWO_PI * 5.93 * f * t + 1.1) * np.exp(-t / 0.05)
    click = band_noise(n, rng, 1800, 7000) * np.exp(-t / 0.0022) * 0.07 * vel
    y = y * _att(n, 0.0012) + click
    off = nsamp(dur + 0.25)
    if off < n:  # gentle damping long after the written note (keeps the mix clean)
        y[off:] *= np.exp(-(t[off:] - t[off]) / 0.35)
    return y * (0.55 + 0.45 * vel)


def v_marimba(f, dur, vel, rng):
    tau = float(np.clip(0.85 * (220.0 / f) ** 0.55, 0.12, 1.3))
    n = nsamp(max(min(dur, 0.8) + 1.0, 4.5 * tau))
    t = tvec(n)
    hard = 0.35 + 0.65 * vel
    y = np.sin(TWO_PI * f * t) * np.exp(-t / tau)
    for ratio, amp, k in ((3.93, 0.30, 0.16), (9.2, 0.07, 0.05)):
        if f * ratio < 18500:
            y += amp * hard * np.sin(TWO_PI * ratio * f * t + 0.5) * np.exp(-t / (tau * k))
    knock = lp_noise(n, rng, 1600) * np.exp(-t / 0.003) * 0.05 * hard
    return (y * _att(n, 0.001) + knock) * (0.55 + 0.45 * vel)


def v_xylo(f, dur, vel, rng):
    tau = float(np.clip(0.45 * (440.0 / f) ** 0.5, 0.08, 0.6))
    n = nsamp(max(0.7, 5.0 * tau))
    t = tvec(n)
    y = np.sin(TWO_PI * f * t) * np.exp(-t / tau)
    if f * 3.0 < 18500:
        y += 0.35 * vel * np.sin(TWO_PI * 3.0 * f * t) * np.exp(-t / (tau * 0.35))
    if f * 6.1 < 18500:
        y += 0.10 * vel * np.sin(TWO_PI * 6.1 * f * t) * np.exp(-t / (tau * 0.12))
    knock = band_noise(n, rng, 1500, 8000) * np.exp(-t / 0.0018) * 0.10 * vel
    return (y * _att(n, 0.0008) + knock) * (0.55 + 0.45 * vel)


def v_glock(f, dur, vel, rng, tau=1.1):
    """Free-bar partials (1, 2.76, 5.40, 8.93) - bright glockenspiel/celesta."""
    n = nsamp(tau * 3.2)
    t = tvec(n)
    y = np.zeros(n)
    for ratio, amp, k in ((1.0, 1.0, 1.0), (2.76, 0.42, 0.32), (5.40, 0.20, 0.13), (8.93, 0.08, 0.06)):
        if f * ratio < 18500:
            y += amp * np.sin(TWO_PI * ratio * f * t + ratio) * np.exp(-t / (tau * k))
    tick = hp_noise(n, rng, 5000) * np.exp(-t / 0.0012) * 0.05 * vel
    return (y * _att(n, 0.0007) + tick) * (0.5 + 0.5 * vel)


def v_bell(f, dur, vel, rng, ratio=3.5, index=2.4, tau=1.3, itau=0.22):
    """Chowning-style FM bell (inharmonic ratio, decaying index)."""
    n = nsamp(tau * 3.0)
    t = tvec(n)
    amp = env_ad(n, 0.0012, tau)
    y = fm(f, n, ratio, index * (0.5 + 0.5 * vel), amp, np.exp(-t / itau) + 0.08)
    y += 0.25 * np.sin(TWO_PI * 2.0 * f * t) * np.exp(-t / (tau * 0.35))
    return y * (0.55 + 0.45 * vel)


def v_pluck(f, dur, vel, rng, decay=1.1, bright=0.55, mute=0.10, pick=0.18):
    ring = min(dur, decay)
    n = nsamp(ring + mute * 5 + 0.02)
    y = ks_pluck(f, n, rng, decay=decay, bright=float(np.clip(bright * (0.75 + 0.35 * vel), 0, 1)),
                 pick=pick)
    off = nsamp(dur)
    if off < n:
        y[off:] *= np.exp(-(tvec(n - off)) / mute)
    y = fft_filter(y, lambda fr: mag_hp(fr, 70.0, 1) * mag_peak(fr, 2600.0, 1.2, 2.0))
    return y * (0.55 + 0.45 * vel)


def v_harp(f, dur, vel, rng):
    return v_pluck(f, dur, vel, rng, decay=2.2, bright=0.45, mute=0.6, pick=0.12)


def v_pizz(f, dur, vel, rng):
    return v_pluck(f, min(dur, 0.25), vel, rng, decay=0.45, bright=0.5, mute=0.08, pick=0.22)


def v_bass(f, dur, vel, rng, squelch=0.6):
    """Playful round synth bass: saw+square additive with a snappy filter env."""
    rel = 0.05
    n = nsamp(dur + 5 * rel)
    t = tvec(n)
    cut = 300.0 + (900.0 + 2000.0 * vel) * np.exp(-t / 0.07)
    y = 0.75 * saw_additive(f, n, cutoff=cut, max_h=40, reso=squelch)
    y += 0.45 * saw_additive(f, n, cutoff=cut * 0.8, max_h=31, odd_only=True)
    y += 0.55 * np.sin(TWO_PI * f * t)
    amp = env_adsr(n, 0.003, 0.35, 0.65, rel, dur)
    return soft_clip(y * amp * 0.9, 1.6) * (0.6 + 0.4 * vel)


def v_softbass(f, dur, vel, rng):
    """Warm finger-ish bass for the menu (sine + few harmonics)."""
    rel = 0.12
    n = nsamp(dur + 5 * rel)
    t = tvec(n)
    y = np.sin(TWO_PI * f * t) + 0.35 * np.sin(TWO_PI * 2 * f * t) * np.exp(-t / 0.25) \
        + 0.12 * np.sin(TWO_PI * 3 * f * t) * np.exp(-t / 0.12)
    thump = lp_noise(n, rng, 900) * np.exp(-t / 0.006) * 0.08
    amp = env_adsr(n, 0.006, 0.6, 0.55, rel, dur)
    return soft_clip((y * amp + thump) * 0.8, 1.3) * (0.6 + 0.4 * vel)


def v_tuba(f, dur, vel, rng):
    """Bouncy cartoon low brass for the boss oom-pah."""
    rel = 0.06
    n = nsamp(dur + 5 * rel)
    t = tvec(n)
    amp = env_adsr(n, 0.012, 0.12, 0.6, rel, dur)
    cut = f * 2.0 + 900.0 * vel * amp ** 1.5
    y = saw_additive(f, n, cutoff=cut, max_h=30) + 0.4 * np.sin(TWO_PI * f * t)
    return soft_clip(y * amp, 1.4) * (0.6 + 0.4 * vel)


def v_lead(f, dur, vel, rng):
    """Toy-piano-ish FM lead: 1:1 FM body + short 3.5:1 tine at the attack."""
    n = nsamp(dur + 0.7)
    t = tvec(n)
    amp = env_adsr(n, 0.004, 0.22, 0.5, 0.14, dur)
    vibd = np.clip((t - 0.22) / 0.2, 0, 1) * 0.0045
    traj = f * (1.0 + vibd * np.sin(TWO_PI * 5.4 * t))
    y = fm(f, n, 1.0, 1.0, amp, 0.45 + 1.6 * vel * np.exp(-t / 0.11), fc_traj=traj)
    tine = fm(2.0 * f, n, 3.5, 1.2, np.exp(-t / 0.045), None)
    y += 0.22 * vel * tine * _att(n, 0.0008)
    return y * (0.55 + 0.45 * vel)


def v_square_lead(f, dur, vel, rng):
    """Soft square/whistle counter-melody voice with delayed vibrato."""
    n = nsamp(dur + 0.5)
    t = tvec(n)
    amp = env_adsr(n, 0.012, 0.3, 0.7, 0.1, dur)
    vib = 1.0 + np.clip((t - 0.15) / 0.25, 0, 1) * 0.006 * np.sin(TWO_PI * 5.6 * t + 0.3)
    y = saw_additive(f, n, cutoff=2600.0, max_h=21, odd_only=True, vib=vib, tilt=1.15)
    y += 0.4 * np.sin(phase_of(f * vib, n))
    return y * amp * (0.55 + 0.45 * vel)


def v_brass(f, dur, vel, rng, bend=0.0):
    """Additive brass: brightness tracks loudness, pitch scoop, delayed vibrato.
    bend: semitones of fall over the last 0.25 s of the note (cartoon 'bwahh')."""
    rel = 0.14
    n = nsamp(dur + 5 * rel)
    t = tvec(n)
    amp = env_adsr(n, 0.028, 0.25, 0.72, rel, dur)
    scoop = 2.0 ** (-0.3 / 12.0 * np.exp(-t / 0.035))
    if bend:
        scoop = scoop * 2.0 ** (bend / 12.0 * np.clip((t - (dur - 0.25)) / 0.35, 0, 1) ** 2)
    vib = scoop * (1.0 + np.clip((t - 0.25) / 0.3, 0, 1) * 0.005 * np.sin(TWO_PI * 5.2 * t))
    cut = f * 1.2 + (700.0 + 2800.0 * vel) * amp ** 1.6
    y = saw_additive(f, n, cutoff=cut, max_h=45, vib=vib)
    breath = band_noise(n, rng, 800, 4000) * np.exp(-t / 0.03) * 0.05
    return soft_clip((y * amp + breath) * 0.8, 1.2) * (0.6 + 0.4 * vel)


def v_strings_stacc(f, dur, vel, rng):
    rel = 0.07
    n = nsamp(dur + 5 * rel)
    t = tvec(n)
    amp = env_adsr(n, 0.012, 0.12, 0.45, rel, dur)
    vib = 1.0 + 0.003 * np.sin(TWO_PI * 5.8 * t + rng.uniform(0, 6))
    y = saw_additive(f, n, cutoff=f * 3.0 + 1800.0 * vel, max_h=40, vib=vib, rng=rng)
    y += 0.7 * saw_additive(f * 1.004, n, cutoff=f * 3.0 + 1500.0, max_h=30, rng=rng)
    return y * amp * 0.6 * (0.6 + 0.4 * vel)


def v_pad_note(f, dur, vel, rng, att=0.45, rel=0.9, cutoff=1500.0):
    """One pad note as a stereo pair of detuned additive saws."""
    n = nsamp(dur + 4.5 * rel)
    t = tvec(n)
    amp = env_adsr(n, att, 1.5, 0.8, rel, dur)
    out = np.zeros((n, 2))
    for ch, cents in ((0, -6.0), (1, 6.5)):
        r = 2.0 ** (cents / 1200.0)
        vib = r * (1.0 + 0.0015 * np.sin(TWO_PI * (0.23 + 0.07 * ch) * t + ch))
        y = saw_additive(f, n, cutoff=cutoff, max_h=16, vib=vib, tilt=1.25, rng=rng)
        out[:, ch] = y * amp
    return out * 0.5 * (0.6 + 0.4 * vel)


# ----------------------------------------------------------------------------
# drums / percussion
# ----------------------------------------------------------------------------

def d_kick(rng, vel=1.0, f_end=50.0, f_start=165.0, tau=0.17, click=0.25, dur=0.45):
    n = nsamp(dur)
    t = tvec(n)
    f = f_end + (f_start - f_end) * np.exp(-t / 0.032)
    body = np.sin(phase_of(f, n)) * env_ad(n, 0.0008, tau)
    clk = band_noise(n, rng, 1500, 7000) * np.exp(-t / 0.0028) * click
    y = soft_clip(body * 1.5, 1.4) * 0.85 + clk
    return fade(y, 0, 0.03) * vel


def d_snare(rng, vel=1.0, dur=0.32, tone=1.0):
    n = nsamp(dur)
    t = tvec(n)
    body = (np.sin(phase_of(190 * (1 + 0.15 * np.exp(-t / 0.01)), n)) * np.exp(-t / 0.045)
            + 0.6 * np.sin(TWO_PI * 330 * t) * np.exp(-t / 0.03)) * tone
    nz = band_noise(n, rng, 1300, 9500) * (0.75 * np.exp(-t / 0.085) + 0.5 * np.exp(-t / 0.012))
    return fade((0.55 * body + nz) * _att(n, 0.0006), 0, 0.03) * vel * 0.8


def d_clap(rng, vel=1.0, dur=0.36):
    n = nsamp(dur)
    t = tvec(n)
    nz = fft_filter(noise(n, rng), lambda f: mag_bp(f, 850, 6500, 2) * mag_peak(f, 1600, 1.5, 4.0))
    nz /= np.std(nz) + 1e-12
    e = np.zeros(n)
    for k, off in enumerate((0.0, 0.0085, 0.0175, 0.027)):
        e += (0.8 + 0.1 * k) * env_exp(n, 0.0032, off)
    e += 0.55 * env_exp(n, 0.10, 0.027)
    return fade(nz * e, 0.0004, 0.04) * vel * 0.55


def d_hat(rng, vel=1.0, open_=False):
    dur = 0.5 if open_ else 0.085
    tau = 0.16 if open_ else 0.02
    n = nsamp(dur)
    t = tvec(n)
    metal = np.zeros(n)
    for fr in (6390, 7290, 8130, 9560, 10900, 12700):
        metal += np.sin(TWO_PI * fr * (1 + rng.uniform(-0.004, 0.004)) * t + rng.uniform(0, 6.3))
    metal /= 6.0
    nz = hp_noise(n, rng, 7000)
    y = (0.45 * metal + 0.8 * nz) * env_ad(n, 0.0005, tau)
    y = fft_filter(y, lambda f: mag_hp(f, 5500, 2))
    return fade(y, 0, 0.02) * vel * 0.5


def d_shaker(rng, vel=1.0):
    n = nsamp(0.11)
    t = tvec(n)
    y = band_noise(n, rng, 4200, 11500) * env_ad(n, 0.007, 0.028)
    return fade(y, 0, 0.015) * vel * 0.45


def d_tamb(rng, vel=1.0):
    n = nsamp(0.26)
    t = tvec(n)
    y = np.zeros(n)
    for _ in range(7):
        fr = rng.uniform(5200, 11000)
        y += np.sin(TWO_PI * fr * t + rng.uniform(0, 6.3)) * np.exp(-t / rng.uniform(0.04, 0.11))
    y = 0.12 * y + 0.6 * hp_noise(n, rng, 6000) * np.exp(-t / 0.05)
    return fade(y * _att(n, 0.002), 0, 0.03) * vel * 0.5


def d_tom(rng, f0, vel=1.0, dur=0.5):
    n = nsamp(dur)
    t = tvec(n)
    f = f0 * (1.0 + 0.4 * np.exp(-t / 0.035))
    y = np.sin(phase_of(f, n)) * env_ad(n, 0.0008, 0.2) \
        + 0.25 * np.sin(phase_of(f * 1.5, n)) * np.exp(-t / 0.08)
    y += lp_noise(n, rng, 2500) * np.exp(-t / 0.012) * 0.25
    return fade(soft_clip(y, 1.2), 0, 0.04) * vel * 0.8


def d_taiko(rng, vel=1.0, f0=72.0, dur=1.1):
    n = nsamp(dur)
    t = tvec(n)
    f = f0 * (1.0 + 0.55 * np.exp(-t / 0.05))
    body = np.sin(phase_of(f, n)) * env_ad(n, 0.001, 0.38)
    body += 0.35 * np.sin(phase_of(f * 2.3, n)) * np.exp(-t / 0.09)
    slap = lp_noise(n, rng, 1400) * np.exp(-t / 0.018) * 0.45
    slap += band_noise(n, rng, 2000, 6000) * np.exp(-t / 0.004) * 0.15
    return fade(soft_clip((body + slap) * 1.3, 1.5), 0, 0.08) * vel * 0.85


def d_crash(rng, vel=1.0, dur=2.2, tau=0.7):
    n = nsamp(dur)
    t = tvec(n)
    metal = np.zeros(n)
    for _ in range(14):
        fr = rng.uniform(3000, 12500)
        metal += np.sin(TWO_PI * fr * t + rng.uniform(0, 6.3)) * np.exp(-t / (tau * rng.uniform(0.5, 1.2)))
    nz = hp_noise(n, rng, 3500) * env_ad(n, 0.002, tau)
    y = 0.6 * nz + 0.04 * metal
    y = fft_filter(y, lambda f: mag_hp(f, 2500, 2) * mag_lp(f, 14000, 1))
    return fade(y, 0, 0.3) * vel * 0.5


def d_rim(rng, vel=1.0):
    n = nsamp(0.07)
    t = tvec(n)
    y = np.sin(TWO_PI * 1720 * t) * np.exp(-t / 0.007) + 0.6 * np.sin(TWO_PI * 830 * t) * np.exp(-t / 0.01)
    y += band_noise(n, rng, 2000, 9000) * np.exp(-t / 0.0015) * 0.5
    return fade(y * _att(n, 0.0003), 0, 0.01) * vel * 0.5


def d_snap(rng, vel=1.0):
    n = nsamp(0.09)
    t = tvec(n)
    y = band_noise(n, rng, 1700, 6500) * np.exp(-t / 0.011) + 0.3 * np.sin(TWO_PI * 2100 * t) * np.exp(-t / 0.004)
    return fade(y * _att(n, 0.0004), 0, 0.01) * vel * 0.5


def d_wood(rng, f=950.0, vel=1.0):
    n = nsamp(0.12)
    t = tvec(n)
    y = np.sin(TWO_PI * f * t) * np.exp(-t / 0.028) + 0.35 * np.sin(TWO_PI * 2.71 * f * t) * np.exp(-t / 0.009)
    y += band_noise(n, rng, 1000, 5000) * np.exp(-t / 0.0015) * 0.3
    return fade(y * _att(n, 0.0004), 0, 0.02) * vel * 0.55


def d_riser(rng, dur, f0=350.0, f1=7000.0, bw=1.2):
    n = nsamp(dur)
    x = np.linspace(0, 1, n)
    y = tv_noise(n, rng, f0 * (f1 / f0) ** (x ** 1.5), bw)
    return fade(y * x ** 2.2, 0.01, 0.01) * 0.35


# ----------------------------------------------------------------------------
# SFX helpers
# ----------------------------------------------------------------------------

SFX_REGISTRY = {}


def sfx(name, length=None, fade_s=None):
    """Register a sound. length (s) forces an exact duration; fade_s sets the
    cosine fade-out at the end (default: short anti-click fade)."""
    def deco(fn):
        SFX_REGISTRY[name] = (fn, length, fade_s)
        return fn
    return deco


def sumz(*arrs):
    n = max(len(a) for a in arrs)
    out = np.zeros(n)
    for a in arrs:
        out[:len(a)] += a
    return out


def canvas(sec):
    return np.zeros(nsamp(sec))


def tail_fade(y, sec):
    m = min(len(y), nsamp(sec))
    if m > 1:
        w = 0.5 + 0.5 * np.cos(np.pi * np.arange(1, m + 1) / m)
        y = y.copy()
        y[len(y) - m:] *= w[:, None] if y.ndim == 2 else w
    return y


def put(dst, src, t0, gain=1.0):
    mix_at(dst, tail_fade(src, 0.01), nsamp(t0) if t0 > 0 else 0, gain)


def pings(n, rng, t0, t1, count, freqs=None, fmin=3000.0, fmax=9000.0, tau=(0.012, 0.06),
          amp=1.0, decay=0.4, skew=1.7):
    """Sparkle: many tiny decaying sine pings, denser near t0."""
    out = np.zeros(n)
    for _ in range(count):
        tp = t0 + (t1 - t0) * rng.random() ** skew
        if freqs is not None:
            f = float(rng.choice(freqs)) * (1.0 + rng.uniform(-0.002, 0.002))
        else:
            f = math.exp(rng.uniform(math.log(fmin), math.log(fmax)))
        ta = rng.uniform(*tau)
        m = nsamp(ta * 6.0)
        tt_ = tvec(m)
        p = np.sin(TWO_PI * f * tt_ + rng.uniform(0, 6.3)) * np.exp(-tt_ / ta) * _att(m, 0.0006)
        mix_at(out, p, nsamp(tp), amp * math.exp(-(tp - t0) / decay) * rng.uniform(0.35, 1.0))
    return out


def thump(n, f0, f1, tau, att=0.002, glide_tau=0.035, drive=1.4):
    t = tvec(n)
    f = f1 + (f0 - f1) * np.exp(-t / glide_tau)
    y = np.sin(phase_of(f, n)) * env_ad(n, att, tau)
    return soft_clip(y * drive, drive)


def chime(f, vel, rng, tau=0.6, glock=1.0, bell=0.5, mar=0.0, index=1.4):
    parts = [glock * v_glock(f, 0.1, vel, rng, tau=tau)]
    if bell:
        parts.append(bell * v_bell(f, 0.1, vel, rng, ratio=3.5, index=index, tau=tau * 1.1, itau=0.12))
    if mar:
        parts.append(mar * v_marimba(f, 0.3, vel, rng))
    return sumz(*parts)


def tick(n, rng, body_f, click_band=(1800, 7000), click_tau=0.002, body_tau=0.009,
         partial=2.17, low_f=None, low_tau=0.012):
    t = tvec(n)
    y = 0.55 * band_noise(n, rng, *click_band) * np.exp(-t / click_tau)
    y += 0.5 * (np.sin(TWO_PI * body_f * t) + 0.35 * np.sin(TWO_PI * partial * body_f * t + 0.5)) \
        * np.exp(-t / body_tau)
    if low_f:
        y += 0.3 * np.sin(TWO_PI * low_f * t) * np.exp(-t / low_tau)
    return y


def droplets(n, rng, t0, t1, count, fmin=650.0, fmax=1700.0, amp=0.5, decay=0.25):
    """Water/ink 'plip' drops: short sines whose pitch rises quickly."""
    out = np.zeros(n)
    for _ in range(count):
        tp = t0 + (t1 - t0) * rng.random() ** 1.5
        f = math.exp(rng.uniform(math.log(fmin), math.log(fmax)))
        m = nsamp(0.05)
        fr = glide(f, f * rng.uniform(1.5, 2.2), m, tau=0.008)
        p = np.sin(phase_of(fr, m)) * env_ad(m, 0.001, 0.011)
        mix_at(out, p, nsamp(tp), amp * math.exp(-(tp - t0) / decay) * rng.uniform(0.4, 1.0))
    return out


PENT_C = [note(n) for n in ("C7", "D7", "E7", "G7", "A7", "C8")]
PENT_A = [note(n) for n in ("A6", "B6", "C#7", "E7", "F#7", "A7")]
SUS_A = [note(n) for n in ("A6", "B6", "E7", "A7", "B7")]


# ----------------------------------------------------------------------------
# SFX: UI
# ----------------------------------------------------------------------------

@sfx("ui_tap", length=0.06)
def _ui_tap(rng):
    return tick(nsamp(0.08), rng, 640, body_tau=0.013, low_f=210, low_tau=0.016)


@sfx("ui_back", length=0.075)
def _ui_back(rng):
    return tick(nsamp(0.09), rng, 430, click_band=(1100, 5000), click_tau=0.0024,
                body_tau=0.016, partial=2.3, low_f=150, low_tau=0.02)


@sfx("ui_toggle", length=0.12)
def _ui_toggle(rng):
    y = canvas(0.14)
    put(y, tick(nsamp(0.07), rng, 880, body_tau=0.01, partial=2.0), 0.0, 0.85)
    put(y, tick(nsamp(0.07), rng, 1320, body_tau=0.012, partial=2.0), 0.048, 1.0)
    return y


@sfx("ui_swipe", length=0.2)
def _ui_swipe(rng):
    n = nsamp(0.2)
    x = np.linspace(0, 1, n)
    center = np.interp(x, [0.0, 0.45, 1.0], [800.0, 3600.0, 2300.0])
    env = env_points(n, [(0, 0), (0.07, 1.0), (0.2, 0.0)]) ** 1.4
    wh = tv_noise(n, rng, center, 1.3)
    crk = crackle(n, rng, 900.0 * env + 50, lo=2500, hi=9500)
    return (wh + 0.35 * crk) * env


@sfx("ui_popup", length=0.36, fade_s=0.12)
def _ui_popup(rng):
    y = canvas(0.45)
    n = nsamp(0.3)
    t = tvec(n)
    blip = sine(glide(470, 1150, n, tau=0.018), n) * env_ad(n, 0.002, 0.05)
    blip += 0.25 * sine(glide(940, 2300, n, tau=0.018), n) * env_ad(n, 0.002, 0.03)
    pop = lp_noise(n, rng, 2600) * np.exp(-t / 0.004) * 0.35
    put(y, blip * 0.8 + pop, 0.0)
    put(y, v_glock(hz("E7"), 0.1, 0.6, rng, tau=0.16), 0.028, 0.22)
    return reverb_mono(y, wet=0.10, seconds=0.6, room=0.3, size=0.5)


@sfx("ui_purchase", length=1.0, fade_s=0.35)
def _ui_purchase(rng):
    y = canvas(1.3)
    for t0, f, g in ((0.0, 2600.0, 0.6), (0.034, 3400.0, 0.75)):
        n = nsamp(0.06)
        t = tvec(n)
        clk = band_noise(n, rng, 1500, 8000) * np.exp(-t / 0.002)
        ring = (np.sin(TWO_PI * f * t) + 0.6 * np.sin(TWO_PI * 1.47 * f * t)) * np.exp(-t / 0.012)
        put(y, 0.6 * clk + 0.3 * ring, t0, g)
    n = nsamp(0.12)
    put(y, tv_noise(n, rng, glide(1200, 4200, n), 1.2) * env_points(n, [(0, 0), (0.03, 1), (0.12, 0)]) * 0.18, 0.0)
    ch = 0.07
    put(y, chime(hz("E6"), 1.0, rng, tau=0.42, bell=0.7, index=2.2), ch, 0.9)
    put(y, chime(hz("B6"), 0.9, rng, tau=0.38, bell=0.6, index=1.8), ch + 0.004, 0.7)
    put(y, v_glock(hz("E7"), 0.1, 0.7, rng, tau=0.35), ch + 0.008, 0.35)
    y += pings(len(y), rng, ch, ch + 0.4, 22, fmin=3200, fmax=7500, amp=0.16, decay=0.18)
    return reverb_mono(y, wet=0.14, seconds=0.9, room=0.4, size=0.6)


@sfx("ui_error")
def _ui_error(rng):
    y = canvas(0.42)
    for t0, f, gate in ((0.0, 205.0, 0.10), (0.155, 183.0, 0.13)):
        n = nsamp(gate + 0.16)
        t = tvec(n)
        b = saw_additive(f, n, cutoff=950.0, max_h=21, odd_only=True, tilt=1.35)
        b += 0.5 * np.sin(TWO_PI * f * t)
        b *= env_adsr(n, 0.005, 0.05, 0.35, 0.03, gate) * (1.0 + 0.22 * np.sin(TWO_PI * 31.0 * t))
        b += lp_noise(n, rng, 1400) * np.exp(-t / 0.004) * 0.9 + np.sin(TWO_PI * 2 * f * t) * np.exp(-t / 0.01) * 0.5
        put(y, b, t0)
    return fft_filter(y, lambda f: mag_lp(f, 1700, 2))


# ----------------------------------------------------------------------------
# SFX: rewards
# ----------------------------------------------------------------------------

@sfx("coin", length=0.5, fade_s=0.18)
def _coin(rng):
    f = hz("B6")
    n = nsamp(0.55)
    t = tvec(n)
    y = np.zeros(n)
    for ratio, amp, tau in ((1.0, 1.0, 0.24), (1.0035, 0.25, 0.2), (2.0, 0.10, 0.1),
                            (2.76, 0.32, 0.09), (5.40, 0.14, 0.045), (8.93, 0.05, 0.025)):
        if f * ratio < 19000:
            y += amp * np.sin(TWO_PI * f * ratio * t + ratio) * np.exp(-t / tau)
    tink = sum(np.sin(TWO_PI * fr * t + fr) * np.exp(-t / tau) for fr, tau in ((7350, 0.004), (9150, 0.003), (11800, 0.002)))
    y = y * _att(n, 0.0006) + hp_noise(n, rng, 6000) * np.exp(-t / 0.0012) * 0.45 + 0.55 * tink * _att(n, 0.0004)
    return reverb_mono(y, wet=0.07, seconds=0.5, room=0.3, size=0.5)


@sfx("coin_count", length=0.04)
def _coin_count(rng):
    n = nsamp(0.05)
    t = tvec(n)
    f = 2350.0
    y = np.sin(TWO_PI * f * t) * np.exp(-t / 0.0075) \
        + 0.3 * np.sin(TWO_PI * 2.76 * f * t) * np.exp(-t / 0.003)
    return y + 0.25 * hp_noise(n, rng, 5000) * np.exp(-t / 0.0007)


def _star(rng, base, top=False):
    y = canvas(1.25 if top else 1.0)
    notes = [base, base + 4, base + 7] + ([base + 12] if top else [])
    for i, m in enumerate(notes):
        vel = 0.75 + 0.08 * i
        put(y, chime(float(midi_hz(m)), vel, rng, tau=0.34 + 0.05 * i, bell=0.45), 0.055 * i,
            0.8 if i < len(notes) - 1 else 1.0)
        nt = nsamp(0.02)
        put(y, hp_noise(nt, rng, 5500) * np.exp(-tvec(nt) / 0.0012), 0.055 * i, 0.55)
    last = 0.055 * (len(notes) - 1)
    fr = [float(midi_hz(m + 24)) for m in notes] + [float(midi_hz(base + 36))]
    y += pings(len(y), rng, last, last + 0.55, 30 if top else 20, freqs=fr, amp=0.22, decay=0.3)
    n = len(y)
    y += hp_noise(n, rng, 6500) * env_ad(n, 0.05, 0.22, delay=last) * 0.035
    return reverb_mono(y, wet=0.18, seconds=1.2, room=0.5, size=0.7)


@sfx("star_1", length=1.0, fade_s=0.35)
def _star1(rng):
    return _star(rng, note("C6"))


@sfx("star_2", length=1.1, fade_s=0.35)
def _star2(rng):
    return _star(rng, note("D6"))


@sfx("star_3", length=1.3, fade_s=0.4)
def _star3(rng):
    return _star(rng, note("E6"), top=True)


@sfx("unlock", length=1.2, fade_s=0.3)
def _unlock(rng):
    y = canvas(1.6)
    n = nsamp(0.36)
    env = env_points(n, [(0, 0), (0.04, 1.0), (0.36, 0.0)])
    rust = crackle(n, rng, 2600.0 * env + 100, lo=1500, hi=8000) * 0.5 \
        + tv_noise(n, rng, glide(1500, 4200, n), 1.4) * 0.35
    put(y, rust * env, 0.0, 0.8)
    arp = ["A4", "C#5", "E5", "A5", "B5", "C#6", "E6", "A6"]
    for i, s in enumerate(arp):
        t0 = 0.06 + 0.068 * i
        put(y, v_harp(hz(s), 0.4, 0.6 + 0.04 * i, rng), t0, 0.7)
        put(y, v_glock(hz(s) * 2.0, 0.1, 0.5 + 0.04 * i, rng, tau=0.35), t0 + 0.003, 0.25)
    n2 = nsamp(0.7)
    put(y, tv_noise(n2, rng, glide(500, 6500, n2), 1.0) * env_points(n2, [(0, 0), (0.5, 1), (0.7, 0)]) * 0.12, 0.05)
    tf = 0.06 + 0.068 * 8
    for s, g in (("A6", 0.8), ("C#7", 0.55), ("E7", 0.5)):
        put(y, chime(hz(s), 0.8, rng, tau=0.5, bell=0.5), tf, g)
    y += pings(len(y), rng, tf, tf + 0.5, 18, freqs=[float(midi_hz(m)) for m in PENT_A], amp=0.18, decay=0.3)
    return reverb_mono(y, wet=0.24, seconds=1.5, room=0.6, size=0.8)


@sfx("chest_open", length=1.2, fade_s=0.35)
def _chest_open(rng):
    y = canvas(1.25)
    n = nsamp(0.4)
    t = tvec(n)
    th = thump(n, 125, 55, 0.09) + lp_noise(n, rng, 650) * np.exp(-t / 0.03) * 0.5
    put(y, th, 0.0, 0.9)
    put(y, d_wood(rng, 420.0, 1.0), 0.0, 0.6)
    s0 = 0.05
    for i, s in enumerate(["G5", "C6", "E6", "G6", "C7"]):
        put(y, chime(hz(s), 0.7 + 0.05 * i, rng, tau=0.45), s0 + 0.04 * i, 0.55)
    y += pings(len(y), rng, s0, s0 + 0.75, 42, freqs=[float(midi_hz(m)) for m in PENT_C], amp=0.25, decay=0.3)
    m = len(y)
    y += hp_noise(m, rng, 5000) * env_ad(m, 0.02, 0.28, delay=s0) * 0.06
    return reverb_mono(y, wet=0.2, seconds=1.2, room=0.55, size=0.7)


@sfx("sticker")
def _sticker(rng):
    y = canvas(0.55)
    n = nsamp(0.21)
    x = np.linspace(0, 1, n)
    rate = 300.0 + 4200.0 * x ** 1.5
    peel = crackle(n, rng, rate, lo=1200, hi=7500, amp_sigma=0.5) * 0.7
    peel += tv_noise(n, rng, 1200.0 * (4.0 ** x), 1.1) * 0.35
    put(y, fade(peel * (0.25 + 0.75 * x), 0.005, 0.004), 0.0, 1.0)
    n2 = nsamp(0.25)
    t = tvec(n2)
    slap = lp_noise(n2, rng, 2300) * env_ad(n2, 0.0008, 0.012) * 0.9
    slap += thump(n2, 170, 105, 0.03) * 0.6
    slap += band_noise(n2, rng, 2500, 9000) * np.exp(-t / 0.0015) * 0.25
    put(y, slap, 0.225, 1.0)
    return reverb_mono(y, wet=0.08, seconds=0.5, room=0.3, size=0.5)


@sfx("reward_big", length=1.5, fade_s=0.4)
def _reward_big(rng):
    y = canvas(1.9)
    seq = [(0.00, ["G4"], 0.09), (0.10, ["C5"], 0.09), (0.20, ["E5"], 0.09), (0.30, ["C5", "E5", "G5"], 0.7)]
    for t0, ns, dur in seq:
        for s in ns:
            put(y, v_brass(hz(s), dur, 0.85, rng), t0, 0.5)
            put(y, v_glock(hz(s) * 2.0, 0.1, 0.65, rng, tau=0.45 if dur > 0.2 else 0.25), t0, 0.32)
    put(y, d_tom(rng, 98.0, 0.7, 0.6), 0.30, 0.55)
    put(y, d_crash(rng, 0.45, 1.6, 0.55), 0.30, 0.55)
    y += pings(len(y), rng, 0.3, 1.35, 46, freqs=[float(midi_hz(m)) for m in PENT_C], amp=0.2, decay=0.45)
    m = len(y)
    y += hp_noise(m, rng, 6000) * env_ad(m, 0.12, 0.4, delay=0.25) * 0.04
    return reverb_mono(y, wet=0.24, seconds=1.6, room=0.65, size=0.85)


# ----------------------------------------------------------------------------
# SFX: countdown and gameplay
# ----------------------------------------------------------------------------

@sfx("countdown")
def _countdown(rng):
    f = hz("A5")
    n = nsamp(0.4)
    t = tvec(n)
    y = pad_to(v_marimba(f, 0.2, 1.0, rng), n) + 0.35 * pad_to(v_xylo(2 * f, 0.1, 0.6, rng), n)
    y += 0.3 * np.sin(TWO_PI * f * t) * env_ad(n, 0.003, 0.06)
    y += band_noise(n, rng, 1200, 7000) * np.exp(-t / 0.0015) * 0.75
    return y


@sfx("go", length=0.7, fade_s=0.22)
def _go(rng):
    y = canvas(0.8)
    for s, g in (("A6", 1.0), ("E6", 0.6), ("C#6", 0.5)):
        put(y, v_marimba(hz(s), 0.3, 1.0, rng), 0.0, g)
        put(y, v_bell(hz(s), 0.1, 0.8, rng, ratio=3.5, index=1.6, tau=0.25, itau=0.08), 0.0, 0.35 * g)
    put(y, d_clap(rng, 0.8), 0.0, 0.7)
    put(y, d_rim(rng, 1.0), 0.0, 2.2)
    n = nsamp(0.16)
    put(y, tv_noise(n, rng, glide(1200, 8000, n), 1.0) * env_points(n, [(0, 0), (0.12, 1), (0.16, 0)]) * 0.22, 0.0)
    y += pings(len(y), rng, 0.02, 0.4, 12, freqs=[float(midi_hz(m)) for m in PENT_A], amp=0.15, decay=0.2)
    return reverb_mono(y, wet=0.14, seconds=0.8, room=0.45, size=0.6)


@sfx("leave_land", length=0.25)
def _leave_land(rng):
    n = nsamp(0.25)
    x = np.linspace(0, 1, n)
    env = env_points(n, [(0, 0), (0.03, 0.8), (0.07, 1.0), (0.25, 0.0)]) ** 1.3
    center = np.interp(x, [0, 0.35, 1], [1400.0, 3800.0, 2500.0])
    y = tv_noise(n, rng, center, 1.4) * 0.8
    y += crackle(n, rng, 1300.0 * env + 40, lo=2500, hi=9500) * 0.32
    return y * env


def _capture(rng, size):
    P = [dict(f0=150, f1=85, tau=0.06, air=0.5, notes=["A5", "E6"], step=0.065, ctau=0.32, wet=0.12, dur=0.6),
         dict(f0=125, f1=66, tau=0.095, air=0.7, notes=["E5", "A5", "E6"], step=0.062, ctau=0.45, wet=0.16, dur=0.9),
         dict(f0=104, f1=48, tau=0.15, air=0.9, notes=["E5", "A5", "B5", "E6", "A6"], step=0.058, ctau=0.62,
              wet=0.22, dur=1.3)][size]
    y = canvas(P["dur"])
    n = nsamp(0.5)
    t = tvec(n)
    body = thump(n, P["f0"], P["f1"], P["tau"], att=0.004, glide_tau=0.04)
    air = tv_noise(n, rng, glide(950, 240, n, tau=0.06), 1.5) * env_ad(n, 0.01, P["tau"] * 0.8) * P["air"]
    m = nsamp(0.05)
    crease = crackle(m, rng, 3500.0, lo=2000, hi=8500) * np.linspace(1, 0, m) ** 2 * 0.3
    put(y, body * (1.0 + 0.15 * size) + air, 0.0, 0.85)
    put(y, crease, 0.0)
    c0 = 0.045
    for i, s in enumerate(P["notes"]):
        vel = 0.7 + 0.06 * i
        put(y, chime(hz(s), vel, rng, tau=P["ctau"], bell=0.35 + 0.15 * size, mar=0.45), c0 + P["step"] * i,
            0.55 + 0.05 * i)
    if size >= 1:
        last = c0 + P["step"] * (len(P["notes"]) - 1)
        put(y, v_bell(hz(P["notes"][-1]) * 2.0, 0.1, 0.6, rng, tau=P["ctau"], index=1.0), last, 0.22)
        y += pings(len(y), rng, last, last + 0.25 + 0.3 * size, 8 + 14 * size,
                   freqs=[float(midi_hz(mm)) for mm in SUS_A], amp=0.12 + 0.04 * size, decay=0.25)
    return reverb_mono(y, wet=P["wet"], seconds=1.0 + 0.4 * size, room=0.45 + 0.1 * size, size=0.7)


@sfx("capture_s", length=0.4, fade_s=0.1)
def _capture_s(rng):
    return _capture(rng, 0)


@sfx("capture_m", length=0.65, fade_s=0.18)
def _capture_m(rng):
    return _capture(rng, 1)


@sfx("capture_l", length=1.0, fade_s=0.3)
def _capture_l(rng):
    return _capture(rng, 2)


@sfx("combo", length=0.6, fade_s=0.2)
def _combo(rng):
    y = canvas(0.6)
    for i, s in enumerate(["E6", "A6", "B6"]):
        put(y, chime(hz(s), 0.75 + 0.1 * i, rng, tau=0.3, bell=0.45), 0.045 * i, 0.75 + 0.1 * i)
    y += pings(len(y), rng, 0.09, 0.4, 14, freqs=[float(midi_hz(m)) for m in SUS_A], amp=0.17, decay=0.18)
    return reverb_mono(y, wet=0.12, seconds=0.7, room=0.4, size=0.6)


@sfx("goal_reached", length=1.0, fade_s=0.3)
def _goal_reached(rng):
    y = canvas(1.3)
    for i, s in enumerate(["A5", "C#6", "E6"]):
        put(y, chime(hz(s), 0.75, rng, tau=0.35, bell=0.45, mar=0.4), 0.07 * i, 0.6)
    t0 = 0.21
    for s, g in (("A6", 1.0), ("E6", 0.55), ("C#7", 0.45), ("A5", 0.4)):
        put(y, chime(hz(s), 0.9, rng, tau=0.55, bell=0.6, mar=0.3), t0, 0.6 * g)
    put(y, v_pad_note(hz("A4"), 0.4, 0.6, rng, att=0.05, rel=0.4, cutoff=1800)[:, 0], t0, 0.25)
    put(y, v_pad_note(hz("C#5"), 0.4, 0.6, rng, att=0.05, rel=0.4, cutoff=1800)[:, 1], t0, 0.2)
    put(y, d_crash(rng, 0.35, 1.1, 0.4), t0, 0.35)
    y += pings(len(y), rng, t0, t0 + 0.7, 26, freqs=[float(midi_hz(m)) for m in PENT_A], amp=0.18, decay=0.35)
    return reverb_mono(y, wet=0.22, seconds=1.2, room=0.6, size=0.8)


@sfx("snip", length=0.6, fade_s=0.18)
def _snip(rng):
    y = canvas(0.55)
    n = nsamp(0.05)
    x = np.linspace(0, 1, n)
    swish = tv_noise(n, rng, glide(3500, 7800, n), 1.0) * x ** 2 * 0.45
    put(y, swish, 0.0)
    n2 = nsamp(0.3)
    t = tvec(n2)
    ring = np.zeros(n2)
    for fr, a in ((3120, 1.0), (4730, 0.8), (6210, 0.6), (7890, 0.45), (9400, 0.3)):
        ring += a * np.sin(TWO_PI * fr * t + fr) * np.exp(-t / rng.uniform(0.035, 0.07))
    put(y, ring * 0.12 * _att(n2, 0.001), 0.038)
    clk = band_noise(n2, rng, 2000, 10000) * np.exp(-t / 0.0012) * 0.95 \
        + np.sin(TWO_PI * 2200 * t) * np.exp(-t / 0.004) * 0.35
    put(y, clk, 0.045)
    pop = sine(glide(1300, 320, n2, tau=0.012), n2) * env_ad(n2, 0.001, 0.045)
    pop += lp_noise(n2, rng, 3000) * np.exp(-t / 0.007) * 0.35
    put(y, pop, 0.06, 0.95)
    put(y, chime(hz("E6"), 0.55, rng, tau=0.22, bell=0.3), 0.10, 0.5)
    put(y, chime(hz("B6"), 0.6, rng, tau=0.25, bell=0.3), 0.15, 0.55)
    return reverb_mono(y, wet=0.1, seconds=0.6, room=0.35, size=0.55)


@sfx("pop_self", length=0.85, fade_s=0.2)
def _pop_self(rng):
    y = canvas(0.95)
    n = nsamp(0.55)
    t = tvec(n)
    rate = 6500.0 * np.exp(-t / 0.17) + 150.0
    crumple = crackle(n, rng, rate, lo=900, hi=6000) * 0.55 + crackle(n, rng, rate * 0.4, lo=350, hi=1800) * 0.3
    put(y, crumple * env_ad(n, 0.003, 0.22), 0.0)
    n2 = nsamp(0.5)
    t2 = tvec(n2)
    deflate = tv_noise(n2, rng, glide(1900, 260, n2), 0.9) * env_ad(n2, 0.02, 0.2) * 0.45
    sigh_f = glide(520, 185, n2) * (1.0 + 0.012 * np.sin(TWO_PI * 6.0 * t2))
    sigh = (np.sin(phase_of(sigh_f, n2)) + 0.12 * np.sin(3 * phase_of(sigh_f, n2))) * env_ad(n2, 0.025, 0.2) * 0.32
    put(y, deflate + sigh, 0.02)
    n3 = nsamp(0.4)
    t3 = tvec(n3)
    thud = thump(n3, 95, 55, 0.1, att=0.003) * 0.9 + lp_noise(n3, rng, 700) * np.exp(-t3 / 0.02) * 0.3
    put(y, thud, 0.42)
    return reverb_mono(y, wet=0.1, seconds=0.7, room=0.4, size=0.6)


# ----------------------------------------------------------------------------
# SFX: pickups, abilities, warnings
# ----------------------------------------------------------------------------

@sfx("pickup_speed", length=0.6, fade_s=0.18)
def _pickup_speed(rng):
    y = canvas(0.62)
    n = nsamp(0.32)
    t = tvec(n)
    f = glide(320, 2600, n) * (1.0 + 0.01 * np.sin(TWO_PI * 28 * t))
    zip_ = (np.sin(phase_of(f, n)) + 0.3 * np.sin(2 * phase_of(f, n))) * env_points(n, [(0, 0), (0.01, 0.7), (0.24, 1.0), (0.32, 0)])
    put(y, zip_ * 0.55, 0.0)
    n2 = nsamp(0.38)
    put(y, tv_noise(n2, rng, glide(600, 7000, n2), 1.0) * env_points(n2, [(0, 0), (0.28, 1), (0.38, 0)]) * 0.4, 0.0)
    put(y, chime(hz("A6"), 0.7, rng, tau=0.22, bell=0.4), 0.28, 0.4)
    put(y, chime(hz("E7"), 0.7, rng, tau=0.22, bell=0.4), 0.32, 0.32)
    return reverb_mono(y, wet=0.12, seconds=0.6, room=0.4, size=0.6)


@sfx("pickup_shield", length=0.9, fade_s=0.28)
def _pickup_shield(rng):
    y = canvas(0.9)
    n = nsamp(0.12)
    t = tvec(n)
    put(y, sine(glide(380, 1400, n, tau=0.025), n) * env_ad(n, 0.002, 0.04) * 0.6, 0.0)
    n2 = nsamp(0.8)
    t2 = tvec(n2)
    trem = 1.0 - 0.35 * (0.5 + 0.5 * np.sin(TWO_PI * 11.0 * t2))
    sh = np.zeros(n2)
    for s, a in (("A5", 0.8), ("E6", 0.7), ("B6", 0.5), ("E7", 0.35)):
        f = hz(s)
        for c in (-7.0, 7.0):
            sh += a * fm(f * 2 ** (c / 1200), n2, 3.5, 0.6, None, np.exp(-t2 / 0.15) + 0.2, ph_mod=c)
    sh *= env_ad(n2, 0.04, 0.32) * trem * 0.25
    put(y, sh, 0.03)
    put(y, hp_noise(n2, rng, 5000) * env_ad(n2, 0.05, 0.25) * 0.06, 0.03)
    return reverb_mono(y, wet=0.2, seconds=1.0, room=0.5, size=0.7)


@sfx("pickup_bomb", length=0.65, fade_s=0.2)
def _pickup_bomb(rng):
    y = canvas(0.75)
    n = nsamp(0.6)
    t = tvec(n)
    boom = thump(n, 112, 45, 0.13, att=0.0015) * 0.55
    splat = lp_noise(n, rng, 1800) * env_ad(n, 0.002, 0.06) * 0.9
    squish = tv_noise(n, rng, glide(1300, 480, n, tau=0.06), 1.2) * env_ad(n, 0.003, 0.1) * 0.75
    put(y, boom + soft_clip((splat + squish) * 1.6, 2.0) * 0.5, 0.0)
    y += droplets(len(y), rng, 0.05, 0.45, 14, amp=0.35, decay=0.2)
    return reverb_mono(y, wet=0.12, seconds=0.7, room=0.4, size=0.6)


@sfx("pickup_freeze", length=1.0, fade_s=0.3)
def _pickup_freeze(rng):
    y = canvas(1.0)
    for i, s in enumerate(["E6", "B6", "E7", "F#7"]):
        f = hz(s)
        n = nsamp(0.9)
        t = tvec(n)
        c = fm(f, n, 4.77, 1.3, env_ad(n, 0.001, 0.4 - 0.05 * i), np.exp(-t / 0.08) + 0.1)
        c += 0.5 * fm(f * 1.006, n, 2.0, 0.4, env_ad(n, 0.001, 0.36 - 0.05 * i))
        c += hp_noise(n, rng, 6500) * np.exp(-t / 0.0012) * 1.7
        put(y, c * (0.6 - 0.08 * i), 0.05 * i)
    m = len(y)
    tt_ = tvec(m)
    y += crackle(m, rng, 450.0 * np.exp(-tt_ / 0.3) + 20, lo=6000, hi=12500, amp_sigma=0.5) * 0.12
    y += tv_noise(m, rng, glide(6500, 9500, m), 0.8) * env_ad(m, 0.08, 0.3) * 0.08
    return reverb_mono(y, wet=0.25, seconds=1.2, room=0.6, size=0.8)


@sfx("ink_burst", length=1.1, fade_s=0.3)
def _ink_burst(rng):
    y = canvas(1.5)
    n = nsamp(0.7)
    x = np.linspace(0, 1, n)
    wh = tv_noise(n, rng, np.interp(x, [0, 0.4, 1], [300.0, 3500.0, 1200.0]), 1.6) * env_points(n, [(0, 0), (0.08, 1), (0.7, 0)]) ** 1.5
    put(y, wh * 0.55, 0.0)
    n2 = nsamp(0.5)
    t2 = tvec(n2)
    splash = lp_noise(n2, rng, 2500) * env_ad(n2, 0.002, 0.07) * 0.7 \
        + band_noise(n2, rng, 600, 5000) * env_ad(n2, 0.004, 0.12) * 0.4
    put(y, splash + thump(n2, 92, 44, 0.16, att=0.002) * 0.8, 0.05)
    y += droplets(len(y), rng, 0.09, 0.6, 18, amp=0.3, decay=0.25)
    for s, g in (("A5", 0.7), ("E6", 0.6), ("A6", 0.5), ("C#7", 0.35)):
        put(y, chime(hz(s), 0.8, rng, tau=0.55, bell=0.55), 0.08, g)
    y += pings(len(y), rng, 0.1, 0.9, 30, freqs=[float(midi_hz(m)) for m in PENT_A], amp=0.15, decay=0.4)
    return reverb_mono(y, wet=0.22, seconds=1.3, room=0.6, size=0.8)


@sfx("danger", length=0.12)
def _danger(rng):
    n = nsamp(0.13)
    t = tvec(n)
    f = 1250.0
    y = (np.sin(TWO_PI * f * t) + 0.4 * np.sin(TWO_PI * 2 * f * t) + 0.25 * np.sin(TWO_PI * 0.5 * f * t)) \
        * env_ad(n, 0.002, 0.03)
    return y + 0.2 * band_noise(n, rng, 2000, 7000) * np.exp(-t / 0.0012)


@sfx("timer_tick", length=0.08)
def _timer_tick(rng):
    n = nsamp(0.09)
    t = tvec(n)
    y = band_noise(n, rng, 2000, 9000) * np.exp(-t / 0.0009) * 0.8
    for fr, tau, a in ((3150, 0.008, 0.4), (4810, 0.005, 0.3), (6650, 0.003, 0.2)):
        y += a * np.sin(TWO_PI * fr * t) * np.exp(-t / tau)
    return y + 0.35 * np.sin(TWO_PI * 1150 * t) * np.exp(-t / 0.005)


@sfx("boss_intro", length=1.5, fade_s=0.4)
def _boss_intro(rng):
    y = canvas(2.0)
    stab = ["A2", "E3", "A3"]
    for t0, v in ((0.0, 0.8), (0.2, 0.85)):
        put(y, d_taiko(rng, v), t0, 0.9)
        for s in stab:
            put(y, v_brass(hz(s), 0.1, 0.9, rng), t0, 0.32)
    put(y, d_tom(rng, 150, 0.6), 0.36, 0.5)
    put(y, d_tom(rng, 120, 0.7), 0.41, 0.55)
    t1 = 0.46
    put(y, d_taiko(rng, 1.0, f0=62.0, dur=1.3), t1, 1.0)
    put(y, d_crash(rng, 0.7, 1.6, 0.55), t1, 0.6)
    for s in ["Bb2", "F3", "Bb3", "D4"]:
        put(y, v_brass(hz(s), 0.72, 1.0, rng, bend=-1.0), t1, 0.3)
    for i, s in enumerate(["E6", "D6", "Bb5", "A5"]):
        put(y, v_xylo(hz(s), 0.1, 0.6, rng), 1.0 + 0.06 * i, 0.3)
    return reverb_mono(y, wet=0.24, seconds=1.6, room=0.7, size=0.9)


# ----------------------------------------------------------------------------
# SFX: meta (spin, gift) and jingles
# ----------------------------------------------------------------------------

@sfx("spin_tick", length=0.03)
def _spin_tick(rng):
    n = nsamp(0.04)
    t = tvec(n)
    return band_noise(n, rng, 1500, 6000) * np.exp(-t / 0.0015) * 0.7 \
        + 0.5 * np.sin(TWO_PI * 1900 * t) * np.exp(-t / 0.0035) \
        + 0.25 * np.sin(TWO_PI * 950 * t) * np.exp(-t / 0.005)


@sfx("spin_win", length=1.4, fade_s=0.4)
def _spin_win(rng):
    y = canvas(1.8)
    n = nsamp(0.2)
    t = tvec(n)
    put(y, lp_noise(n, rng, 3000) * np.exp(-t / 0.006) * 0.8 + thump(n, 200, 80, 0.03) * 0.4, 0.0)
    m = nsamp(0.8)
    tm = tvec(m)
    put(y, crackle(m, rng, 3200.0 * np.exp(-tm / 0.25) + 60, lo=2500, hi=9000) * env_ad(m, 0.01, 0.35) * 0.28, 0.02)
    for i, s in enumerate(["C6", "D6", "E6", "G6", "A6", "C7"]):
        put(y, v_glock(hz(s), 0.1, 0.6 + 0.05 * i, rng, tau=0.25), 0.05 + 0.032 * i, 0.5)
    t0 = 0.26
    for s, g in (("C6", 0.6), ("E6", 0.6), ("G6", 0.6), ("C7", 0.7)):
        put(y, chime(hz(s), 0.85, rng, tau=0.65, bell=0.5, mar=0.4), t0, g * 0.7)
    put(y, d_crash(rng, 0.3, 1.2, 0.45), t0, 0.35)
    y += pings(len(y), rng, t0, t0 + 1.0, 40, freqs=[float(midi_hz(m_)) for m_ in PENT_C], amp=0.18, decay=0.4)
    return reverb_mono(y, wet=0.22, seconds=1.3, room=0.6, size=0.8)


@sfx("daily_gift", length=1.2, fade_s=0.35)
def _daily_gift(rng):
    y = canvas(1.6)
    n = nsamp(0.32)
    x = np.linspace(0, 1, n)
    env = env_points(n, [(0, 0.2), (0.05, 1.0), (0.22, 0.7), (0.3, 1.0), (0.32, 0.0)])
    rust = crackle(n, rng, 1500.0 + 3500.0 * x ** 3, lo=1500, hi=8000) * 0.6 \
        + tv_noise(n, rng, glide(1000, 5000, n), 1.3) * 0.3
    put(y, rust * env, 0.0, 0.85)
    for s in ["G5", "B5", "D6"]:
        put(y, chime(hz(s), 0.7, rng, tau=0.2, bell=0.4, mar=0.4), 0.34, 0.45)
    for s, g in (("C6", 0.6), ("E6", 0.55), ("G6", 0.55), ("C7", 0.6)):
        put(y, chime(hz(s), 0.85, rng, tau=0.6, bell=0.5, mar=0.35), 0.46, g * 0.75)
    y += pings(len(y), rng, 0.46, 1.2, 34, freqs=[float(midi_hz(m)) for m in PENT_C], amp=0.18, decay=0.4)
    return reverb_mono(y, wet=0.22, seconds=1.3, room=0.6, size=0.8)


def _seq(dst, voice, notes, bpm, rng, gain=1.0, t_off=0.0):
    """notes: [(beat, dur_beats, 'C#5' or [names], vel)]"""
    spb = 60.0 / bpm
    for beat, durb, names, vel in notes:
        for s in (names if isinstance(names, (list, tuple)) else [names]):
            y = voice(hz(s), durb * spb, vel, rng)
            if y.ndim == 2:
                y = y.mean(axis=1)
            put(dst, y, t_off + beat * spb, gain)


@sfx("jingle_win", length=3.0, fade_s=0.6)
def _jingle_win(rng):
    bpm = 150.0
    y = canvas(3.6)
    mel = [(0.0, 0.5, "C#5", 0.8), (0.5, 0.5, "E5", 0.8), (1.0, 0.5, "A5", 0.9), (1.5, 0.25, "G#5", 0.7),
           (1.75, 0.25, "A5", 0.75), (2.0, 0.5, "B5", 0.85), (2.5, 0.5, "C#6", 0.85), (3.0, 0.75, "E6", 0.95),
           (3.75, 0.25, "D6", 0.75), (4.0, 0.5, "C#6", 0.85), (4.5, 0.25, "B5", 0.7), (4.75, 0.25, "C#6", 0.75),
           (5.0, 2.0, ["A5", "E6"], 1.0)]
    _seq(y, v_marimba, mel, bpm, rng, 0.55)
    _seq(y, lambda f, d, v, r: v_glock(f * 2, d, v, r, tau=0.4), mel, bpm, rng, 0.2)
    chords = [(0.5, "A3 C#4 E4"), (1.5, "A3 C#4 E4"), (2.5, "D4 F#4 A4"), (3.5, "E4 G#4 B4"), (4.5, "E4 G#4 D5")]
    for b, ch in chords:
        _seq(y, v_pluck, [(b, 0.4, ch.split(), 0.7)], bpm, rng, 0.25)
    _seq(y, lambda f, d, v, r: v_harp(f, d, v, r), [(5.0 + 0.06 * i, 2.0, s, 0.8) for i, s in
                                                    enumerate(["A3", "E4", "A4", "C#5", "E5"])], bpm, rng, 0.3)
    _seq(y, v_softbass, [(0, 0.9, "A2", 0.9), (1, 0.9, "A2", 0.8), (2, 0.9, "D2", 0.9), (3, 0.9, "E2", 0.9),
                         (4, 0.9, "E2", 0.85), (5, 2.0, "A1", 1.0)], bpm, rng, 0.45)
    spb = 60.0 / bpm
    for b in (0, 2, 5):
        put(y, d_kick(rng, 0.8), b * spb, 0.5)
    for b in (1, 3):
        put(y, d_clap(rng, 0.8), b * spb, 0.35)
    for k in range(10):
        put(y, d_shaker(rng, 0.7 if k % 2 else 0.4), k * 0.5 * spb, 0.25)
    put(y, d_crash(rng, 0.5, 1.6, 0.6), 5 * spb, 0.4)
    m = len(y)
    put(y, crackle(nsamp(0.9), rng, 1800.0, lo=2500, hi=9000) * env_ad(nsamp(0.9), 0.02, 0.3) * 0.12, 5 * spb)
    y += pings(m, rng, 5 * spb, 5 * spb + 1.0, 30, freqs=[float(midi_hz(x)) for x in PENT_A], amp=0.15, decay=0.4)
    return reverb_mono(y, wet=0.2, seconds=1.5, room=0.6, size=0.8)


@sfx("jingle_lose", length=2.6, fade_s=0.55)
def _jingle_lose(rng):
    bpm = 92.0
    spb = 60.0 / bpm
    y = canvas(3.2)
    mel = [(0.0, 0.5, "E5", 0.75), (0.5, 0.5, "D5", 0.7), (1.0, 0.5, "C5", 0.7), (1.5, 0.5, "B4", 0.65)]
    _seq(y, v_kalimba, mel, bpm, rng, 0.6)
    _seq(y, v_marimba, mel, bpm, rng, 0.25)
    # the final "aww": a soft note that droops ~70 cents with a little vibrato
    n = nsamp(2.2)
    t = tvec(n)
    f0 = hz("A4")
    droop = 2.0 ** (-0.7 / 12.0 * np.clip((t - 0.35) / 0.7, 0, 1) ** 1.5)
    vib = 1.0 + 0.004 * np.sin(TWO_PI * 5.0 * t) * np.clip((t - 0.2) / 0.3, 0, 1)
    ph = phase_of(f0 * droop * vib, n)
    aww = (np.sin(ph) + 0.18 * np.sin(2 * ph) + 0.06 * np.sin(3 * ph)) * env_adsr(n, 0.02, 0.6, 0.6, 0.35, 0.9)
    put(y, aww * 0.5, 2.0 * spb)
    put(y, v_kalimba(f0, 0.5, 0.7, rng), 2.0 * spb, 0.35)
    for b, ch in ((0.0, "C4 E4 G4"), (1.0, "B3 D4 G4"), (2.0, "A3 C4 E4")):
        for s in ch.split():
            p = v_pad_note(hz(s), spb * (1.0 if b < 2 else 1.6), 0.5, rng, att=0.12, rel=0.6, cutoff=1100).mean(axis=1)
            put(y, p, b * spb, 0.16)
    _seq(y, v_softbass, [(0, 0.9, "C3", 0.6), (1, 0.9, "B2", 0.6), (2, 1.8, "A2", 0.65)], bpm, rng, 0.35)
    return reverb_mono(y, wet=0.22, seconds=1.4, room=0.6, size=0.8)


# ----------------------------------------------------------------------------
# music engine: notation, chords, patterns, rendering, mastering
# ----------------------------------------------------------------------------

CHORD_Q = {
    "": [0, 4, 7], "m": [0, 3, 7], "5": [0, 7], "7": [0, 4, 7, 10], "maj7": [0, 4, 7, 11],
    "m7": [0, 3, 7, 10], "sus4": [0, 5, 7], "sus2": [0, 2, 7], "7sus4": [0, 5, 7, 10],
    "add9": [0, 4, 7, 14], "maj9": [0, 4, 7, 11, 14], "m9": [0, 3, 7, 10, 14], "6": [0, 4, 7, 9],
    "m6": [0, 3, 7, 9], "dim": [0, 3, 6], "m7b5": [0, 3, 6, 10], "dim7": [0, 3, 6, 9], "aug": [0, 4, 8],
    "9": [0, 4, 7, 10, 14], "madd9": [0, 3, 7, 14], "7b9": [0, 4, 7, 10, 13], "mmaj7": [0, 3, 7, 11],
}


def pc_of(s):
    pc = _PC[s[0]]
    for ch in s[1:]:
        pc += 1 if ch == "#" else -1
    return pc % 12


def parse_chord(sym):
    m = re.match(r"^([A-G][#b]?)([^/]*)(?:/([A-G][#b]?))?$", sym)
    if not m:
        raise ValueError("bad chord " + sym)
    root = pc_of(m.group(1))
    ivs = CHORD_Q[m.group(2)]
    bass = pc_of(m.group(3)) if m.group(3) else root
    return root, ivs, bass


def parse_prog(text, beats_per_bar=4):
    """'A | E/G# | F#m7 | Bm7 E7' -> [(beat, dur, symbol)]; 2 chords split a bar,
    'E7sus4:3 E7:1' sets explicit beats."""
    out = []
    beat = 0.0
    for bar in [b.strip() for b in text.split("|") if b.strip()]:
        toks = bar.split()
        durs = []
        for tk in toks:
            durs.append(float(tk.split(":")[1]) if ":" in tk else None)
        free = [d for d in durs if d is None]
        rest = beats_per_bar - sum(d for d in durs if d is not None)
        for tk, d in zip(toks, durs):
            d = d if d is not None else rest / len(free)
            out.append((beat, d, tk.split(":")[0]))
            beat += d
    return out


def parse_mel(text, beats_per_bar=4, start=0.0, check=True):
    """'E5:.5 C#5:.5 r:1 A5:2:0.9 | ...' -> [(beat, dur, midi, vel)].
    '|' separates bars and every bar is checked to add up to beats_per_bar."""
    out = []
    beat = start
    for bi, bar in enumerate([b for b in text.split("|")]):
        bar = bar.strip()
        if not bar:
            continue
        tot = 0.0
        for tk in bar.split():
            parts = tk.split(":")
            d = float(parts[1])
            vel = float(parts[2]) if len(parts) > 2 else 0.8
            if parts[0] != "r":
                out.append((beat, d, note(parts[0]), vel))
            beat += d
            tot += d
        if check and abs(tot - beats_per_bar) > 1e-6:
            raise ValueError("bar %d of melody has %.3f beats: %s" % (bi + 1, tot, bar))
    return out


def chord_at(prog, beat):
    for b, d, s in prog:
        if b <= beat + 1e-9 < b + d:
            return s
    return prog[-1][2]


def voice_chord(sym, low, high, prev=None, center=None, max_span=14):
    root, ivs, _ = parse_chord(sym)
    pcs = [(root + i) % 12 for i in ivs]
    opts = []
    for p in pcs:
        o = [m for m in range(low, high + 1) if m % 12 == p]
        opts.append(o)
    best, best_cost = None, 1e9
    for combo in itertools.product(*opts):
        c = sorted(combo)
        if len(set(c)) < len(c) or c[-1] - c[0] > max_span:
            continue
        if prev:
            pv = sorted(prev)
            k = min(len(pv), len(c))
            cost = sum(abs(a - b) for a, b in zip(c[:k], pv[:k])) + 0.5 * abs(len(c) - len(pv))
        else:
            cost = abs(np.mean(c) - (center if center is not None else (low + high) / 2.0))
        if cost < best_cost:
            best, best_cost = c, cost
    if best is None:
        return voice_chord(sym, low, high + 12, prev, center, max_span + 5)
    return best


def voicings(prog, low, high, center=None):
    out, prev = [], None
    for b, d, s in prog:
        v = voice_chord(s, low, high, prev, center)
        out.append((b, d, v))
        prev = v
    return out


def bass_note(sym, low=note("E1"), high=note("D#3")):
    _, _, b = parse_chord(sym)
    for m in range(low, high + 1):
        if m % 12 == b:
            return m
    return low


class Song:
    def __init__(self, name, bpm, bars, beats_per_bar=4, swing=0.0):
        self.name = name
        self.bpm = bpm
        self.bars = bars
        self.bpb = beats_per_bar
        self.total_beats = bars * beats_per_bar
        self.L = int(round(self.total_beats * 60.0 / bpm * SR))
        self.spb = self.L / self.total_beats
        self.swing = swing
        self.parts = []

    def bar(self, i):
        return i * self.bpb

    def add(self, name, voice, events, level, pan=0.0, send=0.15, kind="tonal", humanize=0.003,
            vel_jitter=0.05, variants=3, width=0.0):
        self.parts.append(dict(name=name, voice=voice, events=events, level=level, pan=pan, send=send,
                               kind=kind, humanize=humanize, vel_jitter=vel_jitter, variants=variants,
                               width=width))

    def beat_to_sample(self, beat):
        if self.swing:
            q = beat * 4.0
            iq = math.floor(q + 1e-9)
            if iq % 2 == 1 and abs(q - iq) < 1e-6:
                beat += self.swing * 0.25
        return int(round(beat * self.spb))


def _pan_gains(p):
    th = (p + 1.0) * math.pi / 4.0
    return math.cos(th) * math.sqrt(2.0), math.sin(th) * math.sqrt(2.0)


def render_part(song, part, tail_s=6.0):
    L = song.L
    buf = np.zeros((L + nsamp(tail_s), 2))
    rng = rng_for(song.name + "/" + part["name"])
    cache = {}
    gl, gr = _pan_gains(part["pan"])
    for ev in part["events"]:
        beat, durb, m, vel = ev
        vel = float(np.clip(vel * (1.0 + part["vel_jitter"] * rng.uniform(-1, 1)), 0.05, 1.0))
        var = int(rng.integers(0, part["variants"]))
        key = (round(durb, 4), tuple(m) if isinstance(m, (list, tuple)) else m, round(vel, 2), var)
        if key not in cache:
            vr = np.random.default_rng(zlib.crc32(repr((song.name, part["name"], key)).encode()))
            if part["kind"] == "drum":
                y = part["voice"](vr, vel)
            elif part["kind"] == "tom":
                y = d_tom(vr, float(m), vel)
            elif part["kind"] == "riser":
                y = d_riser(vr, durb * song.spb / SR) * vel
            else:
                notes_ = m if isinstance(m, (list, tuple)) else [m]
                y = None
                for mm in notes_:
                    z = part["voice"](float(midi_hz(mm)), durb * song.spb / SR, vel, vr)
                    y = z if y is None else sumz2(y, z)
            cache[key] = tail_fade(y, 0.02)
        y = cache[key]
        start = song.beat_to_sample(beat) + int(round(rng.uniform(-1, 1) * part["humanize"] * SR))
        if start < 0:
            start += L
        if y.ndim == 1:
            if part["width"] > 0 and isinstance(m, (int, np.integer)):
                p = float(np.clip(part["pan"] + part["width"] * ((m % 12) / 5.5 - 1.0), -1, 1))
                a, b = _pan_gains(p)
            else:
                a, b = gl, gr
            mix_at(buf[:, 0], y, start, a)
            mix_at(buf[:, 1], y, start, b)
        else:
            mix_at(buf[:, 0], y[:, 0], start, gl)
            mix_at(buf[:, 1], y[:, 1], start, gr)
    return buf


def sumz2(a, b):
    n = max(len(a), len(b))
    shape = (n,) + a.shape[1:]
    out = np.zeros(shape)
    out[:len(a)] += a
    out[:len(b)] += b
    return out


def active_loudness(x, win_s=0.4, rel_gate=-20.0):
    """K-weighted loudness over the parts of a stem that actually play."""
    y = kweight(x)
    p = np.sum(y ** 2, axis=1) if y.ndim == 2 else y ** 2
    w = nsamp(win_s)
    nb = len(p) // w
    if nb == 0:
        return -100.0
    blocks = p[:nb * w].reshape(nb, w).mean(axis=1)
    lv = 10 * np.log10(np.maximum(blocks, 1e-14))
    act = blocks[lv > lv.max() + rel_gate]
    return -0.691 + 10 * math.log10(max(float(np.mean(act)), 1e-14))


def circ_filter(x, magfun):
    n = x.shape[0]
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(n, 1.0 / SR)
    H = magfun(f)
    return np.fft.irfft(X * (H[:, None] if x.ndim == 2 else H), n=n, axis=0)


def render_song(song, reverb=dict(seconds=3.0, room=0.72, damp=0.5, size=1.0, predelay=0.018),
                wet=0.5, target_lufs=-14.0, ceiling_db=MUSIC_PEAK_DB, comp=dict(ratio=1.6, above_rms_db=1.0),
                level_ref=None, max_gr=4.5, eq=(90.0, -2.5, 4000.0, 1.5), stems_out=None, verbose=True):
    """Render all parts, auto-balance them to their target levels, fold into a
    seamless loop, add reverb by circular convolution and master (circular)."""
    L = song.L
    dry = None
    send = None
    report = []
    for part in song.parts:
        t0 = time.time()
        st = render_part(song, part)
        lv = active_loudness(fold(st, L))
        g = float(undb(part["level"] - lv))
        st *= g
        dry = st if dry is None else dry + st
        sd = st * part["send"]
        send = sd if send is None else send + sd
        report.append((part["name"], lv, part["level"], time.time() - t0, len(part["events"])))
        if stems_out is not None:
            stems_out[part["name"]] = fold(st, L)
    if verbose:
        for nm, lv, tgt, dt, ne in report:
            print("    %-14s events %4d  raw %6.1f LU -> %6.1f  (%.1fs)" % (nm, ne, lv, tgt, dt))
    dry = fold(dry, L)
    sendm = fold(send, L).mean(axis=1)
    ir = freeverb_ir(**reverb)
    wetsig = np.stack([fold(fft_convolve(sendm, ir[:, c]), L) for c in range(2)], axis=1)
    mix = dry + wet * wetsig
    lo_f, lo_db, hi_f, hi_db = eq
    mix = circ_filter(mix, lambda f: mag_hp(f, 32.0, 2) * mag_shelf(f, lo_f, lo_db) * mag_shelf(f, hi_f, hi_db, True))
    if level_ref is None:  # compressor threshold sits just above the loop's mean level
        level_ref = {"thresh": rms_db(mix) + comp.get("above_rms_db", 1.0)}
    mix = compressor(mix, thresh_db=level_ref["thresh"], ratio=comp.get("ratio", 1.6), circular=True)
    if "gain" not in level_ref:
        level_ref["gain"] = float(undb(target_lufs - loudness_integrated(mix)))
    mix = mix * level_ref["gain"]
    over = db(peak(mix)) - ceiling_db
    if over > max_gr:  # keep limiting gentle
        mix *= float(undb(max_gr - over))
    mix = limiter(mix, float(undb(ceiling_db - 0.1)), win_ms=8.0, circular=True)
    level_ref["limiter_gr_db"] = max(0.0, min(over, max_gr))
    return mix, level_ref


# ----------------------------------------------------------------------------
# pattern helpers
# ----------------------------------------------------------------------------

VEL = {"X": 1.0, "x": 0.75, "o": 0.45, "g": 0.25}


def in_bars(b, *ranges):
    """b is 0-based; ranges are 1-based inclusive (first_bar, last_bar)."""
    return any(lo - 1 <= b <= hi - 1 for lo, hi in ranges)


def drum_events(bars, pattern_for_bar):
    ev = []
    for b in range(bars):
        pat = pattern_for_bar(b)
        if not pat:
            continue
        pat = pat.replace(" ", "")
        step = 4.0 / len(pat)
        for i, ch in enumerate(pat):
            if ch in VEL:
                ev.append((b * 4 + i * step, 0.0, None, VEL[ch]))
    return ev


def next_chord(prog, beat, total):
    for b, d, s in prog:
        if b > beat + 1e-9:
            return s
    return prog[0][2] if total else prog[-1][2]


def bass_events(prog, bars, pattern_for_bar, low=note("E1"), high=note("D#3"), gate=0.62, accent=0.95,
                soft=0.72):
    ev = []
    total = bars * 4
    for b in range(bars):
        pat = pattern_for_bar(b)
        if not pat:
            continue
        toks = pat.split()
        step = 4.0 / len(toks)
        for i, tok in enumerate(toks):
            if tok == "-":
                continue
            beat = b * 4 + i * step
            sym = chord_at(prog, beat)
            root, ivs, _ = parse_chord(sym)
            r = bass_note(sym, low, high)
            third = 3 if 3 in ivs else 4
            hold = 1
            if tok.endswith("~"):
                tok, hold = tok[:-1], 2
            if tok == "R":
                m = r
            elif tok == "O":
                m = r + 12
            elif tok == "F":
                m = r + 7 if r + 7 <= high + 5 else r - 5
            elif tok == "f":
                m = r - 5
            elif tok == "T":
                m = r + third
            elif tok == "A":
                nr = bass_note(next_chord(prog, b * 4 + 3.99, total), low, high)
                m = nr - 1 if nr - 1 != r else nr + 2
            else:
                raise ValueError(tok)
            v = accent if abs(beat - round(beat)) < 1e-6 and int(round(beat)) % 2 == 0 else soft
            ev.append((beat, step * gate * hold, m, v))
    return ev


def stab_events(vprog, positions, dur, vel=0.8, accent=None, bars=None):
    ev = []
    for b, d, v in vprog:
        bar_i = int(b // 4)
        if bars is not None and not bars(bar_i):
            continue
        for p in positions:
            beat = math.floor(b / 4) * 4 + p
            if b - 1e-9 <= beat < b + d - 1e-9:
                vv = vel if accent is None else (accent if abs(p - round(p)) < 1e-6 else vel)
                ev.append((beat, dur, list(v), vv))
    return ev


def arp_events(vprog, step, order, dur, vel=0.7, bars=None, octave_up=True):
    ev = []
    for b, d, v in vprog:
        bar_i = int(b // 4)
        if bars is not None and not bars(bar_i):
            continue
        tones = list(v) + ([v[0] + 12, v[1] + 12] if octave_up else [])
        k = 0
        beat = b
        while beat < b + d - 1e-9:
            idx = order[k % len(order)] % len(tones)
            acc = 1.0 if abs((beat * 2) - round(beat * 2)) < 1e-6 else 0.8
            ev.append((beat, dur, tones[idx], vel * acc))
            beat += step
            k += 1
    return ev


def pad_events(vprog, vel=0.6, bars=None):
    return [(b, d, list(v), vel) for b, d, v in vprog if bars is None or bars(int(b // 4))]


def repeat_mel(text, times):
    return " | ".join([text] * times)


# ----------------------------------------------------------------------------
# music_game / music_game_intense  (A major, 124 BPM, 32 bars of 4/4)
# ----------------------------------------------------------------------------

GAME_PROG = parse_prog(
    "A | E/G# | F#m7 | C#m7 | Dmaj7 | A/C# | Bm7 | E7sus4 E7 |"
    "A | E/G# | F#m7 | C#m7 | Dmaj7 | D#m7b5 | E | E7 |"
    "Dmaj7 | E | C#m7 | F#m7 | Bm7 | C#m7 | Dmaj7 | E7sus4 E7 |"
    "A | E/G# | F#m7 | C#m7 | Dmaj7 | E | A | E7")

_GA = ("E5:.5 C#5:.5 E5:.5 A5:1 r:.5 F#5:.5 E5:.5 | D5:.5 B4:.5 D5:.5 G#5:1 r:.5 E5:.5 D5:.5 | "
       "C#5:.5 A4:.5 C#5:.5 F#5:.75 E5:.25 E5:.5 C#5:.5 B4:.5 | C#5:1.5 r:.5 E5:.5 G#5:.5 B5:.5 A5:.5")
GAME_MEL = " | ".join([
    _GA,
    "F#5:.5 A5:.5 F#5:.5 C#6:1 r:.5 B5:.5 A5:.5 | E5:1 C#5:.5 E5:.5 A5:1.5 r:.5 | "
    "D5:.5 F#5:.5 A5:.5 B5:.5 D6:.5 C#6:.5 B5:.5 A5:.5 | B5:1.5 A5:.5 G#5:1 r:1",
    _GA,
    "F#5:.5 A5:.5 F#5:.5 C#6:1 r:.5 B5:.5 A5:.5 | C#6:.5 B5:.5 A5:.5 F#5:1 r:.5 D#5:.5 F#5:.5 | "
    "E5:.5 G#5:.5 B5:.5 E6:1.5 r:1 | D6:.5 C#6:.5 B5:.5 G#5:.5 E5:.5 D5:.5 B4:.5 G#4:.5",
    "r:.5 F#5:.5 A5:.5 C#6:2.5 | B5:.5 A5:.5 G#5:.5 B5:2.5 | r:.5 E5:.5 G#5:.5 B5:1.5 A5:.5 G#5:.5 | A5:3 r:1",
    "r:.5 D5:.5 F#5:.5 A5:1.5 B5:.5 A5:.5 | G#5:1.5 E5:.5 C#5:1 E5:1 | F#5:.5 E5:.5 F#5:.5 A5:1.5 B5:1 | "
    "A5:1 B5:1 G#5:1 r:1",
    _GA,
    "F#5:.5 A5:.5 F#5:.5 C#6:1 r:.5 B5:.5 A5:.5 | G#5:.5 B5:.5 G#5:.5 E6:1 r:.5 D6:.5 B5:.5 | "
    "A5:1.5 E5:.5 C#5:.5 E5:.5 A5:1 | r:2.5 B4:.5 C#5:.5 D5:.5",
])

_GC = "A4:1.5 G#4:.5 A4:1 C#5:1 | B4:2 G#4:1 E4:1 | F#4:1.5 E4:.5 F#4:1 A4:1 | G#4:2 E4:1 G#4:1"
GAME_COUNTER = " | ".join([
    _GC,
    "A4:1.5 F#4:.5 A4:1 D5:1 | C#5:2 A4:1 E4:1 | F#4:1 A4:1 D5:1 C#5:1 | B4:2 G#4:1 D5:1",
    _GC,
    "A4:1.5 F#4:.5 A4:1 C#5:1 | A4:2 F#4:1 A4:1 | G#4:1 B4:1 E5:1 D5:1 | D5:1 B4:1 G#4:1 E4:1",
    "r:1 A4:.5 B4:.5 C#5:.5 A4:.5 F#4:1 | r:1 G#4:.5 A4:.5 B4:.5 G#4:.5 E4:1 | r:2 E4:.5 G#4:.5 B4:1 | "
    "r:1 C#5:.5 B4:.5 A4:.5 F#4:.5 E4:1",
    "F#4:2 D4:1 F#4:1 | E4:2 G#4:1 B4:1 | r:1 F#4:.5 A4:.5 C#5:.5 D5:.5 E5:1 | D5:1 C#5:1 B4:1 G#4:1",
    _GC,
    "A4:1.5 F#4:.5 A4:1 C#5:1 | B4:1.5 G#4:.5 B4:1 E5:1 | C#5:2 E5:1 C#5:1 | B4:1 G#4:1 E4:1 D4:1",
])


def v_arp(f, dur, vel, rng):
    return v_pluck(f, dur, vel, rng, decay=0.35, bright=0.75, mute=0.05, pick=0.3)


def build_game(intense):
    s = Song("game", 124.0, 32)
    s.name = "game"  # identical seeds for both versions -> shared parts are sample-identical
    B = s.bars
    vp_stab = voicings(GAME_PROG, note("C#4"), note("E5"), center=note("A4"))
    vp_pad = voicings(GAME_PROG, note("E3"), note("C#5"), center=note("A3") + 3)
    mel = parse_mel(GAME_MEL)

    def bass_pat(b):
        if in_bars(b, (17, 23)):
            return "R - R O - R F -" if b % 2 == 0 else "R - O - F - T A"
        if b % 4 == 3:
            return "R - O R - F O A"
        return "R - O R - R F A"
    s.add("bass", v_bass, bass_events(GAME_PROG, B, bass_pat, low=note("E2"), high=note("D#3")), -15.0,
          pan=0.0, send=0.0, humanize=0.0015)
    s.add("plucks", v_pluck, stab_events(vp_stab, [0.5, 1.5, 2.5, 3.5], 0.3, 0.75,
                                         bars=lambda b: not in_bars(b, (17, 24))), -21.0, pan=-0.4, send=0.18)
    s.add("plucks_b", v_pluck, stab_events(vp_stab, [0.0, 0.75, 1.5, 2.5, 3.0], 0.35, 0.7, accent=0.85,
                                           bars=lambda b: in_bars(b, (17, 24))), -21.0, pan=-0.4, send=0.2)
    s.add("lead", v_lead, mel, -15.5, pan=0.08, send=0.18, humanize=0.003)
    s.add("glock_lift", lambda f, d, v, r: v_glock(2 * f, d, v, r, tau=0.5),
          [e for e in mel if e[0] >= 24 * 4], -26.0, pan=0.45, send=0.25)
    s.add("pad", lambda f, d, v, r: v_pad_note(f, d, v, r, att=0.25, rel=0.6, cutoff=1300),
          pad_events(vp_pad, 0.55, bars=lambda b: in_bars(b, (9, 32))), -25.0, send=0.3)
    s.add("kick", d_kick, drum_events(B, lambda b: "X.......X......." if in_bars(b, (17, 23)) else
                                      ("X.....x.X.....x." if b % 8 == 7 else "X.....x.X.......")),
          -14.0, send=0.0, kind="drum", humanize=0.0005, variants=2)
    s.add("clap", d_clap, drum_events(B, lambda b: "....X.......X..x" if b % 8 == 7 else "....X.......X..."),
          -18.0, pan=0.05, send=0.12, kind="drum", humanize=0.001, variants=4)
    s.add("hats", d_hat, drum_events(B, lambda b: "o.x.o.x.o.x.o.x."), -25.0, pan=0.4, send=0.04,
          kind="drum", humanize=0.001, variants=4)
    s.add("ohat", lambda r, v: d_hat(r, v, open_=True),
          drum_events(B, lambda b: "..............x." if b % 2 == 1 else None), -27.0, pan=0.4, send=0.06,
          kind="drum", humanize=0.001)
    s.add("shaker", d_shaker, drum_events(B, lambda b: "oxgxoxgxoxgxoxgx" if in_bars(b, (9, 32)) else None),
          -28.0, pan=-0.5, send=0.05, kind="drum", humanize=0.002, variants=4)
    if intense:
        s.add("kick2", lambda r, v: d_kick(r, v, f_start=180.0, tau=0.12, click=0.35),
              drum_events(B, lambda b: "....X.......X..."), -17.0, send=0.0, kind="drum", humanize=0.0005)
        s.add("snare", d_snare, drum_events(B, lambda b: "....X.......X.oo" if b % 8 == 7 else "....X.......X..."),
              -19.0, pan=-0.05, send=0.12, kind="drum", humanize=0.001, variants=3)
        s.add("hats16", d_hat, drum_events(B, lambda b: "xoxoxoxoxoxoxoxo"), -27.0, pan=-0.45, send=0.03,
              kind="drum", humanize=0.001, variants=4)
        s.add("tamb", d_tamb, drum_events(B, lambda b: "..x...x...x...x."), -27.0, pan=0.55, send=0.06,
              kind="drum", humanize=0.002, variants=3)
        tom_ev = []
        for b in range(7, B, 8):
            for i, (pitch, v) in enumerate(((210, 0.8), (210, 0.6), (165, 0.85), (165, 0.6),
                                            (130, 0.9), (130, 0.7), (100, 1.0), (100, 0.8))):
                tom_ev.append((b * 4 + 2.0 + i * 0.25, 0.0, pitch, v))
        s.add("toms", None, tom_ev, -18.0, pan=0.0, send=0.12, kind="tom", humanize=0.001)
        s.add("crash", lambda r, v: d_crash(r, v), drum_events(B, lambda b: "X..............." if b % 8 == 0 else None),
              -24.0, pan=-0.3, send=0.15, kind="drum")
        s.add("riser", None, [(b * 4.0, 4.0, None, 0.8) for b in range(7, B, 8)], -27.0, send=0.2, kind="riser")
        s.add("arp", v_arp, arp_events(vp_stab, 0.25, [0, 1, 2, 3, 4, 3, 2, 1], 0.22, 0.75), -23.0,
              pan=0.0, send=0.2, width=0.8, humanize=0.0015)
        s.add("counter", v_square_lead, parse_mel(GAME_COUNTER), -20.5, pan=-0.4, send=0.2)
        s.add("glock_dbl", lambda f, d, v, r: v_glock(2 * f, d, v, r, tau=0.45),
              [e for e in mel if e[0] < 24 * 4], -28.0, pan=0.45, send=0.25)
    return s


# ----------------------------------------------------------------------------
# music_menu  (D major, 96 BPM, 32 bars, light 16th swing)
# ----------------------------------------------------------------------------

MENU_PROG = parse_prog(
    "Dmaj7 | Gmaj7 | Bm7 | A7sus4 | Dmaj7 | Gmaj7 | Em7 | A7sus4 A7 |"
    "Dmaj7 | Gmaj7 | Bm7 | A7sus4 | F#m7 | Bm7 | Em7 | A7sus4 A7 |"
    "Gmaj7 | A6 | F#m7 | Bm7 | Em9 | F#m7 | Gmaj7 | A7sus4 A7 |"
    "Dmaj7 | Gmaj7 | Bm7 | A7sus4 | Gmaj7 | Gm6 | Dmaj7 | A7sus4")

_MA = ("A4:1 F#5:1.5 E5:.5 D5:1 | B4:1.5 D5:.5 F#5:1 E5:1 | D5:.5 C#5:.5 D5:.5 F#5:1.5 A5:1 | "
       "G5:1.5 E5:.5 D5:2")
MENU_MEL_A = " | ".join([
    _MA,
    "A4:1 F#5:1.5 E5:.5 D5:.5 E5:.5 | F#5:1 G5:.5 A5:.5 B5:2 | A5:.5 G5:.5 E5:.5 D5:.5 E5:1 G5:1 | "
    "F#5:1 E5:1 C#5:1 r:1",
    "A4:.5 D5:.5 F#5:1 A5:1 F#5:.5 E5:.5 | D5:1.5 B4:.5 D5:1 G5:1 | F#5:1.5 E5:.5 D5:.5 C#5:.5 B4:1 | D5:3 r:1",
    "C#5:.5 E5:.5 A5:1.5 F#5:.5 E5:1 | D5:1 F#5:.5 B5:1.5 A5:1 | G5:1 F#5:.5 E5:.5 D5:1 B4:1 | "
    "E5:1.5 D5:.5 C#5:1 E5:1",
])
MENU_MEL_B = ("B5:1.5 A5:.5 F#5:1 D5:1 | E5:1.5 F#5:.5 C#5:2 | A5:1 C#6:1 B5:.5 A5:.5 E5:1 | F#5:3 r:1 | "
              "G5:1.5 F#5:.5 E5:1 B5:1 | A5:1.5 E5:.5 C#5:1 F#5:1 | D6:1 B5:.5 A5:.5 G5:1 F#5:1 | "
              "E5:1 D5:1 C#5:1.5 r:.5")
MENU_MEL_C = _MA + (" | B4:.5 D5:.5 F#5:.5 A5:.5 B5:1.5 A5:.5 | Bb5:1.5 A5:.5 G5:1 E5:1 | F#5:2.5 E5:.5 D5:1 | "
                    "E5:1.5 D5:.5 r:2")


def build_menu():
    s = Song("menu", 96.0, 32, swing=0.12)
    B = s.bars
    vp_arp = voicings(MENU_PROG, note("D3"), note("B4"), center=note("F#3") + 3)
    vp_pad = voicings(MENU_PROG, note("F#3"), note("E5"), center=note("B3") + 2)
    vp_kal = voicings(MENU_PROG, note("D5"), note("D6"), center=note("A5"))
    lead = parse_mel(MENU_MEL_A) + parse_mel(MENU_MEL_C, start=96.0)
    s.add("kalimba", v_kalimba, lead, -16.0, pan=0.1, send=0.22, humanize=0.004)
    s.add("marimba", v_marimba, parse_mel(MENU_MEL_B, start=64.0), -16.5, pan=0.05, send=0.22, humanize=0.004)
    s.add("harp", v_harp, arp_events(vp_arp, 0.5, [0, 2, 1, 3, 4, 3, 2, 1], 0.95, 0.6), -22.0, pan=-0.3,
          send=0.25, width=0.5, humanize=0.004)
    s.add("kal_arp", v_kalimba, arp_events(vp_kal, 0.5, [0, 1, 2, 1], 0.45, 0.5,
                                           bars=lambda b: in_bars(b, (17, 24))), -25.0, pan=0.5, send=0.3,
          humanize=0.004)
    s.add("pad", lambda f, d, v, r: v_pad_note(f, d, v, r, att=0.6, rel=1.0, cutoff=1200),
          pad_events(vp_pad, 0.5), -26.0, send=0.35)

    def bass_pat(b):
        if in_bars(b, (17, 24)):
            return "R~ - T F"
        return "R~ - F A" if b % 2 == 1 else "R~ - F -"
    s.add("bass", v_softbass, bass_events(MENU_PROG, B, bass_pat, low=note("D2"), high=note("C#3"), gate=0.92),
          -17.5, send=0.02, humanize=0.002)
    s.add("shaker", d_shaker, drum_events(B, lambda b: "ogxgogxgogxgogxg" if in_bars(b, (9, 32)) else "o.x.o.x.o.x.o.x."),
          -29.0, pan=0.45, send=0.06, kind="drum", humanize=0.002, variants=4)
    s.add("kick", lambda r, v: d_kick(r, v, f_start=120.0, f_end=48.0, tau=0.14, click=0.1),
          drum_events(B, lambda b: "x.......o......." if in_bars(b, (9, 32)) else None), -21.0, send=0.0,
          kind="drum", humanize=0.001)
    s.add("snap", d_snap, drum_events(B, lambda b: "....x.......x..." if in_bars(b, (9, 16), (25, 32)) else None),
          -24.0, pan=-0.25, send=0.18, kind="drum", humanize=0.002, variants=3)
    s.add("rim", d_rim, drum_events(B, lambda b: "....x.......x..o" if in_bars(b, (17, 24)) else None),
          -25.0, pan=-0.25, send=0.15, kind="drum", humanize=0.002, variants=3)
    s.add("wood", lambda r, v: d_wood(r, 1150.0, v),
          drum_events(B, lambda b: "..............x." if b % 4 == 3 else None), -27.0, pan=0.4, send=0.2,
          kind="drum")
    spark = []
    for b in (7, 15, 23, 31):
        for i, nm in enumerate(("D6", "F#6", "A6", "D7")):
            spark.append((b * 4 + 2.5 + 0.25 * i, 0.5, note(nm), 0.55 + 0.05 * i))
    s.add("glock", lambda f, d, v, r: v_glock(f, d, v, r, tau=0.6), spark, -27.0, pan=0.5, send=0.3)
    return s


# ----------------------------------------------------------------------------
# music_boss  (A minor, 132 BPM, 32 bars) - dramatic but cartoonish
# ----------------------------------------------------------------------------

BOSS_PROG = parse_prog(
    "Am | Am | F | E | Am | Am | Dm6 | E7 |"
    "Am | G | F | E | Dm | Am/C | B7 | E7 |"
    "F | G | Em | Am | F | G | E/G# | Am |"
    "Am | Am | F | E | Am | F | Bb | E7")

_BA = ("A4:.5 A4:.5 C5:.5 A4:.5 E5:1 D5:.5 C5:.5 | B4:.5 C5:.5 B4:.5 A4:.5 G#4:1 E4:1 | "
       "A4:.5 A4:.5 C5:.5 A4:.5 F5:1 E5:.5 D5:.5 | E5:1.5 D5:.5 C5:.5 B4:.5 G#4:1")
BOSS_MEL = " | ".join([
    _BA,
    "A4:.5 A4:.5 C5:.5 A4:.5 E5:1 F5:.5 E5:.5 | D5:.5 E5:.5 C5:.5 A4:.5 B4:1 r:1 | "
    "D5:.5 F5:.5 A5:.5 F5:.5 B5:1 A5:.5 F5:.5 | G#5:1.5 E5:.5 D5:.5 B4:.5 G#4:1",
    "A5:1.5 E5:.5 C5:1 A4:1 | B4:.5 D5:.5 G5:1.5 F5:.5 E5:.5 D5:.5 | C5:.5 F5:.5 A5:1.5 G5:.5 F5:.5 E5:.5 | "
    "G#5:2 E5:1 B4:1",
    "F5:.5 E5:.5 D5:.5 A4:.5 D5:1 F5:1 | E5:1.5 C5:.5 A4:1 C5:1 | D#5:1 F#5:1 A5:1 B5:1 | "
    "G#5:.5 F#5:.5 E5:.5 D5:.5 C5:.5 B4:.5 A4:.5 G#4:.5",
    "C5:1 F5:1 A5:2 | B5:1.5 A5:.5 G5:1 D5:1 | E5:1 G5:1 B5:2 | A5:1.5 G5:.5 E5:1 C5:1",
    "F5:1 A5:1 C6:2 | B5:1 D6:1 B5:1 G5:1 | G#5:1.5 B5:.5 E6:1 D6:1 | C6:1 B5:.5 A5:.5 E5:2",
    _BA,
    "A4:.5 A4:.5 C5:.5 A4:.5 E5:1 F5:.5 E5:.5 | F5:.5 E5:.5 C5:.5 A4:.5 C5:1 D5:1 | D5:1 F5:1 Bb5:1.5 A5:.5 | "
    "G#5:1 r:1 B4:.5 C5:.5 D5:.5 E5:.5",
])

BOSS_XYLO = {  # bar (1-based) -> notes for beats 2.0-4.0 as 16ths
    8: "E5 F5 G#5 A5 B5 C6 D6 E6",
    16: "E6 D6 C6 B5 A5 G#5 F#5 E5",
    24: "E5 G5 A5 C6 E6 G6 A6 C7",
    32: "E6 D6 B5 G#5 F5 E5 D5 B4",
}


def build_boss():
    s = Song("boss", 132.0, 32)
    B = s.bars
    s.add("brass", v_brass, parse_mel(BOSS_MEL), -15.5, pan=0.05, send=0.2, humanize=0.003)
    s.add("bells", lambda f, d, v, r: v_bell(2 * f, d, v, r, ratio=3.5, index=1.2, tau=0.7),
          [e for e in parse_mel(BOSS_MEL) if 16 * 4 <= e[0] < 24 * 4], -25.0, pan=0.45, send=0.25)

    def ost(b):
        return "R F O F R F O F" if in_bars(b, (17, 24)) else "R R T R F R T R"
    s.add("ostinato", v_strings_stacc, bass_events(BOSS_PROG, B, ost, low=note("A2"), high=note("G#3"), gate=0.5,
                                                   accent=0.9, soft=0.7), -20.0, pan=-0.4, send=0.15,
          humanize=0.002)
    s.add("tuba", v_tuba, bass_events(BOSS_PROG, B, lambda b: "R - F -" if b % 4 != 3 else "R - F A",
                                      low=note("C2"), high=note("B2"), gate=0.55), -15.5, send=0.03,
          humanize=0.002)
    vp = voicings(BOSS_PROG, note("A3"), note("A4"), center=note("D4"))
    s.add("pizz", v_pizz, stab_events(vp, [1.0, 3.0], 0.25, 0.8), -22.0, pan=0.45, send=0.18, humanize=0.002)
    s.add("pad", lambda f, d, v, r: v_pad_note(f, d, v, r, att=0.4, rel=0.8, cutoff=1100),
          pad_events(voicings(BOSS_PROG, note("E3"), note("E4"), center=note("A3")), 0.55,
                     bars=lambda b: in_bars(b, (9, 24))), -26.0, send=0.3)
    xev = []
    for bar, ns in BOSS_XYLO.items():
        for i, nm in enumerate(ns.split()):
            xev.append(((bar - 1) * 4 + 2.0 + 0.25 * i, 0.25, note(nm), 0.7 + 0.03 * i))
    s.add("xylo", v_xylo, xev, -21.0, pan=0.4, send=0.2, humanize=0.0015)
    s.add("taiko", d_taiko, drum_events(B, lambda b: "X.......x......." if in_bars(b, (17, 24)) else "X..............."),
          -16.0, send=0.08, kind="drum", humanize=0.001, variants=3)
    s.add("kick", d_kick, drum_events(B, lambda b: "X.......X.x....." if b % 2 else "X.......X......."), -17.5,
          send=0.0, kind="drum", humanize=0.0005)

    def snare_pat(b):
        if b == B - 1:
            return "....X...oxoxXXXX"
        return "....X.....g.X..." if b % 2 else "....X.......X..g"
    s.add("snare", d_snare, drum_events(B, snare_pat), -18.0, pan=-0.05, send=0.14, kind="drum", humanize=0.001,
          variants=4)
    s.add("hats", d_hat, drum_events(B, lambda b: "x.o.x.o.x.o.x.o."), -26.0, pan=0.4, send=0.04, kind="drum",
          humanize=0.001, variants=4)
    tom_ev = []
    for b in (7, 15, 23):
        for i, (pitch, v) in enumerate(((190, 0.8), (190, 0.6), (150, 0.85), (150, 0.65),
                                        (118, 0.9), (118, 0.7), (92, 1.0), (92, 0.85))):
            tom_ev.append((b * 4 + 2.0 + i * 0.25, 0.0, pitch, v))
    s.add("toms", None, tom_ev, -18.0, send=0.12, kind="tom", humanize=0.001)
    s.add("crash", lambda r, v: d_crash(r, v), drum_events(B, lambda b: "X..............." if b % 8 == 0 else None),
          -23.0, pan=-0.3, send=0.15, kind="drum")
    return s


# ----------------------------------------------------------------------------
# finishing, QA and command line
# ----------------------------------------------------------------------------

SFX_PLR_TARGET = 9.0   # peak-to-loudness ratio after shaping -> ~-10 LUFS(100 ms) at -1 dBFS
SFX_GR_MAX = 6.0       # never limit a sound by more than this (keeps transients alive)


def finalize_sfx(x, length=None, fade_s=None):
    """DC/rumble cut, trim, 2 ms fade-in, short faded tail, crest-factor shaping."""
    x = fft_filter(np.asarray(x, dtype=float), lambda f: mag_hp(f, 25.0, 2))
    pk = peak(x)
    above = np.nonzero(np.abs(x) > pk * undb(-48.0))[0]
    if len(above):
        x = x[int(above[0]):]
    if length:
        x = pad_to(x, nsamp(length))
        fo = fade_s if fade_s else min(0.015, 0.25 * length)
    else:
        env = -sliding_min(-np.abs(x), nsamp(0.01))
        idx = np.nonzero(env > pk * undb(-54.0))[0]
        x = x[:int(idx[-1]) + 1] if len(idx) else x
        fo = min(0.05, 0.2 * len(x) / SR)
    x = fade(x, 0.002, fo)
    plr = db(peak(x)) - loudness_momentary(x)
    if plr > SFX_PLR_TARGET:
        red = min(plr - SFX_PLR_TARGET, SFX_GR_MAX)
        x = limiter(x, peak(x) * float(undb(-red)), win_ms=2.0 if len(x) < nsamp(0.15) else 3.0)
    return x


def resample_periodic(x, sr_out):
    """Band-limited resampling of a periodic loop (exact, no seam)."""
    L = len(x)
    Lo = int(round(L * sr_out / SR))
    X = np.fft.rfft(x)
    Y = np.zeros(Lo // 2 + 1, dtype=complex)
    k = min(len(X), len(Y))
    Y[:k] = X[:k]
    return np.fft.irfft(Y, Lo) * (Lo / L)


def seam_check(y, ref=None, sr=SR, rot=None):
    """Loop-point checks on a decoded loop y (n, ch):
    seam  = |y[0] - y[-1]| (largest over channels)
    inner = largest sample-to-sample step within 50 ms either side of the seam
    ideal = the same step in the pre-encode (perfectly periodic) loop, i.e. what the
            music itself does at that instant (seam ~= ideal means no added click)
    edge  = codec error (vs. the pre-encode loop) within 5 ms of either end of the
            file, relative to the error on the *same* samples when the loop is
            encoded with its seam moved to the middle of the file (dB; ~0 = the
            file edges are coded as cleanly as the interior)."""
    y2 = y if y.ndim == 2 else y[:, None]
    w = int(0.05 * sr)
    seam = float(np.max(np.abs(y2[0] - y2[-1])))
    inner = max(float(np.max(np.abs(np.diff(y2[:w], axis=0)))), float(np.max(np.abs(np.diff(y2[-w:], axis=0)))))
    ideal = float("nan")
    if ref is not None:
        r2_ = ref if ref.ndim == 2 else ref[:, None]
        ideal = float(np.max(np.abs(r2_[0] - r2_[-1])))
    edge = float("nan")
    if ref is not None and rot is not None and ref.shape == y.shape == rot.shape:
        r2 = ref if ref.ndim == 2 else ref[:, None]
        q2 = rot if rot.ndim == 2 else rot[:, None]
        k = int(0.005 * sr)
        idx = np.r_[0:k, len(y2) - k:len(y2)]
        e_file = float(np.mean((y2[idx] - r2[idx]) ** 2))
        e_mid = float(np.mean((q2[idx] - r2[idx]) ** 2))
        edge = 10 * math.log10((e_file + 1e-20) / (e_mid + 1e-20))
    return seam, ideal, inner, edge


MUSIC_SPECS = {
    "music_menu": dict(build=build_menu, target_lufs=-16.0, wet=0.55, eq=(80.0, -2.0, 3000.0, 2.5),
                       reverb=dict(seconds=3.2, room=0.78, damp=0.45, size=1.0, predelay=0.02)),
    "music_game": dict(build=lambda: build_game(False), target_lufs=-14.5, wet=0.4,
                       reverb=dict(seconds=2.6, room=0.68, damp=0.5, size=0.9, predelay=0.015)),
    "music_game_intense": dict(build=lambda: build_game(True), share="music_game", wet=0.4,
                               reverb=dict(seconds=2.6, room=0.68, damp=0.5, size=0.9, predelay=0.015)),
    "music_boss": dict(build=build_boss, target_lufs=-14.5, wet=0.45, eq=(90.0, -3.0, 4000.0, 1.5), max_gr=5.5,
                       reverb=dict(seconds=2.8, room=0.72, damp=0.45, size=1.0, predelay=0.015)),
}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="comma-separated names to (re)build")
    ap.add_argument("--sfx-only", action="store_true")
    ap.add_argument("--music-only", action="store_true")
    ap.add_argument("--wav", action="store_true", help="write 16-bit WAV instead of OGG Vorbis")
    args = ap.parse_args(argv)
    wav = args.wav or sf is None
    if sf is None and not args.wav:
        print("soundfile not available -> writing WAV")
    only = set(args.only.split(",")) if args.only else None
    ext = ".wav" if wav else ".ogg"
    rows = []
    t_all = time.time()

    if not args.music_only:
        print("== SFX ==")
        for name, (fn, length, fade_s) in SFX_REGISTRY.items():
            if only and name not in only:
                continue
            x = finalize_sfx(fn(rng_for(name)), length, fade_s)
            dec, size = save_audio(SFX_DIR / (name + ext), x, SFX_PEAK_DB, SFX_QUALITY, wav=wav)
            rows.append(("sfx", name + ext, len(dec) / SR, db(peak(dec)), rms_db(dec),
                         loudness_momentary(dec), size))

    seams = []
    if not args.sfx_only:
        print("== music ==")
        refs = {}
        for name, spec in MUSIC_SPECS.items():
            if only and name not in only and not (spec.get("share") in (only or ())):
                continue
            t0 = time.time()
            song = spec["build"]()
            print("  %s: %d bars @ %.0f BPM = %d samples (%.3f s)" % (name, song.bars, song.bpm, song.L, song.L / SR))
            ref = None
            if spec.get("share"):
                if spec["share"] not in refs:
                    base = MUSIC_SPECS[spec["share"]]
                    _, refs[spec["share"]] = render_song(base["build"](), reverb=base["reverb"], wet=base["wet"],
                                                         target_lufs=base["target_lufs"], verbose=False,
                                                         eq=base.get("eq", (90.0, -2.5, 4000.0, 1.5)))
                ref = dict(refs[spec["share"]])
            mix, lv = render_song(song, reverb=spec["reverb"], wet=spec["wet"],
                                  target_lufs=spec.get("target_lufs", -14.0), level_ref=ref,
                                  max_gr=6.0 if ref else spec.get("max_gr", 4.5),
                                  eq=spec.get("eq", (90.0, -2.5, 4000.0, 1.5)))
            refs[name] = lv
            out = mix if not wav else resample_periodic(mix.mean(axis=1), 32000)
            sr_out = SR if not wav else 32000
            dec, size = save_audio(MUSIC_DIR / (name + ext), out, MUSIC_PEAK_DB, MUSIC_QUALITY, wav=wav, sr=sr_out,
                                   tol_db=0.1, max_tries=12)
            print("    limiter GR %.1f dB, done in %.1fs" % (lv.get("limiter_gr_db", 0.0), time.time() - t0))
            rows.append(("music", name + ext, len(dec) / sr_out, db(peak(dec)), rms_db(dec),
                         loudness_integrated(dec) if not wav else loudness_integrated(dec), size))
            ref_out = out * (peak(dec) / max(peak(out), 1e-12))
            rot = None
            if not wav:  # same loop encoded with the seam moved to the middle of the file
                half = len(ref_out) // 2
                rd = decode_bytes(encode_ogg(np.roll(ref_out, half, axis=0), MUSIC_QUALITY, 1))
                rot = np.roll(rd, -half, axis=0)
            seams.append((name, len(dec), song.L if not wav else len(out)) + seam_check(dec, ref_out, sr_out, rot))

    print("\n%-26s %8s %9s %9s %9s %9s" % ("file", "dur s", "peak dBFS", "RMS dBFS", "LUFS*", "size KB"))
    tot = {"sfx": 0, "music": 0}
    for kind, nm, dur, pk, rm, lu, size in rows:
        tot[kind] += size
        print("%-26s %8.3f %9.2f %9.2f %9.2f %9.1f" % (nm, dur, pk, rm, lu, size / 1024.0))
    print("* SFX: max 100 ms K-weighted loudness; music: whole-loop K-weighted loudness")
    print("total SFX %.1f KB, total music %.1f KB (%.2f MB)" % (tot["sfx"] / 1024.0, tot["music"] / 1024.0,
                                                                 tot["music"] / 1048576.0))
    if seams:
        print("\nloop seams (decoded file):")
        print("%-20s %9s %9s %10s %10s %10s %7s %10s" % ("file", "samples", "expected", "seam step", "ideal step",
                                                         "max step", "ratio", "edge err"))
        for nm, n, L, sstep, ideal, inner, edge in seams:
            print("%-20s %9d %9d %10.5f %10.5f %10.5f %7.3f %7.1f dB" % (nm, n, L, sstep, ideal, inner,
                                                                       sstep / max(inner, 1e-9), edge))
        print("seam step = |last - first sample| of the decoded file; ideal = same step in the pre-encode loop;\n"
              "max step = largest sample step within 50 ms of the seam; edge err = codec error at the file edges\n"
              "vs. the same samples encoded mid-file (0 dB = edges coded as cleanly as the interior)")
    ga, gi = MUSIC_DIR / ("music_game" + ext), MUSIC_DIR / ("music_game_intense" + ext)
    if not args.sfx_only and ga.exists() and gi.exists():
        a = decode_wav(ga.read_bytes()) if wav else decode_bytes(ga.read_bytes())
        b = decode_wav(gi.read_bytes()) if wav else decode_bytes(gi.read_bytes())
        if a.shape == b.shape:
            am, bm = (a.mean(axis=1), b.mean(axis=1)) if a.ndim == 2 else (a, b)
            n = len(am)
            xc = np.fft.irfft(np.fft.rfft(am) * np.conj(np.fft.rfft(bm)), n)
            lag = int(np.argmax(xc))
            lag = lag - n if lag > n // 2 else lag
            print("\ngame vs intense: same length %d samples, best alignment lag %d samples, correlation %.2f"
                  % (n, lag, float(np.corrcoef(am, bm)[0, 1])))
    print("\ndone in %.1fs" % (time.time() - t_all))


if __name__ == "__main__":
    main()
