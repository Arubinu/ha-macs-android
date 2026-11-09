import React from 'react';
import { AbsoluteFill, Img, staticFile } from 'remotion';
import { Background } from './components';
import { ACCENT_RGB, BODY } from './theme';

/** Libellé de la pastille, dans les 8 langues des README. */
const LABEL: Record<string, string> = {
  en: '8 screenshots', fr: '8 captures d\'écran', de: '8 Screenshots', es: '8 capturas de pantalla',
  it: '8 schermate', nl: '8 schermafbeeldingen', pt: '8 capturas de ecrã', pl: '8 zrzutów ekranu',
};
const HINT: Record<string, string> = {
  en: 'Click to see them all', fr: 'Cliquez pour toutes les voir', de: 'Klicken, um alle zu sehen',
  es: 'Haz clic para verlas todas', it: 'Clicca per vederle tutte', nl: 'Klik om ze allemaal te zien',
  pt: 'Clique para ver todas', pl: 'Kliknij, aby zobaczyć wszystkie',
};
const SHOTS = ['home', 'dashboard', 'members', 'cameras-energy', 'camera-wall', 'detection-history', 'avatar-editor', 'fullscreen-zoom'];

/**
 * Mosaïque des captures d'une langue pour le README (1600×900), qui renvoie vers la galerie complète.
 * Rendu : npx remotion still src/index.ts Gallery out/gallery-fr.png --frame=200 --props='{"lang":"fr"}'
 */
export const Gallery: React.FC<{ lang: string }> = ({ lang }) => {
  const w = 360;
  const h = w * 10 / 16;
  return (
    <AbsoluteFill style={{ background: '#000' }}>
      <Background intensity={0.8} />
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ display: 'grid', gridTemplateColumns: `repeat(4, ${w}px)`, gap: 24, opacity: 0.75 }}>
          {SHOTS.map((s, i) => (
            <Img key={s} src={staticFile(s === 'fullscreen-zoom' ? 'shots/fullscreen-zoom.jpg' : `shots/${lang}/${s}.jpg`)} style={{
              width: w, height: h, objectFit: 'cover', borderRadius: 14, border: '1px solid rgba(255,255,255,0.14)',
              boxShadow: `0 12px 30px rgba(0,0,0,0.7)`, transform: `rotate(${(i % 2 ? 1 : -1) * 1.2}deg)`,
            }} />
          ))}
        </div>
      </AbsoluteFill>
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '26px 48px', borderRadius: 28,
          background: 'rgba(0,0,0,0.78)', border: `2px solid rgba(${ACCENT_RGB},0.8)`,
          boxShadow: `0 0 60px rgba(${ACCENT_RGB},0.45)`, fontFamily: BODY, color: '#fff',
        }}>
          <div style={{ fontSize: 50, fontWeight: 700 }}>📸 {LABEL[lang] ?? LABEL.en}</div>
          <div style={{ fontSize: 26, fontWeight: 400, color: `rgb(${ACCENT_RGB})` }}>{HINT[lang] ?? HINT.en}</div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
