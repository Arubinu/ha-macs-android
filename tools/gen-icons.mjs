// Generates app/src/main/assets/{mdi,hue}.json from the upstream icon sources.
// Usage: node tools/gen-icons.mjs <materialdesignicons.css> <hass-hue-icons.js>
// The MDI font itself (materialdesignicons-webfont.ttf) goes to app/src/main/res/font/mdi.ttf.
import fs from 'fs';
import path from 'path';

const [, , cssPath, hueJsPath] = process.argv;
const assets = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1')), '..', 'app', 'src', 'main', 'assets');

if (cssPath) {
  const css = fs.readFileSync(cssPath, 'utf8');
  const re = /\.mdi-([a-z0-9-]+)::before\s*\{\s*content:\s*"\\([0-9A-Fa-f]+)"/g;
  const map = {};
  let m;
  while ((m = re.exec(css))) map[m[1]] = parseInt(m[2], 16);
  fs.writeFileSync(path.join(assets, 'mdi.json'), JSON.stringify(map));
  console.log('mdi icons:', Object.keys(map).length);
}

if (hueJsPath) {
  const src = fs.readFileSync(hueJsPath, 'utf8');
  const start = src.indexOf('const HUE_ICONS_MAP');
  const end = src.indexOf('\n};', start);
  const icons = new Function(src.slice(start, end + 3) + '; return HUE_ICONS_MAP;')();
  const out = {};
  for (const [name, def] of Object.entries(icons)) out[name] = def.path;
  fs.writeFileSync(path.join(assets, 'hue.json'), JSON.stringify(out));
  console.log('hue icons:', Object.keys(out).length);
}
