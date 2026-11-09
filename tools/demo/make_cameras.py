"""
Images des caméras de démo (cameras/camera.argus_N_fluent.jpg) depuis des photos libres de Wikimedia Commons
(CC0 ou domaine public, voir README.md) : recadrage 16:9 en 1280×720, horodatage façon caméra de surveillance.
La caméra du couloir est rendue comme une vision de nuit infrarouge (pièce plongée dans le noir).

  python tools/demo/make_cameras.py          (Pillow nécessaire ; les flux vidéo se régénèrent ensuite :
                                              supprimer tools/demo/stream/ avant de relancer demo_ha.py)
"""
import io
import json
import os
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
UA = {'User-Agent': 'ha-macs-demo/1.0 (demo screenshots)'}

# (caméra, fichier Commons, centre vertical du recadrage 0..1, style, heure affichée)
CAMERAS = [
    (1, 'Porch on a house in Brodalen.jpg', 0.55, 'day', '15:40:20'),
    (2, "Interior view, first floor, detail, hallway looking north - W. K. Bowman-O'Neal Apartment Building, 4313 East Kellogg Street, Wichita, Sedgwick County, KS HABS KANS,87-WICH.V,1-35.tif", 0.5, 'night', '02:39:12'),
    (3, 'Plants on balcony D220722.jpg', 0.5, 'day', '15:42:22'),
    (4, 'Brown house blue roof (Unsplash).jpg', 0.62, 'day', '15:43:23'),
]
DATE = '05/11/2026'
DAY = 'THU'


def fetch(title):
    q = urllib.parse.urlencode({'action': 'query', 'format': 'json', 'prop': 'imageinfo', 'iiprop': 'url|extmetadata',
                                'iiurlwidth': 2000, 'titles': 'File:' + title})
    with urllib.request.urlopen(urllib.request.Request('https://commons.wikimedia.org/w/api.php?' + q, headers=UA)) as r:
        page = next(iter(json.load(r)['query']['pages'].values()))
    info = page['imageinfo'][0]
    lic = info['extmetadata'].get('LicenseShortName', {}).get('value', '?')
    assert lic in ('CC0', 'Public domain'), (title, lic)
    with urllib.request.urlopen(urllib.request.Request(info['thumburl'], headers=UA)) as r:
        return Image.open(io.BytesIO(r.read())).convert('RGB'), lic


def crop169(img, cy, xmax=1.0):
    if xmax < 1.0:                   # bord droit retiré (étiquette d'archive du scan)
        img = img.crop((0, 0, int(img.size[0] * xmax), img.size[1]))
    w, h = img.size
    th = int(w * 9 / 16)
    if th > h:                       # image trop haute : on rogne la largeur
        tw = int(h * 16 / 9)
        x = (w - tw) // 2
        return img.crop((x, 0, x + tw, h)).resize((1280, 720), Image.LANCZOS)
    y = int(min(max(cy * h - th / 2, 0), h - th))
    return img.crop((0, y, w, y + th)).resize((1280, 720), Image.LANCZOS)


def night(img):
    """Vision de nuit : niveaux de gris assombris, contraste dur, grain et vignette."""
    g = ImageOps.grayscale(img)
    g = ImageEnhance.Contrast(g).enhance(1.25)
    g = g.point(lambda v: int(255 * (v / 255) ** 1.9 * 0.85))   # ombres profondes, hautes lumières gardées
    noise = Image.effect_noise((1280, 720), 30).filter(ImageFilter.GaussianBlur(0.5))
    g = Image.blend(g, noise, 0.12)
    # Éclairage infrarouge de la caméra : centre éclairé, bords dans le noir.
    vign = Image.radial_gradient('L').resize((1280, 720)).point(lambda v: max(0, 255 - int(v * 1.25)))
    g = Image.composite(g, Image.new('L', (1280, 720), 0), vign)
    return g.convert('RGB')


def stamp(img, time):
    d = ImageDraw.Draw(img)
    font = None
    for f in ('consola.ttf', 'DejaVuSansMono.ttf', 'LiberationMono-Regular.ttf'):
        try:
            font = ImageFont.truetype(f, 26)
            break
        except OSError:
            pass
    text = f'{DATE} {time} {DAY}'
    font = font or ImageFont.load_default()
    w = d.textlength(text, font=font)
    d.text(((1280 - w) / 2, 14), text, font=font, fill=(255, 255, 255), stroke_width=2, stroke_fill=(0, 0, 0))
    return img


for n, title, cy, style, time in CAMERAS:
    src, lic = fetch(title)
    img = crop169(src, cy, 0.9 if style == 'night' else 1.0)
    if style == 'night':
        img = night(img)
    img = stamp(img, time)
    out = os.path.join(HERE, 'cameras', f'camera.argus_{n}_fluent.jpg')
    img.save(out, quality=88)
    print(n, lic, out)
