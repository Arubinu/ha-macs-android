import React from 'react';
import {
  AbsoluteFill, Img, interpolate, OffthreadVideo, random, spring, staticFile, useCurrentFrame, useVideoConfig, Easing,
} from 'remotion';
import { ACCENT, ACCENT_RGB, BODY, TITLE } from './theme';
import { beatPulse } from './timing';

const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

/** Graisse des titres (phrases) : réglable pour toute la vidéo (prop titleWeight). */
export const TitleWeight = React.createContext(800);

/** Fond : halo d'accent qui respire sur les temps, grille en perspective qui défile, particules. */
export const Background: React.FC<{ intensity?: number }> = ({ intensity = 1 }) => {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const pulse = beatPulse(frame);
  const dots = React.useMemo(() => Array.from({ length: 46 }, (_, i) => ({
    x: random(`x${i}`) * width, y: random(`y${i}`) * height, r: 1 + random(`r${i}`) * 2.2,
    v: 0.15 + random(`v${i}`) * 0.5, a: 0.15 + random(`a${i}`) * 0.45,
  })), [width, height]);
  return (
    <AbsoluteFill style={{ background: '#000', overflow: 'hidden' }}>
      <AbsoluteFill style={{
        background: `radial-gradient(ellipse 60% 50% at 50% 55%, rgba(${ACCENT_RGB},${(0.10 + 0.10 * pulse) * intensity}) 0%, rgba(0,0,0,0) 70%)`,
      }} />
      {/* Grille au sol en perspective */}
      <AbsoluteFill style={{ perspective: 900, perspectiveOrigin: '50% 30%' }}>
        <div style={{
          position: 'absolute', left: '-50%', width: '200%', top: '58%', height: '120%',
          transform: 'rotateX(72deg)', transformOrigin: 'top',
          backgroundImage: `linear-gradient(rgba(${ACCENT_RGB},0.16) 1px, transparent 1px), linear-gradient(90deg, rgba(${ACCENT_RGB},0.16) 1px, transparent 1px)`,
          backgroundSize: '80px 80px', backgroundPosition: `0 ${(frame * 1.6) % 80}px`,
          maskImage: 'linear-gradient(to bottom, rgba(0,0,0,0.9), transparent 70%)',
          WebkitMaskImage: 'linear-gradient(to bottom, rgba(0,0,0,0.9), transparent 70%)',
          opacity: 0.55 * intensity,
        }} />
      </AbsoluteFill>
      {dots.map((d, i) => (
        <div key={i} style={{
          position: 'absolute', left: d.x, top: (d.y - frame * d.v + height * 2) % height,
          width: d.r * 2, height: d.r * 2, borderRadius: '50%', background: ACCENT,
          opacity: d.a * intensity * (0.7 + 0.6 * pulse), boxShadow: `0 0 ${6 + 8 * pulse}px ${ACCENT}`,
        }} />
      ))}
    </AbsoluteFill>
  );
};

type Key = { f: number; x: number; y: number; s: number };
const at = (keys: Key[], frame: number, k: 'x' | 'y' | 's') => interpolate(
  frame, keys.map((p) => p.f), keys.map((p) => p[k]), { ...clamp, easing: Easing.inOut(Easing.cubic) },
);

/**
 * Tablette (cadre dessiné) avec une capture de l'app ; [focus] : déplacements de « caméra » dans la capture
 * (centre x/y en fraction, zoom s) ; [tilt] : rotation 3D en degrés, animée par l'entrée.
 */
export const Tablet: React.FC<{
  src: string; width: number; focus?: Key[]; tiltY?: number; tiltX?: number; delay?: number; float?: boolean;
  /** Séquence filmée à la place de la capture : début (s) et vitesse. */
  video?: { src: string; from: number; rate?: number };
}> = ({ src, width, focus = [{ f: 0, x: 0.5, y: 0.5, s: 1 }], tiltY = -14, tiltX = 6, delay = 0, float = true, video }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame: frame - delay, fps, config: { damping: 16, stiffness: 90, mass: 0.9 } });
  const pulse = beatPulse(frame);
  // Écran exactement en 16:10 comme les captures : rien n'est rogné.
  const pad = width * 0.014;
  const height = (width - 2 * pad) * 10 / 16 + 2 * pad;
  // Mouvement de caméra sur la tablette entière (léger zoom), jamais dans la capture.
  const s = 1 + (at(focus, frame, 's') - 1) * 0.12;
  const drift = float ? Math.sin(frame / 50) * 1.5 : 0;
  return (
    <div style={{ perspective: 2400, width, height }}>
      <div style={{
        width, height, borderRadius: width * 0.03, padding: pad, boxSizing: 'border-box',
        background: 'linear-gradient(145deg, #2a2a30, #0b0b0d 45%, #18181c)',
        border: '1px solid rgba(255,255,255,0.14)',
        boxShadow: `0 ${width * 0.04}px ${width * 0.09}px rgba(0,0,0,0.8), 0 0 ${width * (0.04 + 0.03 * pulse)}px rgba(${ACCENT_RGB},${0.22 + 0.2 * pulse})`,
        transform: `translateY(${(1 - enter) * height * 0.35}px) rotateY(${tiltY * (1 - enter * 0.6) + drift}deg) rotateX(${tiltX * (1 - enter * 0.5)}deg) scale(${(0.85 + 0.15 * enter) * s})`,
        opacity: Math.min(1, enter * 1.4),
      }}>
        <div style={{ width: '100%', height: '100%', borderRadius: width * 0.016, overflow: 'hidden', position: 'relative', background: '#000' }}>
          {video ? (
            <OffthreadVideo src={staticFile(video.src)} muted trimBefore={Math.round(video.from * fps)} playbackRate={video.rate ?? 1}
              style={{ position: 'absolute', width: '100%', height: '100%', objectFit: 'contain' }} />
          ) : (
            <Img src={staticFile(src)} style={{ position: 'absolute', width: '100%', height: '100%', objectFit: 'contain' }} />
          )}
          {/* Reflet de vitre */}
          <div style={{
            position: 'absolute', inset: 0,
            background: `linear-gradient(115deg, rgba(255,255,255,0) 30%, rgba(255,255,255,${0.07 + 0.04 * Math.sin(frame / 40)}) 45%, rgba(255,255,255,0) 60%)`,
          }} />
        </div>
      </div>
    </div>
  );
};

/** Texte qui monte mot à mot (masque), `*mot*` en couleur d'accent ; sortie vers le haut à [out] images. */
export const Words: React.FC<{
  text: string; size: number; delay?: number; out?: number; font?: 'title' | 'body'; weight?: number;
  align?: 'center' | 'left'; maxWidth?: number;
}> = ({ text, size, delay = 0, out, font = 'body', weight, align = 'center', maxWidth }) => {
  const titleWeight = React.useContext(TitleWeight);
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  // Découpage par espaces : la ponctuation collée (« *better*. ») reste avec son mot.
  // La mise en valeur peut couvrir plusieurs mots : l'état « entre astérisques » suit tout le texte.
  let on = false;
  const words: { parts: { s: string; hi: boolean }[] }[] = text.split(' ').filter(Boolean).map((tok) => {
    const parts: { s: string; hi: boolean }[] = [];
    let cur = '';
    for (const ch of tok) {
      if (ch === '*') { if (cur) parts.push({ s: cur, hi: on }); cur = ''; on = !on; } else cur += ch;
    }
    if (cur) parts.push({ s: cur, hi: on });
    return { parts };
  });
  const exit = out === undefined ? 0 : interpolate(frame, [out, out + 14], [0, 1], { ...clamp, easing: Easing.in(Easing.cubic) });
  return (
    <div style={{
      display: 'flex', flexWrap: 'wrap', justifyContent: align === 'center' ? 'center' : 'flex-start',
      gap: `0 ${size * 0.28}px`, maxWidth, fontFamily: font === 'title' ? TITLE : BODY, fontWeight: weight ?? titleWeight,
      fontSize: size, lineHeight: 1.15, letterSpacing: font === 'title' ? '0.02em' : '-0.02em',
    }}>
      {words.map((w, i) => {
        const p = spring({ frame: frame - delay - i * 3, fps, config: { damping: 15, stiffness: 140 } });
        // Masque élargi (marges compensées) : le halo des mots en accent n'est pas coupé en rectangle.
        return (
          <span key={i} style={{
            overflow: 'hidden', display: 'inline-block',
            padding: `${size * 0.35}px ${size * 0.4}px ${size * 0.47}px`, margin: `-${size * 0.35}px -${size * 0.4}px -${size * 0.35}px`,
          }}>
            <span style={{ display: 'inline-block', transform: `translateY(${(1 - p) * 175 - exit * 175}%)`, opacity: 1 - exit }}>
              {w.parts.map((pt, j) => (
                <span key={j} style={{
                  color: pt.hi ? ACCENT : '#fff', textShadow: pt.hi ? `0 0 ${size * 0.35}px rgba(${ACCENT_RGB},0.55)` : 'none',
                }}>{pt.s}</span>
              ))}
            </span>
          </span>
        );
      })}
    </div>
  );
};

/** Ligne de liste « ✓ … » qui glisse depuis la gauche, coche dessinée en accent. */
export const CheckItem: React.FC<{ text: string; delay: number; size: number }> = ({ text, delay, size }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - delay, fps, config: { damping: 14, stiffness: 160 } });
  const draw = interpolate(frame - delay, [4, 16], [0, 1], clamp);
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: size * 0.6, opacity: p,
      transform: `translateX(${(1 - p) * -80}px)`, fontFamily: BODY, fontWeight: 600, fontSize: size, color: '#fff',
    }}>
      <svg width={size * 1.4} height={size * 1.4} viewBox="0 0 28 28">
        <circle cx="14" cy="14" r="12.5" fill={`rgba(${ACCENT_RGB},0.14)`} stroke={ACCENT} strokeWidth="2" />
        <path d="M8 14.5l4 4 8-9" fill="none" stroke={ACCENT} strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round"
          strokeDasharray="20" strokeDashoffset={20 * (1 - draw)} />
      </svg>
      {text}
    </div>
  );
};

/** Logo « HA-MACS » : lettres qui jaillissent une à une, halo qui suit la musique. */
export const Logo: React.FC<{ size: number; delay?: number }> = ({ size, delay = 0 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pulse = beatPulse(frame);
  return (
    <div style={{ display: 'flex', fontFamily: TITLE, fontSize: size, color: '#fff', letterSpacing: '0.04em' }}>
      {'HA-MACS'.split('').map((c, i) => {
        const p = spring({ frame: frame - delay - i * 2, fps, config: { damping: 11, stiffness: 180 } });
        return (
          <span key={i} style={{
            display: 'inline-block', transform: `scale(${p}) translateY(${(1 - p) * 40}px)`, opacity: Math.min(1, p * 1.5),
            color: i >= 3 ? ACCENT : '#fff',
            textShadow: `0 0 ${size * (0.18 + 0.2 * pulse)}px rgba(${ACCENT_RGB},${i >= 3 ? 0.85 : 0.35})`,
          }}>{c}</span>
        );
      })}
    </div>
  );
};

/** Calque d'entrée / sortie d'une scène : flou, zoom et fondu. */
export const SceneIO: React.FC<{ duration: number; children: React.ReactNode; inFrames?: number; outFrames?: number }> = ({
  duration, children, inFrames = 12, outFrames = 10,
}) => {
  const frame = useCurrentFrame();
  const i = interpolate(frame, [0, inFrames], [0, 1], { ...clamp, easing: Easing.out(Easing.cubic) });
  const o = interpolate(frame, [duration - outFrames, duration], [0, 1], { ...clamp, easing: Easing.in(Easing.cubic) });
  return (
    <AbsoluteFill style={{
      opacity: i * (1 - o), filter: `blur(${(1 - i) * 14 + o * 10}px)`, transform: `scale(${1.06 - 0.06 * i - 0.04 * o})`,
    }}>
      {children}
    </AbsoluteFill>
  );
};

/** Zone de l'écran à agrandir dans une loupe : centre (x, y) et largeur (w), en fractions de l'écran. */
export type Region = { x: number; y: number; w: number };

/**
 * Loupe : vignette 16:9 agrandie d'un détail de l'écran (même capture ou même séquence que la tablette), sous la
 * tablette en vertical. L'écran de la tablette, lui, reste entier.
 */
export const Callout: React.FC<{
  src: string; video?: { src: string; from: number; rate?: number }; region: Region; width: number; delay?: number;
}> = ({ src, video, region, width, delay = 18 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - delay, fps, config: { damping: 14, stiffness: 120 } });
  const pulse = beatPulse(frame);
  const height = width * 9 / 16;
  // Média dimensionné pour que la région remplisse la vignette (écran 16:10).
  const mw = width / region.w;
  const mh = mw * 10 / 16;
  const left = width / 2 - region.x * mw;
  const top = height / 2 - region.y * mh;
  const media = { position: 'absolute' as const, left, top, width: mw, height: mh };
  return (
    <div style={{
      width, height, borderRadius: width * 0.05, overflow: 'hidden', position: 'relative', background: '#000',
      border: `2px solid rgba(${ACCENT_RGB},${0.55 + 0.35 * pulse})`,
      boxShadow: `0 0 ${30 + 30 * pulse}px rgba(${ACCENT_RGB},0.35), 0 20px 60px rgba(0,0,0,0.8)`,
      transform: `translateY(${(1 - p) * 80}px) scale(${0.8 + 0.2 * p})`, opacity: Math.min(1, p * 1.4),
    }}>
      {video ? (
        <OffthreadVideo src={staticFile(video.src)} muted trimBefore={Math.round(video.from * fps)} playbackRate={video.rate ?? 1} style={media} />
      ) : (
        <Img src={staticFile(src)} style={media} />
      )}
    </div>
  );
};

/** Éclair blanc bref (coupe sur un temps fort). */
export const Flash: React.FC<{ at: number; strength?: number }> = ({ at: start, strength = 0.85 }) => {
  const frame = useCurrentFrame();
  const v = interpolate(frame, [start, start + 2, start + 18], [0, strength, 0], clamp);
  return <AbsoluteFill style={{ background: `rgba(${ACCENT_RGB},1)`, mixBlendMode: 'screen', opacity: v * 0.55, pointerEvents: 'none' }} />;
};
