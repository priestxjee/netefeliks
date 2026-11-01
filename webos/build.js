// Baut die LG-webOS-Version aus der gleichen Oberfläche wie die Fire-TV-App.
// Aufruf: node webos/build.js <repo owner/name> <version>   → Ordner webos-dist/ + webos.html
const fs = require('fs');
const path = require('path');
const esbuild = require('esbuild');

const [repo = '', version = '1'] = process.argv.slice(2);
const root = path.join(__dirname, '..');
const src = path.join(root, 'app/src/main/assets/index.html');
const out = path.join(root, 'webos-dist');
const v = String(parseInt(version, 10) || 1);

let html = fs.readFileSync(src, 'utf8')
  .replace(/__NFK_REPO__/g, repo)
  .replace(/__NFK_VERSION__/g, v);

// JavaScript für den älteren Browser der LG-Fernseher (webOS 22 = Chromium 87) übersetzen
let n = 0;
html = html.replace(/<script(?![^>]*\bsrc=)([^>]*)>([\s\S]*?)<\/script>/g, (all, attrs, code) => {
  if (!code.trim()) return all;
  const r = esbuild.transformSync(code, { loader: 'js', target: 'chrome79', charset: 'utf8', legalComments: 'inline' });
  n++;
  return `<script${attrs}>${r.code.replace(/<\/script/gi, '<\\/script')}</script>`;
});

// Schonender für den schwächeren LG-Prozessor: Bilder erst laden, wenn sie sichtbar werden; Hintergrundbilder kleiner
html = html.replace(/<img (?![^>]*\bloading=)/g, '<img loading="lazy" ')
  .replace(/image\.tmdb\.org\/t\/p\/w1280/g, 'image.tmdb.org/t/p/w780');

// webOS-Anpassungen als erstes Skript einsetzen
const compat = fs.readFileSync(path.join(__dirname, 'compat.js'), 'utf8')
  .replace(/__TVN_REPO__/g, repo)
  .replace(/__TVN_VERSION__/g, v);
const compatJs = esbuild.transformSync(compat, { loader: 'js', target: 'chrome79', charset: 'utf8' }).code;
if (!/<head>/i.test(html)) throw new Error('<head> nicht gefunden');
html = html.replace(/<head>/i, () => `<head><script>${compatJs}</script>`);

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'app.html'), html);
fs.writeFileSync(path.join(root, 'webos.html'), html);
fs.writeFileSync(path.join(out, 'index.html'),
  fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8').replace(/__TVN_VERSION__/g, v));
const info = JSON.parse(fs.readFileSync(path.join(__dirname, 'appinfo.json'), 'utf8'));
info.version = `1.0.${v}`;
fs.writeFileSync(path.join(out, 'appinfo.json'), JSON.stringify(info, null, 2));
for (const f of ['icon.png', 'largeIcon.png']) fs.copyFileSync(path.join(__dirname, f), path.join(out, f));
console.log(`webOS-Version ${info.version} gebaut (${n} Skripte übersetzt, ${Math.round(html.length / 1024)} KB)`);
