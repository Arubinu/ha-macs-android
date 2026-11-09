// Copie les captures du README (données de démo) dans public/shots, et génère la musique si elle manque.
import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const repo = join(root, '..', '..');
mkdirSync(join(root, 'public', 'shots'), { recursive: true });
cpSync(join(repo, 'docs', 'screenshots'), join(root, 'public', 'shots'), { recursive: true });
if (!existsSync(join(root, 'public', 'audio', 'music.wav'))) {
  execFileSync('python', [join(root, 'audio', 'make_audio.py')], { stdio: 'inherit' });
}
console.log('assets ok');
