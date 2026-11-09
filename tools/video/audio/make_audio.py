"""
Musique et effets sonores de la vidéo, entièrement synthétisés ici (numpy seul) : aucun échantillon ni morceau tiers,
donc aucun droit à vérifier et rien que Content ID puisse réclamer.

  python -I tools/video/audio/make_audio.py      → tools/video/public/audio/*.wav

Morceau électro de 52 s à 120 BPM (un temps = 0,5 s = 30 images à 60 i/s), en la mineur (Am – F – C – G) :
  mesures 1-2 intro (nappe, montée) · 3-4 charleys + arpège · 5-14 tout (impact à 8 s) · 15-16 pause (nappe seule)
  · 17-24 tout · 25-26 fin (impact, queue de nappe).
Le découpage est repris dans src/timing.ts.
"""
import os
import wave
import numpy as np

SR = 48000
BPM = 120
BEAT = 60 / BPM
BAR = 4 * BEAT
BARS = 26
DUR = BARS * BAR + 2.0          # + queue
N = int(DUR * SR)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'public', 'audio')
rng = np.random.default_rng(7)

def t_of(n):
    return np.arange(n) / SR

def note(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)

def fft_filter(x, lo=None, hi=None, slope=2.0):
    """Filtre passe-bande doux (gain en 1/(1+(f/fc)^slope)), sans déphasage."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    X = np.fft.rfft(x)
    g = np.ones_like(f)
    if hi:
        g /= 1 + (f / hi) ** (2 * slope)
    if lo:
        g /= 1 + (lo / np.maximum(f, 1e-3)) ** (2 * slope)
    return np.fft.irfft(X * np.sqrt(g), n)

def saw(freq, t, detune=0.0):
    ph = (freq * (1 + detune)) * t
    return 2 * (ph - np.floor(ph + 0.5))

def place(buf, x, start):
    i = int(start * SR)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(x))
    buf[i:j] += x[: j - i]

def stereo(m, width=0.0, delay_ms=0.0):
    """Mono → stéréo : léger décalage d'un côté pour l'ouverture."""
    d = int(delay_ms * SR / 1000)
    l = m.copy()
    r = np.roll(m, d) if d else m.copy()
    return np.stack([l * (1 - width / 2), r * (1 + width / 2) if width else r])

# ---------------------------------------------------------------- éléments
def kick():
    t = t_of(int(0.45 * SR))
    f = 45 + 110 * np.exp(-t * 28)
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-t * 7)
    click = fft_filter(rng.standard_normal(len(t)), lo=1500) * np.exp(-t * 300) * 0.25
    return np.tanh(1.6 * (body + click))

def clap():
    t = t_of(int(0.3 * SR))
    env = np.exp(-t * 18)
    for k in (0.0, 0.011, 0.022):     # trois claquements rapprochés
        env += 0.6 * np.exp(-np.maximum(t - k, 0) * 120) * (t >= k)
    return fft_filter(rng.standard_normal(len(t)), lo=900, hi=5000) * env * 0.5

def hat(open_=False):
    t = t_of(int((0.25 if open_ else 0.06) * SR))
    return fft_filter(rng.standard_normal(len(t)), lo=7000) * np.exp(-t * (14 if open_ else 70)) * 0.22

def pluck(freq, length=0.32):
    t = t_of(int(length * SR))
    x = 0.6 * saw(freq, t) + 0.4 * np.sign(np.sin(2 * np.pi * freq * t))
    return fft_filter(x * np.exp(-t * 11), hi=3200) * 0.16

def impact():
    t = t_of(int(2.2 * SR))
    boom = np.sin(2 * np.pi * (38 + 60 * np.exp(-t * 6)) * t) * np.exp(-t * 2.2)
    noise = fft_filter(rng.standard_normal(len(t)), hi=2500) * np.exp(-t * 5) * 0.35
    return np.tanh(1.4 * (boom + noise)) * 0.9

def riser(length):
    """Bruit dont la bande monte (bloc par bloc), volume croissant."""
    n = int(length * SR)
    out = np.zeros(n)
    block = 4096
    noise = rng.standard_normal(n + block)
    for i in range(0, n, block // 2):
        p = i / n
        seg = fft_filter(noise[i:i + block], lo=300 + 6000 * p ** 2, hi=1200 + 12000 * p ** 2) * np.hanning(block)
        out[i:i + block] += seg[: max(0, min(block, n - i))]
    t = t_of(n)
    sweep = np.sin(2 * np.pi * np.cumsum(200 + 1400 * (t / length) ** 2) / SR) * 0.15
    return (out * 0.5 + sweep) * (t / length) ** 1.6

def whoosh(length=0.55, up=True):
    n = int(length * SR)
    block = 2048
    out = np.zeros(n)
    noise = rng.standard_normal(n + block)
    for i in range(0, n, block // 2):
        p = i / n if up else 1 - i / n
        c = 500 + 5000 * p
        seg = fft_filter(noise[i:i + block], lo=c * 0.5, hi=c * 1.6) * np.hanning(block)
        out[i:i + block] += seg[: max(0, min(block, n - i))]
    t = t_of(n)
    env = np.sin(np.pi * t / length) ** 1.5
    return out * env * 0.6

def pop(f0=900, f1=520, length=0.09):
    t = t_of(int(length * SR))
    f = f1 + (f0 - f1) * np.exp(-t * 60)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 40) * 0.6

def tick():
    t = t_of(int(0.03 * SR))
    return fft_filter(rng.standard_normal(len(t)), lo=2500, hi=9000) * np.exp(-t * 250) * 0.5

def shimmer():
    """Petit carillon (apparition du logo)."""
    t = t_of(int(1.6 * SR))
    x = sum(np.sin(2 * np.pi * note(m) * t) * np.exp(-t * (2.5 + k)) for k, m in enumerate((81, 88, 93, 96)))
    return x * 0.18

# ---------------------------------------------------------------- morceau
CHORDS = [(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)]   # Am F C G
ROOTS = [33, 29, 36, 31]                                            # basses (la, fa, do, sol)

def section(bar):
    if bar < 2: return 'intro'
    if bar < 4: return 'build'
    if bar < 14: return 'full'
    if bar < 16: return 'break'
    if bar < 24: return 'full'
    return 'end'

drums = np.zeros(N); bass = np.zeros(N); pad = np.zeros(N); arp = np.zeros(N); fx = np.zeros(N)
duck = np.ones(N)
K = kick()
duck_shape = 1 - 0.65 * np.exp(-t_of(int(0.4 * SR)) * 9)

for bar in range(BARS):
    sec = section(bar)
    t0 = bar * BAR
    chord = CHORDS[bar % 4]
    root = ROOTS[bar % 4]
    # Nappe : accord en scies désaccordées, attaque lente en intro.
    if sec != 'end' or bar == 24:
        # Mesure + 0,4 s de relâchement, qui chevauche la suivante (pas de creux entre deux accords).
        tt = t_of(int((BAR + 0.4) * SR))
        p = sum(saw(note(m), tt, d) for m in chord for d in (-0.004, 0.0, 0.005)) / 9
        p = fft_filter(p, hi=1800 if sec != 'intro' else 900)
        att = np.minimum(1, tt / 0.12)
        rel = np.clip((BAR + 0.4 - tt) / 0.4, 0, 1)
        # Intro : une seule montée lente sur les deux premières mesures.
        swell = np.clip((t0 + tt) / (2 * BAR), 0.15, 1) if sec == 'intro' else 1
        place(pad, p * att * rel * swell * 0.5, t0)
    for b in range(4):
        tb = t0 + b * BEAT
        if sec in ('full',):
            place(drums, K * 0.95, tb)
            i = int(tb * SR); duck[i:i + len(duck_shape)] = np.minimum(duck[i:i + len(duck_shape)], duck_shape[: max(0, min(len(duck_shape), N - i))])
            if b in (1, 3):
                place(drums, clap(), tb)
            # Basse en croches (contretemps plus fort).
            for e in range(2):
                tt = t_of(int(BEAT / 2 * SR))
                f = note(root + (12 if e else 0))
                x = fft_filter(saw(f, tt), hi=380 if e else 260) * np.exp(-tt * 6) * (0.55 if e else 0.4)
                place(bass, x, tb + e * BEAT / 2)
        if sec in ('build', 'full'):
            place(drums, hat(), tb + BEAT / 2)
            if sec == 'full':
                place(drums, hat() * 0.6, tb + BEAT / 4)
                place(drums, hat() * 0.6, tb + 3 * BEAT / 4)
            if b == 3 and sec == 'full':
                place(drums, hat(open_=True), tb + BEAT / 2)
        # Arpège en doubles croches sur les notes de l'accord (2 octaves au-dessus).
        if sec in ('build', 'full', 'break'):
            seq = [chord[0] + 12, chord[1] + 12, chord[2] + 12, chord[1] + 24]
            for s in range(4):
                place(arp, pluck(note(seq[(b * 4 + s) % 4])) * (0.6 if sec == 'break' else 1.0), tb + s * BEAT / 4)

# Effets dans le morceau : montées avant les entrées, impacts sur les temps forts.
place(fx, riser(2 * BAR), 2 * BAR)        # vers le premier impact (mesure 5)
place(fx, impact(), 4 * BAR)
place(fx, riser(BAR), 15 * BAR)           # fin de la pause
place(fx, impact() * 0.8, 16 * BAR)
place(fx, impact(), 24 * BAR)

pad *= duck
mixL = drums + bass + pad + arp + fx
# Écho ping-pong de l'arpège (3/16), un peu plus à droite.
d = int(0.75 * BEAT * SR)
echo = np.zeros(N); echo[d:] = arp[:-d] * 0.35
echo2 = np.zeros(N); echo2[2 * d:] = arp[:-2 * d] * 0.18
L = drums + bass + pad * 1.0 + arp + echo2 + fx
R = drums + bass + np.roll(pad, int(0.012 * SR)) + arp * 0.85 + echo + fx
# Fondu final de la queue.
fade = np.ones(N); tail = int(2.5 * SR); fade[-tail:] = np.linspace(1, 0, tail) ** 2
master = np.stack([L, R]) * fade
# Niveau : crête à -1 dBFS, puis légère saturation douce seulement sur les crêtes (garde la dynamique).
master /= np.max(np.abs(master)) / 1.2
master = np.where(np.abs(master) > 0.7, np.sign(master) * (0.7 + 0.3 * np.tanh((np.abs(master) - 0.7) / 0.3)), master)
master /= np.max(np.abs(master)) / 0.89

def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    if x.ndim == 1:
        x = np.stack([x, x])
    x = x / max(1e-9, np.max(np.abs(x))) * 0.89 if name != 'music' else x
    pcm = (np.clip(x, -1, 1).T * 32767).astype('<i2')
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    print(name, f'{x.shape[1] / SR:.2f} s')

save('music', master)
save('whoosh', whoosh())
save('whoosh_down', whoosh(up=False))
save('pop', pop())
save('tick', tick())
save('shimmer', shimmer())
save('impact', impact())
