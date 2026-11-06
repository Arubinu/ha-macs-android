"""Bouton « Dashboard » HD : vague (animation.gif) agrandie x2 avec transparence progressive (le noir devient
transparent, la lueur reste douce) + texte HD de text.png (ombre comprise, calque à 80 %), centré, en WebP animé.
Usage : python make_hd.py <animation.gif> <text.png> <ancien.gif> <sortie.webp>"""
import sys
from PIL import Image, ImageSequence, ImageChops

anim_p, text_p, old_p, out_p = sys.argv[1:5]
SCALE = 2
anim = Image.open(anim_p)
W, H = anim.size[0] * SCALE, anim.size[1] * SCALE

# Taille des lettres reprise de l'ancien bouton (boîte des pixels presque blancs).
old = Image.open(old_p)
old.seek(10)
ob = old.convert('RGB').convert('L').point(lambda v: 255 if v > 200 else 0).getbbox()
letters_w = (ob[2] - ob[0]) * SCALE

text = Image.open(text_p).convert('RGBA')
tl = text.convert('RGB').convert('L')
ta = text.split()[3]
tb = ImageChops.multiply(tl, ta).point(lambda v: 255 if v > 200 else 0).getbbox()
k = letters_w / (tb[2] - tb[0])
tw, th = round(text.width * k), round(text.height * k)
small = text.resize((tw, th), Image.LANCZOS)
r, g, b, a = small.split()
small = Image.merge('RGBA', (r, g, b, a.point(lambda v: round(v * 0.8))))  # calque texte à 80 %
# Lettres centrées horizontalement et verticalement.
tcx = (tb[0] + tb[2]) / 2 * k
tcy = (tb[1] + tb[3]) / 2 * k
pos = (round(W / 2 - tcx), round(H / 2 - tcy))

frames, durations = [], []
for fr in ImageSequence.Iterator(anim):
    durations.append(fr.info.get('duration', 40))
    rgb = fr.convert('RGB').resize((W, H), Image.LANCZOS)
    px = rgb.load()
    out = Image.new('RGBA', (W, H))
    op = out.load()
    for y in range(H):
        for x in range(W):
            R, G, B = px[x, y]
            m = max(R, G, B)
            op[x, y] = (0, 0, 0, 0) if m < 6 else (R * 255 // m, G * 255 // m, B * 255 // m, m)
    out.alpha_composite(small, pos)
    frames.append(out)

frames[0].save(out_p, save_all=True, append_images=frames[1:], duration=durations, loop=0, quality=85, method=6)
print('frames', len(frames), 'size', (W, H), 'text', pos, (tw, th))
