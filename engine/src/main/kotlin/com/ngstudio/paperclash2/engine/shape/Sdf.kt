package com.ngstudio.paperclash2.engine.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signed distance to a 2D shape (negative inside), in the shape's own units: shapes are authored
 * roughly inside [-1, 1] with y pointing up. Only the sign decides which cells are playable; the
 * distance keeps smooth unions round.
 */
fun interface Sdf {
    fun d(x: Float, y: Float): Float
}

/** Primitives and operators used by [ShapeLibrary]. */
object S {
    fun circle(cx: Float, cy: Float, r: Float) = Sdf { x, y ->
        val dx = x - cx
        val dy = y - cy
        sqrt(dx * dx + dy * dy) - r
    }

    /** Ellipse (approximate distance, exact sign). */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = Sdf { x, y ->
        val nx = (x - cx) / rx
        val ny = (y - cy) / ry
        (sqrt(nx * nx + ny * ny) - 1f) * min(rx, ry)
    }

    /** Box centred at (cx, cy) with half sizes and corner radius. */
    fun box(cx: Float, cy: Float, hw: Float, hh: Float, r: Float = 0f) = Sdf { x, y ->
        val qx = abs(x - cx) - hw + r
        val qy = abs(y - cy) - hh + r
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
    }

    /** Line segment from (ax, ay) to (bx, by) thickened by r. */
    fun capsule(ax: Float, ay: Float, bx: Float, by: Float, r: Float) = Sdf { x, y ->
        val pax = x - ax
        val pay = y - ay
        val bax = bx - ax
        val bay = by - ay
        val h = ((pax * bax + pay * bay) / (bax * bax + bay * bay)).coerceIn(0f, 1f)
        val dx = pax - bax * h
        val dy = pay - bay * h
        sqrt(dx * dx + dy * dy) - r
    }

    /** Annulus: circle outline of radius r and half thickness w. */
    fun ring(cx: Float, cy: Float, r: Float, w: Float) = Sdf { x, y ->
        val dx = x - cx
        val dy = y - cy
        abs(sqrt(dx * dx + dy * dy) - r) - w
    }

    /** Exact polygon distance (vertices x0, y0, x1, y1, ...; any winding). */
    fun polygon(vararg v: Float): Sdf {
        val n = v.size / 2
        require(n >= 3)
        return Sdf { px, py ->
            var d = (px - v[0]) * (px - v[0]) + (py - v[1]) * (py - v[1])
            var s = 1f
            var j = n - 1
            for (i in 0 until n) {
                val vix = v[i * 2]
                val viy = v[i * 2 + 1]
                val vjx = v[j * 2]
                val vjy = v[j * 2 + 1]
                val ex = vjx - vix
                val ey = vjy - viy
                val wx = px - vix
                val wy = py - viy
                val t = ((wx * ex + wy * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
                val bx = wx - ex * t
                val by = wy - ey * t
                d = min(d, bx * bx + by * by)
                val c1 = py >= viy
                val c2 = py < vjy
                val c3 = ex * wy > ey * wx
                if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
                j = i
            }
            s * sqrt(d)
        }
    }

    /** Regular n-gon with circumradius r; rot in degrees (90 puts a vertex on top). */
    fun regular(n: Int, cx: Float, cy: Float, r: Float, rotDeg: Float): Sdf {
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = (rotDeg * PI / 180.0 + i * 2.0 * PI / n)
            pts[i * 2] = cx + (r * cos(a)).toFloat()
            pts[i * 2 + 1] = cy + (r * sin(a)).toFloat()
        }
        return polygon(*pts)
    }

    /** Star with n points, outer and inner radii; rot in degrees (90 puts a point on top). */
    fun star(n: Int, cx: Float, cy: Float, outer: Float, inner: Float, rotDeg: Float): Sdf {
        val pts = FloatArray(n * 4)
        for (i in 0 until n * 2) {
            val a = rotDeg * PI / 180.0 + i * PI / n
            val r = if (i % 2 == 0) outer else inner
            pts[i * 2] = cx + (r * cos(a)).toFloat()
            pts[i * 2 + 1] = cy + (r * sin(a)).toFloat()
        }
        return polygon(*pts)
    }

    /** Heart centred at (cx, cy); [size] ~ total width / 1.2. */
    fun heart(cx: Float, cy: Float, size: Float) = Sdf { x, y ->
        var px = abs((x - cx) / size)
        val py = (y - cy) / size + 0.55f
        val d = if (py + px > 1f) {
            val dx = px - 0.25f
            val dy = py - 0.75f
            sqrt(dx * dx + dy * dy) - 0.35355338f
        } else {
            val ax = px
            val ay = py - 1f
            val m = 0.5f * max(px + py, 0f)
            val bx = px - m
            val by = py - m
            px = px - py
            sqrt(min(ax * ax + ay * ay, bx * bx + by * by)) * (if (px >= 0f) 1f else -1f)
        }
        d * size
    }

    /** Wobbly blob: a circle whose radius waves with angle. */
    fun blob(cx: Float, cy: Float, r: Float, a1: Float, f1: Int, a2: Float, f2: Int, phase: Float) = Sdf { x, y ->
        val dx = x - cx
        val dy = y - cy
        val ang = atan2(dy, dx)
        val rr = r * (1f + a1 * sin(f1 * ang + phase) + a2 * sin(f2 * ang + phase * 1.7f))
        (sqrt(dx * dx + dy * dy) - rr) * 0.8f
    }

    /** Half plane: inside where (x, y) is on the side the normal (nx, ny) points away from. */
    fun halfPlane(nx: Float, ny: Float, offset: Float) = Sdf { x, y -> x * nx + y * ny - offset }

    fun union(vararg s: Sdf) = Sdf { x, y ->
        var d = Float.MAX_VALUE
        for (k in s) d = min(d, k.d(x, y))
        d
    }

    fun smooth(k: Float, vararg s: Sdf) = Sdf { x, y ->
        var d = s[0].d(x, y)
        for (i in 1 until s.size) {
            val b = s[i].d(x, y)
            val h = (0.5f + 0.5f * (b - d) / k).coerceIn(0f, 1f)
            d = b + (d - b) * h - k * h * (1f - h)
        }
        d
    }

    fun subtract(a: Sdf, b: Sdf) = Sdf { x, y -> max(a.d(x, y), -b.d(x, y)) }

    fun intersect(a: Sdf, b: Sdf) = Sdf { x, y -> max(a.d(x, y), b.d(x, y)) }

    /** Rotates [s] by deg around (cx, cy). */
    fun rotate(s: Sdf, deg: Float, cx: Float = 0f, cy: Float = 0f): Sdf {
        val a = (-deg * PI / 180.0)
        val c = cos(a).toFloat()
        val sn = sin(a).toFloat()
        return Sdf { x, y ->
            val dx = x - cx
            val dy = y - cy
            s.d(cx + dx * c - dy * sn, cy + dx * sn + dy * c)
        }
    }

    /** Mirrors [s] across the vertical axis and unites both halves (symmetric shapes). */
    fun mirrorX(s: Sdf) = Sdf { x, y -> s.d(abs(x), y) }
}
