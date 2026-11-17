"""Game textures for Paper Clash 2.

Outputs:
  app/src/main/assets/textures/paper_grain.png  512 x 512, "L", seamlessly tileable,
                                                mean ~200, std ~10 (multiplied in shaders)
  app/src/main/assets/textures/atlas.png        2048 x 2048 RGBA (straight alpha)
  app/src/main/assets/textures/atlas.json       {"width","height","sprites":{name:{x,y,w,h,cells}}}
  art/build/atlas_preview.png                   atlas on a checkerboard (review)
  art/build/paper_grain_tiled.png               2x2 tiling check (review)

Sprites are top-down views (the camera looks down at the desk): toy-like
shading and highlights, crisp ink outlines and no baked drop shadow (the
engine adds contact shadows). Each sprite is drawn at 4x and reduced with
LANCZOS; transparent texels get the colour of the nearest opaque texel
("colour bleeding") so bilinear filtering never pulls in dark fringes.

Drawing units: 1 unit = 0.1 grid cell.
"""
import json
import math
import os
import random
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import *  # noqa: E402,F401,F403

ATLAS = 2048
PAD = 6                 # transparent padding around every sprite (gap between sprites >= 12)
PPC_PROPS = 30.0        # atlas pixels per grid cell for desk props
S = 4
OW_CELLS = 0.085        # outer ink outline, in cells
IW_CELLS = 0.06         # inner ink lines between parts, in cells

TEX_DIR = os.path.join(ASSETS, "textures")

WOOD_L = hexc("#F1CF9C")
WOOD = hexc("#DDAA6E")
WOOD_D = hexc("#B98149")
STEEL_L = hexc("#EEF2F8")
STEEL = hexc("#C3CCDC")
STEEL_D = hexc("#8E9AB2")
RED = hexc("#EC4B3F")
PINK = hexc("#FF9DBD")
PAPER = hexc("#FFFBF2")
LEMON = hexc("#FFE36E")
GRAPHITE = hexc("#3B4560")
CHARCOAL = hexc("#3A3E4C")
GOLD = hexc("#FFCB45")
GOLD_D = hexc("#D99A1E")
GOLD_L = hexc("#FFE89A")
WAX = hexc("#C8333E")


# --------------------------------------------------------------------------
# Paper grain
# --------------------------------------------------------------------------
def make_paper_grain(seed=1234):
    size = (512, 512)
    w, h = size
    fine = levels(tile_blur(noise_L(size, seed), 0.55), 128, 30)
    mottle = levels(value_noise(size, 64, seed + 1), 128, 30)
    cloud = levels(value_noise(size, 12, seed + 2), 128, 30)
    base = Image.blend(fine, mottle, 0.30)
    base = Image.blend(base, cloud, 0.22)

    # fibres: thin curved strokes, a few bright and a few dark, drawn with wrap-around
    fib = Image.new("L", size, 128)
    d = ImageDraw.Draw(fib)
    rnd = random.Random(seed + 3)
    for _ in range(1400):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h)
        a = rnd.uniform(0, math.pi)
        ln = rnd.uniform(5, 26)
        bend = rnd.uniform(-0.6, 0.6)
        val = 128 + (rnd.randint(14, 34) if rnd.random() < 0.55 else -rnd.randint(12, 28))
        pts = []
        for k in range(7):
            t = k / 6.0 - 0.5
            aa = a + bend * t
            pts.append((x + math.cos(aa) * ln * t, y + math.sin(aa) * ln * t))
        for ox in (-w, 0, w):
            for oy in (-h, 0, h):
                d.line([(px + ox, py + oy) for px, py in pts], fill=val, width=1)
    fib = tile_blur(fib, 0.45)
    g = ImageChops.add(base, fib, 1.0, -128)
    return levels(g, 200, 10)


def seam_report(g):
    """Mean |difference| across the wrap seam vs. across an interior line."""
    w, h = g.size
    px = g.load()

    def col_diff(x0, x1):
        return sum(abs(px[x0, y] - px[x1, y]) for y in range(h)) / h

    def row_diff(y0, y1):
        return sum(abs(px[x, y0] - px[x, y1]) for x in range(w)) / w

    return {
        "seam_x": col_diff(w - 1, 0), "inner_x": col_diff(w // 2 - 1, w // 2),
        "seam_y": row_diff(h - 1, 0), "inner_y": row_diff(h // 2 - 1, h // 2),
    }


# --------------------------------------------------------------------------
# Sprite builder
# --------------------------------------------------------------------------
class Spr:
    def __init__(self, c, ppc):
        self.c = c
        self.ppc = ppc
        self.layers = []
        self.outline = True

    def part(self, mask, color, outline=True, grad=True, bevel=True, fill=None, gloss=False, alpha=1.0, iw=None,
             solid=True, light_dir=(-1, -1)):
        self.layers.append(("part", mask, dict(color=color, outline=outline, grad=grad, bevel=bevel, fill=fill,
                                               gloss=gloss, alpha=alpha, iw=iw, solid=solid, light_dir=light_dir)))
        return mask

    def detail(self, mask, color, alpha=1.0, solid=False):
        self.layers.append(("detail", mask, dict(color=color, alpha=alpha, solid=solid)))
        return mask

    def ink(self, mask, alpha=1.0):
        return self.detail(mask, NAVY, alpha)

    def hl(self, mask, alpha=0.5):
        return self.detail(mask, WHITE, alpha)


def draw_sprite_part(img, m, sp, ppc):
    px_cell = ppc * S
    col = sp["color"]
    a = sp["alpha"]
    if sp["outline"]:
        iw = (sp["iw"] if sp["iw"] is not None else IW_CELLS) * px_cell
        paint(img, NAVY, dilate(m, iw), min(1.0, a + 0.25))
    bb = m.getbbox()
    if bb is None:
        return
    if sp["fill"] is not None:
        paint(img, sp["fill"], m, a)
    elif sp["grad"]:
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        p0 = (bb[0], bb[1])
        p1 = (bb[0] + w * 0.35, bb[3]) if w > 2.5 * h else (bb[2], bb[3]) if h < 2.5 * w else (bb[2], bb[1] + h * 0.35)
        paint(img, lgrad(img.size, p0, p1, light(col, 0.20), dark(col, 0.12), step=16), m, a)
    else:
        paint(img, col, m, a)
    if sp["bevel"]:
        k = max(1.0, 0.07 * px_cell)
        dx, dy = sp["light_dir"]
        hi = sub(m, shift(m, -dx * k, -dy * k))
        lo = sub(m, shift(m, dx * k, dy * k))
        paint(img, WHITE, inter(blur(hi, 0.25 * k), m), 0.45 * a)
        paint(img, dark(col, 0.35), inter(blur(lo, 0.35 * k), m), 0.45 * a)
    if sp["gloss"]:
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        g = m_ellipse(img.size, bb[0] + w * 0.34, bb[1] + h * 0.28, w * 0.18, h * 0.10, -35)
        paint(img, WHITE, inter(blur(g, 0.02 * px_cell), erode(m, 0.04 * px_cell)), 0.55 * a)


def render_sprite(fn, w_units, h_units, ppc, outline=True):
    """Render a sprite drawn in units of 0.1 cell; returns the final RGBA image."""
    k = ppc / 10.0 * S
    c = Ctx(w_units, h_units, k)
    B = Spr(c, ppc)
    fn(B)
    img = Image.new("RGBA", c.size, (0, 0, 0, 0))
    if outline and B.outline:
        sil = [m for kind, m, sp in B.layers if sp.get("solid")]
        if sil:
            paint(img, NAVY, dilate(union(*sil), OW_CELLS * ppc * S))
    for kind, m, sp in B.layers:
        if kind == "part":
            draw_sprite_part(img, m, sp, ppc)
        else:
            paint(img, sp["color"], m, sp["alpha"])
    W = max(1, int(round(w_units / 10.0 * ppc)))
    H = max(1, int(round(h_units / 10.0 * ppc)))
    return down(img, (W, H))


# --------------------------------------------------------------------------
# Shared prop pieces
# --------------------------------------------------------------------------
def R(c, x0, y0, x1, y1, r=0):
    return c.rrect(x0, y0, x1, y1, r) if r else c.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def cyl_fill(c, y0, y1, c_top, c_mid, c_bot):
    """Horizontal-cylinder shading (light band near the top) as an RGBA image."""
    W, H = c.size
    Y0, Y1 = y0 * c.k, y1 * c.k
    col = []
    for y in range(H):
        t = (y + 0.5 - Y0) / max(1e-6, (Y1 - Y0))
        t = max(0.0, min(1.0, t))
        if t < 0.3:
            col.append(mix(c_top, c_mid, t / 0.3))
        else:
            col.append(mix(c_mid, c_bot, (t - 0.3) / 0.7))
    strip = Image.new("RGBA", (1, H))
    strip.putdata(col)
    return strip.resize((W, H), NEAREST)


def facet_star(B, cx, cy, R_, ratio, col_light, col_dark, rot=-90.0, rr=None, n=5):
    c = B.c
    pts = star_pts(cx, cy, R_, R_ * ratio, n, rot)
    rr = R_ * 0.10 if rr is None else rr
    body = c.poly(pts, rr=[rr, rr * 0.5] * n)
    B.part(body, col_light, grad=False, bevel=False)
    dark_m = c.mask()
    for i in range(0, 2 * n, 2):
        dark_m = union(dark_m, c.poly([(cx, cy), pts[i], pts[(i + 1) % (2 * n)]]))
    B.detail(inter(dark_m, body), col_dark, 1.0)
    lines = c.mask()
    for p in pts:
        lines = union(lines, c.stroke([(cx, cy), p], R_ * 0.03))
    B.detail(inter(lines, body), dark(col_dark, 0.2), 0.35)
    return body


def pencil(B, length, width, body_col, tip=True, eraser=True, x0=2.0, y0=2.0):
    c = B.c
    h = width
    yc = y0 + h / 2
    x = x0
    if eraser:
        B.part(c.poly([(x + 2, y0), (x + 13, y0), (x + 13, y0 + h), (x + 2, y0 + h)], rr=[4, 0, 0, 4]), PINK)
        x += 12
        fer = R(c, x, y0 - 0.5, x + 12, y0 + h + 0.5)
        B.part(fer, STEEL, fill=cyl_fill(c, y0, y0 + h, STEEL_L, STEEL, STEEL_D), bevel=False)
        for gx in (x + 3.5, x + 8.5):
            B.ink(inter(c.stroke([(gx, y0 - 1), (gx, y0 + h + 1)], 0.9), fer), 0.35)
        x += 12
    else:
        x += 2
    body_end = x0 + length - 30
    body = R(c, x, y0, body_end, y0 + h)
    B.part(body, body_col, fill=cyl_fill(c, y0, y0 + h, light(body_col, 0.35), body_col, dark(body_col, 0.25)),
           bevel=False)
    for fy in (y0 + h * 0.33, y0 + h * 0.67):
        B.ink(inter(c.stroke([(x, fy), (body_end, fy)], 0.5), body), 0.22)
    if not eraser:
        B.part(c.ellipse(x, yc, 2.2, h / 2), WOOD_L, bevel=False)
        B.detail(c.circle(x, yc, 1.4), GRAPHITE)
    if tip:
        cone = c.poly([(body_end, y0), (x0 + length - 4, yc - 0.9), (x0 + length - 4, yc + 0.9), (body_end, y0 + h)],
                      rr=[0, 1, 1, 0])
        B.part(cone, WOOD_L, fill=cyl_fill(c, y0, y0 + h, hexc("#FBE3BF"), WOOD_L, WOOD), bevel=False)
        scal = c.mask()
        for k in range(4):
            yy = y0 + h * (k + 0.5) / 4
            scal = union(scal, c.ellipse(body_end, yy, 2.2, h / 8 + 0.2))
        B.detail(inter(scal, cone), body_col, 1.0)
        lead = c.poly([(x0 + length - 11, yc - 2.3), (x0 + length, yc), (x0 + length - 11, yc + 2.3)], rr=[0, 0.8, 0])
        B.part(lead, GRAPHITE, outline=False, bevel=False)
        B.hl(c.stroke([(x0 + length - 9, yc - 0.9), (x0 + length - 3, yc - 0.2)], 0.6), 0.5)


# --------------------------------------------------------------------------
# Desk props (units = 0.1 cell)
# --------------------------------------------------------------------------
def sp_scissors_big(B):
    c = B.c
    piv = (96.0, 45.0)
    for side in (-1, 1):
        tip = (196.0, 45 + side * 21)
        a = math.atan2(tip[1] - piv[1], tip[0] - piv[0])
        nx, ny = -math.sin(a), math.cos(a)
        back = (piv[0] - 14 * math.cos(a), piv[1] - 14 * math.sin(a))
        w = 7.5
        P = [(back[0] + nx * w * 0.6 * side, back[1] + ny * w * 0.6 * side),
             (piv[0] + 30 * math.cos(a) + nx * w * side, piv[1] + 30 * math.sin(a) + ny * w * side),
             tip,
             (piv[0] + 40 * math.cos(a) - nx * 1.0 * side, piv[1] + 40 * math.sin(a) - ny * 1.0 * side),
             (back[0] - nx * w * 0.4 * side, back[1] - ny * w * 0.4 * side)]
        bl = c.poly(P, rr=[3, 6, 1.2, 4, 3])
        B.part(bl, STEEL, fill=lgrad(c.size, c.p(*piv), c.p(*tip), STEEL_L, STEEL_D, step=16), bevel=True)
        B.hl(inter(c.stroke([v_lerp(piv, tip, 0.15), v_lerp(piv, tip, 0.9)], 1.1), bl), 0.75)
    for side, (hx, hy, rx, ry) in ((-1, (44.0, 26.0, 30.0, 19.0)), (1, (40.0, 66.0, 34.0, 21.0))):
        ring = sub(c.ellipse(hx, hy, rx, ry, side * -12), c.ellipse(hx + 2, hy, rx - 11, ry - 9, side * -12))
        arm = c.stroke([piv, (hx + rx * 0.75, hy + side * -4)], 15)
        B.part(union(ring, arm), RED, gloss=True)
        B.hl(inter(c.ellipse(hx - rx * 0.35, hy - ry * 0.55, rx * 0.35, ry * 0.15, -15), ring), 0.55)
    B.part(c.circle(piv[0], piv[1], 6.5), STEEL, bevel=True)
    B.ink(c.stroke([(piv[0] - 3.5, piv[1] - 2), (piv[0] + 3.5, piv[1] + 2)], 1.3), 0.6)


def sp_pencil_yellow(B):
    pencil(B, 196, 16, SUN)


def sp_pencil_green(B):
    pencil(B, 176, 15, MINT, eraser=False)


def sp_paper_clip(B):
    c = B.c
    path = [(14, 4)]
    path += [(72, 4)] + arc_pts(72, 13.5, 9.5, -90, 90, n=20)[1:]
    path += [(8, 23)] + arc_pts(8, 15.5, 7.5, 90, 270, n=20)[1:]
    path += [(62, 8)] + arc_pts(62, 13, 5, -90, 90, n=14)[1:]
    path += [(22, 18)]
    path = [(x + 2, y + 1) for x, y in path]
    m = c.stroke(path, 3.4)
    B.part(m, SKY, bevel=True)
    B.hl(inter(c.stroke([(p[0] - 0.4, p[1] - 0.6) for p in path], 1.0), m), 0.55)


def sp_button_red(B):
    c = B.c
    cx = cy = 30
    disc = c.circle(cx, cy, 27)
    B.part(disc, RED, gloss=False)
    dish = c.circle(cx, cy, 19)
    B.detail(dish, dark(RED, 0.12), 0.6)
    B.detail(sub(c.circle(cx, cy, 19.5), c.circle(cx + 0.8, cy + 0.8, 18.5)), WHITE, 0.25)
    for dx, dy in ((-5.5, -5.5), (5.5, -5.5), (-5.5, 5.5), (5.5, 5.5)):
        h = c.circle(cx + dx, cy + dy, 3.2)
        B.detail(h, dark(RED, 0.75), 1.0)
        B.detail(inter(c.circle(cx + dx + 0.8, cy + dy + 0.8, 2.6), h), WHITE, 0.25)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 23.5, 200, 260, n=16), 2.0), disc), 0.6)


def sp_tape_roll(B):
    c = B.c
    cx = cy = 50
    strip = c.poly([(78, 64), (100, 74), (95, 92), (72, 82)], rr=1.5)
    B.part(strip, CORAL, alpha=0.95, bevel=False)
    B.detail(inter(union(*[c.stroke([(70 + k * 6, 100), (86 + k * 6, 56)], 2.2) for k in range(-2, 6)]), strip),
             WHITE, 0.7)
    outer = c.circle(cx, cy, 47)
    roll = sub(outer, c.circle(cx, cy, 28))
    B.part(roll, CORAL, fill=rgrad(c.size, *c.p(cx - 14, cy - 16), 66 * c.k, light(CORAL, 0.25), dark(CORAL, 0.15)))
    rings = c.mask()
    for r in range(30, 47, 3):
        rings = union(rings, sub(c.circle(cx, cy, r + 0.35), c.circle(cx, cy, r - 0.35)))
    B.detail(inter(rings, roll), WHITE, 0.25)
    core = sub(c.circle(cx, cy, 28), c.circle(cx, cy, 22))
    B.part(core, KRAFT, iw=0.04)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 43, 200, 255, n=16), 2.2), roll), 0.6)


def sp_twine_spool(B):
    c = B.c
    body = R(c, 14, 10, 86, 60)
    B.part(body, KRAFT, fill=cyl_fill(c, 10, 60, hexc("#E6C49B"), KRAFT, hexc("#93673F")), bevel=False)
    lines = c.mask()
    for k in range(-8, 16):
        x = 14 + k * 6
        lines = union(lines, c.stroke([(x, 10), (x + 22, 60)], 1.0))
    B.detail(inter(lines, body), hexc("#8A5E37"), 0.45)
    lines2 = c.mask()
    for k in range(-8, 16):
        x = 14 + k * 9
        lines2 = union(lines2, c.stroke([(x + 20, 10), (x, 60)], 0.8))
    B.detail(inter(lines2, body), hexc("#F2D8B5"), 0.35)
    for x0 in (2, 84):
        fl = c.rrect(x0, 2, x0 + 14, 68, 6)
        B.part(fl, WOOD, fill=cyl_fill(c, 2, 68, WOOD_L, WOOD, WOOD_D), bevel=True)
    tail = catmull([(60, 60), (64, 68), (74, 66), (80, 70), (92, 68)], n=10)
    B.part(c.stroke(tail, 2.4), KRAFT, iw=0.035)


def sp_rubber_stamp(B):
    c = B.c
    base = c.rrect(4, 4, 86, 86, 10)
    B.part(base, WOOD, fill=lgrad(c.size, c.p(4, 4), c.p(86, 86), WOOD_L, WOOD_D))
    B.detail(sub(base, c.rrect(10, 10, 80, 80, 7)), WHITE, 0.18)
    grain = c.mask()
    for k in range(7):
        y = 14 + k * 10
        grain = union(grain, c.stroke(wave_pts(12, 78, y, 1.2, 1.5 + 0.3 * k, n=30), 0.7))
    B.detail(inter(grain, c.rrect(10, 10, 80, 80, 7)), WOOD_D, 0.25)
    B.part(c.circle(45, 45, 24), RED, gloss=True)
    B.part(c.circle(43, 43, 14), light(RED, 0.15), bevel=False, outline=False)
    B.hl(c.ellipse(37, 36, 6, 3.5, -35), 0.7)


def wave_pts(x0, x1, cy, amp, waves, n=60):
    return [(x0 + (x1 - x0) * i / n, cy + amp * math.sin(2 * math.pi * waves * i / n)) for i in range(n + 1)]


def tag_shape(c, x0, y0, x1, y1, cut):
    return c.poly([(x0 + cut, y0), (x1, y0), (x1, y1), (x0 + cut, y1), (x0, y1 - cut), (x0, y0 + cut)],
                  rr=[2, 4, 4, 2, 2, 2])


def sp_kraft_tag(B):
    c = B.c
    hole = c.circle(30, 33, 3.6)
    tag = sub(tag_shape(c, 16, 6, 118, 60, 14), hole)
    B.part(tag, KRAFT, fill=lgrad(c.size, c.p(16, 6), c.p(118, 60), hexc("#DDB587"), hexc("#B88655")))
    B.part(sub(c.circle(30, 33, 7), hole), PAPER, iw=0.035)
    for k, ww in enumerate((52, 40, 46)):
        B.detail(c.stroke([(50, 22 + k * 11), (50 + ww, 22 + k * 11)], 2.2), hexc("#7A5532"), 0.5)
    B.part(c.poly(star_pts(102, 46, 7, 3.2, 5, -90), rr=[1, 0.5] * 5), CORAL_D, outline=False, bevel=False)
    loop = catmull([(30, 33), (18, 26), (6, 30), (2, 40), (10, 48), (24, 42), (30, 33)], n=8)
    B.part(c.stroke(loop, 2.0), hexc("#E8D2AE"), iw=0.03)


def sp_wax_seal(B):
    c = B.c
    cx = cy = 40
    rnd = random.Random(5)
    pts = []
    for i in range(48):
        a = 2 * math.pi * i / 48
        r = 34 + 2.2 * math.sin(a * 5 + 0.7) + rnd.uniform(-1.4, 1.4)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    blob = c.poly(catmull(pts, n=4, closed=True))
    B.part(blob, WAX, gloss=False)
    inner = c.circle(cx, cy, 23)
    B.detail(sub(c.circle(cx, cy, 24.5), inner), dark(WAX, 0.3), 0.7)
    B.detail(inner, light(WAX, 0.06), 0.9)
    st = c.poly(star_pts(cx, cy, 14, 6.3, 5, -90), rr=[1.5, 0.8] * 5)
    B.detail(shift(st, 0.8 * c.k, 0.8 * c.k), dark(WAX, 0.4), 0.8)
    B.detail(st, light(WAX, 0.2), 1.0)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 29, 195, 255, n=12), 2.4), blob), 0.5)


def sp_eraser_pink(B):
    c = B.c
    er = c.rrect(3, 4, 87, 42, 6)
    B.part(er, PINK)
    sleeve = c.rrect(42, 2, 76, 44, 2)
    B.part(sleeve, SKY, fill=lgrad(c.size, c.p(42, 2), c.p(76, 44), light(SKY, 0.25), dark(SKY, 0.1)))
    B.detail(inter(R(c, 42, 16, 76, 22), sleeve), WHITE, 0.85)
    B.detail(inter(R(c, 42, 25, 76, 28), sleeve), WHITE, 0.6)


def sp_pen_blue(B):
    c = B.c
    y0, h = 3.0, 13.0
    yc = y0 + h / 2
    body = c.poly([(40, y0 + 0.5), (150, y0 + 1.2), (150, y0 + h - 1.2), (40, y0 + h - 0.5)])
    B.part(body, SKY, fill=cyl_fill(c, y0, y0 + h, light(SKY, 0.45), SKY, dark(SKY, 0.25)), bevel=False)
    grip = R(c, 132, y0 + 0.6, 158, y0 + h - 0.6, 2)
    B.part(grip, SKY_D, fill=cyl_fill(c, y0, y0 + h, light(SKY_D, 0.3), SKY_D, dark(SKY_D, 0.3)), bevel=False)
    for gx in range(135, 157, 4):
        B.ink(inter(c.stroke([(gx, y0), (gx, y0 + h)], 0.8), grip), 0.3)
    cone = c.poly([(158, y0 + 1.5), (180, yc - 1.4), (180, yc + 1.4), (158, y0 + h - 1.5)], rr=[0, 1, 1, 0])
    B.part(cone, STEEL, fill=cyl_fill(c, y0, y0 + h, STEEL_L, STEEL, STEEL_D), bevel=False)
    B.part(c.circle(182, yc, 2.0), GRAPHITE, outline=False, bevel=False)
    cap = c.poly([(2, y0 + 1), (44, y0), (44, y0 + h), (2, y0 + h - 1)], rr=[5, 1, 1, 5])
    B.part(cap, NAVY_SOFT, fill=cyl_fill(c, y0, y0 + h, light(NAVY_SOFT, 0.35), NAVY_SOFT, dark(NAVY_SOFT, 0.3)),
           bevel=False)
    clip = c.rrect(8, y0 + 3.5, 40, y0 + 7, 1.6)
    B.part(clip, STEEL, iw=0.03, bevel=False)
    B.hl(c.stroke([(10, y0 + 4.3), (38, y0 + 4.3)], 0.6), 0.8)


def sp_sticky_note(B):
    c = B.c
    note = c.poly([(4, 4), (116, 4), (116, 94), (94, 116), (4, 116)], rr=[2, 2, 3, 3, 2])
    B.part(note, LEMON, fill=lgrad(c.size, c.p(4, 4), c.p(116, 116), hexc("#FFF0A0"), hexc("#FFD84A")),
           bevel=False)
    B.detail(inter(R(c, 4, 4, 116, 20), note), hexc("#F5CF45"), 0.45)
    curl = c.poly([(116, 94), (94, 116), (99, 99)], rr=[1, 1, 3])
    B.part(curl, hexc("#FFF6C4"), iw=0.035, bevel=False)
    for k, (x0, x1) in enumerate(((18, 92), (18, 80), (18, 86))):
        pts = wave_pts(x0, x1, 40 + k * 16, 1.6, 3 + k, n=40)
        B.detail(c.stroke(pts, 2.4), NAVY_SOFT, 0.45)
    B.detail(c.poly([(p[0], p[1]) for p in heart_pts(36, 94, 18)]), CORAL, 0.85)


def heart_pts(cx, cy, w, n=80):
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((cx + x * w / 32.0, cy + (y - 2.5) * w / 32.0))
    return pts


def sp_ruler_wood(B):
    c = B.c
    body = c.rrect(3, 3, 217, 31, 4)
    B.part(body, WOOD_L, fill=lgrad(c.size, c.p(3, 3), c.p(3, 31), hexc("#F7DDB1"), hexc("#D9A86E")))
    grain = c.mask()
    rnd = random.Random(9)
    for k in range(6):
        y = 8 + k * 4.2 + rnd.uniform(-1, 1)
        grain = union(grain, c.stroke(wave_pts(8, 212, y, 0.9, rnd.uniform(1.0, 2.2), n=60), 0.5))
    B.detail(inter(grain, body), WOOD_D, 0.22)
    edge = R(c, 3, 26.5, 217, 31)
    B.detail(inter(edge, body), STEEL, 1.0)
    B.detail(inter(R(c, 3, 26.5, 217, 27.5), body), WHITE, 0.6)
    ticks = c.mask()
    i = 0
    x = 12.0
    while x < 196:
        L = 9 if i % 10 == 0 else (6 if i % 5 == 0 else 3.6)
        ticks = union(ticks, c.stroke([(x, 26.5), (x, 26.5 - L)], 0.7, caps=False))
        x += 2.0
        i += 1
    B.detail(inter(ticks, body), NAVY, 0.75)
    for n_ in range(0, 10):
        xx = 12 + n_ * 20
        B.detail(c.circle(xx, 10.5, 1.2), NAVY, 0.55)
    hole = c.circle(207, 15, 3.2)
    B.detail(hole, NAVY, 0.85)


def sp_compass_tool(B):
    c = B.c
    hinge = (26.0, 48.0)
    for side, tip in ((-1, (154.0, 20.0)), (1, (154.0, 76.0))):
        a = math.atan2(tip[1] - hinge[1], tip[0] - hinge[0])
        nx, ny = -math.sin(a), math.cos(a)
        end = (tip[0] - 22 * math.cos(a), tip[1] - 22 * math.sin(a))
        leg = c.poly([(hinge[0] + nx * 5.5, hinge[1] + ny * 5.5), (end[0] + nx * 3.5, end[1] + ny * 3.5),
                      (end[0] - nx * 3.5, end[1] - ny * 3.5), (hinge[0] - nx * 5.5, hinge[1] - ny * 5.5)], rr=1.5)
        B.part(leg, STEEL, fill=lgrad(c.size, c.p(*hinge), c.p(*tip), STEEL_L, STEEL_D, step=16))
        if side < 0:
            needle = c.poly([(end[0] + nx * 1.6, end[1] + ny * 1.6), tip, (end[0] - nx * 1.6, end[1] - ny * 1.6)])
            B.part(needle, STEEL_D, iw=0.035, bevel=False)
        else:
            holder = c.poly([(end[0] + nx * 3.8, end[1] + ny * 3.8), (end[0] + 10 * math.cos(a) + nx * 3.8, end[1] + 10 * math.sin(a) + ny * 3.8),
                             (end[0] + 10 * math.cos(a) - nx * 3.8, end[1] + 10 * math.sin(a) - ny * 3.8),
                             (end[0] - nx * 3.8, end[1] - ny * 3.8)])
            B.part(holder, GRAPE, iw=0.035, bevel=False)
            lead = c.poly([(end[0] + 10 * math.cos(a) + nx * 2, end[1] + 10 * math.sin(a) + ny * 2), tip,
                           (end[0] + 10 * math.cos(a) - nx * 2, end[1] + 10 * math.sin(a) - ny * 2)])
            B.part(lead, GRAPHITE, iw=0.03, bevel=False)
    B.part(c.rrect(2, 42, 24, 54, 4), GRAPE, fill=cyl_fill(c, 42, 54, light(GRAPE, 0.35), GRAPE, dark(GRAPE, 0.3)))
    B.ink(inter(union(*[c.stroke([(x, 42), (x, 54)], 0.6) for x in range(6, 24, 3)]), c.rrect(2, 42, 24, 54, 4)), 0.3)
    B.part(c.circle(hinge[0], hinge[1], 10), STEEL, gloss=True)
    B.part(c.circle(hinge[0], hinge[1], 4), STEEL_D, iw=0.03, bevel=False)


def sp_set_square(B):
    c = B.c
    outer = c.poly([(6, 6), (6, 154), (154, 154)], rr=[5, 5, 5])
    inner = c.poly([(30, 62), (30, 130), (98, 130)], rr=[3, 3, 3])
    tri = sub(outer, inner)
    B.part(tri, MINT, alpha=0.72, fill=lgrad(c.size, c.p(6, 6), c.p(154, 154), light(MINT, 0.35), MINT))
    rim = sub(tri, erode(tri, 2.0 * c.k))
    B.detail(rim, WHITE, 0.35)
    ticks = c.mask()
    for i in range(1, 29):
        y = 154 - i * 5
        L = 7 if i % 2 == 0 else 4
        ticks = union(ticks, c.stroke([(6, y), (6 + L, y)], 0.7, caps=False))
    for i in range(1, 29):
        x = 6 + i * 5
        L = 7 if i % 2 == 0 else 4
        ticks = union(ticks, c.stroke([(x, 154), (x, 154 - L)], 0.7, caps=False))
    B.detail(inter(ticks, tri), NAVY, 0.6)


def sp_pencil_mech(B):
    c = B.c
    y0, h = 3.0, 12.0
    yc = y0 + h / 2
    body = R(c, 16, y0, 128, y0 + h)
    B.part(body, GRAPE, fill=cyl_fill(c, y0, y0 + h, light(GRAPE, 0.4), GRAPE, dark(GRAPE, 0.3)), bevel=False)
    grip = R(c, 120, y0 - 0.6, 150, y0 + h + 0.6, 2)
    B.part(grip, NAVY_SOFT, fill=cyl_fill(c, y0, y0 + h, light(NAVY_SOFT, 0.3), NAVY_SOFT, dark(NAVY_SOFT, 0.35)),
           bevel=False)
    dots = c.mask()
    for gx in range(123, 149, 3):
        for gy in (y0 + 2, y0 + 5, y0 + 8, y0 + 11):
            dots = union(dots, c.circle(gx + (gy % 2), gy, 0.55))
    B.detail(inter(dots, grip), WHITE, 0.25)
    cone = c.poly([(150, y0 + 1.2), (164, yc - 1.4), (164, yc + 1.4), (150, y0 + h - 1.2)], rr=[0, 0.8, 0.8, 0])
    B.part(cone, STEEL, fill=cyl_fill(c, y0, y0 + h, STEEL_L, STEEL, STEEL_D), bevel=False)
    B.part(R(c, 163, yc - 0.6, 169, yc + 0.6), GRAPHITE, outline=False, bevel=False)
    B.part(c.rrect(2, y0 + 1.5, 18, y0 + h - 1.5, 3), PINK, bevel=False)
    B.part(R(c, 14, y0 + 0.5, 20, y0 + h - 0.5), STEEL, fill=cyl_fill(c, y0, y0 + h, STEEL_L, STEEL, STEEL_D),
           bevel=False)
    clip = c.rrect(20, y0 + 2.6, 58, y0 + 6.0, 1.6)
    B.part(clip, STEEL, iw=0.03, bevel=False)


def push_pin(B, col):
    c = B.c
    base = c.circle(30, 31, 26)
    B.part(base, col, fill=rgrad(c.size, *c.p(22, 22), 38 * c.k, light(col, 0.25), dark(col, 0.2)))
    top = c.circle(28, 29, 16)
    B.part(top, light(col, 0.12), fill=rgrad(c.size, *c.p(23, 23), 22 * c.k, light(col, 0.45), col), iw=0.04)
    B.hl(c.ellipse(23, 23, 6, 3.5, -40), 0.75)
    B.hl(inter(c.stroke(arc_pts(30, 31, 23, 195, 250, n=12), 1.6), base), 0.45)


def sp_push_pin_red(B):
    push_pin(B, RED)


def sp_push_pin_blue(B):
    push_pin(B, SKY)


def sp_polaroid(B):
    c = B.c
    frame = c.rrect(4, 4, 116, 136, 3)
    B.part(frame, PAPER, fill=lgrad(c.size, c.p(4, 4), c.p(116, 136), hexc("#FFFFFF"), hexc("#EFE9DD")))
    photo = R(c, 14, 14, 106, 104)
    B.part(photo, SKY, fill=vgrad(c.size, *[v * c.k for v in (14, 104)], light(SKY, 0.45), light(SKY, 0.1)),
           outline=False, bevel=False)
    B.detail(inter(c.circle(84, 38, 11), photo), SUN, 1.0)
    hills = union(c.ellipse(36, 112, 44, 30), c.ellipse(92, 116, 40, 26))
    B.detail(inter(hills, photo), MINT, 1.0)
    B.detail(inter(c.ellipse(40, 86, 18, 6), hills), light(MINT, 0.25), 0.6)
    pl = c.poly([(30, 52), (58, 40), (44, 58)], rr=0.5)
    B.detail(pl, WHITE, 1.0)
    B.detail(c.poly([(44, 58), (58, 40), (46, 52)]), hexc("#C9D6F0"), 1.0)
    B.detail(sub(c.rrect(13, 13, 107, 105, 1), photo), NAVY, 0.25)
    B.detail(c.stroke(wave_pts(30, 90, 120, 1.2, 2.5, n=30), 1.8), NAVY_SOFT, 0.5)


def sp_note_card(B):
    c = B.c
    card = c.rrect(4, 4, 146, 96, 3)
    B.part(card, PAPER, fill=lgrad(c.size, c.p(4, 4), c.p(146, 96), hexc("#FFFFFF"), hexc("#F2EBDD")))
    B.detail(R(c, 4, 22, 146, 23.4), CORAL, 0.9)
    for k in range(5):
        B.detail(R(c, 4, 36 + k * 12, 146, 37 + k * 12), SKY, 0.55)
    for k, (x1, y) in enumerate(((112, 31), (96, 43), (120, 55), (80, 67))):
        B.detail(c.stroke(wave_pts(14, x1, y, 1.1, 4 + k, n=40), 1.6), NAVY_SOFT, 0.55)


def sp_brush_round(B):
    c = B.c
    y0, h = 3.0, 14.0
    yc = y0 + h / 2
    handle = c.poly([(4, yc - 3.5), (118, y0 + 0.5), (118, y0 + h - 0.5), (4, yc + 3.5)], rr=[3, 0, 0, 3])
    B.part(handle, CORAL_D, fill=cyl_fill(c, y0, y0 + h, light(CORAL, 0.35), CORAL_D, dark(CORAL_D, 0.3)),
           bevel=False)
    fer = c.poly([(116, y0), (152, y0 + 1.6), (152, y0 + h - 1.6), (116, y0 + h)])
    B.part(fer, STEEL, fill=cyl_fill(c, y0, y0 + h, STEEL_L, STEEL, STEEL_D), bevel=False)
    for gx in (124, 129):
        B.ink(inter(c.stroke([(gx, y0), (gx, y0 + h)], 0.7), fer), 0.3)
    bristle = c.poly([(150, y0 + 1.8)] + bezier((150, y0 + 1.8), (176, y0 - 1.5), (186, yc - 2), (198, yc), 16)[1:] +
                     bezier((198, yc), (186, yc + 2), (176, y0 + h + 1.5), (150, y0 + h - 1.8), 16)[1:])
    B.part(bristle, hexc("#8C5A33"), fill=cyl_fill(c, y0, y0 + h, hexc("#C08A5A"), hexc("#8C5A33"), hexc("#5E3A1E")),
           bevel=False)
    paint_tip = inter(bristle, R(c, 176, 0, 200, 20))
    B.detail(paint_tip, SKY, 1.0)
    B.detail(inter(paint_tip, R(c, 176, 0, 200, yc - 0.5)), WHITE, 0.3)
    B.hl(c.stroke([(14, yc - 1.6), (112, y0 + 2.6)], 0.9), 0.5)


def sp_paint_pan(B):
    c = B.c
    tray = c.rrect(3, 3, 157, 77, 8)
    B.part(tray, PAPER, fill=lgrad(c.size, c.p(3, 3), c.p(157, 77), hexc("#FFFFFF"), hexc("#E3E8F2")))
    cols = [CORAL, ORANGE, SUN, MINT, SKY, GRAPE, BUBBLE, KRAFT]
    for i, col in enumerate(cols):
        x = 12 + (i % 4) * 36
        y = 11 + (i // 4) * 34
        pan = c.rrect(x, y, x + 30, y + 26, 5)
        B.part(pan, STEEL_L, iw=0.035, bevel=False)
        paint_m = c.rrect(x + 2.5, y + 2.5, x + 27.5, y + 23.5, 4)
        B.detail(paint_m, col, 1.0)
        B.detail(c.ellipse(x + 10, y + 9, 6, 3, -20), WHITE, 0.45)
        B.detail(sub(paint_m, shift(paint_m, -1.5 * c.k, -1.5 * c.k)), dark(col, 0.3), 0.5)


def sp_water_cup(B):
    c = B.c
    cx = cy = 60
    rim = c.circle(cx, cy, 56)
    B.part(rim, hexc("#E7F0FA"), alpha=0.92, fill=rgrad(c.size, *c.p(40, 40), 80 * c.k, hexc("#FFFFFF"),
                                                        hexc("#C9D9EE")))
    water = c.circle(cx, cy, 46)
    B.part(water, SKY, alpha=0.88, fill=rgrad(c.size, *c.p(50, 50), 60 * c.k, light(SKY, 0.45), SKY), iw=0.03,
           bevel=False)
    swirl = union(c.stroke([(cx + 24 * math.cos(math.radians(a)) * (0.4 + a / 600.0),
                             cy + 24 * math.sin(math.radians(a)) * (0.4 + a / 600.0)) for a in range(0, 360, 12)], 3.2))
    B.detail(inter(swirl, water), GRAPE, 0.55)
    B.detail(inter(c.circle(cx + 16, cy + 14, 8), water), BUBBLE, 0.45)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 51, 200, 250, n=12), 3.0), rim), 0.8)
    B.hl(inter(c.ellipse(cx - 18, cy - 20, 10, 5, -40), water), 0.55)


def sp_charcoal_stick(B):
    c = B.c
    rnd = random.Random(17)
    top = [(4 + i * 4.0, 3.5 + rnd.uniform(-1.2, 1.2)) for i in range(29)]
    bot = [(4 + i * 4.0, 16.5 + rnd.uniform(-1.2, 1.2)) for i in range(29)][::-1]
    stick = c.poly(top + [(118, 10)] + bot + [(2, 10)], rr=0.6)
    B.part(stick, CHARCOAL, fill=cyl_fill(c, 3, 17, hexc("#6E7488"), CHARCOAL, hexc("#1F222B")), bevel=False)
    specks = c.mask()
    for _ in range(40):
        specks = union(specks, c.circle(rnd.uniform(8, 116), rnd.uniform(6, 14), rnd.uniform(0.3, 0.8)))
    B.detail(inter(specks, stick), WHITE, 0.25)
    wrap = c.poly([(4, 2.0), (52, 1.5), (56, 18.5), (4, 18.0)], rr=0.8)
    B.part(wrap, KRAFT, fill=cyl_fill(c, 2, 18, hexc("#E6C49B"), KRAFT, hexc("#93673F")), bevel=False)
    B.detail(c.stroke([(20, 2), (24, 18)], 0.8), hexc("#8A5E37"), 0.5)


def sp_sharpener(B):
    c = B.c
    block = c.rrect(3, 3, 77, 49, 7)
    B.part(block, STEEL, fill=lgrad(c.size, c.p(3, 3), c.p(77, 49), STEEL_L, STEEL_D))
    ridges = c.mask()
    for x in range(8, 30, 4):
        ridges = union(ridges, c.stroke([(x, 8), (x, 44)], 1.1))
    B.detail(inter(ridges, block), NAVY, 0.25)
    blade = c.rrect(34, 10, 72, 36, 3)
    B.part(blade, hexc("#9EA9BF"), fill=lgrad(c.size, c.p(34, 10), c.p(72, 36), hexc("#DDE3EE"), hexc("#7F8BA4")),
           iw=0.035)
    B.part(c.circle(62, 23, 4), STEEL_L, iw=0.03, bevel=False)
    B.ink(c.stroke([(59.5, 23), (64.5, 23)], 0.9), 0.6)
    hole = c.ellipse(46, 42, 9, 4.5)
    B.detail(hole, GRAPHITE, 0.9)
    B.detail(inter(c.ellipse(46, 41, 6, 2.5), hole), WOOD_D, 0.6)


def sp_eraser_kneaded(B):
    c = B.c
    rnd = random.Random(23)
    pts = []
    for i in range(40):
        a = 2 * math.pi * i / 40
        r = 1.0 + 0.10 * math.sin(3 * a + 0.4) + 0.06 * math.sin(5 * a + 1.3) + rnd.uniform(-0.03, 0.03)
        pts.append((40 + 32 * r * math.cos(a), 30 + 23 * r * math.sin(a)))
    blob = c.poly(catmull(pts, n=4, closed=True))
    B.part(blob, hexc("#A3ACBF"), fill=rgrad(c.size, *c.p(30, 20), 60 * c.k, hexc("#D3D9E5"), hexc("#7E889D")))
    for (x, y, rx, ry, a) in ((30, 30, 7, 4, -20), (52, 22, 6, 3.5, 10), (50, 40, 5, 3, 30)):
        B.detail(inter(c.ellipse(x, y, rx, ry, a), blob), hexc("#7C869B"), 0.45)
        B.detail(inter(c.ellipse(x - 1.5, y - 1.5, rx * 0.7, ry * 0.5, a), blob), WHITE, 0.3)


def sp_bow_red(B):
    c = B.c
    cx, cy = 70, 46
    for side in (-1, 1):
        tail = c.poly([(cx + side * 4, cy + 6), (cx + side * 26, cy + 58), (cx + side * 18, cy + 52),
                       (cx + side * 13, cy + 62), (cx - side * 6, cy + 10)], rr=[2, 1, 2, 1, 2])
        B.part(tail, RED, fill=lgrad(c.size, c.p(cx, cy), c.p(cx + side * 26, cy + 60), light(RED, 0.15), dark(RED, 0.2)))
    for side in (-1, 1):
        loop = c.poly([(cx, cy - 2)] + bezier((cx, cy - 2), (cx + side * 30, cy - 46), (cx + side * 70, cy - 30),
                                               (cx + side * 62, cy + 4), 24)[1:] +
                      bezier((cx + side * 62, cy + 4), (cx + side * 56, cy + 28), (cx + side * 26, cy + 20), (cx, cy + 4),
                             24)[1:])
        B.part(loop, RED, fill=lgrad(c.size, c.p(cx, cy - 30), c.p(cx + side * 60, cy + 20), light(RED, 0.25),
                                       dark(RED, 0.15)))
        inner = c.ellipse(cx + side * 30, cy - 6, 15, 7, side * -18)
        B.detail(inter(inner, loop), dark(RED, 0.35), 0.6)
        B.hl(inter(c.stroke(bezier((cx + side * 10, cy - 12), (cx + side * 26, cy - 34), (cx + side * 50, cy - 30),
                                   (cx + side * 58, cy - 14), 16), 2.2), loop), 0.5)
    knot = c.rrect(cx - 11, cy - 11, cx + 11, cy + 12, 6)
    B.part(knot, RED, fill=lgrad(c.size, c.p(cx - 11, cy - 11), c.p(cx + 11, cy + 12), light(RED, 0.3), dark(RED, 0.1)))


def sp_ribbon_spool(B):
    c = B.c
    cx = cy = 56
    end = c.poly([(96, 52), (118, 58), (112, 66), (120, 74), (94, 68)], rr=[1, 1, 1, 1, 1])
    B.part(end, BUBBLE)
    fl = c.circle(cx, cy, 52)
    B.part(fl, PAPER, fill=rgrad(c.size, *c.p(38, 36), 78 * c.k, hexc("#FFFFFF"), hexc("#E2DCD0")))
    rib = sub(c.circle(cx, cy, 44), c.circle(cx, cy, 20))
    B.part(rib, BUBBLE, fill=rgrad(c.size, *c.p(44, 42), 60 * c.k, light(BUBBLE, 0.3), dark(BUBBLE, 0.12)), iw=0.035)
    rings = c.mask()
    for r in range(22, 44, 3):
        rings = union(rings, sub(c.circle(cx, cy, r + 0.3), c.circle(cx, cy, r - 0.3)))
    B.detail(inter(rings, rib), WHITE, 0.3)
    core = sub(c.circle(cx, cy, 20), c.circle(cx, cy, 9))
    B.part(core, KRAFT, iw=0.035, bevel=False)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 48, 200, 250, n=12), 2.0), fl), 0.7)


def sp_gift_tag(B):
    c = B.c
    pts = []
    for i in range(90):
        a = 2 * math.pi * i / 90
        rx, ry = 50, 28
        bump = 1.8 * (0.5 + 0.5 * math.cos(16 * a))
        pts.append((64 + (rx + bump) * math.cos(a), 34 + (ry + bump) * math.sin(a)))
    tag = c.poly(pts)
    B.part(tag, PAPER, fill=lgrad(c.size, c.p(14, 6), c.p(114, 62), hexc("#FFFFFF"), hexc("#F1E6D2")))
    B.detail(sub(c.ellipse(64, 34, 43, 22), c.ellipse(64, 34, 41.6, 20.6)), CORAL, 0.75)
    B.detail(c.poly(heart_pts(78, 36, 22)), CORAL, 1.0)
    B.part(sub(c.circle(28, 34, 6), c.circle(28, 34, 3)), GOLD, iw=0.03, bevel=False)
    string = catmull([(28, 34), (16, 24), (4, 26), (2, 38), (12, 46), (28, 34)], n=8)
    B.part(c.stroke(string, 1.8), CORAL_D, iw=0.025, bevel=False)
    for k, ww in enumerate((24, 18)):
        B.detail(c.stroke([(40, 28 + k * 10), (40 + ww, 28 + k * 10)], 1.8), NAVY_SOFT, 0.5)


def sp_crane_paper(B):
    """Origami crane, the classic silhouette: diamond body, raised wings, neck and tail."""
    c = B.c
    lt, md, dk = hexc("#FFFFFF"), hexc("#E2E8F5"), hexc("#B4C0DC")
    facets = [
        ([(62, 86), (44, 20), (76, 80)], dk),                  # far wing
        ([(58, 94), (24, 30), (72, 98)], md),                  # neck
        ([(24, 30), (8, 44), (30, 38)], dk),                   # head
        ([(104, 94), (144, 24), (90, 98)], lt),                # tail
        ([(56, 94), (82, 80), (82, 110)], md),                 # body left
        ([(82, 80), (108, 94), (82, 110)], dk),                # body right
        ([(64, 88), (106, 6), (86, 88)], lt),                  # near wing, lit facet
        ([(86, 88), (106, 6), (102, 88)], md),                 # near wing, shaded facet
    ]
    sil = c.mask()
    for pts, col in facets:
        sil = union(sil, c.poly(pts))
    sil = smooth(sil, 0.25 * c.k)
    B.part(sil, lt, grad=False, bevel=False)
    for pts, col in facets:
        B.detail(c.poly(pts), col, 1.0)
    edges = c.mask()
    for pts, col in facets:
        edges = union(edges, c.stroke(pts + [pts[0]], 0.8))
    B.detail(inter(edges, erode(sil, 0.5 * c.k)), NAVY_SOFT, 0.5)


def sp_moon_paper(B):
    c = B.c
    moon = sub(c.circle(62, 66, 58), c.circle(92, 46, 50))
    B.part(moon, GOLD, fill=lgrad(c.size, c.p(10, 20), c.p(90, 120), GOLD_L, GOLD_D), bevel=True)
    fold = inter(sub(c.circle(62, 66, 40), c.circle(86, 50, 42)), moon)
    B.detail(fold, WHITE, 0.25)
    folds = c.mask()
    for a in (150, 175, 200, 225):
        rr = math.radians(a)
        folds = union(folds, c.stroke([(62 + 20 * math.cos(rr), 66 + 20 * math.sin(rr)),
                                      (62 + 58 * math.cos(rr), 66 + 58 * math.sin(rr))], 0.8))
    B.detail(inter(folds, moon), GOLD_D, 0.4)
    for (x, y, r) in ((24, 74, 3), (40, 104, 2.4)):
        B.detail(c.poly(star_pts(x, y, r * 2, r * 0.9, 4, -90)), WHITE, 0.6)


def sp_star_paper(B):
    facet_star(B, 50, 52, 46, 0.48, hexc("#FFF6D8"), hexc("#F2D894"), rr=3)


def sp_crown_paper(B):
    c = B.c
    pts = [(8, 92), (8, 30), (40, 58), (70, 10), (100, 58), (132, 30), (132, 92)]
    cr = c.poly(pts, rr=[4, 3, 4, 3, 4, 3, 4])
    B.part(cr, GOLD, fill=lgrad(c.size, c.p(8, 10), c.p(132, 92), GOLD_L, GOLD_D))
    sh = c.mask()
    sh = union(sh, c.poly([(70, 10), (100, 58), (70, 92)]), c.poly([(132, 30), (132, 92), (100, 58)]),
               c.poly([(8, 30), (40, 58), (8, 92)]))
    B.detail(inter(sh, cr), GOLD_D, 0.35)
    band = inter(cr, R(c, 0, 70, 140, 92))
    B.detail(band, dark(GOLD, 0.15), 0.6)
    for x, col in ((32, CORAL), (70, SKY), (108, MINT)):
        g = c.poly(reg_poly(x, 81, 6.5, 4, 0), rr=1)
        B.part(g, col, iw=0.03, bevel=False)
    for (x, y) in ((8, 30), (70, 10), (132, 30)):
        B.part(c.circle(x, y, 6), GOLD_L, iw=0.03)


def sp_gem_paper(B):
    c = B.c
    cx, cy = 50, 46
    outer = reg_poly(cx, cy, 44, 8, -90 + 22.5)
    table = reg_poly(cx, cy, 20, 8, -90 + 22.5)
    gem = c.poly(outer)
    B.part(gem, SKY, grad=False, bevel=False)
    shades = [light(CYAN, 0.45), CYAN, dark(SKY, 0.05), SKY_D, dark(SKY_D, 0.15), SKY_D, SKY, light(CYAN, 0.25)]
    for i in range(8):
        a, b = outer[i], outer[(i + 1) % 8]
        ta, tb = table[i], table[(i + 1) % 8]
        B.detail(c.poly([a, b, tb, ta]), shades[i], 1.0)
    B.detail(c.poly(table), light(CYAN, 0.55), 1.0)
    edges = c.mask()
    for i in range(8):
        edges = union(edges, c.stroke([outer[i], table[i]], 0.8))
    edges = union(edges, c.stroke(table + [table[0]], 0.8))
    B.detail(inter(edges, gem), NAVY, 0.35)
    B.hl(c.poly(star_pts(cx - 8, cy - 10, 7, 1.8, 4, -90)), 0.9)


def sp_gold_seal(B):
    c = B.c
    cx, cy = 50, 44
    for side in (-1, 1):
        tail = c.poly([(cx + side * 6, cy + 10), (cx + side * 22, cy + 52), (cx + side * 14, cy + 46),
                       (cx + side * 6, cy + 56), (cx - side * 6, cy + 14)], rr=[1, 1, 1, 1, 1])
        B.part(tail, CORAL_D)
    seal = c.poly(burst_pts(cx, cy, 40, 35, 20))
    B.part(seal, GOLD, fill=lgrad(c.size, c.p(cx - 40, cy - 40), c.p(cx + 40, cy + 40), GOLD_L, GOLD_D))
    B.detail(sub(c.circle(cx, cy, 30), c.circle(cx, cy, 27.5)), GOLD_D, 0.7)
    B.detail(c.circle(cx, cy, 27.5), light(GOLD, 0.1), 0.6)
    st = c.poly(star_pts(cx, cy, 15, 6.6, 5, -90), rr=[1.2, 0.6] * 5)
    B.detail(shift(st, 0.6 * c.k, 0.8 * c.k), GOLD_D, 0.9)
    B.detail(st, GOLD_L, 1.0)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 33, 200, 250, n=12), 1.8), seal), 0.6)


def burst_pts(cx, cy, R_, r, n, rot=-90.0):
    pts = []
    for i in range(2 * n):
        a = math.radians(rot + i * 180.0 / n)
        rr = R_ if i % 2 == 0 else r
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


# --------------------------------------------------------------------------
# Gameplay sprites
# --------------------------------------------------------------------------
def badge(B, center_col, glyph_fn):
    c = B.c
    cx = cy = 12.0
    rim = c.circle(cx, cy, 11.0)
    B.part(rim, PAPER, fill=rgrad(c.size, *c.p(8, 7), 18 * c.k, hexc("#FFFFFF"), hexc("#DCE2EE")), iw=0.05)
    face = c.circle(cx, cy, 8.4)
    B.part(face, center_col, fill=rgrad(c.size, *c.p(9.5, 8.5), 11 * c.k, light(center_col, 0.3),
                                         dark(center_col, 0.12)), iw=0.05)
    glyph_fn(B, cx, cy)
    B.hl(inter(c.stroke(arc_pts(cx, cy, 9.8, 200, 250, n=12), 0.55), rim), 0.9)


def g_bolt(B, cx, cy):
    c = B.c
    p = [(0.18, -0.50), (-0.28, 0.06), (-0.02, 0.06), (-0.16, 0.50), (0.30, -0.08), (0.04, -0.08)]
    s = 14.0
    B.part(c.poly([(cx + x * s, cy + y * s) for x, y in p], rr=0.6), SUN, iw=0.045,
           fill=vgrad(c.size, (cy - 7) * c.k, (cy + 7) * c.k, hexc("#FFF0A0"), SUN))


def g_shield(B, cx, cy):
    c = B.c
    w, h, top = 9.0, 11.0, cy - 5.8
    hw = w / 2
    right = bezier((cx + hw, top + h * 0.12), (cx + hw, top + h * 0.62), (cx + hw * 0.55, top + h * 0.86), (cx, top + h), 16)
    left = bezier((cx, top + h), (cx - hw * 0.55, top + h * 0.86), (cx - hw, top + h * 0.62), (cx - hw, top + h * 0.12), 16)
    topc = qbezier((cx - hw, top + h * 0.12), (cx, top - h * 0.06), (cx + hw, top + h * 0.12), 12)
    sh = c.poly(topc[:-1] + right[:-1] + left[:-1])
    B.part(sh, WHITE, iw=0.045, fill=vgrad(c.size, top * c.k, (top + h) * c.k, hexc("#FFFFFF"), hexc("#D9E6FA")))
    B.detail(inter(sh, R(c, cx, 0, 30, 30)), SKY, 0.18)


def g_bomb(B, cx, cy):
    c = B.c
    B.part(c.stroke(qbezier((cx + 2.4, cy - 3.6), (cx + 3.6, cy - 6.2), (cx + 5.6, cy - 5.6), 10), 0.8), KRAFT, iw=0.03,
           bevel=False)
    body = c.circle(cx - 0.6, cy + 1.0, 4.9)
    B.part(body, NAVY_SOFT, fill=rgrad(c.size, *c.p(cx - 2.2, cy - 0.8), 7 * c.k, light(NAVY_SOFT, 0.35), NAVY),
           iw=0.045)
    B.part(c.rrect(cx + 0.6, cy - 4.6, cx + 3.6, cy - 2.4, 0.6), STEEL, iw=0.03, bevel=False)
    B.hl(c.ellipse(cx - 2.4, cy - 1.0, 1.4, 0.8, -35), 0.7)
    B.part(c.poly(star_pts(cx + 5.8, cy - 5.8, 2.6, 1.1, 5, -90), rr=[0.3, 0.2] * 5), SUN, iw=0.03, bevel=False)


def g_snow(B, cx, cy):
    c = B.c
    m = c.mask()
    Rr, w = 6.6, 1.25
    for i in range(6):
        a = math.radians(-90 + 60 * i)
        tip = (cx + Rr * math.cos(a), cy + Rr * math.sin(a))
        m = union(m, c.stroke([(cx, cy), tip], w))
        b = (cx + Rr * 0.58 * math.cos(a), cy + Rr * 0.58 * math.sin(a))
        for side in (-1, 1):
            aa = a + side * math.radians(48)
            m = union(m, c.stroke([b, (b[0] + Rr * 0.32 * math.cos(aa), b[1] + Rr * 0.32 * math.sin(aa))], w * 0.85))
    B.part(m, WHITE, iw=0.04, bevel=False)


def sp_power_speed(B):
    badge(B, ORANGE, g_bolt)


def sp_power_shield(B):
    badge(B, SKY, g_shield)


def sp_power_bomb(B):
    badge(B, GRAPE, g_bomb)


def sp_power_freeze(B):
    badge(B, CYAN, g_snow)


def sp_token_star(B):
    facet_star(B, 10.0, 10.6, 9.2, 0.5, GOLD, GOLD_D, rr=0.9)
    c = B.c
    B.hl(c.ellipse(7.2, 7.0, 1.2, 0.7, -35), 0.8)


def sp_hazard_scissors(B):
    c = B.c
    cx = cy = 20.0
    for side in (-1, 1):
        a = math.radians(-90 + side * 34)
        tip = (cx + 18.5 * math.cos(a), cy + 18.5 * math.sin(a))
        nx, ny = -math.sin(a), math.cos(a)
        back = (cx - 2.5 * math.cos(a), cy - 2.5 * math.sin(a))
        w = 2.2
        P = [(back[0] + nx * w * 0.6, back[1] + ny * w * 0.6),
             (cx + 7 * math.cos(a) + nx * w, cy + 7 * math.sin(a) + ny * w),
             tip,
             (cx + 9 * math.cos(a) - nx * 0.3, cy + 9 * math.sin(a) - ny * 0.3),
             (back[0] - nx * w * 0.5, back[1] - ny * w * 0.5)]
        bl = c.poly(P, rr=[0.6, 1.2, 0.3, 1.0, 0.6])
        B.part(bl, STEEL, fill=lgrad(c.size, c.p(cx, cy), c.p(*tip), STEEL_L, STEEL_D, step=8))
        B.hl(inter(c.stroke([v_lerp((cx, cy), tip, 0.2), v_lerp((cx, cy), tip, 0.85)], 0.35), bl), 0.8)
    for side in (-1, 1):
        a = math.radians(90 + side * 34)
        hc = (cx + 11.0 * math.cos(a), cy + 11.0 * math.sin(a))
        ring = sub(c.ellipse(hc[0], hc[1], 6.4, 5.2, math.degrees(a)), c.ellipse(hc[0], hc[1], 3.4, 2.4, math.degrees(a)))
        arm = c.stroke([(cx, cy), (cx + 5.5 * math.cos(a), cy + 5.5 * math.sin(a))], 3.2)
        B.part(union(ring, arm), RED, gloss=True)
    B.part(c.circle(cx, cy, 2.0), STEEL, iw=0.04)
    B.ink(c.stroke([(cx - 1.1, cy - 0.6), (cx + 1.1, cy + 0.6)], 0.4), 0.6)


def soft_sprite(size_px, kind):
    """Pure soft sprites (no outline): blob_shadow, glow, ring, sparkle."""
    N4 = size_px * S
    c = N4 / 2.0
    img = Image.new("RGBA", (N4, N4), (0, 0, 0, 0))
    if kind == "blob_shadow":
        a = radial_alpha((N4, N4), c, c, c * 0.98, 128, gamma=1.6, step=4)
        img = Image.new("RGBA", (N4, N4), (0, 0, 0, 0))
        img.putalpha(a)
    elif kind == "glow":
        a = radial_alpha((N4, N4), c, c, c * 0.98, 255, gamma=1.5, step=4)
        img = Image.new("RGBA", (N4, N4), (255, 255, 255, 0))
        img.putalpha(a)
    elif kind == "ring":
        m = sub(m_circle((N4, N4), c, c, c * 0.80), m_circle((N4, N4), c, c, c * 0.58))
        m = blur(m, c * 0.045)
        img = Image.new("RGBA", (N4, N4), (255, 255, 255, 0))
        img.putalpha(m)
    elif kind == "sparkle":
        pts = []
        n = 4
        for i in range(2 * n):
            a = math.radians(-90 + i * 180.0 / n)
            r = c * 0.92 if i % 2 == 0 else c * 0.16
            pts.append((c + r * math.cos(a), c + r * math.sin(a)))
        star_m = m_poly((N4, N4), round_poly(pts, [c * 0.03, c * 0.12] * n, seg=2))
        glow_a = radial_alpha((N4, N4), c, c, c * 0.55, 150, gamma=2.0, step=4)
        m = ImageChops.lighter(star_m, glow_a)
        img = Image.new("RGBA", (N4, N4), (255, 255, 255, 0))
        img.putalpha(m)
    return down(img, (size_px, size_px))


def confetti_square(px):
    N4 = px * S
    m = m_rrect((N4, N4), N4 * 0.06, N4 * 0.06, N4 * 0.94, N4 * 0.94, N4 * 0.16)
    img = Image.new("RGBA", (N4, N4), (255, 255, 255, 0))
    img.putalpha(m)
    return down(img, (px, px))


def confetti_strip(w, h):
    W4, H4 = w * S, h * S
    m = m_rrect((W4, H4), W4 * 0.03, H4 * 0.12, W4 * 0.97, H4 * 0.88, H4 * 0.2)
    img = Image.new("RGBA", (W4, H4), (255, 255, 255, 0))
    img.putalpha(m)
    return down(img, (w, h))


# name, cells (longest side of the sprite rect), canvas w x h in units of 0.1 cell, draw fn
PROPS = [
    # craft desk
    ("scissors_big", 20.0, 200, 92, sp_scissors_big),
    ("pencil_yellow", 20.0, 200, 20, sp_pencil_yellow),
    ("paper_clip", 8.0, 80, 28, sp_paper_clip),
    ("button_red", 6.0, 60, 60, sp_button_red),
    ("tape_roll", 10.0, 102, 100, sp_tape_roll),
    # kraft studio
    ("twine_spool", 10.0, 100, 72, sp_twine_spool),
    ("rubber_stamp", 9.0, 90, 90, sp_rubber_stamp),
    ("kraft_tag", 12.0, 120, 64, sp_kraft_tag),
    ("wax_seal", 8.0, 80, 80, sp_wax_seal),
    # notebook lane
    ("eraser_pink", 9.0, 90, 46, sp_eraser_pink),
    ("pen_blue", 19.0, 190, 19, sp_pen_blue),
    ("sticky_note", 12.0, 120, 120, sp_sticky_note),
    ("pencil_green", 18.0, 180, 19, sp_pencil_green),
    # blueprint bay
    ("ruler_wood", 22.0, 220, 34, sp_ruler_wood),
    ("compass_tool", 16.0, 160, 96, sp_compass_tool),
    ("set_square", 16.0, 160, 160, sp_set_square),
    ("pencil_mech", 17.0, 170, 18, sp_pencil_mech),
    # corkboard park
    ("push_pin_red", 6.0, 60, 60, sp_push_pin_red),
    ("push_pin_blue", 6.0, 60, 60, sp_push_pin_blue),
    ("polaroid", 14.0, 120, 140, sp_polaroid),
    ("note_card", 15.0, 150, 100, sp_note_card),
    # watercolor isle
    ("brush_round", 20.0, 200, 20, sp_brush_round),
    ("paint_pan", 16.0, 160, 80, sp_paint_pan),
    ("water_cup", 12.0, 120, 120, sp_water_cup),
    # sketchbook hills
    ("charcoal_stick", 12.0, 120, 20, sp_charcoal_stick),
    ("sharpener", 8.0, 80, 52, sp_sharpener),
    ("eraser_kneaded", 8.0, 80, 60, sp_eraser_kneaded),
    # gift wrap plaza
    ("bow_red", 14.0, 140, 112, sp_bow_red),
    ("ribbon_spool", 12.0, 122, 112, sp_ribbon_spool),
    ("gift_tag", 12.0, 120, 68, sp_gift_tag),
    # origami night
    ("crane_paper", 16.0, 154, 118, sp_crane_paper),
    ("moon_paper", 13.0, 124, 130, sp_moon_paper),
    ("star_paper", 10.0, 100, 100, sp_star_paper),
    # gold foil palace
    ("crown_paper", 14.0, 140, 100, sp_crown_paper),
    ("gem_paper", 10.0, 100, 92, sp_gem_paper),
    ("gold_seal", 10.0, 100, 100, sp_gold_seal),
]

# name, cells, canvas units, pixels per cell, draw fn
GAMEPLAY = [
    ("power_speed", 2.4, 24, 24, 80.0, sp_power_speed),
    ("power_shield", 2.4, 24, 24, 80.0, sp_power_shield),
    ("power_bomb", 2.4, 24, 24, 80.0, sp_power_bomb),
    ("power_freeze", 2.4, 24, 24, 80.0, sp_power_freeze),
    ("token_star", 2.0, 20, 20, 80.0, sp_token_star),
    ("hazard_scissors", 4.0, 40, 40, 64.0, sp_hazard_scissors),
]


# --------------------------------------------------------------------------
# Packing
# --------------------------------------------------------------------------
class MaxRects:
    def __init__(self, W, H):
        self.free = [(0, 0, W, H)]

    def insert(self, w, h):
        best = None
        for (fx, fy, fw, fh) in self.free:
            if w <= fw and h <= fh:
                short = min(fw - w, fh - h)
                long_ = max(fw - w, fh - h)
                key = (short, long_, fy, fx)
                if best is None or key < best[0]:
                    best = (key, (fx, fy))
        if best is None:
            return None
        x, y = best[1]
        self._split(x, y, w, h)
        return x, y

    def _split(self, x, y, w, h):
        out = []
        for (fx, fy, fw, fh) in self.free:
            if x >= fx + fw or x + w <= fx or y >= fy + fh or y + h <= fy:
                out.append((fx, fy, fw, fh))
                continue
            if x > fx:
                out.append((fx, fy, x - fx, fh))
            if x + w < fx + fw:
                out.append((x + w, fy, fx + fw - (x + w), fh))
            if y > fy:
                out.append((fx, fy, fw, y - fy))
            if y + h < fy + fh:
                out.append((fx, y + h, fw, fy + fh - (y + h)))
        # prune rectangles contained in others
        pruned = []
        for i, a in enumerate(out):
            contained = False
            for j, b in enumerate(out):
                if i != j and a[0] >= b[0] and a[1] >= b[1] and a[0] + a[2] <= b[0] + b[2] and a[1] + a[3] <= b[1] + b[3]:
                    if a != b or i > j:
                        contained = True
                        break
            if not contained and a[2] > 0 and a[3] > 0:
                pruned.append(a)
        self.free = pruned


def bleed(img):
    """Fill RGB of fully transparent texels with nearby opaque colours."""
    a = img.getchannel("A")
    out = img.copy()
    hole = a.point(lambda v: 255 if v == 0 else 0)
    filled_mask = a.point(lambda v: 255 if v > 0 else 0)
    for r in (1.5, 3.0, 6.0, 12.0):
        pm = img.convert("RGBa").filter(ImageFilter.GaussianBlur(r))
        un = pm.convert("RGBA")
        ok = un.getchannel("A").point(lambda v: 255 if v > 0 else 0)
        take = ImageChops.multiply(ok, ImageChops.subtract(hole, filled_mask))
        rgb = Image.composite(un, out, take)
        out = Image.merge("RGBA", (*rgb.split()[:3], a))
        filled_mask = ImageChops.lighter(filled_mask, take)
    return out


def build_atlas():
    sprites = []
    for name, cells, wu, hu, fn in PROPS:
        im = render_sprite(fn, wu, hu, PPC_PROPS)
        sprites.append((name, cells, im))
    for name, cells, wu, hu, ppc, fn in GAMEPLAY:
        im = render_sprite(fn, wu, hu, ppc)
        sprites.append((name, cells, im))
    sprites.append(("blob_shadow", 2.0, soft_sprite(128, "blob_shadow")))
    sprites.append(("glow", 2.0, soft_sprite(128, "glow")))
    sprites.append(("ring", 2.0, soft_sprite(160, "ring")))
    sprites.append(("sparkle", 1.0, soft_sprite(96, "sparkle")))
    sprites.append(("confetti_square", 0.4, confetti_square(32)))
    sprites.append(("confetti_strip", 0.6, confetti_strip(48, 14)))

    order = sorted(range(len(sprites)), key=lambda i: (-max(sprites[i][2].size), -sprites[i][2].size[0] * sprites[i][2].size[1]))
    packer = MaxRects(ATLAS, ATLAS)
    atlas = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
    meta = {}
    for i in order:
        name, cells, im = sprites[i]
        w, h = im.size
        pos = packer.insert(w + 2 * PAD, h + 2 * PAD)
        if pos is None:
            raise SystemExit("atlas overflow while placing %s (%dx%d)" % (name, w, h))
        x, y = pos[0] + PAD, pos[1] + PAD
        tile = Image.new("RGBA", (w + 2 * PAD, h + 2 * PAD), (0, 0, 0, 0))
        tile.paste(im, (PAD, PAD))
        tile = bleed(tile)
        atlas.paste(tile, (pos[0], pos[1]))
        meta[name] = {"x": x, "y": y, "w": w, "h": h, "cells": float(cells)}
    names = [s[0] for s in sprites]
    ordered = {n: meta[n] for n in names}
    return atlas, ordered


def atlas_preview(atlas, meta, path):
    W, H = atlas.size
    chk = Image.new("RGBA", (W, H), (236, 238, 244, 255))
    d = ImageDraw.Draw(chk)
    t = 32
    for y in range(0, H, t):
        for x in range(0, W, t):
            if (x // t + y // t) % 2:
                d.rectangle([x, y, x + t - 1, y + t - 1], fill=(206, 211, 222, 255))
    chk.alpha_composite(atlas)
    d = ImageDraw.Draw(chk)
    f = font(20)
    for name, r in meta.items():
        d.rectangle([r["x"] - 1, r["y"] - 1, r["x"] + r["w"], r["y"] + r["h"]], outline=(255, 107, 87, 150), width=1)
        d.text((r["x"] + 2, r["y"] + 1), name, font=f, fill=(31, 42, 74, 255))
    save(chk.convert("RGB"), path)
    return path


def main(parts=("grain", "atlas")):
    written = []
    if "grain" in parts:
        g = make_paper_grain()
        p = save(g, os.path.join(TEX_DIR, "paper_grain.png"))
        st = ImageStat.Stat(g)
        # tile 2x2 and compare the texel steps across the seams with steps inside the tile
        tiled = Image.new("L", (1024, 1024))
        for yy in (0, 512):
            for xx in (0, 512):
                tiled.paste(g, (xx, yy))
        rep = seam_report(g)
        tp = tiled.load()
        seam_mid = sum(abs(tp[511, y] - tp[512, y]) for y in range(1024)) / 1024.0
        print("paper_grain: mean %.1f std %.1f  seam x %.2f (inner %.2f)  seam y %.2f (inner %.2f)  2x2 mid-seam %.2f" % (
            st.mean[0], st.stddev[0], rep["seam_x"], rep["inner_x"], rep["seam_y"], rep["inner_y"], seam_mid))
        assert abs(st.mean[0] - 200) < 1.5 and abs(st.stddev[0] - 10) < 1.5
        assert rep["seam_x"] < rep["inner_x"] * 1.25 and rep["seam_y"] < rep["inner_y"] * 1.25, "grain not seamless"
        if os.environ.get("ART_PREVIEW_DIR"):
            tiled.save(os.path.join(os.environ["ART_PREVIEW_DIR"], "paper_grain_tiled.png"))
        written.append(p)
    if "atlas" in parts:
        atlas, meta = build_atlas()
        p = save(atlas, os.path.join(TEX_DIR, "atlas.png"))
        jp = os.path.join(TEX_DIR, "atlas.json")
        with open(jp, "w") as fh:
            json.dump({"width": ATLAS, "height": ATLAS, "sprites": meta}, fh, separators=(",", ":"))
            fh.write("\n")
        atlas_preview(atlas, meta, os.path.join(ART, "build", "atlas_preview.png"))
        used = sum((r["w"] + 2 * PAD) * (r["h"] + 2 * PAD) for r in meta.values())
        print("atlas: %d sprites, %.1f%% of the sheet used" % (len(meta), 100.0 * used / (ATLAS * ATLAS)))
        written += [p, jp]
    for p in written:
        print("wrote", rel(p))
    return written


if __name__ == "__main__":
    main(tuple(sys.argv[1:]) if len(sys.argv) > 1 else ("grain", "atlas"))
