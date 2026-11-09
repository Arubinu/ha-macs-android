import React from 'react';
import { AbsoluteFill } from 'remotion';
import { Background, Logo, Tablet, Words } from './components';
import { Lang, T } from './i18n';
import { ACCENT, ACCENT_RGB, BODY } from './theme';

/**
 * Image d'aperçu pour les réseaux et GitHub (Settings › Social preview, 1280×640) : même style que la vidéo.
 * Rendu : npx remotion still src/index.ts Social out/social-en.png --frame=200 --props='{"lang":"en"}'
 */
export const Social: React.FC<{ lang: Lang }> = ({ lang }) => {
  const t = T[lang];
  return (
    <AbsoluteFill style={{ background: '#000' }}>
      <Background intensity={1} />
      <AbsoluteFill style={{ flexDirection: 'row', alignItems: 'center', padding: '0 0 0 64px', gap: 36 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18, width: 470, flexShrink: 0 }}>
          <Logo size={86} />
          <Words text={t.tagline} size={30} weight={600} align="left" maxWidth={460} />
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 10 }}>
            {[t.outro1, 'Android 8+', 'Raspberry Pi', t.languages].map((x) => (
              <span key={x} style={{
                fontFamily: BODY, fontWeight: 600, fontSize: 18, color: '#fff', padding: '6px 14px', borderRadius: 999,
                border: `1px solid rgba(${ACCENT_RGB},0.5)`, background: `rgba(${ACCENT_RGB},0.08)`,
              }}>{x}</span>
            ))}
          </div>
          <div style={{ fontFamily: BODY, fontSize: 18, color: ACCENT, marginTop: 4 }}>{t.url}</div>
        </div>
        <div style={{ marginRight: -120 }}>
          <Tablet src={`shots/${lang}/dashboard.jpg`} width={780} tiltY={-16} tiltX={4} float={false} />
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
