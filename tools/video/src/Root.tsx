import React from 'react';
import { AbsoluteFill, Composition, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';

type Props = { lang: 'en' | 'fr' };

// Écran de test (le montage viendra ensuite) : vérifie que Remotion rend bien une vidéo.
const Placeholder: React.FC<Props> = ({ lang }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 12 } });
  const glow = interpolate(frame, [0, 60], [0, 1], { extrapolateRight: 'clamp' });
  return (
    <AbsoluteFill style={{ background: '#000', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{
        color: 'rgb(140,245,255)', fontFamily: 'sans-serif', fontSize: 120, fontWeight: 700,
        transform: `scale(${pop})`, textShadow: `0 0 ${40 * glow}px rgba(140,245,255,0.8)`,
      }}>
        HA-MACS
      </div>
      <div style={{ color: '#fff', fontFamily: 'sans-serif', fontSize: 40, opacity: glow, marginTop: 24 }}>
        {lang === 'fr' ? 'Votre tablette murale Home Assistant' : 'Your Home Assistant wall tablet'}
      </div>
    </AbsoluteFill>
  );
};

export const Root: React.FC = () => (
  <>
    <Composition id="Main" component={Placeholder} durationInFrames={90} fps={30} width={1920} height={1080} defaultProps={{ lang: 'en' }} />
    <Composition id="Vertical" component={Placeholder} durationInFrames={90} fps={30} width={1080} height={1920} defaultProps={{ lang: 'en' }} />
  </>
);
