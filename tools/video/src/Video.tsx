import React from 'react';
import {
  AbsoluteFill, Audio, Img, interpolate, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig, Easing,
} from 'remotion';
import { Background, Callout, CheckItem, Flash, Logo, Region, SceneIO, Tablet, TitleWeight, Words } from './components';
import { Lang, LANGS, T } from './i18n';
import { ACCENT, ACCENT_RGB, BODY, TITLE } from './theme';
import { BAR, BEAT, CLIPS, DURATION, S } from './timing';

export type VideoProps = { lang: Lang; titleWeight?: number };

const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

/** Effet sonore à une image donnée. */
const Sfx: React.FC<{ at: number; name: string; volume?: number }> = ({ at, name, volume = 0.55 }) => (
  <Sequence from={Math.max(0, at)} durationInFrames={150} layout="none">
    <Audio src={staticFile(`audio/${name}.wav`)} volume={volume} />
  </Sequence>
);

/** Disposition commune : titre en haut, tablette au centre (16:9 ou vertical). */
const useLayout = () => {
  const { width, height } = useVideoConfig();
  const vertical = height > width;
  return {
    vertical, width, height,
    tabletW: vertical ? width * 0.92 : width * 0.58,
    titleSize: vertical ? 78 : 68,
    titleTop: vertical ? height * 0.08 : height * 0.075,
    tabletTop: vertical ? height * 0.25 : height * 0.25,
    // Vertical : loupe sous la tablette (détail agrandi), pour occuper la hauteur.
    calloutTop: height * 0.6,
    calloutW: width * 0.82,
  };
};

const FeatureScene: React.FC<{
  title: string; src: string; duration: number; focus?: { f: number; x: number; y: number; s: number }[]; tiltY?: number;
  extra?: React.ReactNode; video?: { src: string; from: number; rate?: number }; region?: Region;
}> = ({ title, src, duration, focus, tiltY, extra, video, region }) => {
  const L = useLayout();
  return (
    <SceneIO duration={duration}>
      <AbsoluteFill style={{ alignItems: 'center' }}>
        <div style={{ position: 'absolute', top: L.titleTop, width: '100%', display: 'flex', justifyContent: 'center', padding: '0 60px', boxSizing: 'border-box' }}>
          <Words text={title} size={L.titleSize} delay={4} maxWidth={L.width * 0.9} />
        </div>
        <div style={{ position: 'absolute', top: L.tabletTop }}>
          <Tablet src={src} width={L.tabletW} focus={focus} tiltY={tiltY} video={video} />
        </div>
        {L.vertical && region && (
          <div style={{ position: 'absolute', top: L.calloutTop }}>
            <Callout src={src} video={video} region={region} width={L.calloutW} />
          </div>
        )}
        {extra}
      </AbsoluteFill>
    </SceneIO>
  );
};

/** Mesures 3-4 : coupes rapides dans l'interface, de plus en plus serrées jusqu'à l'impact. */
const BuildCuts: React.FC<{ lang: Lang }> = ({ lang }) => {
  const frame = useCurrentFrame();
  const cuts = [
    { src: 'dashboard', x: 0.82, y: 0.2, s: 2.2, d: BEAT * 2 },
    { src: 'camera-wall', x: 0.3, y: 0.3, s: 1.8, d: BEAT * 2 },
    { src: 'members', x: 0.55, y: 0.12, s: 2.4, d: BEAT },
    { src: 'cameras-energy', x: 0.45, y: 0.35, s: 2.0, d: BEAT },
    { src: 'detection-history', x: 0.25, y: 0.3, s: 2.2, d: BEAT / 2 },
    { src: 'dashboard', x: 0.2, y: 0.75, s: 2.6, d: BEAT / 2 },
    { src: 'home', x: 0.5, y: 0.3, s: 2.0, d: BEAT / 2 },
    { src: 'camera-wall', x: 0.75, y: 0.7, s: 2.2, d: BEAT / 2 },
    { src: 'members', x: 0.3, y: 0.6, s: 2.4, d: BEAT / 2 },
    { src: 'dashboard', x: 0.5, y: 0.5, s: 2.8, d: BEAT / 2 },
  ];
  let start = 0;
  const items = cuts.map((c) => { const it = { ...c, start }; start += c.d; return it; });
  const cur = [...items].reverse().find((c) => frame >= c.start) ?? items[0];
  const local = frame - cur.start;
  const zoom = cur.s * (1 + local / cur.d * 0.08);
  const shake = Math.sin(frame * 1.7) * 4;
  return (
    <AbsoluteFill style={{ overflow: 'hidden' }}>
      <Img src={staticFile(`shots/${lang}/${cur.src}.jpg`)} style={{
        position: 'absolute', width: '100%', height: '100%', objectFit: 'cover',
        transform: `translate(${shake}px, ${-shake}px) scale(${zoom})`, transformOrigin: `${cur.x * 100}% ${cur.y * 100}%`,
        filter: `brightness(${0.55 + 0.45 * interpolate(local, [0, 4], [1, 0], clamp)}) saturate(0.6)`,
      }} />
      {/* Teinte d'accent et vignette : on devine l'interface sans la lire encore. */}
      <AbsoluteFill style={{ background: `radial-gradient(ellipse at center, rgba(0,0,0,0) 30%, rgba(0,0,0,0.85) 100%), rgba(${ACCENT_RGB},0.10)` }} />
      {items.map((c, i) => i > 0 && <Sfx key={i} at={S.build + c.start} name="tick" volume={0.35} />)}
    </AbsoluteFill>
  );
};

/** Deux doigts qui s'écartent (geste de zoom). */
const PinchHint: React.FC<{ x: number; y: number }> = ({ x, y }) => {
  const frame = useCurrentFrame();
  const p = interpolate(frame, [40, 100], [0, 1], { ...clamp, easing: Easing.inOut(Easing.cubic) });
  const a = interpolate(frame, [30, 40, 110, 125], [0, 1, 1, 0], clamp);
  const dot = (sx: number) => (
    <div style={{
      position: 'absolute', left: x + sx * (30 + 110 * p) - 34, top: y - sx * (20 + 70 * p) - 34, width: 68, height: 68,
      borderRadius: '50%', border: `3px solid ${ACCENT}`, background: `rgba(${ACCENT_RGB},0.25)`, opacity: a,
      boxShadow: `0 0 24px rgba(${ACCENT_RGB},0.7)`,
    }} />
  );
  return <>{dot(-1)}{dot(1)}</>;
};

/** Les 8 langues qui défilent une à une, puis « 8 langues ». */
const Languages: React.FC<{ lang: Lang; vertical: boolean }> = ({ lang, vertical }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const step = 9;
  const shown = Math.min(LANGS.length, Math.floor((frame - 30) / step) + 1);
  const big = spring({ frame: frame - 30 - LANGS.length * step, fps, config: { damping: 12 } });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: vertical ? 'center' : 'flex-start', gap: 6 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 14px', maxWidth: vertical ? 900 : 560, justifyContent: vertical ? 'center' : 'flex-start' }}>
        {LANGS.slice(0, Math.max(0, shown)).map((n, i) => (
          <span key={n} style={{
            fontFamily: BODY, fontWeight: 600, fontSize: vertical ? 40 : 34, color: i === shown - 1 ? ACCENT : 'rgba(255,255,255,0.8)',
            padding: '6px 16px', borderRadius: 999, border: `1px solid rgba(${ACCENT_RGB},0.4)`, background: 'rgba(255,255,255,0.04)',
          }}>{n}</span>
        ))}
      </div>
      <div style={{
        fontFamily: TITLE, fontSize: vertical ? 96 : 84, color: ACCENT, marginTop: 18,
        transform: `scale(${big})`, transformOrigin: vertical ? 'center' : 'left', textShadow: `0 0 30px rgba(${ACCENT_RGB},0.6)`,
      }}>{T[lang].languages}</div>
      {LANGS.map((_, i) => <Sfx key={i} at={S.features2 + 3 * 2 * BAR + 30 + i * step} name="tick" volume={0.3} />)}
    </div>
  );
};

export const HaMacsVideo: React.FC<VideoProps> = ({ lang, titleWeight = 800 }) => {
  const frame = useCurrentFrame();
  const L = useLayout();
  const t = T[lang];
  const shot = (n: string) => `shots/${lang}/${n}.jpg`;
  const clip = (n: string) => ({ src: `rec/${lang}/${n}.mp4`, ...CLIPS[n] });
  const sceneLen = 2 * BAR;
  const bgIntensity = interpolate(frame, [0, BAR, S.drop], [0.2, 0.5, 1], clamp);
  const endFade = interpolate(frame, [DURATION - 50, DURATION], [0, 1], clamp);
  const f1 = S.features1;
  const f2 = S.features2;

  return (
    <TitleWeight.Provider value={titleWeight}>
    <AbsoluteFill style={{ background: '#000' }}>
      <Audio src={staticFile('audio/music.wav')} />
      <Background intensity={bgIntensity} />

      {/* 1-2 : intro */}
      <Sequence from={S.intro} durationInFrames={S.build}>
        <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 10 }}>
          <Words text={t.intro1} size={L.vertical ? 92 : 104} delay={24} out={S.build - 22} />
          <Words text={t.intro2} size={L.vertical ? 92 : 104} delay={84} out={S.build - 22} />
        </AbsoluteFill>
      </Sequence>

      {/* 3-4 : montée, coupes rapides */}
      <Sequence from={S.build} durationInFrames={S.drop - S.build}>
        <BuildCuts lang={lang} />
      </Sequence>

      {/* 5-6 : impact, logo */}
      <Sequence from={S.drop} durationInFrames={2 * BAR}>
        <SceneIO duration={2 * BAR} inFrames={2}>
          <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 28 }}>
            <Logo size={L.vertical ? 150 : 210} delay={2} />
            <Words text={t.tagline} size={L.vertical ? 44 : 46} weight={400} delay={30} maxWidth={L.width * 0.85} />
          </AbsoluteFill>
        </SceneIO>
      </Sequence>

      {/* 7-14 : M.A.C.S. apparaît, puis réagit ; tableau de bord ; membres (séquences filmées) */}
      <Sequence from={f1} durationInFrames={sceneLen}>
        <FeatureScene title={t.home} src={shot('home')} duration={sceneLen} video={clip('home_intro')} region={{ x: 0.5, y: 0.43, w: 0.5 }}
          focus={[{ f: 0, x: 0.5, y: 0.4, s: 1 }, { f: 220, x: 0.5, y: 0.38, s: 1.4 }]} />
      </Sequence>
      <Sequence from={f1 + sceneLen} durationInFrames={sceneLen}>
        <FeatureScene title={t.moods} src={shot('home')} duration={sceneLen} tiltY={14} video={clip('home_moods')} region={{ x: 0.5, y: 0.45, w: 0.42 }}
          focus={[{ f: 0, x: 0.5, y: 0.4, s: 1.1 }, { f: 220, x: 0.5, y: 0.38, s: 1.5 }]} />
      </Sequence>
      <Sequence from={f1 + 2 * sceneLen} durationInFrames={sceneLen}>
        <FeatureScene title={t.dashboard} src={shot('dashboard')} duration={sceneLen} video={clip('dashboard')} region={{ x: 0.47, y: 0.27, w: 0.42 }}
          focus={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 230, x: 0.5, y: 0.5, s: 1.4 }]} />
      </Sequence>
      <Sequence from={f1 + 3 * sceneLen} durationInFrames={sceneLen}>
        <FeatureScene title={t.members} src={shot('members')} duration={sceneLen} tiltY={14} video={clip('members')} region={{ x: 0.62, y: 0.17, w: 0.36 }}
          focus={[{ f: 0, x: 0.6, y: 0.2, s: 1.05 }, { f: 220, x: 0.6, y: 0.12, s: 1.4 }]} />
      </Sequence>

      {/* 13-14 : pause, rien à configurer */}
      <Sequence from={S.break} durationInFrames={2 * BAR}>
        <SceneIO duration={2 * BAR}>
          <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: L.vertical ? 60 : 44 }}>
            <Words text={t.breakTitle} size={L.vertical ? 76 : 72} delay={6} maxWidth={L.width * 0.88} />
            <div style={{ display: 'flex', flexDirection: 'column', gap: L.vertical ? 30 : 22 }}>
              {[t.noCards, t.noHelpers, t.noTemplates, t.noToken].map((x, i) => (
                <CheckItem key={x} text={x} delay={40 + i * BEAT} size={L.vertical ? 50 : 44} />
              ))}
            </div>
          </AbsoluteFill>
        </SceneIO>
      </Sequence>
      {[0, 1, 2, 3].map((i) => <Sfx key={i} at={S.break + 40 + i * BEAT} name="pop" volume={0.5} />)}

      {/* 15-22 : mur de caméras, historique, zoom, énergie + langues */}
      <Sequence from={f2} durationInFrames={sceneLen}>
        <FeatureScene title={t.wall} src={shot('camera-wall')} duration={sceneLen} tiltY={14} video={clip('wall')} region={{ x: 0.76, y: 0.27, w: 0.46 }}
          focus={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 230, x: 0.55, y: 0.45, s: 1.18 }]} />
      </Sequence>
      <Sequence from={f2 + sceneLen} durationInFrames={sceneLen}>
        <FeatureScene title={t.history} src={shot('detection-history')} duration={sceneLen} region={{ x: 0.83, y: 0.44, w: 0.33 }}
          focus={[{ f: 0, x: 0.5, y: 0.5, s: 1 }, { f: 60, x: 0.3, y: 0.25, s: 1.5 }, { f: 170, x: 0.8, y: 0.45, s: 1.6 }, { f: 240, x: 0.8, y: 0.45, s: 1.65 }]} />
      </Sequence>
      <Sequence from={f2 + 2 * sceneLen} durationInFrames={sceneLen}>
        <FeatureScene title={t.zoom} src="shots/fullscreen-zoom.jpg" duration={sceneLen} tiltY={12} region={{ x: 0.3, y: 0.75, w: 0.55 }}
          focus={[{ f: 0, x: 0.5, y: 0.45, s: 1 }, { f: 40, x: 0.5, y: 0.45, s: 1 }, { f: 110, x: 0.35, y: 0.4, s: 1.5 }, { f: 240, x: 0.35, y: 0.4, s: 1.55 }]}
          extra={<PinchHint x={L.width / 2} y={L.tabletTop + L.tabletW * 0.3} />} />
      </Sequence>
      <Sequence from={f2 + 3 * sceneLen} durationInFrames={sceneLen}>
        <SceneIO duration={sceneLen}>
          <AbsoluteFill>
            <div style={{ position: 'absolute', top: L.titleTop, width: '100%', display: 'flex', justifyContent: 'center' }}>
              <Words text={t.energy} size={L.titleSize} delay={4} />
            </div>
            {L.vertical ? (
              <>
                <div style={{ position: 'absolute', top: L.height * 0.27, left: (L.width - L.tabletW) / 2 }}>
                  <Tablet src={shot('cameras-energy')} video={clip('energy')} width={L.tabletW} focus={[{ f: 0, x: 0.45, y: 0.4, s: 1 }, { f: 230, x: 0.45, y: 0.35, s: 1.25 }]} />
                </div>
                <div style={{ position: 'absolute', top: L.height * 0.68, width: '100%', display: 'flex', justifyContent: 'center' }}>
                  <Languages lang={lang} vertical />
                </div>
              </>
            ) : (
              <>
                <div style={{ position: 'absolute', top: L.height * 0.27, left: L.width * 0.06 }}>
                  <Tablet src={shot('cameras-energy')} video={clip('energy')} width={L.width * 0.5} tiltY={16} focus={[{ f: 0, x: 0.45, y: 0.4, s: 1 }, { f: 230, x: 0.45, y: 0.35, s: 1.25 }]} />
                </div>
                <div style={{ position: 'absolute', top: L.height * 0.36, left: L.width * 0.62 }}>
                  <Languages lang={lang} vertical={false} />
                </div>
              </>
            )}
          </AbsoluteFill>
        </SceneIO>
      </Sequence>

      {/* 23-24 : fin */}
      <Sequence from={S.outro} durationInFrames={DURATION - S.outro}>
        <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 26 }}>
          <Logo size={L.vertical ? 130 : 170} delay={2} />
          <Words text={t.outro1} size={L.vertical ? 56 : 54} delay={26} />
          <Words text={t.outro2} size={L.vertical ? 38 : 36} weight={400} delay={40} />
          <UrlPill text={t.url} delay={60} size={L.vertical ? 34 : 34} />
        </AbsoluteFill>
      </Sequence>

      {/* Éclairs sur les impacts, sons de transition */}
      <Flash at={S.drop} />
      <Flash at={S.features2} strength={0.6} />
      <Flash at={S.outro} strength={0.7} />
      {[f1, f1 + sceneLen, f1 + 2 * sceneLen, f1 + 3 * sceneLen, f2, f2 + sceneLen, f2 + 2 * sceneLen, f2 + 3 * sceneLen].map((f, i) => (
        <Sfx key={i} at={f - 14} name={i % 2 ? 'whoosh_down' : 'whoosh'} volume={0.45} />
      ))}
      <Sfx at={S.outro + 4} name="shimmer" volume={0.6} />
      <AbsoluteFill style={{ background: '#000', opacity: endFade }} />
    </AbsoluteFill>
    </TitleWeight.Provider>
  );
};

const UrlPill: React.FC<{ text: string; delay: number; size: number }> = ({ text, delay, size }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - delay, fps, config: { damping: 14 } });
  return (
    <div style={{
      marginTop: 14, fontFamily: BODY, fontWeight: 600, fontSize: size, color: '#fff', padding: '14px 30px', borderRadius: 999,
      border: `2px solid ${ACCENT}`, background: `rgba(${ACCENT_RGB},0.1)`, boxShadow: `0 0 30px rgba(${ACCENT_RGB},0.35)`,
      opacity: p, transform: `translateY(${(1 - p) * 30}px)`,
    }}>{text}</div>
  );
};
