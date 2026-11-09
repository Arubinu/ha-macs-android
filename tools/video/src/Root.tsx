import React from 'react';
import { Composition } from 'remotion';
import { HaMacsVideo, VideoProps } from './Video';
import { DURATION, FPS } from './timing';

// 16:9 (README, YouTube, forums) et vertical 9:16 (Shorts, réseaux) ; langue en paramètre (`--props='{"lang":"fr"}'`).
export const Root: React.FC = () => (
  <>
    <Composition id="Main" component={HaMacsVideo} durationInFrames={DURATION} fps={FPS} width={1920} height={1080}
      defaultProps={{ lang: 'en' } as VideoProps} />
    <Composition id="Vertical" component={HaMacsVideo} durationInFrames={DURATION} fps={FPS} width={1080} height={1920}
      defaultProps={{ lang: 'en' } as VideoProps} />
  </>
);
