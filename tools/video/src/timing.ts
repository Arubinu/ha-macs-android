// Rythme commun à l'image et à la musique (audio/make_audio.py) : 120 BPM, 60 images/s.
export const FPS = 60;
export const BEAT = 30;            // un temps = 0,5 s
export const BAR = 4 * BEAT;       // une mesure = 2 s
export const DURATION = 26 * BAR + 60; // 26 mesures + 1 s de queue

// Découpage du morceau (en images).
export const S = {
  intro: 0,                 // mesures 1-2 : nappe seule
  build: 2 * BAR,           // 3-4 : charleys, arpège, montée
  drop: 4 * BAR,            // 5 : impact → logo
  features1: 6 * BAR,       // 7-14 : quatre scènes de 2 mesures
  break: 14 * BAR,          // 15-16 : pause (rien à configurer)
  features2: 16 * BAR,      // 17-24 : quatre scènes de 2 mesures
  outro: 24 * BAR,          // 25-26 : impact → fin
} as const;

/** Intensité d'une pulsation sur chaque temps (1 au temps, décroît ensuite), seulement dans les parties « pleines ». */
export const beatPulse = (frame: number): number => {
  const full = (frame >= S.drop && frame < S.break) || (frame >= S.features2 && frame < S.outro);
  if (!full) return 0;
  const p = (frame % BEAT) / BEAT;
  return Math.exp(-p * 6);
};

/**
 * Séquences filmées (record.sh) : début dans le fichier (secondes) et vitesse. Réglées d'après les enregistrements :
 * home_intro = lancement de l'app (apparition de M.A.C.S.), home_moods = surpris puis content, puis le doigt.
 */
export const CLIPS: Record<string, { from: number; rate?: number }> = {
  home_intro: { from: 8.0 },
  home_moods: { from: 2.2, rate: 1.5 },
  dashboard: { from: 2.6 },
  members: { from: 0.5 },
  energy: { from: 0.5 },
  wall: { from: 3 },
};
