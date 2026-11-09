import { loadFont as loadAudiowide } from '@remotion/google-fonts/Audiowide';
import { loadFont as loadInter } from '@remotion/google-fonts/Inter';

// Mêmes couleurs et polices que l'app (accent cyan, fond noir, titres en Audiowide).
export const ACCENT = 'rgb(140,245,255)';
export const ACCENT_RGB = '140,245,255';
export const BG = '#000';

export const TITLE = loadAudiowide().fontFamily;
export const BODY = loadInter('normal', { weights: ['400', '500', '600', '700', '800'], subsets: ['latin', 'latin-ext'] }).fontFamily;
