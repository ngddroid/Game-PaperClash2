package com.ngstudio.paperclash2.engine.core

import kotlin.math.floor
import kotlin.math.sqrt

object MathX {
    const val PI = 3.1415927f
    const val TAU = 6.2831855f

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun wrapAngle(a: Float): Float {
        var x = a
        while (x > PI) x -= TAU
        while (x < -PI) x += TAU
        return x
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp((x - e0) / (e1 - e0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun len(x: Float, y: Float): Float = sqrt(x * x + y * y)

    fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    /** Smooth 2D value noise in roughly [0, 1], deterministic for a given seed. */
    fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = Rng.hash01(x0 * 73856093 xor seed, y0 * 19349663)
        val b = Rng.hash01((x0 + 1) * 73856093 xor seed, y0 * 19349663)
        val c = Rng.hash01(x0 * 73856093 xor seed, (y0 + 1) * 19349663)
        val d = Rng.hash01((x0 + 1) * 73856093 xor seed, (y0 + 1) * 19349663)
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sy)
    }
}
