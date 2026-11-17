"""Launcher icon set, splash emblem, wordmark and store art for Paper Clash 2.

Outputs (relative to the project root):
  app/src/main/res/mipmap-*/ic_launcher_{foreground,background,monochrome}.png  (108 dp)
  app/src/main/res/mipmap-*/ic_launcher.png, ic_launcher_round.png             (48 dp legacy)
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
  app/src/main/res/drawable-nodpi/splash_emblem.png   1152 x 1152
  app/src/main/res/drawable-nodpi/logo_wordmark.png   1400 x 640
  art/store/play_icon_512.png, art/store/feature_graphic_1024x500.png

Design units: the adaptive icon canvas is 108 x 108 units (1 unit = 1 dp);
the important art stays inside the 66-unit safe circle around (54, 54).
"""
import math
import os
import random
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import *  # noqa: E402,F401,F403

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
CENTER = 54.0

# --------------------------------------------------------------------------
# Emblem geometry (design units, 108 x 108 canvas)
# --------------------------------------------------------------------------
STAR_C = (49.0, 60.0)
STAR_R = 27.0
STAR_r = 13.6
STAR_ROT = -47.0  # one arm points up-right, where the plane takes off
STAR_TIP_R = 3.6
STAR_VALLEY_R = 2.4
STAR_DEPTH = 2.4

SHADOW_WARM = hexc("#8E3B22")


def star_outline():
    pts = star_pts(STAR_C[0], STAR_C[1], STAR_R, STAR_r, 5, STAR_ROT)
    radii = [STAR_TIP_R if i % 2 == 0 else STAR_VALLEY_R for i in range(10)]
    return pts, radii


def sweep_down(mask, d_px, step=2):
    """Union of the mask shifted down 0..d_px (an extruded side)."""
    out = mask.copy()
    k = step
    while k <= d_px:
        out = ImageChops.lighter(out, shift(mask, 0, k))
        k += step
    return union(out, shift(mask, 0, d_px))


def plane_geometry():
    """Folded paper plane as 3D facets, projected for a tilted camera.

    Local frame: x forward, y to the left wing, z up.  World frame: X right,
    Y up (screen), Z towards the viewer.  Returns (facets, crease, tail) where
    each facet is (kind, points2d_world, shade, depth), sorted back to front."""
    L = 14.0
    verts = {
        "nose": (L, 0.0, 0.0),
        "tail": (-L + 2.5, 0.0, 0.0),
        "lt": (-L, 14.0, 3.0),
        "rt": (-L, -14.0, 3.0),
        "keel": (-L + 4.0, 0.0, -6.5),
    }
    yaw, roll, tilt = PLANE_YAW, -25.0, 38.0
    W = {}
    t = math.radians(tilt)
    for k, p in verts.items():
        X, Y, Z = rot3(p, yaw=yaw, roll=roll)
        W[k] = (X, Y * math.cos(t) + Z * math.sin(t), -Y * math.sin(t) + Z * math.cos(t))
    faces = [
        ("wing_l", ["nose", "lt", "tail"]),
        ("wing_r", ["nose", "tail", "rt"]),
        ("keel", ["nose", "tail", "keel"]),
    ]
    light = (-0.55, 0.55, 0.65)
    out = []
    for kind, ids in faces:
        P = [W[i] for i in ids]
        n = v3_cross(v3_sub(P[1], P[0]), v3_sub(P[2], P[0]))
        s = facet_shade(n, light)
        depth = sum(p[2] for p in P) / 3.0
        out.append((kind, [(p[0], p[1]) for p in P], s, depth))
    out.sort(key=lambda f: f[3])
    crease = [(W["nose"][0], W["nose"][1]), (W["tail"][0], W["tail"][1])]
    return out, crease, (W["tail"][0], W["tail"][1])


PLANE_YAW = 45.0
PLANE_POS = (67.0, 40.0)  # where the plane's local origin lands (design units)
PLANE_SCALE = 1.08


def plane_to_design(p):
    # world (X right, Y up) -> design (x right, y down)
    return (PLANE_POS[0] + p[0] * PLANE_SCALE, PLANE_POS[1] - p[1] * PLANE_SCALE)


RIBBON_START = (30.0, 58.0)  # on the coral land, left arm


def ribbon_path():
    _, _, tail = plane_geometry()
    return loop_trail(RIBBON_START, plane_to_design(tail), b=2.8, yscale=0.8)


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------
class View:
    """Pixel canvas with a design-unit transform centred on (54, 54)."""

    def __init__(self, size_px, unit_px, cx_units=CENTER, cy_units=CENTER):
        self.size = (size_px, size_px) if isinstance(size_px, int) else size_px
        self.k = unit_px
        self.cx, self.cy = cx_units, cy_units

    def p(self, x, y):
        return (self.size[0] / 2 + (x - self.cx) * self.k, self.size[1] / 2 + (y - self.cy) * self.k)

    def pts(self, pts):
        return [self.p(x, y) for x, y in pts]

    def d(self, v):
        return v * self.k


def render_star(v, img):
    size = v.size
    pts, radii = star_outline()
    P = round_poly(v.pts(pts), [r * v.k for r in radii], seg=max(1.0, v.k * 0.4))
    top = m_poly(size, P)
    depth = v.d(STAR_DEPTH)
    side = sweep_down(top, int(round(depth)), step=max(1, int(v.k * 0.25)))

    # contact shadow on the desk/background
    sh = union(side, top)
    paint(img, SHADOW_WARM, blur(shift(sh, v.d(1.2), v.d(2.6)), v.d(2.2)), 0.42)
    paint(img, SHADOW_WARM, blur(shift(sh, v.d(0.3), v.d(0.9)), v.d(0.7)), 0.30)

    # ink outline around the whole cut-out (top + side)
    paint(img, NAVY, smooth(dilate(sh, v.d(1.0)), v.d(0.3)))

    # cardstock side wall
    bb = side.getbbox()
    side_grad = vgrad(size, bb[1] + (bb[3] - bb[1]) * 0.55, bb[3], hexc("#EBDDC4"), hexc("#D2B994"))
    paint(img, side_grad, side)
    # dark seam where the side meets the top face
    seam = sub(dilate(top, v.d(0.35)), top)
    paint(img, hexc("#B89A72"), inter(seam, side), 0.55)

    # top face: paper with a faint warm gradient
    tb = top.getbbox()
    face = vgrad(size, tb[1], tb[3], hexc("#FFFEFA"), hexc("#FFF0D8"))
    face = apply_grain(face, strength=0.035, seed=5, scale=max(0.25, v.k / 16.0))
    paint(img, face, top)
    # cut-edge rim: light on the edges facing the light, a soft warm tone on the others
    rim_lit = sub(top, shift(top, v.d(0.45), v.d(0.6)))
    paint(img, WHITE, rim_lit, 0.9)
    rim_dark = sub(top, shift(top, -v.d(0.35), -v.d(0.5)))
    paint(img, hexc("#E9D3AE"), rim_dark, 0.7)
    return top, side


CORAL_LAND = [(2.0, 44.0), (24.0, 50.0), (34.0, 56.0), (40.0, 63.5), (47.0, 67.5), (54.0, 74.0),
              (54.5, 84.0), (50.0, 100.0), (2.0, 100.0)]
SKY_LAND = [(104.0, 44.0), (78.0, 47.5), (66.0, 53.5), (59.0, 60.5), (57.0, 68.0), (59.0, 76.0),
            (70.0, 90.0), (104.0, 100.0)]


def land_mask(v, ctrl):
    return smooth(m_poly(v.size, v.pts(catmull(ctrl, n=20, closed=True))), v.d(0.4))


def render_territory(v, img, top):
    size = v.size
    regions = [(inter(land_mask(v, CORAL_LAND), top), CORAL, CORAL_D),
               (inter(land_mask(v, SKY_LAND), top), SKY, SKY_D)]
    th = int(round(v.d(1.0)))
    for t, col, cold in regions:
        # raised coloured paper: soft shadow on the cardstock, side, top, rim
        paint(img, NAVY, inter(blur(shift(t, v.d(0.5), v.d(1.3)), v.d(1.0)), top), 0.20)
        lifted = shift(t, 0, -th)
        lifted = inter(lifted, top)
        side = inter(sweep_down(lifted, th, step=max(1, int(v.k * 0.25))), top)
        paint(img, cold, side)
        bb = lifted.getbbox()
        g = vgrad(size, bb[1], bb[3], light(col, 0.10), dark(col, 0.03))
        paint(img, g, lifted)
        rim = sub(lifted, shift(lifted, v.d(0.35), v.d(0.55)))
        paint(img, WHITE, rim, 0.45)
    return regions


def ribbon_dashes(v, w_units=2.4, dash_units=4.8, gap_units=2.7):
    size = v.size
    path = v.pts(ribbon_path())
    acc = resample_by_length(path)
    total = acc[-1]
    dash, gap = v.d(dash_units), v.d(gap_units)
    w = v.d(w_units)
    dashes = new_mask(size)
    s = v.d(1.2)
    while s < total - v.d(2.4):
        e = min(total - v.d(2.4), s + dash)
        if e - s > v.d(1.0):
            m_stroke(size, sub_polyline(path, acc, s, e), w, caps=False, m=dashes)
        s = e + gap
    return smooth(dashes, v.d(0.35))


def render_ribbon(v, img):
    dashes = ribbon_dashes(v)
    border = dilate(dashes, v.d(0.6))
    paint(img, SHADOW_WARM, blur(shift(border, v.d(1.2), v.d(2.2)), v.d(1.0)), 0.24)
    paint(img, WHITE, border)
    paint(img, CORAL, dashes)
    lo = sub(dashes, shift(dashes, 0, -v.d(0.65)))
    paint(img, CORAL_D, lo, 0.75)
    hl = sub(dashes, shift(dashes, 0, v.d(0.6)))
    paint(img, WHITE, hl, 0.45)
    return dashes


PLANE_COLORS = {
    "wing_l": (hexc("#FFFFFF"), hexc("#EEF2FB")),
    "wing_r": (hexc("#D9E1F3"), hexc("#B7C4E4")),
    "keel": (hexc("#7F93C7"), hexc("#5B6FA8")),
}


def plane_masks(v):
    size = v.size
    facets, crease, _ = plane_geometry()
    masks = []
    sil = new_mask(size)
    for kind, pts, s, depth in facets:
        P = v.pts([plane_to_design(p) for p in pts])
        m = m_poly(size, P)
        masks.append((kind, m, P))
        sil = union(sil, m)
    C = v.pts([plane_to_design(p) for p in crease])
    return masks, sil, C


def render_plane(v, img, shadow=True):
    size = v.size
    masks, sil, C = plane_masks(v)
    outline = smooth(dilate(sil, v.d(1.3)), v.d(0.3))
    if shadow:
        paint(img, SHADOW_WARM, blur(shift(outline, v.d(4.0), v.d(6.5)), v.d(2.0)), 0.30)
    paint(img, NAVY, outline)
    for kind, m, P in masks:
        c0, c1 = PLANE_COLORS[kind]
        xs = [p[0] for p in P]
        ys = [p[1] for p in P]
        g = lgrad(size, (min(xs), min(ys)), (max(xs), max(ys)), c0, c1, step=16)
        paint(img, g, m)
    # fold lines: the centre crease and the keel's upper edge
    edges = m_stroke(size, C, v.d(0.7))
    for kind, m, P in masks:
        if kind == "keel":
            edges = union(edges, m_stroke(size, [P[0], P[2]], v.d(0.7)))
            edges = union(edges, m_stroke(size, [P[1], P[2]], v.d(0.7)))
    paint(img, NAVY, inter(edges, sil), 0.9)
    # crisp paper highlight along the near wing's leading edge
    for kind, m, P in masks:
        if kind == "wing_l":
            hl = m_stroke(size, [v_lerp(P[0], P[1], 0.08), v_lerp(P[0], P[1], 0.85)], v.d(0.9))
            paint(img, WHITE, inter(hl, erode(m, v.d(0.5))), 0.9)
    return sil


def render_emblem(size_px, unit_px, cx=CENTER, cy=CENTER, with_shadow=True):
    v = View(size_px, unit_px, cx, cy)
    img = Image.new("RGBA", v.size, (0, 0, 0, 0))
    top, side = render_star(v, img)
    render_territory(v, img, top)
    render_ribbon(v, img)
    render_plane(v, img)
    return img


def render_background(size_px, unit_px, cx=CENTER, cy=CENTER, seed=11):
    v = View(size_px, unit_px, cx, cy)
    W, H = v.size
    y0 = v.p(0, 0)[1]
    y1 = v.p(0, 108)[1]
    bg = vgrad(v.size, y0, y1, hexc("#FFCF4A"), hexc("#FF6F55"), gamma=1.05)
    # warm radial highlight up top
    hx, hy = v.p(42, 30)
    hl = radial_alpha(v.size, hx, hy, v.d(62), 255, gamma=1.6, step=8)
    paint(bg, hexc("#FFF4C9"), hl, 0.55)
    # gentle darkening at the bottom corners
    vx, vy = v.p(54, 128)
    vg = radial_alpha(v.size, vx, vy, v.d(70), 255, gamma=1.2, step=8)
    paint(bg, hexc("#E8413A"), vg, 0.25)
    bg = apply_grain(bg, strength=0.05, seed=seed, scale=max(0.25, unit_px / 16.0))
    return bg


# --------------------------------------------------------------------------
# Monochrome (themed icon) and fitting helpers
# --------------------------------------------------------------------------
def render_mono_mask(v):
    """White plane + dashed loop, as an L mask, for Android 13 themed icons."""
    size = v.size
    masks, sil, C = plane_masks(v)
    body = smooth(dilate(sil, v.d(1.1)), v.d(0.3))
    cuts = m_stroke(size, C, v.d(1.05))
    for kind, m, P in masks:
        if kind == "keel":
            cuts = union(cuts, m_stroke(size, [P[0], P[2]], v.d(1.05)), m_stroke(size, [P[1], P[2]], v.d(1.05)))
    plane = sub(body, inter(cuts, erode(body, v.d(0.9))))
    dashes = ribbon_dashes(v, w_units=3.4, dash_units=5.6, gap_units=3.1)
    dashes = sub(dashes, dilate(body, v.d(1.6)))
    return union(plane, dashes)


def art_extent(mask, k, view, thr=24):
    """Centre (design units, bbox centre of texels above thr) and radius
    (design units) of the farthest texel above thr from that centre."""
    W, H = mask.size
    solid = mask.point(lambda v: 255 if v > thr else 0)
    bb = solid.getbbox()
    cxp, cyp = (bb[0] + bb[2]) / 2.0, (bb[1] + bb[3]) / 2.0
    data = solid.getdata()
    r2 = 0.0
    for i, a in enumerate(data):
        if a:
            x = (i % W) + 0.5
            y = (i // W) + 0.5
            d2 = (x - cxp) ** 2 + (y - cyp) ** 2
            if d2 > r2:
                r2 = d2
    cx = view.cx + (cxp - W / 2) / k
    cy = view.cy + (cyp - H / 2) / k
    return cx, cy, math.sqrt(r2) / k


_FIT = {}


def emblem_fit(thr):
    """(cx, cy, radius) of the colour emblem in design units at scale 1."""
    if thr not in _FIT:
        probe_k = 8.0
        size = int(108 * probe_k)
        em = render_emblem(size, probe_k)
        pv = View(size, probe_k)
        _FIT[thr] = art_extent(em.getchannel("A"), probe_k, pv, thr)
    return _FIT[thr]


def mono_view(size_px, target_radius_units=30.0):
    probe_k = 6.0
    pv = View(108 * int(probe_k), probe_k)
    m = render_mono_mask(pv)
    cx, cy, r = art_extent(m, probe_k, pv)
    unit = size_px / 108.0 * target_radius_units / r
    return View(size_px, unit, cx, cy)


def emblem_view(size_px, radius_px, thr=0):
    """Centre and unit size that fit the emblem's texels above thr inside radius_px."""
    cx, cy, r = emblem_fit(thr)
    return cx, cy, radius_px / r


SAFE_RADIUS_DP = 32.6  # solid art stays inside the 66 dp safe circle


# --------------------------------------------------------------------------
# Outputs
# --------------------------------------------------------------------------
def mip(density, name):
    return os.path.join(RES, "mipmap-" + density, name)


def build_launcher():
    K = 16  # px per dp at 4x of xxxhdpi
    S = 108 * K
    bg = render_background(S, K)
    cx, cy, unit = emblem_view(S, SAFE_RADIUS_DP * K, thr=128)
    fg = render_emblem(S, unit, cx, cy)
    mono_v = mono_view(S)
    mono = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    mono.putalpha(render_mono_mask(mono_v))
    written = []
    for dens, f in DENSITIES:
        n = int(round(108 * f))
        written.append(save(down(bg, n).convert("RGB"), mip(dens, "ic_launcher_background.png")))
        written.append(save(down(fg, n), mip(dens, "ic_launcher_foreground.png")))
        written.append(save(down(mono, n), mip(dens, "ic_launcher_monochrome.png")))

    # legacy icons: the 72 dp viewport of the composited layers
    comp = bg.copy()
    comp.alpha_composite(fg)
    vp = comp.crop((18 * K, 18 * K, 90 * K, 90 * K))
    N4 = 768  # 4x of the 192 px xxxhdpi legacy icon
    inner = int(round(N4 * 44.0 / 48.0))
    off = (N4 - inner) // 2
    tile = down(vp, inner)
    for shape in ("square", "round"):
        m = new_mask((N4, N4))
        if shape == "square":
            m_rrect((N4, N4), off, off, off + inner, off + inner, inner * 0.22, m=m)
        else:
            m_circle((N4, N4), N4 / 2, N4 / 2, inner / 2, m=m)
        canvas = Image.new("RGBA", (N4, N4), (0, 0, 0, 0))
        paint(canvas, NAVY, blur(shift(m, 0, N4 * 0.012), N4 * 0.012), 0.30)
        layer = Image.new("RGBA", (N4, N4), (0, 0, 0, 0))
        layer.paste(tile, (off, off))
        canvas.alpha_composite(clip(layer, m))
        # thin light rim so the edge stays crisp on dark wallpapers
        rim = sub(m, erode(m, N4 * 0.004))
        paint(canvas, WHITE, rim, 0.25)
        name = "ic_launcher.png" if shape == "square" else "ic_launcher_round.png"
        for dens, f in DENSITIES:
            written.append(save(down(canvas, int(round(48 * f))), mip(dens, name)))
    return written, bg, fg


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""


def write_xml_and_cleanup():
    written, removed = [], []
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = os.path.join(RES, "mipmap-anydpi-v26", name)
        ensure_dir(p)
        with open(p, "w") as fh:
            fh.write(ADAPTIVE_XML)
        written.append(p)
    for dens, _ in DENSITIES:
        d = os.path.join(RES, "mipmap-" + dens)
        if os.path.isdir(d):
            for fn in sorted(os.listdir(d)):
                if fn.startswith("ic_launcher") and fn.endswith(".webp"):
                    os.remove(os.path.join(d, fn))
                    removed.append(os.path.join(d, fn))
    for fn in ("ic_launcher_foreground.xml", "ic_launcher_background.xml"):
        p = os.path.join(RES, "drawable", fn)
        if os.path.exists(p):
            os.remove(p)
            removed.append(p)
    return written, removed


def build_splash():
    """1152 x 1152 transparent emblem inside the central 768 px circle."""
    N = 1152
    SSF = 4
    radius = 376 * SSF
    cx, cy, unit = emblem_view(N * SSF, radius, thr=0)
    img = render_emblem(N * SSF, unit, cx, cy)
    out = down(img, N)
    # hard guarantee: nothing outside the 768 px splash circle
    clipm = m_circle((N * 4, N * 4), N * 2, N * 2, 383 * 4)
    out.putalpha(ImageChops.multiply(out.getchannel("A"), down(clipm, N)))
    return save(out, os.path.join(RES, "drawable-nodpi", "splash_emblem.png"))


def build_play_icon():
    N4 = 2048
    k = N4 / 80.0  # background: ~80 design units across the store icon
    bg = render_background(N4, k)
    cx, cy, unit = emblem_view(N4, N4 * 0.405, thr=128)
    fg = render_emblem(N4, unit, cx, cy)
    bg.alpha_composite(fg)
    return save(down(bg, 512).convert("RGB"), os.path.join(ART, "store", "play_icon_512.png"))


# --------------------------------------------------------------------------
# Wordmark
# --------------------------------------------------------------------------
LETTER_STYLES = [  # (face top, face bottom, extrude)
    (hexc("#FF8A6A"), hexc("#FF5A48"), hexc("#C8392E")),  # coral
    (hexc("#FFC15A"), hexc("#FF8C38"), hexc("#D0601E")),  # orange
    (hexc("#FFE27A"), hexc("#FFB92E"), hexc("#D98A10")),  # sun
]
WORD_SEQ = [0, 1, 2, 1, 0, None, 2, 0, 1, 2, 0]
WORD_ROT = [-5.0, 3.0, -2.5, 4.0, -3.5, 0.0, 3.5, -4.0, 2.5, -3.0, 4.5]
WORD_DY = [4.0, -7.0, 2.0, -5.0, 5.0, 0.0, -4.0, 5.0, -6.0, 3.0, -2.0]


def glyph_mask(ch, fnt, angle):
    """Rotated glyph mask plus the rotated position of its baseline-left origin."""
    asc, desc = fnt.getmetrics()
    adv = fnt.getlength(ch)
    pad = int(fnt.size * 0.3)
    W = int(adv + 2 * pad)
    H = int(asc + desc + 2 * pad)
    m = Image.new("L", (W, H), 0)
    ImageDraw.Draw(m).text((pad, pad + asc), ch, font=fnt, fill=255, anchor="ls")
    bb = m.getbbox()
    cx, cy = (bb[0] + bb[2]) / 2.0, (bb[1] + bb[3]) / 2.0
    r = m.rotate(angle, resample=BICUBIC, expand=False, center=(cx, cy))
    return r, (pad, pad + asc), adv


def scallop_pts(cx, cy, r, bumps, depth, n=720):
    pts = []
    for i in range(n):
        a = 2 * math.pi * i / n
        rr = r - depth * (0.5 - 0.5 * math.cos(bumps * a)) ** 0.8
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def build_wordmark(path=None, return_image=False, hires=False):
    S = 4
    W, H = 1400 * S, 640 * S
    s = 222.0 * S  # font size
    fnt = font(s)
    text = "PAPER CLASH"
    track = -0.045 * s
    space = 0.17 * s
    advs = []
    for ch in text:
        advs.append(space if ch == " " else fnt.getlength(ch) + track)
    total = sum(advs) - track
    x = (W - total) / 2.0
    base_y = 214 * S

    faces, glyph_pos = [], []
    for i, ch in enumerate(text):
        if ch == " ":
            x += advs[i]
            faces.append(None)
            continue
        gm, origin, adv = glyph_mask(ch, fnt, WORD_ROT[i])
        m = new_mask((W, H))
        ox = int(round(x - origin[0]))
        oy = int(round(base_y + WORD_DY[i] * S - origin[1]))
        m.paste(gm, (ox, oy))
        faces.append(m)
        x += advs[i]

    ext = int(0.085 * s)
    o1 = 0.050 * s  # ink outline around each letter
    letters = []
    for i, f in enumerate(faces):
        if f is None:
            continue
        body = sweep_down(f, ext, step=3)
        letters.append((i, f, body))

    # ---- "2" badge ----------------------------------------------------
    bc = (W / 2.0 + 4 * S, 422 * S)
    br = 136 * S
    badge_rot = -8.0
    disc = m_poly((W, H), rot_pts(scallop_pts(bc[0], bc[1], br, 18, 0.07 * br), badge_rot, bc))
    badge_depth = int(0.06 * br)
    badge_body = sweep_down(disc, badge_depth, step=3)
    f2 = font(0.80 * 2 * br)
    g2, origin2, adv2 = glyph_mask("2", f2, badge_rot + 4.0)
    gb = g2.getbbox()
    two = new_mask((W, H))
    two.paste(g2, (int(round(bc[0] - (gb[0] + gb[2]) / 2.0)), int(round(bc[1] - (gb[1] + gb[3]) / 2.0 - 0.02 * br))))
    two_body = sweep_down(two, int(0.05 * br), step=3)

    all_bodies = union(*[b for _, _, b in letters], badge_body)
    ink = dilate(all_bodies, o1)
    white = smooth(closing(dilate(all_bodies, o1 + 0.085 * s), 0.05 * s), 0.01 * s)
    outer = dilate(white, 0.018 * s)

    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    paint(img, NAVY, blur(shift(outer, 0, 0.05 * s), 0.035 * s), 0.35)
    paint(img, NAVY, outer)
    wb = white.getbbox()
    paint(img, vgrad((W, H), wb[1], wb[3], hexc("#FFFFFF"), hexc("#F3EEE6")), white)
    paint(img, NAVY, ink)

    # badge first (letters overlap it)
    paint(img, NAVY, dilate(badge_body, o1 * 0.9))
    paint(img, hexc("#1F5FB0"), badge_body)
    db = disc.getbbox()
    paint(img, rgrad((W, H), bc[0] - 0.3 * br, bc[1] - 0.45 * br, 1.5 * br, hexc("#7CC0FF"), hexc("#2F86EA")), disc)
    ring = sub(disc, erode(disc, 0.11 * br))
    paint(img, hexc("#2B78D6"), ring, 0.55)
    stitch_r = br * 0.80
    stitch = new_mask((W, H))
    n_st = 30
    for k in range(n_st):
        a0 = 2 * math.pi * k / n_st + math.radians(badge_rot)
        a1 = a0 + 2 * math.pi / n_st * 0.55
        pts = [(bc[0] + stitch_r * math.cos(a0 + (a1 - a0) * t / 6), bc[1] + stitch_r * math.sin(a0 + (a1 - a0) * t / 6))
               for t in range(7)]
        m_stroke((W, H), pts, 0.028 * br, m=stitch)
    paint(img, WHITE, stitch, 0.75)
    # the numeral: ink outline, extrude, cream face
    paint(img, NAVY, dilate(two_body, o1 * 0.95))
    paint(img, hexc("#C9D7F2"), two_body)
    tb = two.getbbox()
    paint(img, vgrad((W, H), tb[1], tb[3], hexc("#FFFFFF"), hexc("#E9F0FF")), two)
    paint(img, WHITE, sub(two, shift(two, 0, 0.02 * br)), 0.9)
    paint(img, hexc("#9FB5E0"), sub(two, shift(two, 0, -0.025 * br)), 0.6)
    # badge gloss
    gl = inter(disc, m_ellipse((W, H), bc[0] - 0.25 * br, bc[1] - 0.55 * br, 0.55 * br, 0.22 * br, -20))
    paint(img, WHITE, sub(gl, dilate(two, o1)), 0.18)

    # letters, left to right, each with its own ink outline so overlaps read as stacked paper
    for i, f, body in letters:
        top_c, bot_c, ext_c = LETTER_STYLES[WORD_SEQ[i]]
        paint(img, NAVY, dilate(body, o1))
        bb = body.getbbox()
        paint(img, vgrad((W, H), bb[1], bb[3], light(ext_c, 0.08), dark(ext_c, 0.1)), body)
        fb = f.getbbox()
        paint(img, vgrad((W, H), fb[1], fb[3], top_c, bot_c), f)
        # inner paper layer: slightly lighter sheet inset from the edge
        inner = erode(f, 0.03 * s)
        paint(img, NAVY, inter(blur(shift(inner, 0, 0.012 * s), 0.008 * s), f), 0.18)
        inner_up = shift(inner, 0, -0.008 * s)
        ib = inner_up.getbbox()
        paint(img, vgrad((W, H), ib[1], ib[3], light(top_c, 0.35), light(bot_c, 0.12)), inter(inner_up, f))
        # top light edge
        paint(img, WHITE, inter(sub(f, shift(f, 0, 0.018 * s)), f), 0.75)
    img = apply_grain(img, strength=0.035, seed=21, scale=1.0)
    if hires:
        return img
    out = down(img, (1400, 640))
    if return_image:
        return out
    return save(out, path or os.path.join(RES, "drawable-nodpi", "logo_wordmark.png"))


# --------------------------------------------------------------------------
# Feature graphic
# --------------------------------------------------------------------------
CONFETTI_COLORS = [MINT, SKY, GRAPE, BUBBLE, PWHITE, SUN, CORAL, hexc("#FFE08A")]


def confetti_piece(size, rnd, x, y, scale):
    """Mask + colour for one paper confetti piece (square, strip, dot or star)."""
    kind = rnd.choice(["square", "square", "strip", "strip", "dot", "star"])
    ang = rnd.uniform(0, 360)
    if kind == "square":
        a = rnd.uniform(14, 24) * scale
        pts = rot_pts([(x - a / 2, y - a / 2), (x + a / 2, y - a / 2), (x + a / 2, y + a / 2), (x - a / 2, y + a / 2)],
                      ang, (x, y))
        m = m_poly(size, round_poly(pts, a * 0.12))
    elif kind == "strip":
        l, w = rnd.uniform(34, 56) * scale, rnd.uniform(8, 11) * scale
        bend = rnd.uniform(-0.35, 0.35)
        pts = [(x + (t - 0.5) * l, y + bend * l * ((t - 0.5) ** 2 - 0.25)) for t in [i / 12 for i in range(13)]]
        pts = rot_pts(pts, ang, (x, y))
        m = m_stroke(size, pts, w, caps=False)
    elif kind == "dot":
        r = rnd.uniform(6, 10) * scale
        m = m_circle(size, x, y, r)
    else:
        r = rnd.uniform(12, 17) * scale
        m = m_poly(size, round_poly(star_pts(x, y, r, r * 0.5, 5, ang), r * 0.12))
    return m, rnd.choice(CONFETTI_COLORS)


def build_feature_graphic():
    S = 4
    W, H = 1024 * S, 500 * S
    # warm background
    img = lgrad((W, H), (0, 0), (W * 0.35, H * 1.15), hexc("#FFD24F"), hexc("#FF6E55"), step=16)
    paint(img, hexc("#FFF4C9"), radial_alpha((W, H), W * 0.30, H * 0.30, W * 0.55, 255, 1.4, step=16), 0.55)
    paint(img, hexc("#E8413A"), radial_alpha((W, H), W * 0.85, H * 1.25, W * 0.6, 255, 1.2, step=16), 0.22)
    # soft paper sunburst behind the emblem
    cx, cy = 790 * S, 250 * S
    rays = new_mask((W, H))
    for i in range(18):
        a0 = math.radians(i * 20)
        a1 = a0 + math.radians(9)
        R = 900 * S
        m_poly((W, H), [(cx, cy), (cx + R * math.cos(a0), cy + R * math.sin(a0)), (cx + R * math.cos(a1), cy + R * math.sin(a1))],
               m=rays)
    fade = radial_alpha((W, H), cx, cy, 560 * S, 255, 1.0, step=16)
    paint(img, hexc("#FFF6E0"), inter(rays, fade), 0.20)
    img = apply_grain(img, strength=0.05, seed=31, scale=1.0)

    # confetti (seeded), skipping the logo block so it stays clean
    rnd = random.Random(2024)
    pieces = []
    tries = 0
    while len(pieces) < 46 and tries < 2000:
        tries += 1
        x, y = rnd.uniform(10, 1014), rnd.uniform(10, 490)
        if 30 < x < 620 and 70 < y < 430:
            continue
        if math.hypot(x - 790, y - 250) < 175:
            continue
        if any(math.hypot(x - px, y - py) < 48 for px, py in pieces):
            continue
        pieces.append((x, y))
    for (x, y) in pieces:
        m, col = confetti_piece((W, H), rnd, x * S, y * S, S * rnd.uniform(0.8, 1.25))
        paint(img, hexc("#8E3B22"), blur(shift(m, 3 * S, 5 * S), 3 * S), 0.22)
        bb = m.getbbox()
        paint(img, vgrad((W, H), bb[1], bb[3], light(col, 0.18), dark(col, 0.05)), m)
        paint(img, WHITE, sub(m, shift(m, 0, 2.5 * S)), 0.35)

    # emblem on the right
    radius_px = 205 * S
    ecx, ecy, unit = emblem_view(W, radius_px, thr=24)
    em_full = render_emblem((W, H), unit, ecx + (W / 2 - cx) / unit, ecy + (H / 2 - cy) / unit)
    img.alpha_composite(em_full)

    # logo on the left
    logo = build_wordmark(hires=True)
    lw = 584 * S
    lh = int(round(logo.size[1] * lw / logo.size[0]))
    logo = down(logo, (lw, lh))
    img.alpha_composite(logo, (int(34 * S), int((H - lh) / 2 - 6 * S)))
    out = down(img, (1024, 500)).convert("RGB")
    return save(out, os.path.join(ART, "store", "feature_graphic_1024x500.png"))


def preview():
    out = os.path.join(sys.argv[2] if len(sys.argv) > 2 else ".", "emblem_preview.png")
    k = 16
    bg = render_background(108 * k, k)
    em = render_emblem(108 * k, k)
    comp = bg.copy()
    comp.alpha_composite(em)
    d = ImageDraw.Draw(comp)
    c = 54 * k
    d.ellipse([c - 33 * k, c - 33 * k, c + 33 * k, c + 33 * k], outline=(0, 0, 0, 90), width=4)
    down(comp, 864).save(out)
    down(comp, 144).save(out.replace(".png", "_small.png"))
    print("wrote", out)


def main(parts=("launcher", "splash", "store", "wordmark", "feature")):
    written = []
    if "launcher" in parts:
        w, bg, fg = build_launcher()
        written += w
        w2, removed = write_xml_and_cleanup()
        written += w2
        for p in removed:
            print("removed", rel(p))
    if "splash" in parts:
        written.append(build_splash())
    if "store" in parts:
        written.append(build_play_icon())
    if "wordmark" in parts and "build_wordmark" in globals():
        written.append(build_wordmark())
    if "feature" in parts and "build_feature_graphic" in globals():
        written.append(build_feature_graphic())
    for p in written:
        if p.endswith(".png"):
            im = Image.open(p)
            print("wrote %-66s %dx%d %s" % (rel(p), im.size[0], im.size[1], im.mode))
        else:
            print("wrote %s" % rel(p))
    return written


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "preview":
        preview()
    elif len(sys.argv) > 1:
        main(tuple(sys.argv[1:]))
    else:
        main()
