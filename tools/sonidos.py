"""
Sonidos de Lobo, versión 2.

- Fondos largos (150 s) en estéreo que empalman sin corte: hoguera, noche y mercado.
- Sonidos sueltos cortos que la app reproduce al azar encima del fondo (búho, rama, ranas,
  ráfagas, aullidos lejanos, chasquidos de la hoguera, yunque, perro, carreta…), para que el
  ambiente nunca suene igual.
- Efectos con varias versiones (la app escoge una al azar): aullido, manada, campanas, flecha,
  salvado y pájaros.

Todo es síntesis (numpy/scipy) + Vorbis con ffmpeg. Uso: python3 gen2.py [carpeta_salida]
"""
import numpy as np, scipy.signal as sg, scipy.io.wavfile as wf, subprocess, os, sys

SR = 44100
rng = np.random.default_rng(2026)
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)

# ---------------------------------------------------------------- utilidades
def T(sec): return np.arange(int(sec * SR)) / SR
def norm(x, peak=0.9): return x / (np.max(np.abs(x)) + 1e-12) * peak
def _f(kind, f, o=2):
    f = np.clip(np.asarray(f, float) / (SR / 2), 1e-4, 0.999)
    return sg.butter(o, f if f.ndim else float(f), kind, output="sos")
def lp(x, f, o=2): return sg.sosfilt(_f("low", f, o), x, axis=0)
def hp(x, f, o=2): return sg.sosfilt(_f("high", f, o), x, axis=0)
def bp(x, f1, f2, o=2): return sg.sosfilt(_f("band", [f1, f2], o), x, axis=0)
def reson(x, f, q):  # resonador de un polo doble (formante)
    b, a = sg.iirpeak(min(f, SR / 2 - 100) / (SR / 2), q)
    return sg.lfilter(b, a, x)
def white(n): return rng.standard_normal(n)
def pink(n):
    w = np.fft.rfft(white(n)); f = np.arange(len(w)); f[0] = 1
    return norm(np.fft.irfft(w / np.sqrt(f), n))
def brown(n): return norm(hp(np.cumsum(white(n)), 12))
def ctrl(n, rate, lo=0.0, hi=1.0, smooth=True):
    """Control lento aleatorio entre lo y hi (rate = cambios por segundo)."""
    k = max(3, int(n / SR * rate) + 3)
    pts = rng.random(k); xs = np.linspace(0, n, k)
    if smooth:
        from scipy.interpolate import CubicSpline
        y = CubicSpline(xs, pts, bc_type="periodic" if False else "not-a-knot")(np.arange(n))
        y = np.clip(y, 0, 1)
    else:
        y = np.interp(np.arange(n), xs, pts)
    return lo + (hi - lo) * y
def env_adsr(n, a, d, s, r, sustain=0.7):
    a, d, r = int(a * SR), int(d * SR), int(r * SR)
    s = max(0, n - a - d - r)
    e = np.concatenate([np.linspace(0, 1, a), np.linspace(1, sustain, d), np.full(s, sustain), np.linspace(sustain, 0, r)])
    return np.pad(e, (0, max(0, n - len(e))))[:n]
def pan(x, p):  # p: -1 izquierda … 1 derecha (potencia constante)
    a = (p + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)], 1)
def place(dst, src, t0):
    i = int(t0 * SR)
    if i >= len(dst): return
    L = min(len(src), len(dst) - i)
    dst[i:i + L] += src[:L]

def make_ir(rt=1.8, pre=0.012, bright=0.5, stereo=True):
    """Respuesta de impulso: reflexiones tempranas + cola que se oscurece."""
    n = int((rt * 1.3 + pre) * SR); t = np.arange(n) / SR
    chans = []
    for c in range(2 if stereo else 1):
        ir = np.zeros(n)
        for k in range(10):
            d = pre + rng.uniform(0.004, 0.07)
            ir[int(d * SR)] += rng.uniform(0.2, 0.7) * (-1) ** rng.integers(2) * np.exp(-d * 8)
        nz = white(n)
        late_b = hp(nz, 1500) * np.exp(-6.9 * t / (rt * 0.45))
        late_d = lp(nz, 1500) * np.exp(-6.9 * t / rt)
        fade_in = np.clip((t - pre) / 0.03, 0, 1)
        ir += (bright * late_b + late_d) * fade_in * 0.35
        chans.append(ir)
    ir = np.stack(chans, 1) if stereo else chans[0]
    return ir / np.sqrt(np.sum(ir ** 2, axis=0) + 1e-12)

def reverb(x, rt=1.8, mix=0.3, bright=0.5):
    """x mono o estéreo → estéreo con reverberación."""
    ir = make_ir(rt, bright=bright)
    if x.ndim == 1: x = np.stack([x, x], 1)
    wet = np.stack([sg.fftconvolve(x[:, c], ir[:, c])[: len(x)] for c in range(2)], 1)
    dry_rms = np.sqrt(np.mean(x ** 2)) + 1e-12; wet_rms = np.sqrt(np.mean(wet ** 2)) + 1e-12
    wet *= dry_rms / wet_rms
    return (1 - mix) * x + mix * wet

def reverb_tail(x, rt, mix, bright=0.5):
    """Igual que reverb pero deja espacio para que la cola termine."""
    pad = np.zeros((int(rt * 1.2 * SR),) + x.shape[1:]) if x.ndim > 1 else np.zeros(int(rt * 1.2 * SR))
    return reverb(np.concatenate([x, pad]), rt, mix, bright)

def distant(x, d):
    """Aleja un sonido mono: menos agudos y menos volumen (d = 0 cerca … 1 muy lejos)."""
    return lp(x, 9000 * (1 - d) ** 2 + 700, 2) * (1 - 0.75 * d)

def loop_stereo(x, fade=4.0):
    """Hace que el final empalme con el inicio sin que se note."""
    f = int(fade * SR); t = np.linspace(0, np.pi / 2, f)[:, None]
    y = x[:-f].copy()
    y[:f] = x[:f] * np.sin(t) + x[-f:] * np.cos(t)
    return y

def mono_to_st(x): return np.stack([x, x], 1)

def fadeout(x, sec=0.4):
    f = min(int(sec * SR), len(x)); x = x.copy(); x[-f:] *= np.linspace(1, 0, f) ** 2; return x

def save(name, x, q=4, stereo=None, loop=False, rms_db=None):
    if x.ndim == 1 and stereo: x = mono_to_st(x)
    if x.ndim == 2 and stereo is False: x = x.mean(1)
    # solo se quita lo inaudible (por debajo de 25 Hz); los graves se conservan para celulares con subwoofer
    if loop:  # filtrado circular para que el bucle siga empalmando perfecto
        x = hp(np.concatenate([x, x]), 25, 2)[len(x):]
    else:
        x = hp(x, 25, 2)
    if rms_db is None:
        x = norm(x, 0.89)
    else:  # volumen parejo entre fondos; los picos se suavizan sin cortar
        x = x * (10 ** (rms_db / 20) / (np.sqrt(np.mean(x ** 2)) + 1e-12))
        x = np.where(np.abs(x) > 0.6, np.sign(x) * (0.6 + 0.25 * np.tanh((np.abs(x) - 0.6) / 0.25)), x)
    if not loop:  # quita el silencio del final (cola de reverberación inaudible)
        a = np.abs(x) if x.ndim == 1 else np.abs(x).max(1)
        idx = np.nonzero(lp(a, 30) > 0.0025)[0]
        if len(idx): x = x[max(0, idx[0] - int(0.01 * SR)): min(len(x), idx[-1] + int(0.08 * SR))]
        tail = min(int(0.3 * SR), len(x) // 3)
        fade = np.linspace(1, 0, tail) ** 2
        if x.ndim == 1: x[-tail:] *= fade
        else: x[-tail:] *= fade[:, None]
    # pequeño fundido para evitar clics (los bucles no lo necesitan)
    if not loop:
        f = min(200, len(x) // 4)
        ramp = np.linspace(0, 1, f)
        if x.ndim == 1: x[:f] *= ramp; x[-f:] *= ramp[::-1]
        else: x[:f] *= ramp[:, None]; x[-f:] *= ramp[::-1, None]
    path = os.path.join(OUT, name)
    wf.write(path + ".wav", SR, (x * 32767).astype(np.int16))
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", path + ".wav", "-c:a", "libvorbis", "-q:a", str(q)]
    if loop: cmd += ["-metadata", "ANDROID_LOOP=true"]
    subprocess.run(cmd + [path + ".ogg"], check=True)
    os.remove(path + ".wav")
    print(f"{name:18s} {len(x)/SR:6.1f} s  {os.path.getsize(path + '.ogg')/1024:7.0f} KB  {'estéreo' if x.ndim == 2 else 'mono'}")

# ---------------------------------------------------------------- piezas reutilizables
def cricket_voice(n, cf, rate_chirp, pulses, pulse_rate):
    """Un grillo: grupos de pulsos (cri-cri-cri) con pequeñas variaciones."""
    out = np.zeros(n); t0 = rng.uniform(0, 1.5)
    while t0 < n / SR:
        k = int(rng.integers(max(2, pulses - 1), pulses + 2))
        for p in range(k):
            L = int(rng.uniform(0.012, 0.018) * SR); tt = np.arange(L) / SR
            f = cf * (1 + rng.uniform(-0.01, 0.01))
            tone = np.sin(2 * np.pi * f * tt) + 0.15 * np.sin(2 * np.pi * 2 * f * tt)
            place(out, tone * np.sin(np.pi * np.arange(L) / L) ** 2, t0 + p / pulse_rate)
        t0 += (1 / rate_chirp) * rng.uniform(0.85, 1.2)
        if rng.random() < 0.03: t0 += rng.uniform(1, 4)  # a veces se calla un rato
    return out

def wind_layer(n, strength=1.0):
    """Viento entre árboles: bandas de ruido con ráfagas lentas y hojas que se mueven con ellas."""
    gust = ctrl(n, 0.07) ** 1.5 * 0.7 + ctrl(n, 0.25) * 0.3
    w = np.zeros(n)
    for f0, g in [(180, 0.9), (420, 1.0), (900, 0.55), (1800, 0.22)]:
        band = bp(white(n), f0 * 0.6, f0 * 1.5)
        w += g * band * (0.25 + 0.75 * np.clip(gust + 0.15 * ctrl(n, 0.6) - 0.1, 0, 1))
    leaves = bp(white(n), 2500, 7000) * np.clip(gust - 0.35, 0, 1) ** 2 * 0.9
    leaves *= 0.6 + 0.4 * ctrl(n, 6)  # aleteo de las hojas
    rumble = lp(brown(n), 120) * (0.4 + 0.6 * gust)
    return (norm(w) * 0.7 + leaves * 0.35 + rumble * 0.35) * strength, gust

# ---------------------------------------------------------------- FONDOS (150 s, estéreo)
def bed_noche(sec=150):
    n = int(sec * SR)
    out = np.zeros((n, 2))
    for c in range(2):  # viento distinto en cada lado para dar amplitud
        w, _ = wind_layer(n)
        out[:, c] += w
    # grillos: 6 grillos en distintos lugares; cada uno se calla y vuelve a cantar
    for i in range(6):
        cf = rng.uniform(3900, 5600)
        g = cricket_voice(n, cf, rng.uniform(0.9, 1.8), int(rng.integers(3, 6)), rng.uniform(22, 32))
        presence = np.clip(ctrl(n, 1 / 25) * 1.6 - 0.3, 0, 1)  # entra y sale cada ~25 s
        d = rng.uniform(0.2, 0.8)
        out += pan(distant(g * presence, d) * 0.13, rng.uniform(-0.9, 0.9))
    # coro de ranas lejano y muy suave
    frogs = np.zeros(n)
    t0 = 0.0
    while t0 < sec:
        frogs_c = frog_croak(rng.uniform(0.8, 1.2), int(rng.integers(1, 3)))
        place(frogs, frogs_c * rng.uniform(0.3, 1.0), t0)
        t0 += rng.exponential(1.6)
    out += pan(distant(frogs, 0.85) * 0.05, -0.4)
    out = reverb(out, 2.2, 0.25, bright=0.3)
    return loop_stereo(out)

def crackle_track(n, rate_ctrl, bright=1.0):
    """Chasquidos de la leña: proceso de Poisson con densidad que cambia."""
    out = np.zeros(n); t = 0.0
    while t < n / SR:
        r = rate_ctrl[min(int(t * SR), n - 1)]
        t += rng.exponential(1 / max(r, 0.5))
        L = int(rng.uniform(0.0015, 0.01) * SR)
        a = rng.pareto(2.2) * 0.25 + 0.05
        burst = white(L) * np.exp(-np.linspace(0, rng.uniform(5, 12), L)) * min(a, 1.5)
        f = rng.uniform(1200, 6000) * bright
        burst = bp(burst, f * 0.5, min(f * 1.8, 18000))
        place(out, burst, t)
    return out

def bed_hoguera(sec=150):
    n = int(sec * SR)
    flick = ctrl(n, 0.8, 0.5, 1.0)
    out = np.zeros((n, 2))
    roar = hp(lp(brown(n), 900), 35) * (0.55 + 0.45 * ctrl(n, 0.3)) * flick
    out += pan(norm(roar) * 0.55, 0)
    hiss = bp(white(n), 2000, 8000) * 0.06 * ctrl(n, 1.5, 0.4, 1.0)
    out += mono_to_st(hiss)
    rate = ctrl(n, 0.12, 3, 22) * (0.6 + 0.4 * ctrl(n, 1.2))  # rachas de chisporroteo
    for p in (-0.25, 0.0, 0.25):
        out += pan(crackle_track(n, rate / 3) * 0.75, p + rng.uniform(-0.1, 0.1))
    # noche alrededor de la fogata, muy de fondo
    w, _ = wind_layer(n, 0.25)
    out += mono_to_st(w * 0.5)
    g = cricket_voice(n, 4600, 1.3, 4, 26)
    out += pan(distant(g, 0.7) * 0.05, 0.7)
    out = reverb(out, 1.0, 0.12, bright=0.4)
    return loop_stereo(out)

VOWELS = {"a": (750, 1250, 2600), "e": (480, 1900, 2600), "i": (320, 2300, 3000),
          "o": (500, 900, 2500), "u": (350, 750, 2400)}

def babble_voice(sec, f0_base):
    """Una persona hablando a lo lejos: frases de sílabas con vocales y consonantes, entonación que baja."""
    n = int(sec * SR); out = np.zeros(n); t = rng.uniform(0, 4)
    while t < sec:
        phrase = rng.uniform(0.8, 3.5)
        syl_t = t; f0s = f0_base * rng.uniform(1.05, 1.25)
        while syl_t < t + phrase:
            d = rng.uniform(0.11, 0.24); L = int(d * SR); tt = np.arange(L) / SR
            prog = (syl_t - t) / phrase
            f0 = f0s * (1 - 0.25 * prog) * (1 + 0.06 * np.sin(2 * np.pi * rng.uniform(2, 5) * tt))
            ph = 2 * np.pi * np.cumsum(f0) / SR
            src = sum(np.sin(k * ph) / k ** 1.1 for k in range(1, 18))
            v = VOWELS[rng.choice(list(VOWELS))]
            seg = sum(g * reson(src, F * rng.uniform(0.92, 1.08), q) for F, q, g in zip(v, (6, 9, 12), (1, 0.6, 0.25)))
            seg *= np.sin(np.pi * np.arange(L) / L) ** 0.7
            if rng.random() < 0.55:  # consonante
                cl = int(rng.uniform(0.02, 0.06) * SR)
                cons = bp(white(cl), rng.uniform(1500, 3500), 7000) * np.hanning(cl) * 0.35
                seg[:cl] = seg[:cl] * 0.3 + cons
            place(out, seg * rng.uniform(0.6, 1.0), syl_t)
            syl_t += d * rng.uniform(0.95, 1.15)
        t += phrase + rng.exponential(1.8)
    return out

def bed_mercado(sec=150):
    n = int(sec * SR); out = np.zeros((n, 2))
    for i in range(34):
        f0 = rng.uniform(95, 140) if rng.random() < 0.55 else rng.uniform(175, 250)
        v = babble_voice(sec, f0)
        d = rng.uniform(0.35, 0.95)
        out += pan(distant(v, d) * (0.06 if d > 0.6 else 0.09), rng.uniform(-1, 1))
    bustle = lp(pink(n), 900) * 0.08 * ctrl(n, 0.2, 0.6, 1.0)  # gente moviéndose
    out += np.stack([bustle, np.roll(bustle, 1234)], 1)
    # pasos y golpecitos
    taps = np.zeros(n)
    t = 0.0
    while t < sec:
        t += rng.exponential(0.35)
        L = int(0.03 * SR)
        place(taps, lp(white(L), rng.uniform(500, 1500)) * np.exp(-np.linspace(0, 9, L)) * rng.uniform(0.1, 0.5), t)
    out += pan(distant(taps, 0.6) * 0.25, rng.uniform(-0.3, 0.3))
    # pájaros de día, a lo lejos
    birds = np.zeros(n)
    t = 2.0
    while t < sec:
        place(birds, bird_call(rng.integers(4)), t)
        t += rng.exponential(6)
    out += pan(distant(birds, 0.7) * 0.12, rng.uniform(-0.8, 0.8))
    out = reverb(out, 1.1, 0.3, bright=0.4)
    return loop_stereo(out)

# ---------------------------------------------------------------- SONIDOS SUELTOS (mono)
def owl(kind=0):
    """Búho: 'hu… hu-huuu' (dos versiones)."""
    pattern = [(0, 0.45), (0.9, 0.18), (1.25, 0.18), (1.55, 0.7)] if kind == 0 else [(0, 0.3), (0.45, 0.3), (1.2, 0.8)]
    out = np.zeros(int(3.2 * SR)); f0 = rng.uniform(330, 420)
    for t0, d in pattern:
        tt = T(d); f = f0 * (1 + 0.04 * np.exp(-tt * 8)) * np.interp(tt, [0, d], [1.02, 0.95])
        ph = 2 * np.pi * np.cumsum(f) / SR
        x = np.sin(ph) + 0.12 * np.sin(2 * ph) + 0.05 * np.sin(3 * ph)
        x += bp(white(len(tt)), f0 * 0.8, f0 * 1.3) * 0.25  # soplido
        e = np.sin(np.pi * np.clip(tt / d, 0, 1)) ** 1.5
        place(out, x * e, t0)
    return reverb_tail(distant(out, 0.35), 2.4, 0.45, 0.3).mean(1)

def twig(k):
    """Rama que cruje bajo un pie."""
    out = np.zeros(int(0.6 * SR))
    for j in range(int(rng.integers(1, 4))):
        L = int(rng.uniform(0.004, 0.012) * SR)
        crack = white(L) * np.exp(-np.linspace(0, 7, L))
        body = sum(reson(np.pad(crack, (0, 2000)), f, 25) for f in rng.uniform(900, 3200, 3))
        place(out, (np.pad(crack, (0, 2000)) * 0.6 + body * 0.5), j * rng.uniform(0.03, 0.12))
    return reverb_tail(distant(out, 0.25 + 0.15 * k), 1.6, 0.35, 0.4).mean(1)

def frog_croak(scale=1.0, reps=2):
    out = np.zeros(int((0.45 * reps + 0.3) * SR))
    for r in range(reps):
        d = rng.uniform(0.18, 0.3); tt = T(d)
        f0 = rng.uniform(140, 220) * scale
        pulses = (np.sin(2 * np.pi * rng.uniform(22, 35) * tt) > 0).astype(float)
        buzz = sg.sawtooth(2 * np.pi * f0 * tt) * lp(pulses, 300)
        x = reson(buzz, rng.uniform(600, 900), 5) + 0.5 * reson(buzz, 1600, 8)
        place(out, x * np.sin(np.pi * tt / d), r * rng.uniform(0.35, 0.5))
    return out

def frogs(k):
    out = np.zeros(int(2.6 * SR))
    for i in range(int(rng.integers(2, 5))):
        place(out, frog_croak(rng.uniform(0.85, 1.15), int(rng.integers(1, 4))) * rng.uniform(0.4, 1), rng.uniform(0, 1.2))
    return reverb_tail(distant(out, 0.55), 1.8, 0.4, 0.3).mean(1)

def gust(k):
    """Ráfaga de viento que sube y baja."""
    d = rng.uniform(3.5, 5.5); n = int(d * SR); t = np.arange(n) / SR
    e = np.sin(np.pi * t / d) ** 2
    x = np.zeros(n)
    for f0, g in [(300, 1), (700, 0.7), (1500, 0.35)]:
        x += g * bp(white(n), f0 * 0.5, f0 * 1.6)
    leaves = bp(white(n), 2500, 7000) * np.clip(e - 0.3, 0, 1) ** 2 * 0.25 * (0.6 + 0.4 * ctrl(n, 8))
    return reverb(lp(norm(x), 2200) * e + leaves, 1.5, 0.25).mean(1)

def howl(f_peak=600, dur=4.5, jitter=1.0):
    """Aullido de lobo: sube, se sostiene con vibrato y cae; voz algo nasal con aire."""
    tt = T(dur)
    knots_t = [0, 0.35, 0.9, dur * 0.65, dur * 0.88, dur]
    knots_f = np.array([0.55, 0.9, 1.0, 0.97, 0.82, 0.6]) * f_peak
    knots_f *= 1 + rng.uniform(-0.04, 0.04, len(knots_f)) * jitter
    f = np.interp(tt, knots_t, knots_f)
    vib_depth = np.interp(tt, [0, 1.0, dur], [0.0, 0.012, 0.02])
    f *= 1 + vib_depth * np.sin(2 * np.pi * rng.uniform(4.5, 6) * tt)
    f *= 1 + 0.003 * lp(white(len(tt)), 8)  # temblor natural
    ph = 2 * np.pi * np.cumsum(f) / SR
    tone = np.sin(ph) + 0.28 * np.sin(2 * ph + 0.3) + 0.09 * np.sin(3 * ph) + 0.04 * np.sin(4 * ph)
    tone = tone * 0.85 + 0.25 * reson(tone, 1100, 3)
    air = bp(white(len(tt)), 500, 2200) * 0.018
    e = np.interp(tt, [0, 0.25, 0.6, dur * 0.8, dur], [0, 0.8, 1, 0.85, 0]) ** 1.3
    return (tone + air) * e

def howl_far(k):
    x = howl(rng.uniform(520, 650), rng.uniform(3.5, 4.8))
    return reverb_tail(distant(x, 0.8), 3.0, 0.6, 0.25).mean(1)

def fire_pop(k):
    """Chasquido fuerte de la leña con chispitas."""
    out = np.zeros(int(1.0 * SR))
    L = int(rng.uniform(0.006, 0.02) * SR)
    burst = white(L) * np.exp(-np.linspace(0, 6, L))
    body = np.pad(burst, (0, 3000))
    x = body * 0.7 + reson(body, rng.uniform(700, 1600), 8) * 0.8 + reson(body, rng.uniform(2500, 4500), 12) * 0.4
    place(out, x, 0)
    t = 0.02
    for s in range(int(rng.integers(3, 12))):  # chispas
        t += rng.exponential(0.04)
        l = int(rng.uniform(0.001, 0.004) * SR)
        place(out, hp(white(l), 3000) * np.exp(-np.linspace(0, 6, l)) * rng.uniform(0.05, 0.3), t)
    return reverb_tail(out, 0.8, 0.12).mean(1)

def log_shift(k):
    """Un leño que se acomoda: golpe sordo + crujido."""
    out = np.zeros(int(1.6 * SR))
    tt = T(0.35)
    thump = np.sin(2 * np.pi * rng.uniform(70, 110) * tt) * np.exp(-tt * 14) + lp(white(len(tt)), 400) * np.exp(-tt * 25) * 0.6
    place(out, thump, 0.05)
    cr = crackle_track(int(1.2 * SR), np.full(int(1.2 * SR), 45.0), 0.7) * np.linspace(1, 0, int(1.2 * SR)) ** 1.5
    place(out, cr * 0.8, 0.0)
    return reverb_tail(out, 0.9, 0.15).mean(1)

def anvil(k):
    """Herrero a lo lejos: 2–3 golpes en el yunque."""
    out = np.zeros(int(2.2 * SR)); f0 = rng.uniform(850, 1250)
    for h in range(int(rng.integers(2, 4))):
        tt = T(1.2); x = np.zeros(len(tt))
        for ratio, g, dec in [(1, 1, 6), (2.76, 0.6, 9), (5.40, 0.35, 14), (8.93, 0.2, 20)]:
            x += g * np.sin(2 * np.pi * f0 * ratio * tt) * np.exp(-tt * dec)
        x[:60] += white(60) * 0.8
        place(out, fadeout(x, 0.3) * rng.uniform(0.7, 1), h * rng.uniform(0.38, 0.5))
    return reverb_tail(distant(out, 0.6), 1.4, 0.35).mean(1)

def handbell(k):
    out = np.zeros(int(2.4 * SR)); f0 = rng.uniform(1300, 1800)
    for h in range(int(rng.integers(3, 6))):
        tt = T(1.2); x = np.zeros(len(tt))
        for ratio, g, dec in [(1, 1, 4), (2.0, 0.5, 6), (2.98, 0.3, 8), (4.2, 0.15, 11)]:
            x += g * np.sin(2 * np.pi * f0 * ratio * tt * (1 + 0.002 * h)) * np.exp(-tt * dec)
        place(out, fadeout(x, 0.3), h * 0.17)
    return reverb_tail(distant(out, 0.55), 1.2, 0.35).mean(1)

def dog(k):
    """Perro que ladra a lo lejos."""
    out = np.zeros(int(2.2 * SR))
    for b in range(int(rng.integers(2, 4))):
        d = rng.uniform(0.12, 0.2); tt = T(d)
        f = np.interp(tt, [0, d * 0.3, d], [rng.uniform(380, 480), rng.uniform(550, 700), 350])
        ph = 2 * np.pi * np.cumsum(f) / SR
        src = sum(np.sin(j * ph) / j for j in range(1, 14)) + white(len(tt)) * 0.4
        x = reson(src, 900, 4) + 0.6 * reson(src, 1700, 6) + 0.3 * reson(src, 2800, 8)
        e = np.exp(-tt * 10) * np.clip(tt / 0.008, 0, 1)
        place(out, x * e, b * rng.uniform(0.32, 0.5))
    return reverb_tail(distant(out, 0.65), 1.4, 0.4).mean(1)

def cart(k):
    """Carreta de madera que pasa."""
    d = rng.uniform(3.0, 4.2); n = int(d * SR); out = np.zeros(n); t = 0.0
    while t < d:
        t += 1 / rng.uniform(7, 13)
        L = int(0.02 * SR)
        place(out, bp(white(L), rng.uniform(400, 900), 2200) * np.exp(-np.linspace(0, 8, L)) * rng.uniform(0.3, 1), t)
    out += lp(brown(n), 150) * 0.4
    e = np.sin(np.pi * np.arange(n) / n) ** 1.5
    return reverb(distant(out * e, 0.45), 1.0, 0.3).mean(1)

def bird_call(kind):
    if kind == 0:  # silbido que sube
        d = rng.uniform(0.15, 0.3); tt = T(d)
        f = np.interp(tt, [0, d], [rng.uniform(2200, 3000), rng.uniform(3500, 4800)])
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * tt / d)
        return np.concatenate([x, np.zeros(int(0.1 * SR)), x * 0.8])
    if kind == 1:  # trino
        d = rng.uniform(0.6, 1.1); tt = T(d)
        f = rng.uniform(3500, 5000) * (1 + 0.12 * np.sin(2 * np.pi * rng.uniform(18, 30) * tt))
        return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * tt / d) ** 0.5 * 0.7
    if kind == 2:  # dos notas "fi-bi"
        a = rng.uniform(3000, 4000); out = []
        for f0 in (a, a * 0.8):
            tt = T(0.22); f = f0 * (1 - 0.03 * tt / 0.22)
            out.append(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * tt / 0.22))
            out.append(np.zeros(int(0.06 * SR)))
        return np.concatenate(out)
    out = []  # parloteo
    for i in range(int(rng.integers(4, 9))):
        d = rng.uniform(0.03, 0.07); tt = T(d)
        f = np.interp(tt, [0, d], [rng.uniform(2500, 4500), rng.uniform(2500, 5500)])
        out.append(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.hanning(len(tt)))
        out.append(np.zeros(int(rng.uniform(0.02, 0.06) * SR)))
    return np.concatenate(out) * 0.7

# ---------------------------------------------------------------- EFECTOS (varias versiones)
def fx_aullido(k):
    peak = [600, 560, 650][k]
    x = howl(peak, [4.6, 5.2, 4.2][k])
    if k == 1:  # con un segundo aullido más grave que responde
        y = howl(peak * 0.82, 3.8); z = np.zeros(int(1.8 * SR) + len(y)); z[int(1.8 * SR):] = y * 0.55
        L = max(len(x), len(z)); x = np.pad(x, (0, L - len(x))) + np.pad(z, (0, L - len(z)))
    return reverb_tail(distant(x, 0.12), 2.8, 0.42, 0.35).mean(1)

def fx_manada(k):
    out = np.zeros(int(9.0 * SR))
    peaks = rng.permutation([480, 540, 600, 660, 720])
    for i, p in enumerate(peaks):
        place(out, howl(p, rng.uniform(3.6, 5.0)) * rng.uniform(0.55, 1.0), i * rng.uniform(0.45, 0.8))
    for y in range(int(rng.integers(3, 6))):  # gañidos cortos
        d = rng.uniform(0.12, 0.2); tt = T(d)
        f = np.interp(tt, [0, d], [rng.uniform(700, 900), rng.uniform(1000, 1300)])
        ph = 2 * np.pi * np.cumsum(f) / SR
        place(out, (np.sin(ph) + 0.3 * np.sin(2 * ph)) * np.sin(np.pi * tt / d) * 0.4, rng.uniform(0.5, 5))
    return reverb_tail(distant(out, 0.2), 3.0, 0.45, 0.3).mean(1)

def church_bell(f, dur=6.5, strike=1.0):
    tt = T(dur); x = np.zeros(len(tt))
    partials = [(0.5, 0.5, 0.35), (1.0, 1.0, 0.6), (1.183, 0.6, 0.9), (1.506, 0.45, 1.1), (2.0, 0.6, 1.4),
                (2.514, 0.35, 2.0), (2.662, 0.3, 2.2), (3.011, 0.3, 2.6), (4.166, 0.2, 3.4),
                (5.433, 0.14, 4.5), (6.80, 0.09, 6.0), (8.31, 0.06, 8.0)]
    for ratio, g, dec in partials:
        det = 1 + rng.uniform(-0.0012, 0.0012)
        x += g * (np.sin(2 * np.pi * f * ratio * tt) + 0.6 * np.sin(2 * np.pi * f * ratio * det * tt)) * np.exp(-tt * dec)
    hit = white(400) * np.exp(-np.linspace(0, 8, 400)) * 0.5
    x[:400] += bp(hit, 2000, 9000)
    x[:300] *= np.linspace(0, 1, 300)
    return fadeout(x, 1.2) * strike

def fx_campana(k):
    f = [175, 196][k]
    x = church_bell(f, 7.5)
    if k == 1:  # dos campanadas
        y = church_bell(f, 6.0, 0.8); z = np.zeros(int(2.2 * SR) + len(y)); z[int(2.2 * SR):] = y
        L = max(len(x), len(z)); x = np.pad(x, (0, L - len(x))) + np.pad(z, (0, L - len(z))) * 0.9
    return reverb_tail(distant(x, 0.15), 3.2, 0.38).mean(1)[: int(9.5 * SR)]

def fx_campanas(k):
    """Repique de fiesta: varias campanas en escala."""
    scale = [523.3, 440, 392, 329.6, 293.7] if k == 0 else [392, 440, 523.3, 587.3, 659.3]
    out = np.zeros(int(7.5 * SR)); t = 0.0
    for r in range(2):
        for f in scale:
            place(out, church_bell(f, 3.5, rng.uniform(0.6, 0.9)) * 0.6, t)
            t += rng.uniform(0.22, 0.3)
        t += 0.25
    return reverb_tail(out, 2.4, 0.35).mean(1)[: int(8.5 * SR)]

def fx_salvado(k):
    notes = [523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0] if k == 0 else [587.3, 740.0, 880.0, 1174.7, 1480.0, 1760.0]
    out = np.zeros(int(3.5 * SR))
    for i, f in enumerate(notes):
        tt = T(2.4)
        x = (np.sin(2 * np.pi * f * tt) + 0.3 * np.sin(2 * np.pi * 2.01 * f * tt) + 0.1 * np.sin(2 * np.pi * 3.98 * f * tt))
        x *= np.exp(-tt * 2.2) * (1 + 0.15 * np.sin(2 * np.pi * 6 * tt))
        place(out, x * (0.5 + 0.08 * i), i * 0.09)
    pad = sum(np.sin(2 * np.pi * f * T(3.5)) for f in notes[:3]) * env_adsr(int(3.5 * SR), 0.4, 0.4, 0, 2.0, 0.6) * 0.15
    sh = hp(white(len(out)), 6000) * np.exp(-np.linspace(0, 4, len(out))) * 0.05
    return reverb_tail(out + pad + sh, 2.4, 0.42, 0.7).mean(1)

def fx_flecha(k):
    out = np.zeros(int(1.8 * SR))
    w = int(rng.uniform(0.4, 0.55) * SR); tt = np.arange(w) / SR
    e = np.sin(np.pi * tt / tt[-1]) ** 3
    fc = np.interp(tt, [0, tt[-1]], [3800, 1800])  # efecto Doppler: el silbido baja
    whoosh = np.zeros(w)
    for i0 in range(0, w, 512):  # filtro que se mueve por bloques
        seg = white(min(512, w - i0) + 256)
        whoosh[i0:i0 + 512] = bp(seg, fc[i0] * 0.6, fc[i0] * 1.4)[256:256 + min(512, w - i0)]
    whistle = np.sin(2 * np.pi * np.cumsum(fc * 0.9) / SR) * 0.12
    place(out, (whoosh * 0.7 + whistle) * e, 0)
    tt2 = T(0.6)
    thunk = (np.sin(2 * np.pi * 180 * tt2) * np.exp(-tt2 * 22) + reson(white(len(tt2)) * np.exp(-tt2 * 40), 320, 6) * 0.9
             + white(len(tt2)) * np.exp(-tt2 * 90) * 0.4)
    quiver = np.sin(2 * np.pi * 420 * tt2) * np.exp(-tt2 * 7) * (0.5 + 0.5 * np.sin(2 * np.pi * 38 * tt2)) * 0.12
    place(out, thunk + quiver, w / SR - 0.02)
    return reverb_tail(out, 1.0, 0.2).mean(1)

def fx_pajaros(k):
    out = np.zeros(int(6.5 * SR))
    for s in range(int(rng.integers(10, 16))):
        kind = int(rng.integers(4)); c = bird_call(kind) * rng.uniform(0.3, 0.9)
        place(out, distant(c, rng.uniform(0.1, 0.6)), rng.uniform(0.1, 5.5))
    w, _ = wind_layer(len(out), 0.15)
    out = out + w * 0.6
    out *= np.clip(np.arange(len(out)) / (0.6 * SR), 0, 1)
    return reverb_tail(out, 1.4, 0.3, 0.6).mean(1)

# ---------------------------------------------------------------- generar todo
if __name__ == "__main__":
    which = sys.argv[2:] or ["beds", "shots", "fx"]
    if "beds" in which:
        save("amb_noche", bed_noche(), q=4, loop=True, rms_db=-19)
        save("amb_hoguera", bed_hoguera(), q=4, loop=True, rms_db=-21.5)
        save("amb_mercado", bed_mercado(), q=4, loop=True, rms_db=-19)
    if "shots" in which:
        for i in range(2): save(f"s_buho{i}", owl(i), q=3)
        for i in range(3): save(f"s_rama{i}", twig(i), q=3)
        for i in range(2): save(f"s_ranas{i}", frogs(i), q=3)
        for i in range(2): save(f"s_rafaga{i}", gust(i), q=3)
        for i in range(2): save(f"s_aullidolejos{i}", howl_far(i), q=3)
        for i in range(3): save(f"s_chasquido{i}", fire_pop(i), q=3)
        for i in range(2): save(f"s_leno{i}", log_shift(i), q=3)
        for i in range(2): save(f"s_yunque{i}", anvil(i), q=3)
        save("s_campanita0", handbell(0), q=3)
        for i in range(2): save(f"s_perro{i}", dog(i), q=3)
        save("s_carreta0", cart(0), q=3)
        for i in range(3): save(f"s_pajaro{i}", reverb_tail(distant(bird_call(i), 0.45), 1.2, 0.3).mean(1), q=3)
    if "fx" in which:
        for i in range(3): save(f"fx_aullido{i}", fx_aullido(i), q=5)
        for i in range(2): save(f"fx_manada{i}", fx_manada(i), q=5)
        for i in range(2): save(f"fx_campana{i}", fx_campana(i), q=5)
        for i in range(2): save(f"fx_campanas{i}", fx_campanas(i), q=5)
        for i in range(2): save(f"fx_salvado{i}", fx_salvado(i), q=5)
        for i in range(2): save(f"fx_flecha{i}", fx_flecha(i), q=5)
        for i in range(2): save(f"fx_pajaros{i}", fx_pajaros(i), q=5)
