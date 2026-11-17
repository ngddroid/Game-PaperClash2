package com.ngstudio.paperclash2.gl

import com.ngstudio.paperclash2.game.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Paper confetti and sparkles: simple physics on the CPU (gravity, a bounce on the board,
 * tumbling), drawn as camera-facing quads squashed by their flip phase.
 */
class Particles(capacity: Int = 900) {
    private val cap = capacity
    private val x = FloatArray(cap)
    private val y = FloatArray(cap)
    private val z = FloatArray(cap)
    private val vx = FloatArray(cap)
    private val vy = FloatArray(cap)
    private val vz = FloatArray(cap)
    private val rot = FloatArray(cap)
    private val spin = FloatArray(cap)
    private val flip = FloatArray(cap)
    private val flipSpeed = FloatArray(cap)
    private val life = FloatArray(cap)
    private val maxLife = FloatArray(cap)
    private val size = FloatArray(cap)
    private val color = IntArray(cap)
    private val kind = IntArray(cap)
    private var count = 0
    private var seed = 1234567

    /** Fraction of the requested amount actually spawned (lower on battery-saving quality). */
    var density = 1f

    private fun rnd(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    private fun spawn(px: Float, py: Float, pz: Float, svx: Float, svy: Float, svz: Float, c: Int, k: Int, s: Float, l: Float) {
        if (count >= cap) return
        val i = count++
        x[i] = px; y[i] = py; z[i] = pz
        vx[i] = svx; vy[i] = svy; vz[i] = svz
        rot[i] = rnd() * 6.28f
        spin[i] = (rnd() - 0.5f) * 14f
        flip[i] = rnd() * 6.28f
        flipSpeed[i] = 6f + rnd() * 10f
        life[i] = l
        maxLife[i] = l
        size[i] = s
        color[i] = c
        kind[i] = k
    }

    /** Confetti burst from a point (captures, snips). */
    fun burst(px: Float, py: Float, pz: Float, n: Int, colors: IntArray, power: Float) {
        val count = (n * density).toInt()
        for (k in 0 until count) {
            val a = rnd() * 6.28f
            val sp = (2f + rnd() * 5f) * power
            val c = colors[(rnd() * colors.size).toInt().coerceIn(0, colors.size - 1)]
            spawn(px, py, pz, cos(a) * sp, sin(a) * sp, 4f + rnd() * 7f * power, c, if (rnd() < 0.6f) 0 else 1, 0.22f + rnd() * 0.22f, 1.2f + rnd() * 0.9f)
        }
    }

    /** Confetti strewn along a closed ribbon (capture), rising a little. */
    fun along(pts: FloatArray, size: Int, color: Int, every: Int) {
        var i = 0
        while (i < size) {
            if (rnd() < density) {
                spawn(pts[i], pts[i + 1], 0.4f, (rnd() - 0.5f) * 2f, (rnd() - 0.5f) * 2f, 3f + rnd() * 4f, if (rnd() < 0.5f) color else 0xFFFFFFFF.toInt(),
                    if (rnd() < 0.5f) 0 else 1, 0.18f + rnd() * 0.16f, 0.9f + rnd() * 0.6f)
            }
            i += every * 2
        }
    }

    /** White twinkles (pickups, stars). */
    fun sparkle(px: Float, py: Float, pz: Float, n: Int, c: Int) {
        for (k in 0 until n) {
            val a = rnd() * 6.28f
            val sp = 1f + rnd() * 3f
            spawn(px, py, pz, cos(a) * sp, sin(a) * sp, 2f + rnd() * 3f, c, 2, 0.35f + rnd() * 0.3f, 0.6f + rnd() * 0.4f)
        }
    }

    /** Surface the paper scraps land on; null = a flat floor at the given height. */
    var ground: GroundHeight? = null

    fun update(dt: Float, groundZ: Float) {
        val g = ground
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                val last = count - 1
                if (i != last) copy(last, i)
                count--
                continue
            }
            val drag = if (kind[i] == 2) 0.9f else 0.97f
            vx[i] *= drag
            vy[i] *= drag
            vz[i] -= (if (kind[i] == 2) 6f else 14f) * dt
            if (kind[i] != 2 && vz[i] < -3f) vz[i] = -3f // paper flutters down slowly
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            z[i] += vz[i] * dt
            val floorZ = if (g != null) g.at(x[i], y[i]) + 0.02f else groundZ
            if (z[i] < floorZ) {
                z[i] = floorZ
                vz[i] = abs(vz[i]) * 0.25f
                vx[i] *= 0.6f
                vy[i] *= 0.6f
                spin[i] *= 0.5f
                flipSpeed[i] *= 0.4f
            }
            rot[i] += spin[i] * dt
            flip[i] += flipSpeed[i] * dt
            i++
        }
    }

    private fun copy(from: Int, to: Int) {
        x[to] = x[from]; y[to] = y[from]; z[to] = z[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        rot[to] = rot[from]; spin[to] = spin[from]; flip[to] = flip[from]; flipSpeed[to] = flipSpeed[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from]; color[to] = color[from]; kind[to] = kind[from]
    }

    fun draw(batch: SpriteBatch, atlas: Atlas) {
        if (count == 0) return
        val square = atlas.sprite("confetti_square")
        val strip = atlas.sprite("confetti_strip")
        val sparkle = atlas.sprite("sparkle")
        val tex = atlas.texture
        for (i in 0 until count) {
            val t = life[i] / maxLife[i]
            val a = (t * 3f).coerceAtMost(1f)
            val c = color[i]
            val s = size[i]
            when (kind[i]) {
                2 -> batch.billboard(tex, sparkle, x[i], y[i], z[i], s * t, s * t, rot[i], Palette.r(c), Palette.g(c), Palette.b(c), a)
                else -> {
                    val sq = if (kind[i] == 0) square else strip
                    val f = 0.25f + 0.75f * abs(cos(flip[i]))
                    val shade = 0.82f + 0.18f * abs(sin(flip[i]))
                    batch.billboard(tex, sq, x[i], y[i], z[i], s * f * (if (kind[i] == 1) 0.5f else 1f), s * (if (kind[i] == 1) 1.4f else 1f),
                        rot[i], Palette.r(c) * shade, Palette.g(c) * shade, Palette.b(c) * shade, a)
                }
            }
        }
    }

    fun clear() {
        count = 0
    }
}
