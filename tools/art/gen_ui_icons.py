"""Sticker-style UI icons for Paper Clash 2.

Every icon is a bold glyph built from "paper parts" (palette fill with a soft
top light, a darker lip at the bottom and an ink outline), wrapped in a thick
white die-cut sticker border with a soft navy drop shadow.

Outputs: app/src/main/res/drawable-nodpi/<name>.png (192 x 192 RGBA)
         art/build/ui_icons_sheet.png (contact sheet for review)

Glyphs are designed in a 192-unit space; each glyph is then auto-fitted and
centred so all stickers share the same visual size and margins.
"""
import math
import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import *  # noqa: E402,F401,F403

SZ = 192           # output size
S = 4              # supersampling
N = SZ * S
OW = 5.0           # outer ink outline (px at 1x)
IW = 3.6           # inner ink outline between parts
BW = 8.0           # white sticker border
SH_DY, SH_BLUR, SH_A = 4.0, 3.6, 0.25
FIT_W, FIT_H = 168.0, 160.0   # max sticker size (incl. border) at 1x
FIT_CX, FIT_CY = 96.0, 92.5   # sticker centre (a little high: the shadow hangs below)

SAND = hexc("#FFE2B4")
PEACH = hexc("#FFD0A6")
SLATE = hexc("#8C9BC4")
STEEL = hexc("#C7D0E2")
PAPER = hexc("#FFF9EE")
CREAMY = hexc("#FFEFD2")
GOLD_HI = hexc("#FFE48A")
GREY = hexc("#C9D0E1")
WOOD = hexc("#D9A46B")
INK_PURPLE = hexc("#6A4BEF")


# --------------------------------------------------------------------------
# Icon builder
# --------------------------------------------------------------------------
class Icon:
    def __init__(self, c):
        self.c = c
        self.layers = []

    def part(self, mask, color, outline=True, lip=True, hl=True, grad=True, iw=None, fill=None, gloss=False,
             solid=True):
        self.layers.append(("part", mask, dict(color=color, outline=outline, lip=lip, hl=hl, grad=grad, iw=iw,
                                               fill=fill, gloss=gloss, solid=solid)))
        return mask

    def detail(self, mask, color, alpha=1.0, solid=False):
        self.layers.append(("detail", mask, dict(color=color, alpha=alpha, solid=solid)))
        return mask

    def ink(self, mask, alpha=1.0):
        return self.detail(mask, NAVY, alpha)

    def hl(self, mask, alpha=0.55):
        return self.detail(mask, WHITE, alpha)

    def silhouette(self):
        ms = [m for kind, m, sp in self.layers if sp.get("solid")]
        return union(*ms)


def draw_part(img, m, sp, s):
    col = sp["color"]
    if sp["outline"]:
        paint(img, NAVY, dilate(m, (sp["iw"] or IW) * s))
    bb = m.getbbox()
    if bb is None:
        return
    if sp["fill"] is not None:
        paint(img, sp["fill"], m)
    elif sp["grad"]:
        h = bb[3] - bb[1]
        paint(img, vgrad(img.size, bb[1] - h * 0.05, bb[3], light(col, 0.24), dark(col, 0.10)), m)
    else:
        paint(img, col, m)
    if sp["lip"]:
        k = min(6.5 * s, (bb[3] - bb[1]) * 0.22)
        band = sub(m, shift(m, 0, -k))
        paint(img, dark(col, 0.30), inter(blur(band, 1.2 * s), m), 0.55)
    if sp["hl"]:
        k = min(3.4 * s, (bb[3] - bb[1]) * 0.12)
        band = sub(m, shift(m, 0, k))
        paint(img, WHITE, inter(blur(band, 0.6 * s), m), 0.55)
    if sp["gloss"]:
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        g = m_ellipse(img.size, bb[0] + w * 0.33, bb[1] + h * 0.27, w * 0.2, h * 0.1, -30)
        paint(img, WHITE, inter(g, erode(m, 3 * s)), 0.45)


def build(fn, c):
    I = Icon(c)
    fn(I)
    return I


def render(fn):
    # pass 1: measure the glyph at 2x, then fit it into the sticker box
    m2 = 2.0
    c1 = Ctx(SZ, SZ, m2)
    sil = build(fn, c1).silhouette()
    bb = sil.getbbox()
    gx0, gy0, gx1, gy1 = [v / m2 for v in bb]
    gw, gh = gx1 - gx0, gy1 - gy0
    pad = 2 * (OW + BW)
    f = min((FIT_W - pad) / gw, (FIT_H - pad) / gh)
    gcx, gcy = (gx0 + gx1) / 2.0, (gy0 + gy1) / 2.0
    c = Ctx(SZ, SZ, k=f * S, ox=gcx - FIT_CX / f, oy=gcy - FIT_CY / f, size=(N, N))
    I = build(fn, c)
    sil = I.silhouette()
    ink = dilate(sil, OW * S)
    st = sticker_border(ink, BW * S)
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    paint(img, NAVY, blur(shift(st, 0, SH_DY * S), SH_BLUR * S), SH_A)
    paint(img, WHITE, st)
    # faint cool edge keeps the white border crisp on white/cream screens
    paint(img, hexc("#C9D3E8"), sub(st, erode(st, 1.0 * S)), 0.55)
    paint(img, NAVY, ink)
    for kind, m, sp in I.layers:
        if kind == "part":
            draw_part(img, m, sp, S)
        else:
            paint(img, sp["color"], m, sp["alpha"])
    return down(img, SZ)


# --------------------------------------------------------------------------
# Geometry helpers (design units)
# --------------------------------------------------------------------------
def heart_pts(cx, cy, w, n=160):
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((cx + x * w / 32.0, cy + (y - 2.5) * w / 32.0))
    return pts


def shield_pts(cx, top, w, h):
    hw = w / 2.0
    right = bezier((cx + hw, top + h * 0.12), (cx + hw, top + h * 0.62), (cx + hw * 0.55, top + h * 0.86), (cx, top + h),
                   24)
    left = bezier((cx, top + h), (cx - hw * 0.55, top + h * 0.86), (cx - hw, top + h * 0.62), (cx - hw, top + h * 0.12),
                  24)
    topc = qbezier((cx - hw, top + h * 0.12), (cx, top - h * 0.06), (cx + hw, top + h * 0.12), 16)
    return topc[:-1] + right[:-1] + left[:-1]


def bolt_pts(cx, cy, s):
    p = [(0.18, -0.50), (-0.28, 0.06), (-0.02, 0.06), (-0.16, 0.50), (0.30, -0.08), (0.04, -0.08), (0.18, -0.50)]
    return [(cx + x * s, cy + y * s) for x, y in p[:-1]]


def gear_pts(cx, cy, R, r, n=8, top=0.42, base=0.62):
    pts = []
    for i in range(n):
        a = 2 * math.pi * i / n - math.pi / 2
        ht = math.pi / n * top
        hb = math.pi / n * base
        pts += [(cx + r * math.cos(a - hb), cy + r * math.sin(a - hb)),
                (cx + R * math.cos(a - ht), cy + R * math.sin(a - ht)),
                (cx + R * math.cos(a + ht), cy + R * math.sin(a + ht)),
                (cx + r * math.cos(a + hb), cy + r * math.sin(a + hb))]
    return pts


def arrow_pts(x0, x1, cy, shaft, head_w, head_l):
    """Arrow pointing right from x0 to x1."""
    return [(x0, cy - shaft / 2), (x1 - head_l, cy - shaft / 2), (x1 - head_l, cy - head_w / 2), (x1, cy),
            (x1 - head_l, cy + head_w / 2), (x1 - head_l, cy + shaft / 2), (x0, cy + shaft / 2)]


def drop_pts(cx, cy, r, tip):
    """Teardrop: circle of radius r centred at (cx, cy) with a point at (cx, cy - tip)."""
    a = math.asin(min(0.999, r / tip))
    pts = [(cx, cy - tip)]
    start = -math.pi / 2 + (math.pi / 2 - a)
    for i in range(61):
        t = start + (2 * math.pi - 2 * (math.pi / 2 - a)) * i / 60
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    return pts


def burst_pts(cx, cy, R, r, n, rot=-90.0):
    return star_pts(cx, cy, R, r, n, rot)


def wave_pts(x0, x1, cy, amp, waves, n=80):
    return [(x0 + (x1 - x0) * i / n, cy + amp * math.sin(2 * math.pi * waves * i / n)) for i in range(n + 1)]


def arc_stroke(c, cx, cy, r, a0, a1, w):
    return c.stroke(arc_pts(cx, cy, r, a0, a1, n=48), w)


def arc_arrow(c, cx, cy, r, a0, a1, w, head_w, head_l):
    """Arc from a0 to a1 (degrees) with an arrowhead at a1."""
    body = c.stroke(arc_pts(cx, cy, r, a0, a1 - math.copysign(head_l * 0.35 / r * 57.3, a1 - a0), n=48), w)
    a = math.radians(a1)
    sgn = 1 if a1 > a0 else -1
    ex, ey = cx + r * math.cos(a), cy + r * math.sin(a)
    tx, ty = -math.sin(a) * sgn, math.cos(a) * sgn
    nx, ny = math.cos(a), math.sin(a)
    tip = (ex + tx * head_l * 0.65, ey + ty * head_l * 0.65)
    b1 = (ex - tx * head_l * 0.35 + nx * head_w / 2, ey - ty * head_l * 0.35 + ny * head_w / 2)
    b2 = (ex - tx * head_l * 0.35 - nx * head_w / 2, ey - ty * head_l * 0.35 - ny * head_w / 2)
    head = c.poly([tip, b1, b2], rr=3)
    return union(body, head)


def dashed(c, pts, w, dash, gap, offset=0.0):
    P = c.pts(pts)
    acc = resample_by_length(P)
    total = acc[-1]
    m = c.mask()
    s = offset * c.k
    while s < total:
        e = min(total, s + dash * c.k)
        if e - s > 0.5 * c.k:
            m_stroke(c.size, sub_polyline(P, acc, s, e), w * c.k, m=m)
        s = e + gap * c.k
    return m


def rect(c, x0, y0, x1, y1, r=0):
    return c.rrect(x0, y0, x1, y1, r) if r else c.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def star(c, cx, cy, R, ratio=0.5, rr=None, rot=-90.0, n=5):
    rr = rr if rr is not None else R * 0.12
    return c.poly(star_pts(cx, cy, R, R * ratio, n, rot), rr=[rr, rr * 0.6] * n)


def star_facets(I, cx, cy, R, ratio, color, rot=-90.0, alpha=0.35, n=5):
    """Folded-paper look: darken the right half of each star arm."""
    c = I.c
    pts = star_pts(cx, cy, R, R * ratio, n, rot)
    shade = c.mask()
    for i in range(0, 2 * n, 2):
        tip = pts[i]
        val = pts[(i + 1) % (2 * n)]
        shade = union(shade, c.poly([(cx, cy), tip, val]))
    return shade


def padlock(I, cx, cy, s, body_col=SUN, shackle_col=STEEL):
    c = I.c
    sh = arc_stroke(c, cx, cy - 0.30 * s, 0.30 * s, 180, 360, 0.13 * s)
    legs = union(c.stroke([(cx - 0.30 * s, cy - 0.30 * s), (cx - 0.30 * s, cy - 0.05 * s)], 0.13 * s),
                 c.stroke([(cx + 0.30 * s, cy - 0.30 * s), (cx + 0.30 * s, cy - 0.05 * s)], 0.13 * s))
    I.part(union(sh, legs), shackle_col, lip=False)
    body = c.rrect(cx - 0.48 * s, cy - 0.10 * s, cx + 0.48 * s, cy + 0.50 * s, 0.12 * s)
    I.part(body, body_col, gloss=True)
    kh = union(c.circle(cx, cy + 0.14 * s, 0.085 * s),
               c.poly([(cx - 0.05 * s, cy + 0.16 * s), (cx + 0.05 * s, cy + 0.16 * s), (cx + 0.035 * s, cy + 0.34 * s),
                       (cx - 0.035 * s, cy + 0.34 * s)], rr=0.01 * s))
    I.ink(kh)


def speaker(I, cx, cy, s, col=SKY):
    c = I.c
    box = c.rrect(cx - 0.50 * s, cy - 0.20 * s, cx - 0.18 * s, cy + 0.20 * s, 0.06 * s)
    cone = c.poly([(cx - 0.24 * s, cy - 0.20 * s), (cx + 0.10 * s, cy - 0.46 * s), (cx + 0.10 * s, cy + 0.46 * s),
                   (cx - 0.24 * s, cy + 0.20 * s)], rr=0.06 * s)
    I.part(union(box, cone), col)


def phone(I, cx, cy, s, col=NAVY_SOFT):
    c = I.c
    body = c.rrect(cx - 0.27 * s, cy - 0.46 * s, cx + 0.27 * s, cy + 0.46 * s, 0.10 * s)
    I.part(body, col)
    scr = c.rrect(cx - 0.19 * s, cy - 0.34 * s, cx + 0.19 * s, cy + 0.30 * s, 0.04 * s)
    I.part(scr, SKY, outline=False, lip=False)
    I.detail(c.circle(cx, cy + 0.38 * s, 0.035 * s), WHITE, 0.9)


def slash(I, x0, y0, x1, y1, w, col=CORAL):
    I.part(I.c.stroke([(x0, y0), (x1, y1)], w), col, lip=False)


def note(I, cx, cy, s, col=GRAPE):
    """Beamed pair of eighth notes."""
    c = I.c
    h1 = c.ellipse(cx - 0.30 * s, cy + 0.30 * s, 0.17 * s, 0.13 * s, -22)
    h2 = c.ellipse(cx + 0.30 * s, cy + 0.20 * s, 0.17 * s, 0.13 * s, -22)
    st1 = c.stroke([(cx - 0.16 * s, cy + 0.28 * s), (cx - 0.16 * s, cy - 0.30 * s)], 0.09 * s, caps=False)
    st2 = c.stroke([(cx + 0.44 * s, cy + 0.18 * s), (cx + 0.44 * s, cy - 0.40 * s)], 0.09 * s, caps=False)
    beam = c.poly([(cx - 0.205 * s, cy - 0.27 * s), (cx + 0.485 * s, cy - 0.47 * s), (cx + 0.485 * s, cy - 0.30 * s),
                   (cx - 0.205 * s, cy - 0.10 * s)], rr=0.02 * s)
    I.part(union(h1, h2, st1, st2, beam), col)


def snowflake_mask(c, cx, cy, R, w):
    m = c.mask()
    for i in range(6):
        a = math.radians(-90 + 60 * i)
        tip = (cx + R * math.cos(a), cy + R * math.sin(a))
        m = union(m, c.stroke([(cx, cy), tip], w))
        for frac, bl in ((0.55, 0.30), (0.80, 0.20)):
            b = (cx + R * frac * math.cos(a), cy + R * frac * math.sin(a))
            for side in (-1, 1):
                aa = a + side * math.radians(48)
                m = union(m, c.stroke([b, (b[0] + R * bl * math.cos(aa), b[1] + R * bl * math.sin(aa))], w * 0.8))
    return union(m, c.poly(reg_poly(cx, cy, w * 1.1, 6, -90)))


def scissors_parts(I, cx, cy, s, ang=0.0, open_deg=28.0, handle=CORAL, blade=STEEL):
    """Scissors with the pivot at (cx, cy), blades up, rotated by ang degrees."""
    c = I.c

    def R(p):
        return v_rot(p, ang, (cx, cy))

    for side in (1, -1):
        th = side * open_deg / 2.0
        P = [(cx - 0.085 * s, cy + 0.06 * s), (cx + 0.09 * s, cy + 0.06 * s), (cx + 0.016 * s, cy - 0.64 * s)]
        P = [R(v_rot(p, th, (cx, cy))) for p in P]
        I.part(c.poly(P, rr=[3, 3, 4]), blade, lip=False)
        # edge highlight along the blade
        I.detail(c.stroke([P[0], v_lerp(P[0], P[2], 0.85)], 0.02 * s), WHITE, 0.6)
    for side in (-1, 1):
        hc = R((cx + side * 0.19 * s, cy + 0.36 * s))
        ring = sub(c.ellipse(hc[0], hc[1], 0.165 * s, 0.195 * s, ang + side * 14),
                   c.ellipse(hc[0], hc[1], 0.08 * s, 0.105 * s, ang + side * 14))
        arm = c.stroke([R((cx, cy + 0.02 * s)), R((cx + side * 0.13 * s, cy + 0.22 * s))], 0.11 * s)
        I.part(union(ring, arm), handle)
    pv = R((cx, cy))
    I.part(c.circle(pv[0], pv[1], 0.055 * s), SILVER, lip=False, iw=2.4)


# --------------------------------------------------------------------------
# Navigation
# --------------------------------------------------------------------------
def ic_nav_home(I):
    c = I.c
    I.part(c.poly([(118, 46), (140, 46), (140, 86), (118, 70)], rr=4), CORAL_D)
    I.part(c.poly([(46, 88), (146, 88), (146, 160), (46, 160)], rr=12), SAND)
    I.part(c.poly([(22, 100), (96, 34), (170, 100)], rr=[12, 14, 12]), CORAL, gloss=True)
    I.part(c.poly([(78, 112), (114, 112), (114, 160), (78, 160)], rr=[18, 18, 0, 0]), SKY)
    I.detail(c.circle(105, 138, 3.8), NAVY)
    I.part(c.rrect(56, 108, 70, 122, 3), hexc("#FFF6D6"), lip=False, hl=False, iw=3)


def ic_nav_levels(I):
    c = I.c
    tile = c.poly(rot_pts([(34, 52), (158, 52), (158, 162), (34, 162)], -6, (96, 107)), rr=24)
    I.part(tile, MINT)
    path = catmull([(52, 146), (78, 140), (86, 122), (70, 106), (84, 90), (112, 92), (120, 76)], n=18)
    I.detail(dashed(c, path, 8.5, 11, 7, 2), WHITE, 0.95)
    I.part(c.circle(52, 146, 9.5), SUN, lip=False)
    I.part(c.stroke([(120, 84), (120, 24)], 7), NAVY_SOFT, lip=False, hl=False)
    I.part(c.poly([(123, 24), (162, 35), (123, 50)], rr=[2, 5, 2]), CORAL, gloss=True)
    I.part(c.ellipse(120, 86, 12, 5), MINT_D, lip=False, hl=False, iw=2.5)


def ic_nav_shop(I):
    c = I.c
    I.part(arc_stroke(c, 96, 70, 28, 180, 360, 10), CORAL_D, lip=False)
    bag = c.poly([(40, 66), (152, 66), (160, 160), (32, 160)], rr=[8, 8, 16, 16])
    I.part(bag, CORAL, gloss=True)
    I.detail(inter(bag, rect(c, 30, 62, 162, 80)), WHITE, 0.25)
    I.part(star(c, 96, 118, 25, 0.5, rr=4), SUN, iw=3)
    I.detail(c.circle(68, 80, 4.5), NAVY)
    I.detail(c.circle(124, 80, 4.5), NAVY)


def ic_nav_album(I):
    c = I.c
    I.part(c.rrect(58, 42, 156, 160, 12), PAPER, lip=False)
    I.detail(union(*[c.stroke([(146, 56 + 18 * k), (146, 64 + 18 * k)], 2.2) for k in range(5)]), GREY, 0.9)
    cover = c.rrect(36, 32, 140, 152, 14)
    I.part(cover, GRAPE, gloss=True)
    I.detail(inter(cover, rect(c, 36, 32, 56, 152)), GRAPE_D, 0.85)
    I.part(c.circle(98, 90, 31), WHITE, lip=False, iw=2.6)
    I.part(star(c, 98, 92, 23, 0.5, rr=3.5), SUN, iw=2.6)
    I.detail(c.stroke([(72, 136), (124, 136)], 5), WHITE, 0.5)


def ic_nav_quests(I):
    c = I.c
    I.part(c.rrect(46, 36, 146, 156, 6), PAPER, lip=False)
    I.part(c.rrect(34, 24, 158, 48, 12), KRAFT)
    I.part(c.rrect(34, 144, 158, 168, 12), KRAFT)
    for k, y in enumerate((70, 96, 122)):
        box = c.rrect(58, y - 10, 78, y + 10, 5)
        I.part(box, MINT if k < 2 else SAND, lip=False, iw=2.6)
        if k < 2:
            I.detail(c.stroke([(62, y), (67, y + 5), (75, y - 6)], 4), WHITE, 1.0)
        I.detail(c.stroke([(90, y), (134 - 14 * (k == 1), y)], 7), NAVY_SOFT, 0.75)


# --------------------------------------------------------------------------
# Currency and rewards
# --------------------------------------------------------------------------
def plane_glyph(c, cx, cy, s, ang=-30.0):
    """Stylised paper plane (two facets) pointing right, rotated by ang."""
    P = [(0.55, 0.0), (-0.45, -0.42), (-0.12, 0.02), (-0.30, 0.40)]
    P = [v_rot((cx + x * s, cy + y * s), ang, (cx, cy)) for x, y in P]
    big = c.poly([P[0], P[1], P[2]], rr=0.03 * s)
    small = c.poly([P[0], P[2], P[3]], rr=0.03 * s)
    return big, small


def ic_coin(I):
    c = I.c
    I.part(c.circle(96, 104, 66), SUN_D, lip=False, hl=False)
    face = c.circle(96, 94, 66)
    I.part(face, SUN, lip=False, gloss=True)
    I.detail(sub(face, c.circle(96, 94, 54)), SUN_D, 0.35)
    I.detail(sub(c.circle(96, 94, 54), c.circle(96, 94, 50)), WHITE, 0.35)
    big, small = plane_glyph(c, 98, 96, 70, -28)
    I.detail(shift(union(big, small), 0.0, 2.5 * c.k), SUN_D, 0.85)
    I.detail(big, GOLD_HI, 1.0)
    I.detail(small, hexc("#FFD45E"), 1.0)


def coin_side(I, cx, y, rx, ry, th):
    c = I.c
    side = union(c.ellipse(cx, y + th, rx, ry), rect(c, cx - rx, y, cx + rx, y + th))
    I.part(side, SUN_D, lip=False, hl=False)
    top = c.ellipse(cx, y, rx, ry)
    I.part(top, SUN, lip=False, hl=False, iw=2.8)
    I.detail(sub(top, c.ellipse(cx, y, rx - 9, ry - 4)), SUN_D, 0.3)


def ic_coins_stack(I):
    c = I.c
    for y in (138, 118, 98):
        coin_side(I, 78, y, 46, 15, 13)
    I.part(c.circle(128, 122, 40), SUN_D, lip=False, hl=False)
    f = c.circle(124, 118, 40)
    I.part(f, SUN, lip=False, gloss=True)
    I.detail(sub(f, c.circle(124, 118, 31)), SUN_D, 0.35)
    I.part(star(c, 124, 120, 19, 0.5, rr=3), GOLD_HI, lip=False, hl=False, iw=2.2)


def ic_star(I):
    c = I.c
    m = star(c, 96, 100, 74, 0.5, rr=11)
    I.part(m, SUN, lip=True)
    I.detail(inter(star_facets(I, 96, 100, 74, 0.5, SUN), m), SUN_D, 0.35)
    I.detail(inter(c.ellipse(72, 70, 12, 7, -35), m), WHITE, 0.7)


def ic_star_empty(I):
    c = I.c
    m = star(c, 96, 100, 74, 0.5, rr=11)
    I.part(m, GREY, lip=True)
    I.detail(inter(star_facets(I, 96, 100, 74, 0.5, GREY), m), SLATE, 0.30)
    I.detail(inter(c.ellipse(72, 70, 11, 6, -35), m), WHITE, 0.6)


def ic_chest(I):
    c = I.c
    body = c.rrect(30, 96, 162, 160, 10)
    I.part(body, KRAFT)
    lid_pts = [(30, 100)] + arc_pts(96, 76, 66, 180, 360, n=40, ry=30) + [(162, 100)]
    lid = c.poly(lid_pts, rr=6)
    I.part(lid, light(KRAFT, 0.12), gloss=True)
    for x in (52, 140):
        band = inter(union(body, lid), rect(c, x - 9, 30, x + 9, 170))
        I.part(band, SUN, lip=False, iw=2.6)
    I.part(c.rrect(80, 84, 112, 120, 7), SUN, iw=3)
    I.ink(union(c.circle(96, 97, 5), c.poly([(93, 98), (99, 98), (101, 110), (91, 110)])))


def ic_gift(I):
    c = I.c
    box = c.rrect(40, 86, 152, 162, 8)
    I.part(box, SKY)
    I.part(inter(box, rect(c, 84, 80, 108, 170)), CORAL, lip=False, iw=2.6)
    for side in (-1, 1):
        loop = c.ellipse(96 + side * 27, 50, 28, 18, side * 26)
        I.part(loop, CORAL, lip=False)
        I.detail(c.ellipse(96 + side * 22, 53, 13, 7, side * 26), CORAL_D, 0.75)
    lid = c.rrect(32, 66, 160, 92, 9)
    I.part(lid, light(SKY, 0.08), gloss=True)
    I.part(inter(lid, rect(c, 82, 60, 110, 96)), CORAL, lip=False, hl=False, iw=2.6)
    I.part(c.rrect(83, 46, 109, 70, 9), CORAL_D, lip=False)


def ic_trophy(I):
    c = I.c
    for side in (-1, 1):
        hx = 96 + side * 50
        arc = arc_pts(hx, 66, 21, 90, 270, n=30) if side < 0 else arc_pts(hx, 66, 21, -90, 90, n=30)
        I.part(c.stroke(arc, 10), SUN_D, lip=False)
    cup_pts = [(46, 34), (146, 34)] + bezier((146, 34), (146, 88), (122, 106), (104, 110), 20)[1:] + \
        [(104, 124), (88, 124)] + bezier((88, 110), (70, 106), (46, 88), (46, 34), 20)
    I.part(c.poly(cup_pts, rr=6), SUN, gloss=True)
    I.part(c.rrect(78, 120, 114, 136, 4), SUN_D, lip=False)
    I.part(c.rrect(56, 132, 136, 162, 9), GRAPE)
    I.detail(c.rrect(76, 142, 116, 150, 4), WHITE, 0.6)
    I.part(star(c, 96, 68, 18, 0.5, rr=3), GOLD_HI, lip=False, iw=2.4)


def crown_mask(c, cx, base, w, h, k=1.0):
    hw = w / 2
    pts = [(cx - hw, base), (cx - hw, base - h * 0.78), (cx - hw * 0.48, base - h * 0.42), (cx, base - h),
           (cx + hw * 0.48, base - h * 0.42), (cx + hw, base - h * 0.78), (cx + hw, base)]
    return c.poly(pts, rr=[6 * k, 4 * k, 6 * k, 4 * k, 6 * k, 4 * k, 6 * k])


def ic_crown(I):
    c = I.c
    cr = crown_mask(c, 96, 150, 128, 104)
    I.part(cr, SUN, gloss=True)
    for (x, y) in ((32, 68.9), (96, 46), (160, 68.9)):
        I.part(c.circle(x, y, 10), SUN, lip=False)
    band = inter(cr, rect(c, 20, 124, 172, 160))
    I.part(band, SUN_D, lip=False, iw=2.6)
    for x, col in ((62, CORAL), (96, SKY), (130, MINT)):
        I.part(c.circle(x, 138, 8), col, lip=False, iw=2.4)


def ic_sticker(I):
    c = I.c
    body = c.poly([(36, 36), (156, 36), (156, 112), (112, 156), (36, 156)], rr=[22, 22, 4, 4, 22])
    I.part(body, BUBBLE, gloss=True)
    I.part(star(c, 88, 90, 30, 0.5, rr=5), SUN, lip=False, iw=3)
    flap = c.poly([(156, 112), (112, 156), (120, 118)], rr=[3, 3, 8])
    I.detail(blur(shift(flap, -2 * c.k, -2 * c.k), 2.5 * c.k), NAVY, 0.25)
    I.part(flap, hexc("#F4F6FB"), lip=False)


# --------------------------------------------------------------------------
# Controls
# --------------------------------------------------------------------------
def ic_play(I):
    c = I.c
    I.part(c.poly([(54, 32), (156, 96), (54, 160)], rr=[18, 16, 18]), MINT, gloss=True)


def ic_pause(I):
    c = I.c
    for x in (60, 132):
        I.part(c.rrect(x - 19, 34, x + 19, 158, 13), SKY, gloss=True)


def ic_restart(I):
    c = I.c
    I.part(arc_arrow(c, 96, 98, 50, -60, 225, 22, 48, 38), SKY)


def ic_next(I):
    c = I.c
    I.part(c.poly(arrow_pts(28, 166, 96, 40, 104, 62), rr=[9, 4, 9, 12, 9, 4, 9]), MINT, gloss=True)


def ic_back(I):
    c = I.c
    pts = [(192 - x, y) for x, y in arrow_pts(28, 166, 96, 40, 104, 62)]
    I.part(c.poly(pts, rr=[9, 4, 9, 12, 9, 4, 9]), SKY, gloss=True)


def ic_close(I):
    c = I.c
    m = union(c.stroke([(44, 44), (148, 148)], 38), c.stroke([(148, 44), (44, 148)], 38))
    I.part(m, CORAL, gloss=True)


def ic_check(I):
    c = I.c
    I.part(c.stroke([(36, 100), (78, 140), (156, 52)], 38), MINT, gloss=True)


def ic_share(I):
    c = I.c
    a, b, d = (132, 50), (60, 96), (132, 142)
    I.part(union(c.stroke([a, b], 16), c.stroke([b, d], 16)), GRAPE_D, lip=False)
    for p in (a, b, d):
        I.part(c.circle(p[0], p[1], 27), GRAPE, gloss=True)


def ic_settings(I):
    c = I.c
    g = c.poly(gear_pts(96, 96, 72, 56, 8), rr=5)
    I.part(sub(g, c.circle(96, 96, 22)), SKY, gloss=True)
    I.detail(sub(c.circle(96, 96, 34), c.circle(96, 96, 22)), SKY_D, 0.35)


def ic_info(I):
    c = I.c
    I.part(c.circle(96, 96, 70), SKY, gloss=True)
    I.part(c.circle(96, 56, 11), WHITE, outline=False, lip=False, hl=False)
    I.part(c.rrect(84, 76, 108, 140, 11), WHITE, outline=False, lip=False, hl=False)


def ic_lock(I):
    padlock(I, 96, 100, 128)


def ic_edit(I):
    c = I.c
    ang = -45.0
    cx, cy = 96, 96

    def R(pts):
        return rot_pts(pts, ang, (cx, cy))

    I.part(c.poly(R([(24, 80), (52, 80), (52, 112), (24, 112)]), rr=[12, 0, 0, 12]), BUBBLE, lip=False)
    I.part(c.poly(R([(50, 80), (68, 80), (68, 112), (50, 112)])), STEEL, lip=False)
    body = c.poly(R([(66, 80), (138, 80), (138, 112), (66, 112)]))
    I.part(body, SUN, lip=False)
    I.detail(inter(body, c.poly(R([(60, 102), (140, 102), (140, 114), (60, 114)]))), SUN_D, 0.55)
    I.detail(inter(body, c.poly(R([(60, 78), (140, 78), (140, 87), (60, 87)]))), WHITE, 0.45)
    I.part(c.poly(R([(136, 80), (170, 96), (136, 112)]), rr=[2, 4, 2]), WOOD, lip=False)
    I.part(c.poly(R([(158, 90.5), (170, 96), (158, 101.5)]), rr=[1, 3, 1]), NAVY_SOFT, lip=False, outline=False)


def ic_ad(I):
    c = I.c
    I.part(c.stroke([(78, 46), (60, 22)], 6), NAVY_SOFT, lip=False, hl=False)
    I.part(c.stroke([(114, 46), (132, 22)], 6), NAVY_SOFT, lip=False, hl=False)
    I.part(c.rrect(56, 140, 76, 160, 5), GRAPE_D, lip=False)
    I.part(c.rrect(116, 140, 136, 160, 5), GRAPE_D, lip=False)
    I.part(c.rrect(26, 44, 166, 148, 20), GRAPE, gloss=False)
    scr = c.rrect(42, 58, 150, 134, 12)
    I.part(scr, SKY, lip=False, iw=3)
    I.detail(inter(scr, c.poly([(42, 58), (110, 58), (60, 134), (42, 134)])), WHITE, 0.18)
    I.part(c.poly([(84, 76), (118, 96), (84, 116)], rr=[6, 6, 6]), WHITE, outline=False, lip=False, hl=False)


def ic_spin(I):
    c = I.c
    cx, cy, R = 96, 102, 64
    I.part(c.circle(cx, cy, R + 8), NAVY_SOFT, lip=False)
    cols = [CORAL, SUN, MINT, SKY, GRAPE, BUBBLE, SUN, MINT]
    for i, col in enumerate(cols):
        a0, a1 = -90 + i * 45 - 22.5, -90 + i * 45 + 22.5
        wedge = c.poly([(cx, cy)] + arc_pts(cx, cy, R, a0, a1, n=12))
        I.part(wedge, col, outline=False, lip=False, hl=False)
    I.detail(sub(c.circle(cx, cy, R), c.circle(cx, cy, R - 6)), NAVY, 0.25)
    for i in range(8):
        a = math.radians(-90 + i * 45 + 22.5)
        I.detail(c.stroke([(cx, cy), (cx + R * math.cos(a), cy + R * math.sin(a))], 2.4), WHITE, 0.9)
        b = math.radians(-90 + i * 45)
        I.detail(c.circle(cx + (R + 4) * math.cos(b), cy + (R + 4) * math.sin(b), 3), SUN, 1.0)
    I.part(c.circle(cx, cy, 15), PAPER, lip=False, iw=3)
    I.part(c.poly([(78, 22), (114, 22), (96, 54)], rr=[6, 6, 4]), CORAL)


def ic_rate(I):
    c = I.c
    I.part(c.poly(heart_pts(96, 96, 148)), BUBBLE, gloss=True)


def ic_profile(I):
    c = I.c
    face = c.circle(96, 96, 70)
    I.part(face, SUN, gloss=True)
    I.ink(c.ellipse(72, 86, 8, 11))
    I.ink(c.ellipse(120, 86, 8, 11))
    I.detail(c.circle(69, 82, 2.6), WHITE)
    I.detail(c.circle(117, 82, 2.6), WHITE)
    I.ink(c.stroke(arc_pts(96, 100, 30, 25, 155, n=24), 8))
    I.detail(c.ellipse(56, 114, 11, 7), BUBBLE, 0.8)
    I.detail(c.ellipse(136, 114, 11, 7), BUBBLE, 0.8)


def ic_help(I):
    c = I.c
    I.part(c.circle(96, 96, 70), GRAPE, gloss=True)
    I.part(c.text("?", 97, 102, 128), WHITE, outline=False, lip=False, hl=False)


# --------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------
def ic_sound_on(I):
    c = I.c
    speaker(I, 86, 96, 120)
    I.part(union(arc_stroke(c, 98, 96, 34, -42, 42, 11), arc_stroke(c, 98, 96, 60, -40, 40, 11)), SKY, lip=False)


def ic_sound_off(I):
    c = I.c
    speaker(I, 76, 96, 120, SLATE)
    I.part(union(c.stroke([(116, 74), (158, 118)], 15), c.stroke([(158, 74), (116, 118)], 15)), CORAL, lip=False)


def ic_music_on(I):
    note(I, 96, 98, 120)


def ic_music_off(I):
    note(I, 96, 98, 120, SLATE)
    slash(I, 40, 40, 152, 152, 15)


def vib_lines(I, cx, cy, s):
    c = I.c
    m = c.mask()
    for side in (-1, 1):
        for k, off in enumerate((0.40, 0.55)):
            x = cx + side * off * s
            h = (0.28 - k * 0.07) * s
            pts = [(x, cy - h), (x + side * 0.05 * s, cy - h / 2), (x - side * 0.03 * s, cy), (x + side * 0.05 * s, cy + h / 2),
                   (x, cy + h)]
            m = union(m, c.stroke(pts, 0.06 * s))
    return m


def ic_vibrate_on(I):
    phone(I, 96, 96, 140)
    I.part(vib_lines(I, 96, 96, 140), SUN, lip=False)


def ic_vibrate_off(I):
    phone(I, 96, 96, 140, SLATE)
    slash(I, 44, 40, 148, 152, 15)


def ic_graphics(I):
    c = I.c
    top_y, mid_y, bot_y = 54, 80, 156
    pts = [(46, mid_y), (70, top_y), (122, top_y), (146, mid_y), (96, bot_y)]
    gem = c.poly(pts, rr=[5, 5, 5, 5, 6])
    I.part(gem, SKY, lip=False)
    I.detail(inter(gem, c.poly([(46, mid_y), (146, mid_y), (146, 40), (46, 40)])), WHITE, 0.35)
    I.detail(inter(gem, c.poly([(96, bot_y), (122, mid_y), (146, mid_y)])), SKY_D, 0.45)
    I.detail(inter(gem, c.poly([(96, bot_y), (70, mid_y), (122, mid_y)])), WHITE, 0.15)
    ln = union(c.stroke([(46, mid_y), (146, mid_y)], 3), c.stroke([(70, top_y), (84, mid_y), (96, bot_y)], 3),
               c.stroke([(122, top_y), (108, mid_y), (96, bot_y)], 3))
    I.detail(inter(ln, gem), NAVY, 0.55)
    I.part(c.poly(star_pts(146, 40, 24, 7, 4, -90), rr=[3, 1.5] * 4), WHITE, lip=False, hl=False, iw=3)
    I.part(c.poly(star_pts(40, 132, 14, 4.5, 4, -90), rr=[2, 1] * 4), WHITE, lip=False, hl=False, iw=3)


def ic_controls(I):
    c = I.c
    I.part(c.stroke([(96, 104), (96, 56)], 16), STEEL, lip=False)
    I.part(c.ellipse(96, 132, 66, 30), GRAPE_D, lip=False, hl=False)
    I.part(c.ellipse(96, 120, 66, 30), GRAPE)
    I.part(c.ellipse(96, 112, 18, 8), NAVY_SOFT, lip=False, hl=False, iw=2.4)
    I.part(c.stroke([(96, 110), (96, 60)], 14), STEEL, lip=False)
    I.part(c.circle(96, 48, 26), CORAL, gloss=True)
    I.part(c.ellipse(140, 116, 10, 6), SUN, lip=False, iw=2.4)


def ic_privacy(I):
    c = I.c
    I.part(c.poly(shield_pts(96, 26, 136, 144)), MINT, gloss=True)
    padlock(I, 96, 100, 74, body_col=PAPER, shackle_col=PAPER)


def ic_terms(I):
    c = I.c
    doc = c.poly([(42, 26), (124, 26), (152, 54), (152, 166), (42, 166)], rr=[10, 4, 4, 10, 10])
    I.part(doc, PAPER, lip=False)
    I.part(c.poly([(124, 26), (124, 54), (152, 54)], rr=[2, 4, 2]), GREY, lip=False, iw=2.6)
    for k, y in enumerate((62, 84, 106)):
        I.detail(c.stroke([(60, y), (134 - (16 if k == 0 else 0) - 10 * (k == 2), y)], 7), NAVY_SOFT, 0.7)
    I.part(c.circle(118, 138, 18), CORAL, lip=False, iw=2.8)
    I.detail(c.stroke([(110, 138), (116, 144), (127, 132)], 4.5), WHITE)


def ic_hand(I):
    """Open left hand, palm towards the viewer (thumb on the right)."""
    c = I.c
    fingers = [(56, 54, 10.5), (79, 34, 11.5), (103, 26, 11.5), (127, 38, 11)]
    palm = c.rrect(44, 84, 142, 166, 34)
    fing = [c.rrect(x - w, top, x + w, 112, w) for x, top, w in fingers]
    thumb = c.stroke([(124, 140), (150, 110), (160, 92)], 25)
    I.part(union(palm, thumb, *fing), PEACH, gloss=True)
    for x, top, w in fingers:
        I.detail(c.ellipse(x, top + 12, w * 0.55, 6), WHITE, 0.45)


def ic_motion(I):
    c = I.c
    for k, (y, col) in enumerate(((56, SKY), (96, MINT), (136, GRAPE))):
        I.part(c.stroke(wave_pts(32, 160, y, 11, 1.5), 18), col, lip=False, gloss=False)


def ic_palette(I):
    c = I.c
    base = c.poly(catmull([(96, 28), (150, 40), (168, 92), (148, 146), (98, 164), (48, 150), (26, 104), (38, 54)],
                          n=12, closed=True))
    hole = c.ellipse(118, 128, 14, 11, -20)
    notch = c.circle(166, 120, 22)
    pal = sub(sub(base, hole), notch)
    I.part(pal, WOOD, gloss=True)
    for (x, y, col) in ((66, 62, CORAL), (104, 52, SUN), (138, 74, MINT), (54, 104, SKY), (78, 136, GRAPE)):
        I.part(c.circle(x, y, 13), col, lip=False, iw=2.6)


def ic_reset(I):
    c = I.c
    I.part(arc_arrow(c, 96, 98, 52, 230, -55, 22, 48, 38), CORAL)
    I.part(c.rrect(88, 70, 104, 108, 7), CORAL_D, lip=False, iw=3)
    I.part(c.circle(96, 124, 9), CORAL_D, lip=False, iw=3)


# --------------------------------------------------------------------------
# Power-ups / gameplay
# --------------------------------------------------------------------------
def ic_bolt(I):
    c = I.c
    m = c.poly(bolt_pts(96, 96, 150), rr=6)
    bb = m.getbbox()
    I.part(m, SUN, fill=vgrad(c.size, bb[1], bb[3], hexc("#FFE680"), ORANGE), gloss=True)


def ic_shield(I):
    c = I.c
    sh = c.poly(shield_pts(96, 24, 132, 148))
    I.part(sh, SKY, gloss=True)
    inner = c.poly(shield_pts(96, 46, 92, 104))
    I.detail(inner, light(SKY, 0.35), 0.55)
    I.detail(inter(inner, rect(c, 96, 0, 192, 192)), SKY_D, 0.25)


def ic_bomb(I):
    c = I.c
    I.part(c.stroke(qbezier((112, 50), (124, 30), (146, 34), 12), 7), KRAFT, lip=False, hl=False)
    body = c.circle(88, 110, 56)
    I.part(body, GRAPE, gloss=True)
    I.part(c.rrect(96, 44, 128, 66, 6), NAVY_SOFT, lip=False)
    drip = union(c.rrect(56, 132, 72, 170, 8), c.rrect(84, 140, 98, 176, 7), c.circle(64, 132, 8))
    I.part(inter(union(drip, body), rect(c, 0, 128, 192, 192)), INK_PURPLE, outline=False, lip=False, hl=False)
    I.part(c.poly(star_pts(152, 32, 22, 9, 5, -90), rr=[3, 2] * 5), SUN, lip=False)
    I.part(c.circle(152, 32, 7), WHITE, outline=False, lip=False, hl=False)


def ic_freeze(I):
    c = I.c
    I.part(snowflake_mask(c, 96, 96, 70, 15), CYAN, gloss=False)
    I.detail(c.poly(reg_poly(96, 96, 10, 6, -90)), WHITE, 0.8)


def ic_ink(I):
    c = I.c
    I.part(c.poly(drop_pts(92, 112, 48, 96)), GRAPE, gloss=True)
    for (x, y, r) in ((148, 128, 13), (142, 66, 8), (40, 64, 10), (44, 152, 7)):
        I.part(c.circle(x, y, r), GRAPE, lip=False)


def ic_scissors(I):
    scissors_parts(I, 96, 92, 150, ang=-35.0, open_deg=34)


def ic_clock(I):
    c = I.c
    I.part(c.circle(96, 100, 70), SKY, lip=False)
    face = c.circle(96, 100, 56)
    I.part(face, PAPER, lip=False, iw=3)
    for i in range(12):
        a = math.radians(i * 30)
        r0 = 44 if i % 3 else 40
        I.ink(c.stroke([(96 + r0 * math.cos(a), 100 + r0 * math.sin(a)), (96 + 49 * math.cos(a), 100 + 49 * math.sin(a))],
                       4 if i % 3 else 5.5), 0.75)
    I.ink(c.stroke([(96, 100), (96, 66)], 8))
    I.ink(c.stroke([(96, 100), (122, 112)], 8))
    I.part(c.circle(96, 100, 7), CORAL, lip=False, iw=2.4)
    I.part(c.rrect(84, 18, 108, 32, 5), SKY_D, lip=False)


def ic_target(I):
    c = I.c
    for r, col in ((70, CORAL), (52, WHITE), (34, CORAL), (16, WHITE)):
        I.part(c.circle(96, 98, r), col, lip=(r == 70), hl=(r == 70), iw=2.6, outline=(r == 70))
    I.detail(sub(c.circle(96, 98, 52), c.circle(96, 98, 49.5)), NAVY, 0.25)
    I.part(c.stroke([(96, 98), (150, 44)], 8), NAVY_SOFT, lip=False)
    I.part(c.poly([(150, 44), (168, 22), (172, 40), (164, 30)], rr=1), CORAL_D, lip=False)
    I.part(c.poly([(146, 30), (162, 28), (166, 48), (150, 50)], rr=2), SUN, lip=False)


def ic_pop(I):
    c = I.c
    I.part(c.poly(burst_pts(96, 96, 76, 50, 12, -90), rr=[6, 5] * 12), SUN, gloss=True)
    I.part(c.poly(burst_pts(96, 96, 46, 30, 9, -70), rr=[4, 3] * 9), BUBBLE, lip=False)
    I.detail(c.circle(96, 96, 12), WHITE, 0.9)


# --------------------------------------------------------------------------
# Boosters
# --------------------------------------------------------------------------
def ic_boost_big(I):
    c = I.c
    I.part(c.rrect(62, 62, 130, 130, 14), MINT, gloss=True)
    for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        ang = math.degrees(math.atan2(sy, sx))
        base = (96 + sx * 46, 96 + sy * 46)
        pts = [v_rot((base[0] + x, base[1] + y), ang, base) for x, y in arrow_pts(-10, 34, 0, 13, 36, 22)]
        I.part(c.poly(pts, rr=[3, 2, 3, 5, 3, 2, 3]), SUN, lip=False)


def ic_boost_shield(I):
    c = I.c
    I.part(c.poly(shield_pts(96, 24, 134, 150)), SKY, gloss=True)
    I.part(union(c.rrect(84, 58, 108, 136, 9), c.rrect(58, 85, 134, 109, 9)), WHITE, lip=False, hl=False, iw=3)


def ic_boost_ink(I):
    c = I.c
    bottle = c.poly([(70, 60), (122, 60), (122, 76), (152, 98), (152, 164), (40, 164), (40, 98), (70, 76)],
                    rr=[4, 4, 12, 14, 14, 14, 14, 12])
    I.part(bottle, hexc("#DDE7F7"), lip=False)
    ink = inter(erode(bottle, 6 * c.k), rect(c, 0, 92, 192, 192))
    bb = ink.getbbox()
    I.part(ink, GRAPE, outline=False, lip=False, fill=vgrad(c.size, bb[1], bb[3], hexc("#9A7BFF"), GRAPE_D))
    I.detail(c.rrect(58, 116, 134, 146, 7), PAPER, 0.95)
    I.detail(c.poly(drop_pts(96, 135, 7, 15)), GRAPE, 1.0)
    I.part(c.rrect(64, 30, 128, 64, 9), NAVY_SOFT)
    I.detail(inter(bottle, c.poly([(48, 100), (62, 100), (62, 158), (48, 158)])), WHITE, 0.45)


# --------------------------------------------------------------------------
# Modes
# --------------------------------------------------------------------------
def ic_mode_claim(I):
    c = I.c
    land = c.poly(catmull([(30, 132), (60, 112), (104, 110), (150, 118), (166, 140), (140, 162), (84, 166),
                           (40, 158)], n=12, closed=True))
    I.part(land, MINT, gloss=False)
    I.detail(dashed(c, catmull([(46, 146), (90, 132), (150, 140)], n=12), 4, 8, 6), WHITE, 0.7)
    I.part(c.stroke([(96, 132), (96, 28)], 9), NAVY_SOFT, lip=False, hl=False)
    I.part(c.poly([(100, 28), (156, 46), (100, 72)], rr=[3, 6, 3]), CORAL, gloss=True)


def ic_mode_rush(I):
    c = I.c
    for k, (y, l) in enumerate(((70, 44), (100, 58), (130, 40))):
        I.part(c.stroke([(24, y), (24 + l, y)], 12), SKY, lip=False, hl=False)
    m = star(c, 114, 98, 62, 0.5, rr=9, rot=-80)
    I.part(m, SUN)
    I.detail(inter(star_facets(I, 114, 98, 62, 0.5, SUN, rot=-80), m), SUN_D, 0.35)


def ic_mode_knockout(I):
    c = I.c
    rib_l = c.poly(rot_pts([(18, 120), (86, 120), (80, 146), (18, 146)], -12, (60, 132)), rr=3)
    rib_r = c.poly(rot_pts([(108, 132), (174, 132), (174, 158), (114, 158)], -12, (140, 146)), rr=3)
    I.part(rib_l, CORAL, lip=False)
    I.part(rib_r, CORAL, lip=False)
    I.detail(inter(union(rib_l, rib_r), union(*[c.stroke([(x, 100), (x + 30, 180)], 5) for x in range(0, 192, 18)])),
             WHITE, 0.45)
    scissors_parts(I, 98, 84, 130, ang=0.0, open_deg=40)


def ic_mode_dominate(I):
    c = I.c
    I.part(c.rrect(28, 112, 72, 164, 6), SKY)
    I.part(c.rrect(120, 124, 164, 164, 6), MINT)
    I.part(c.rrect(68, 88, 124, 164, 6), GRAPE)
    I.part(c.text("1", 96, 128, 50), WHITE, outline=False, lip=False, hl=False)
    cr = crown_mask(c, 96, 78, 64, 46)
    I.part(cr, SUN, gloss=True)
    for (x, y) in ((64, 42), (96, 32), (128, 42)):
        I.part(c.circle(x, y, 6), SUN, lip=False, iw=2.6)


def ic_mode_survive(I):
    c = I.c
    I.part(c.poly(heart_pts(84, 86, 136)), BUBBLE, gloss=True)
    I.part(c.circle(132, 132, 34), SKY, lip=False)
    I.part(c.circle(132, 132, 25), PAPER, lip=False, iw=2.6)
    I.ink(c.stroke([(132, 132), (132, 116)], 5.5))
    I.ink(c.stroke([(132, 132), (143, 138)], 5.5))


def ic_mode_boss(I):
    c = I.c
    for side in (-1, 1):
        outer = bezier((96 + side * 52, 128), (96 + side * 80, 116), (96 + side * 90, 76), (96 + side * 76, 38), 20)
        inner = bezier((96 + side * 76, 38), (96 + side * 70, 66), (96 + side * 58, 88), (96 + side * 36, 102), 20)
        I.part(c.poly(outer + inner[1:], rr=2), PAPER, lip=False, gloss=False)
    body = c.poly([(42, 162), (42, 104), (56, 74), (74, 100), (96, 40), (118, 100), (136, 74), (150, 104), (150, 162)],
                  rr=[12, 4, 4, 5, 5, 5, 4, 4, 12])
    I.part(body, GRAPE, gloss=True)
    for (x, y, r) in ((56, 72, 8), (96, 40, 10), (136, 72, 8)):
        I.part(c.circle(x, y, r), SUN, lip=False, iw=2.8)
    I.part(c.poly([(86, 92), (96, 80), (106, 92), (96, 104)], rr=3), CORAL, lip=False, iw=2.6)
    for side in (-1, 1):
        ex = 96 + side * 26
        eye = c.poly([(ex + side * 18, 116), (ex - side * 17, 126), (ex - side * 13, 142), (ex + side * 15, 142)],
                     rr=[4, 3, 6, 6])
        I.part(eye, WHITE, lip=False, hl=False, iw=3)
        I.ink(c.circle(ex - side * 3, 133, 5.5))
    I.detail(inter(body, rect(c, 30, 152, 170, 170)), GRAPE_D, 0.55)


# --------------------------------------------------------------------------
ICONS = [
    "ic_nav_home", "ic_nav_levels", "ic_nav_shop", "ic_nav_album", "ic_nav_quests",
    "ic_coin", "ic_coins_stack", "ic_star", "ic_star_empty", "ic_chest", "ic_gift", "ic_trophy", "ic_crown",
    "ic_sticker",
    "ic_play", "ic_pause", "ic_restart", "ic_next", "ic_back", "ic_close", "ic_check", "ic_share", "ic_settings",
    "ic_info", "ic_lock", "ic_edit", "ic_ad", "ic_spin", "ic_rate", "ic_profile", "ic_help",
    "ic_sound_on", "ic_sound_off", "ic_music_on", "ic_music_off", "ic_vibrate_on", "ic_vibrate_off", "ic_graphics",
    "ic_controls", "ic_privacy", "ic_terms", "ic_hand", "ic_motion", "ic_palette", "ic_reset",
    "ic_bolt", "ic_shield", "ic_bomb", "ic_freeze", "ic_ink", "ic_scissors", "ic_clock", "ic_target", "ic_pop",
    "ic_boost_big", "ic_boost_shield", "ic_boost_ink",
    "ic_mode_claim", "ic_mode_rush", "ic_mode_knockout", "ic_mode_dominate", "ic_mode_survive", "ic_mode_boss",
]


def contact_sheet(images, path, cols=9):
    cell_w, cell_h = 220, 250
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell_w + 20, rows * cell_h + 70), CREAM)
    d = ImageDraw.Draw(sheet)
    d.text((20, 18), "Paper Clash 2 - sticker UI icons (%d)" % len(images), font=font(30), fill=NAVY)
    small = font(17)
    for i, (name, im) in enumerate(images):
        x = 20 + (i % cols) * cell_w
        y = 70 + (i // cols) * cell_h
        sheet.alpha_composite(im, (x + 4, y))
        sm = im.resize((48, 48), LANCZOS)
        sheet.alpha_composite(sm, (x + 164, y + 150))
        d.text((x + 100, y + 214), name, font=small, fill=NAVY, anchor="mm")
    save(sheet.convert("RGB"), path)
    return path


def main(only=None):
    names = [n for n in ICONS if not only or n in only]
    out_dir = os.path.join(RES, "drawable-nodpi")
    images = []
    for name in names:
        fn = globals()[name]
        im = render(fn)
        p = save(im, os.path.join(out_dir, name + ".png"))
        images.append((name, im))
        a = im.getchannel("A").getbbox()
        print("wrote %-48s %dx%d  alpha bbox %s" % (rel(p), im.size[0], im.size[1], a))
    if not only:
        sheet = contact_sheet(images, os.path.join(ART, "build", "ui_icons_sheet.png"))
        print("wrote", rel(sheet))
    elif os.environ.get("ART_PREVIEW_DIR"):
        # partial runs (while iterating on a few icons) only preview outside the project
        contact_sheet(images, os.path.join(os.environ["ART_PREVIEW_DIR"], "ui_icons_partial.png"))
    return images


if __name__ == "__main__":
    main(set(sys.argv[1:]) if len(sys.argv) > 1 else None)
