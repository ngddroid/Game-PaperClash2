"""Shared helpers for the Paper Clash 2 art generators.

Only the Python standard library and Pillow are used. Everything is drawn
supersampled (normally 4x) and reduced with LANCZOS at the end, so plain
(non anti-aliased) ImageDraw primitives come out smooth.

Conventions
-----------
* Colours are RGBA tuples.
* Masks are Pillow "L" images (0 = empty, 255 = full).
* All geometry helpers work in *pixel* coordinates of the supersampled
  canvas; callers convert their design units first (see `Ctx`).
* Randomness always comes from a seeded `random.Random`, so every run of a
  generator produces byte-identical images.
"""
import math
import os
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps, ImageStat

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
ART = os.path.join(ROOT, "art")
FONT_BALOO = os.path.join(ASSETS, "fonts", "Baloo2-ExtraBold.ttf")

SS = 4  # supersampling factor
LANCZOS = Image.Resampling.LANCZOS
BILINEAR = Image.Resampling.BILINEAR
BICUBIC = Image.Resampling.BICUBIC
NEAREST = Image.Resampling.NEAREST


# --------------------------------------------------------------------------
# Colour
# --------------------------------------------------------------------------
def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


CREAM = hexc("#FFF6E6")
PWHITE = hexc("#FFFDF8")
WHITE = (255, 255, 255, 255)
NAVY = hexc("#1F2A4A")
NAVY_SOFT = hexc("#3A4A75")
CORAL = hexc("#FF6B57")
CORAL_D = hexc("#D9483A")
SUN = hexc("#FFC83D")
SUN_D = hexc("#E09A12")
MINT = hexc("#2EC4A0")
MINT_D = hexc("#1E9B7D")
SKY = hexc("#4BA3FF")
SKY_D = hexc("#2B78D6")
GRAPE = hexc("#7A5CFF")
GRAPE_D = hexc("#5A3FD6")
BUBBLE = hexc("#FF7AB6")
BUBBLE_D = hexc("#E0559A")
KRAFT = hexc("#C89B6D")
KRAFT_D = hexc("#A87B4F")
ORANGE = hexc("#FF9233")
CYAN = hexc("#3CCFE6")
SILVER = hexc("#C9D2E3")
BLACK = (0, 0, 0, 255)
CLEAR = (0, 0, 0, 0)


def mix(c1, c2, t):
    """Linear blend of two RGBA colours (t=0 -> c1, t=1 -> c2)."""
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a + (b - a) * t)) for a, b in zip(c1, c2))


def light(c, t):
    """Lighten towards white."""
    return mix(c, (255, 255, 255, c[3]), t)


def dark(c, t):
    """Darken towards ink navy (keeps shadows in the palette's cool ink)."""
    return mix(c, (NAVY[0], NAVY[1], NAVY[2], c[3]), t)


def with_alpha(c, a):
    return (c[0], c[1], c[2], int(a))


# --------------------------------------------------------------------------
# Small vector maths
# --------------------------------------------------------------------------
def v_add(a, b):
    return (a[0] + b[0], a[1] + b[1])


def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1])


def v_mul(a, k):
    return (a[0] * k, a[1] * k)


def v_len(a):
    return math.hypot(a[0], a[1])


def v_norm(a):
    l = v_len(a)
    return (a[0] / l, a[1] / l) if l > 1e-12 else (0.0, 0.0)


def v_lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def v_rot(p, ang_deg, c=(0.0, 0.0)):
    a = math.radians(ang_deg)
    ca, sa = math.cos(a), math.sin(a)
    x, y = p[0] - c[0], p[1] - c[1]
    return (c[0] + x * ca - y * sa, c[1] + x * sa + y * ca)


def rot_pts(pts, ang_deg, c=(0.0, 0.0)):
    return [v_rot(p, ang_deg, c) for p in pts]


def tr_pts(pts, dx, dy, k=1.0):
    return [(p[0] * k + dx, p[1] * k + dy) for p in pts]


# --------------------------------------------------------------------------
# Curves and polygons
# --------------------------------------------------------------------------
def star_pts(cx, cy, R, r, n=5, rot=-90.0):
    pts = []
    for i in range(2 * n):
        a = math.radians(rot + i * 180.0 / n)
        rr = R if i % 2 == 0 else r
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def reg_poly(cx, cy, r, n, rot=-90.0):
    return [(cx + r * math.cos(math.radians(rot + i * 360.0 / n)),
             cy + r * math.sin(math.radians(rot + i * 360.0 / n))) for i in range(n)]


def ellipse_pts(cx, cy, rx, ry, rot=0.0, n=None):
    if n is None:
        n = max(24, int(max(rx, ry) * 0.9))
    pts = []
    for i in range(n):
        a = 2 * math.pi * i / n
        pts.append((rx * math.cos(a), ry * math.sin(a)))
    return [v_add(v_rot(p, rot), (cx, cy)) for p in pts]


def arc_pts(cx, cy, r, a0, a1, n=None, ry=None):
    """Points along an arc, angles in degrees (screen coords, y down)."""
    ry = r if ry is None else ry
    if n is None:
        n = max(8, int(abs(a1 - a0) / 360.0 * 2 * math.pi * max(r, ry) / 3.0))
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def bezier(p0, p1, p2, p3, n=40):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
                    u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]))
    return out


def qbezier(p0, p1, p2, n=30):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                    u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return out


def catmull(pts, n=16, closed=False):
    """Centripetal-ish (uniform) Catmull-Rom spline through pts."""
    if closed:
        P = [pts[-1]] + list(pts) + [pts[0], pts[1]]
    else:
        P = [pts[0]] + list(pts) + [pts[-1]]
    out = []
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
        for k in range(n):
            t = k / n
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                       + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                       + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    if not closed:
        out.append(pts[-1])
    return out


def round_poly(pts, radius, seg=2.0):
    """Polygon with every corner replaced by a circular fillet.

    `radius` is a number or a per-vertex list. Works for convex and reflex
    corners (reflex corners get an outside fillet). `seg` is the target arc
    segment length in pixels.
    """
    n = len(pts)
    radii = radius if isinstance(radius, (list, tuple)) else [radius] * n
    out = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        r = radii[i]
        e1 = v_sub(p0, p1)
        e2 = v_sub(p2, p1)
        l1, l2 = v_len(e1), v_len(e2)
        if r <= 0 or l1 < 1e-9 or l2 < 1e-9:
            out.append(p1)
            continue
        u1, u2 = v_mul(e1, 1 / l1), v_mul(e2, 1 / l2)
        cosang = max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1]))
        ang = math.acos(cosang)
        if ang < 1e-3 or ang > math.pi - 1e-3:
            out.append(p1)
            continue
        d = r / math.tan(ang / 2)
        d = min(d, 0.5 * l1, 0.5 * l2)
        re = d * math.tan(ang / 2)
        t1 = v_add(p1, v_mul(u1, d))
        t2 = v_add(p1, v_mul(u2, d))
        bis = v_norm(v_add(u1, u2))
        c = v_add(p1, v_mul(bis, re / math.sin(ang / 2)))
        a1 = math.atan2(t1[1] - c[1], t1[0] - c[0])
        a2 = math.atan2(t2[1] - c[1], t2[0] - c[0])
        da = a2 - a1
        while da > math.pi:
            da -= 2 * math.pi
        while da < -math.pi:
            da += 2 * math.pi
        steps = max(2, int(abs(da) * re / seg))
        for k in range(steps + 1):
            a = a1 + da * k / steps
            out.append((c[0] + re * math.cos(a), c[1] + re * math.sin(a)))
    return out


def loop_trail(S, E, b=2.6, side=1.0, yscale=1.0, n=200):
    """Loop-de-loop path from S to E (a prolate cycloid over one period).

    b > 1 sets the loop size; side=+1 puts the loop on the left of the
    direction of travel (above it for left-to-right travel on screen)."""
    t0, t1 = -math.pi, math.pi
    dx, dy = E[0] - S[0], E[1] - S[1]
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    px, py = uy * side, -ux * side
    k = L / ((t1 - t0) - b * (math.sin(t1) - math.sin(t0)))
    xs0 = t0 - b * math.sin(t0)
    pts = []
    for i in range(n + 1):
        t = t0 + (t1 - t0) * i / n
        x = (t - b * math.sin(t) - xs0) * k
        y = (b * math.cos(t) - b * math.cos(t0)) * k * yscale
        pts.append((S[0] + ux * x + px * y, S[1] + uy * x + py * y))
    return pts


def poly_length(pts):
    return sum(v_len(v_sub(pts[i + 1], pts[i])) for i in range(len(pts) - 1))


def resample_by_length(pts):
    """Returns cumulative lengths for a polyline."""
    acc = [0.0]
    for i in range(len(pts) - 1):
        acc.append(acc[-1] + v_len(v_sub(pts[i + 1], pts[i])))
    return acc


def sub_polyline(pts, acc, s0, s1):
    """Part of a polyline between arc lengths s0 and s1."""
    out = []

    def at(s):
        for i in range(len(acc) - 1):
            if acc[i + 1] >= s:
                seg = acc[i + 1] - acc[i]
                t = 0 if seg < 1e-9 else (s - acc[i]) / seg
                return v_lerp(pts[i], pts[i + 1], t)
        return pts[-1]

    out.append(at(s0))
    for i in range(len(pts)):
        if s0 < acc[i] < s1:
            out.append(pts[i])
    out.append(at(s1))
    return out


def point_at(pts, acc, s):
    for i in range(len(acc) - 1):
        if acc[i + 1] >= s:
            seg = acc[i + 1] - acc[i]
            t = 0 if seg < 1e-9 else (s - acc[i]) / seg
            p = v_lerp(pts[i], pts[i + 1], t)
            d = v_norm(v_sub(pts[i + 1], pts[i]))
            return p, d
    return pts[-1], v_norm(v_sub(pts[-1], pts[-2]))


# --------------------------------------------------------------------------
# Masks
# --------------------------------------------------------------------------
def new_mask(size, v=0):
    return Image.new("L", size, v)


def m_poly(size, pts, v=255, m=None):
    m = new_mask(size) if m is None else m
    if len(pts) >= 3:
        ImageDraw.Draw(m).polygon([(float(x), float(y)) for x, y in pts], fill=v)
    return m


def m_circle(size, cx, cy, r, v=255, m=None):
    m = new_mask(size) if m is None else m
    ImageDraw.Draw(m).ellipse([cx - r, cy - r, cx + r, cy + r], fill=v)
    return m


def m_ellipse(size, cx, cy, rx, ry, rot=0.0, v=255, m=None):
    if abs(rot) < 1e-6:
        m = new_mask(size) if m is None else m
        ImageDraw.Draw(m).ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=v)
        return m
    return m_poly(size, ellipse_pts(cx, cy, rx, ry, rot, n=max(48, int(max(rx, ry)))), v, m)


def m_rrect(size, x0, y0, x1, y1, r, v=255, m=None):
    m = new_mask(size) if m is None else m
    r = max(0, min(r, (x1 - x0) / 2, (y1 - y0) / 2))
    ImageDraw.Draw(m).rounded_rectangle([x0, y0, x1, y1], radius=r, fill=v)
    return m


def m_stroke(size, pts, w, v=255, closed=False, caps=True, m=None):
    """Thick polyline with round joins and (optionally) round caps."""
    m = new_mask(size) if m is None else m
    d = ImageDraw.Draw(m)
    pts = list(pts)
    if closed:
        pts = pts + [pts[0]]
    h = w / 2.0
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        l = math.hypot(dx, dy)
        if l < 1e-9:
            continue
        nx, ny = -dy / l * h, dx / l * h
        d.polygon([(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny), (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)],
                  fill=v)
    joints = pts if caps or closed else pts[1:-1]
    for p in joints:
        d.ellipse([p[0] - h, p[1] - h, p[0] + h, p[1] + h], fill=v)
    return m


def m_text(size, text, fnt, x, y, anchor="mm", v=255, m=None, stroke=0):
    m = new_mask(size) if m is None else m
    ImageDraw.Draw(m).text((x, y), text, font=fnt, fill=v, anchor=anchor, stroke_width=int(stroke),
                           stroke_fill=v)
    return m


def union(*ms):
    ms = [m for m in ms if m is not None]
    out = ms[0].copy()
    for m in ms[1:]:
        out = ImageChops.lighter(out, m)
    return out


def sub(a, b):
    return ImageChops.subtract(a, b)


def inter(a, b):
    return ImageChops.multiply(a, b)


def invert(m):
    return ImageOps.invert(m)


def shift(m, dx, dy):
    out = Image.new(m.mode, m.size, 0)
    out.paste(m, (int(round(dx)), int(round(dy))))
    return out


def scale_mask(m, k):
    if k >= 0.999:
        return m
    lut = [int(round(v * k)) for v in range(256)]
    return m.point(lut)


def thresh(m, t=128, soft=0):
    if soft <= 0:
        lut = [255 if v >= t else 0 for v in range(256)]
    else:
        lo, hi = t - soft, t + soft
        lut = [0 if v <= lo else 255 if v >= hi else int(round(255 * (v - lo) / (hi - lo))) for v in range(256)]
    return m.point(lut)


def _expand_bbox(bb, r, size):
    r = int(math.ceil(r)) + 2
    return (max(0, bb[0] - r), max(0, bb[1] - r), min(size[0], bb[2] + r), min(size[1], bb[3] + r))


def _ring(m, rho):
    n = max(8, int(math.ceil(2 * math.pi * rho / 1.0)))
    offs = set()
    for k in range(n):
        a = 2 * math.pi * k / n
        offs.add((int(round(rho * math.cos(a))), int(round(rho * math.sin(a)))))
    acc = m
    for dx, dy in offs:
        if dx == 0 and dy == 0:
            continue
        acc = ImageChops.lighter(acc, shift(m, dx, dy))
    return acc


def dilate(m, r):
    """Euclidean (disk) dilation by r pixels, computed on the mask's bbox.

    Dilating by a disk of radius a and then b equals dilating by a+b, and a
    ring of offsets of radius rho is an exact disk dilation once the shape is
    a union of disks of radius >= rho, so the radius is built up as
    1, 1, 2, 4, 8, ... with ring passes.
    """
    if r <= 0.25:
        return m.copy()
    bb = m.getbbox()
    if bb is None:
        return m.copy()
    bb = _expand_bbox(bb, r, m.size)
    s = m.crop(bb)
    s = s.filter(ImageFilter.MaxFilter(3))
    done = 1.0
    while done < r - 0.25:
        step = min(done, r - done)
        s = _ring(s, step)
        done += step
    out = new_mask(m.size)
    out.paste(s, bb[:2])
    return out


def erode(m, r):
    if r <= 0.25:
        return m.copy()
    bb = m.getbbox()
    if bb is None:
        return m.copy()
    bb = _expand_bbox(bb, r, m.size)
    s = m.crop(bb)
    s = ImageOps.invert(s)
    # make the outside of the crop count as "empty" so erosion works at the crop border
    pad = int(math.ceil(r)) + 2
    big = Image.new("L", (s.size[0] + 2 * pad, s.size[1] + 2 * pad), 255)
    big.paste(s, (pad, pad))
    big = big.filter(ImageFilter.MaxFilter(3))
    done = 1.0
    while done < r - 0.25:
        step = min(done, r - done)
        big = _ring(big, step)
        done += step
    s = ImageOps.invert(big.crop((pad, pad, pad + s.size[0], pad + s.size[1])))
    out = new_mask(m.size)
    out.paste(s, bb[:2])
    return out


def blur(m, r):
    if r <= 0:
        return m.copy()
    bb = m.getbbox()
    if bb is None:
        return m.copy()
    bb = _expand_bbox(bb, r * 3.2, m.size)
    s = m.crop(bb).filter(ImageFilter.GaussianBlur(r))
    out = Image.new(m.mode, m.size, 0)
    out.paste(s, bb[:2])
    return out


def smooth(m, r):
    """Round off small corners/notches: blur then re-threshold (stays crisp)."""
    return thresh(blur(m, r), 128, soft=24)


def opening(m, r):
    return dilate(erode(m, r), r)


def closing(m, r):
    return erode(dilate(m, r), r)


def bbox_of(m):
    return m.getbbox()


# --------------------------------------------------------------------------
# Fills / gradients
# --------------------------------------------------------------------------
def solid(size, c):
    return Image.new("RGBA", size, c)


def vgrad(size, y0, y1, c0, c1, gamma=1.0):
    """Vertical gradient image: c0 at y<=y0, c1 at y>=y1."""
    W, H = size
    col = []
    span = max(1e-6, y1 - y0)
    for y in range(H):
        t = (y + 0.5 - y0) / span
        t = max(0.0, min(1.0, t))
        if gamma != 1.0:
            t = t ** gamma
        col.append(mix(c0, c1, t))
    strip = Image.new("RGBA", (1, H))
    strip.putdata(col)
    return strip.resize((W, H), NEAREST)


def field(size, fn, step=8):
    """Evaluate fn(x, y)->0..255 on a coarse grid and bilinear-upsample."""
    W, H = size
    cw = int(math.ceil(W / step)) + 2
    ch = int(math.ceil(H / step)) + 2
    data = []
    for j in range(ch):
        y = (j + 0.5) * step - 0.5
        for i in range(cw):
            x = (i + 0.5) * step - 0.5
            v = fn(x, y)
            data.append(0 if v < 0 else 255 if v > 255 else int(v + 0.5))
    small = Image.new("L", (cw, ch))
    small.putdata(data)
    return small.transform((W, H), Image.Transform.AFFINE, (1.0 / step, 0, 0, 0, 1.0 / step, 0), resample=BILINEAR)


def lgrad(size, p0, p1, c0, c1, step=8):
    """Linear gradient from colour c0 at point p0 to c1 at point p1."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L2 = dx * dx + dy * dy or 1.0

    def fn(x, y):
        return 255.0 * ((x - p0[0]) * dx + (y - p0[1]) * dy) / L2

    t = field(size, fn, step)
    return Image.composite(solid(size, c1), solid(size, c0), t)


def rgrad(size, cx, cy, r, c_in, c_out, gamma=1.0, step=4):
    """Radial gradient (c_in at the centre, c_out at radius r and beyond)."""

    def fn(x, y):
        t = math.hypot(x - cx, y - cy) / r
        t = min(1.0, t)
        return 255.0 * (t ** gamma)

    t = field(size, fn, step)
    return Image.composite(solid(size, c_out), solid(size, c_in), t)


def radial_alpha(size, cx, cy, r, a_center=255, gamma=1.0, step=4, smoothstep=True):
    """'L' mask: a_center at the centre falling to 0 at radius r."""

    def fn(x, y):
        t = math.hypot(x - cx, y - cy) / r
        if t >= 1.0:
            return 0
        u = 1.0 - t
        if smoothstep:
            u = u * u * (3 - 2 * u)
        return a_center * (u ** gamma)

    return field(size, fn, step)


# --------------------------------------------------------------------------
# Compositing
# --------------------------------------------------------------------------
def paint(base, fill, mask, opacity=1.0):
    """Composite `fill` (colour tuple or RGBA image of base.size) onto `base`
    through `mask`, in place. Work is limited to the mask's bounding box."""
    bb = mask.getbbox()
    if bb is None:
        return base
    msk = mask.crop(bb)
    if opacity < 0.999:
        msk = scale_mask(msk, opacity)
    if isinstance(fill, tuple):
        layer = Image.new("RGBA", msk.size, fill)
        if fill[3] < 255:
            msk = ImageChops.multiply(msk, Image.new("L", msk.size, fill[3]))
        layer.putalpha(msk)
    else:
        layer = fill.crop(bb)
        a = layer.getchannel("A")
        layer.putalpha(ImageChops.multiply(a, msk))
    base.alpha_composite(layer, dest=bb[:2])
    return base


def over(base, top, dest=(0, 0)):
    base.alpha_composite(top, dest=dest)
    return base


def shadow_layer(size, mask, dx, dy, r, color=NAVY, opacity=0.3):
    m = blur(shift(mask, dx, dy), r)
    layer = Image.new("RGBA", size, color[:3] + (0,))
    layer.putalpha(scale_mask(m, opacity))
    return layer


def alpha_of(img):
    return img.getchannel("A")


def mul_alpha(img, mask):
    out = img.copy()
    out.putalpha(ImageChops.multiply(img.getchannel("A"), mask))
    return out


def clip(img, mask):
    return mul_alpha(img, mask)


# --------------------------------------------------------------------------
# Paper grain / noise (tileable, seeded)
# --------------------------------------------------------------------------
def noise_L(size, seed):
    rnd = random.Random(seed)
    return Image.frombytes("L", size, rnd.randbytes(size[0] * size[1]))


def tile3(img):
    w, h = img.size
    big = Image.new(img.mode, (w * 3, h * 3))
    for j in range(3):
        for i in range(3):
            big.paste(img, (i * w, j * h))
    return big


def tile_blur(img, r):
    """Gaussian blur with wrap-around edges (keeps a texture tileable)."""
    w, h = img.size
    return tile3(img).filter(ImageFilter.GaussianBlur(r)).crop((w, h, 2 * w, 2 * h))


def value_noise(size, cells, seed, resample=BICUBIC):
    """Tileable value noise: a cells x cells random grid smoothly upsampled."""
    w, h = size
    g = noise_L((cells, cells), seed)
    big = tile3(g).resize((w * 3, h * 3), resample)
    return big.crop((w, h, 2 * w, 2 * h))


def levels(m, mean_target, std_target):
    """Linearly remap an L image to the requested mean / standard deviation."""
    st = ImageStat.Stat(m)
    mean, std = st.mean[0], max(1e-6, st.stddev[0])
    k = std_target / std
    lut = [max(0, min(255, int(round(mean_target + (v - mean) * k)))) for v in range(256)]
    return m.point(lut)


def grain_tile(size=(512, 512), seed=7, fibres=True):
    """Soft paper grain centred on 128 (neutral) - tileable."""
    w, h = size
    fine = tile_blur(noise_L(size, seed), 0.6)
    fine = levels(fine, 128, 20)
    blot = value_noise(size, 16, seed + 1)
    blot = levels(blot, 128, 20)
    blot2 = value_noise(size, 48, seed + 2)
    blot2 = levels(blot2, 128, 20)
    g = Image.blend(Image.blend(fine, blot2, 0.4), blot, 0.35)
    if fibres:
        fl = Image.new("L", size, 128)
        d = ImageDraw.Draw(fl)
        rnd = random.Random(seed + 3)
        for _ in range(int(w * h / 900)):
            x, y = rnd.uniform(0, w), rnd.uniform(0, h)
            a = rnd.uniform(0, math.pi)
            ln = rnd.uniform(6, 22)
            bend = rnd.uniform(-0.5, 0.5)
            val = 128 + rnd.choice([-1, 1]) * rnd.randint(18, 40)
            pts = []
            for k in range(6):
                t = k / 5.0
                aa = a + bend * (t - 0.5)
                pts.append((x + math.cos(aa) * ln * (t - 0.5), y + math.sin(aa) * ln * (t - 0.5)))
            for ox in (-w, 0, w):
                for oy in (-h, 0, h):
                    d.line([(px + ox, py + oy) for px, py in pts], fill=val, width=1)
        fl = tile_blur(fl, 0.5)
        g = ImageChops.add(g, fl, 1.0, -128)
    return levels(g, 128, 14)


_GRAIN_CACHE = {}


def grain_for(size, seed=7, scale=1.0):
    """Grain image (L, neutral 128) of any size, built by tiling a 512 tile."""
    key = (seed, scale)
    if key not in _GRAIN_CACHE:
        _GRAIN_CACHE[key] = grain_tile((512, 512), seed)
    tile = _GRAIN_CACHE[key]
    if scale != 1.0:
        ts = max(8, int(512 * scale))
        tile = tile.resize((ts, ts), BICUBIC)
    w, h = size
    out = Image.new("L", size)
    tw, th = tile.size
    for y in range(0, h, th):
        for x in range(0, w, tw):
            out.paste(tile, (x, y))
    return out


def apply_grain(img, strength=0.06, seed=7, scale=1.0, mask=None):
    """Multiply-style paper grain on an RGBA image (alpha untouched)."""
    g = grain_for(img.size, seed, scale)
    # per-pixel factor f = 1 + s*(g-128)/128. ImageChops.multiply can only
    # scale by <= 1, so multiply by f/(1+s) and then gain by (1+s).
    lut = []
    for v in range(256):
        f = (1.0 + strength * (v - 128) / 128.0) / (1.0 + strength)
        lut.append(max(0, min(255, int(round(255 * f)))))
    gl = g.point(lut)
    rgb = img.convert("RGB")
    mult = ImageChops.multiply(rgb, Image.merge("RGB", (gl, gl, gl)))
    # undo the global darkening introduced by the normalisation
    gain = 1.0 + strength
    mult = mult.point([max(0, min(255, int(round(v * gain)))) for v in range(256)] * 3)
    out = Image.merge("RGBA", (*mult.split(), img.getchannel("A")))
    if mask is not None:
        out = Image.composite(out, img, mask)
    return out


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------
def down(img, size):
    """High quality reduction (premultiplied for RGBA so edges stay clean)."""
    if isinstance(size, int):
        size = (size, size)
    if img.mode == "RGBA":
        return img.convert("RGBa").resize(size, LANCZOS).convert("RGBA")
    return img.resize(size, LANCZOS)


def ensure_dir(path):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)


def save(img, path):
    ensure_dir(path)
    img.save(path, optimize=True)
    return path


def rel(path):
    return os.path.relpath(path, ROOT)


_FONTS = {}


def font(px, path=FONT_BALOO):
    key = (path, int(px))
    if key not in _FONTS:
        _FONTS[key] = ImageFont.truetype(path, int(px))
    return _FONTS[key]


# --------------------------------------------------------------------------
# Sticker look
# --------------------------------------------------------------------------
def sticker_border(mask, r, smooth_r=None):
    """Die-cut sticker silhouette: disk dilation + gentle smoothing."""
    m = dilate(mask, r)
    sr = smooth_r if smooth_r is not None else max(1.0, r * 0.18)
    return smooth(m, sr)


# --------------------------------------------------------------------------
# Tiny 3D helpers (for folded paper facets)
# --------------------------------------------------------------------------
def v3_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def v3_cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def v3_dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def v3_norm(a):
    l = math.sqrt(v3_dot(a, a))
    return (a[0] / l, a[1] / l, a[2] / l) if l > 1e-12 else (0.0, 0.0, 0.0)


def rot3(p, yaw=0.0, pitch=0.0, roll=0.0):
    """Rotate a point: roll about X (forward), pitch about Y, yaw about Z."""
    x, y, z = p
    r = math.radians(roll)
    y, z = y * math.cos(r) - z * math.sin(r), y * math.sin(r) + z * math.cos(r)
    pch = math.radians(pitch)
    x, z = x * math.cos(pch) - z * math.sin(pch), x * math.sin(pch) + z * math.cos(pch)
    yw = math.radians(yaw)
    x, y = x * math.cos(yw) - y * math.sin(yw), x * math.sin(yw) + y * math.cos(yw)
    return (x, y, z)


def facet_shade(normal, light=(-0.45, -0.55, 0.70)):
    """Lambert term for a facet normal; normal z points at the viewer."""
    n = v3_norm(normal)
    if n[2] < 0:
        n = (-n[0], -n[1], -n[2])
    l = v3_norm(light)
    return max(0.0, v3_dot(n, l))


class Ctx:
    """Maps design units to supersampled pixels: px = (u - ox) * k."""

    def __init__(self, w_units, h_units, k, ox=0.0, oy=0.0, size=None):
        self.k = float(k)
        self.ox, self.oy = ox, oy
        self.size = size or (int(round(w_units * k)), int(round(h_units * k)))

    def p(self, x, y):
        return ((x - self.ox) * self.k, (y - self.oy) * self.k)

    def pts(self, pts):
        return [((x - self.ox) * self.k, (y - self.oy) * self.k) for x, y in pts]

    def d(self, v):
        return v * self.k

    def mask(self):
        return new_mask(self.size)

    def layer(self):
        return Image.new("RGBA", self.size, (0, 0, 0, 0))

    # mask shortcuts in design units
    def poly(self, pts, rr=0.0):
        P = self.pts(pts)
        if rr:
            P = round_poly(P, rr * self.k if not isinstance(rr, (list, tuple)) else [r * self.k for r in rr],
                           seg=max(1.5, self.k * 0.5))
        return m_poly(self.size, P)

    def circle(self, cx, cy, r):
        x, y = self.p(cx, cy)
        return m_circle(self.size, x, y, r * self.k)

    def ellipse(self, cx, cy, rx, ry, rot=0.0):
        x, y = self.p(cx, cy)
        return m_ellipse(self.size, x, y, rx * self.k, ry * self.k, rot)

    def rrect(self, x0, y0, x1, y1, r):
        a = self.p(x0, y0)
        b = self.p(x1, y1)
        return m_rrect(self.size, a[0], a[1], b[0], b[1], r * self.k)

    def stroke(self, pts, w, closed=False, caps=True):
        return m_stroke(self.size, self.pts(pts), w * self.k, closed=closed, caps=caps)

    def text(self, s, x, y, px, anchor="mm"):
        X, Y = self.p(x, y)
        return m_text(self.size, s, font(px * self.k), X, Y, anchor)
