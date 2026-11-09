import React from 'react';
import { Composition } from 'remotion';
import { HaMacsVideo, VideoProps } from './Video';
import { Social } from './Social';
import { Gallery } from './Gallery';
import { DURATION, FPS } from './timing';

// 16:9 (README, YouTube, forums) et vertical 9:16 (Shorts, réseaux) ; langue en paramètre (`--props='{"lang":"fr"}'`).
// Social : image d'aperçu 1280×640 ; Gallery : mosaïque des captures pour le README (images fixes, rendu à l'image 200).
export const Root: React.FC = () => (
  <>
    <Composition id="Main" component={HaMacsVideo} durationInFrames={DURATION} fps={FPS} width={1920} height={1080}
      defaultProps={{ lang: 'en', titleWeight: 700 } as VideoProps} />
    <Composition id="Vertical" component={HaMacsVideo} durationInFrames={DURATION} fps={FPS} width={1080} height={1920}
      defaultProps={{ lang: 'en', titleWeight: 700 } as VideoProps} />
    <Composition id="Social" component={Social} durationInFrames={240} fps={FPS} width={1280} height={640}
      defaultProps={{ lang: 'en' as const }} />
    <Composition id="Gallery" component={Gallery} durationInFrames={240} fps={FPS} width={1600} height={600}
      defaultProps={{ lang: 'en' as const }} />
  </>
);
