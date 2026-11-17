package com.ngstudio.paperclash2.gl

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/**
 * Tilted perspective camera that looks down at the board. It follows a focus point with a
 * critically damped spring, has a distance ("zoom") and a yaw for showcase orbits, and adds a
 * decaying shake.
 */
class Camera {
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val vp = FloatArray(16)
    private val tmp = FloatArray(16)

    var aspect = 0.5f
    var fovY = 42f
    /** Degrees above the horizon (90 = straight down). */
    var pitch = 52f
    /** Degrees around the vertical axis (0 = looking north / +y). */
    var yaw = 0f

    var x = 0f
    var y = 0f
    var dist = 50f
    private var vx = 0f
    private var vy = 0f
    var targetX = 0f
    var targetY = 0f
    var targetDist = 50f
    /** Height the camera looks at (the surface of the 3D shape under the focus). */
    var z = 0f
    var targetZ = 0f

    private var shake = 0f
    private var shakeTime = 0f
    private var clock = 0f

    val eye = FloatArray(3)

    /** Distance at which [cellsAcross] cells fill the screen width at the focus. */
    fun distForWidth(cellsAcross: Float): Float {
        val halfV = tan(Math.toRadians(fovY / 2.0)).toFloat()
        return cellsAcross / (2f * halfV * aspect)
    }

    /** Distance at which a square of [cells] fits the screen (both directions). */
    fun distToFit(cells: Float): Float {
        val halfV = tan(Math.toRadians(fovY / 2.0)).toFloat()
        val byWidth = cells / (2f * halfV * aspect)
        val byHeight = cells / (2f * halfV) * 1.15f
        return maxOf(byWidth, byHeight)
    }

    /** Focus shift along y found by the last [fitBox] call. */
    var fitOffset = 0f
        private set

    /**
     * Distance at which a [w]×[h] ground box fits the screen (yaw 0), keeping the given fractions
     * of the screen clear at the sides, top and bottom. The near edge looms larger under
     * perspective, so the focus also slides along y to centre the box in the clear area: aim at
     * the box centre plus [fitOffset].
     */
    fun fitBox(w: Float, h: Float, side: Float, top: Float, bottom: Float): Float {
        val t = tan(Math.toRadians(fovY / 2.0)).toFloat()
        val p = Math.toRadians(pitch.toDouble())
        val sp = sin(p).toFloat()
        val cp = cos(p).toFloat()
        val xLim = 1f - 2f * side
        val topLim = 1f - 2f * top
        val bottomLim = 1f - 2f * bottom
        val mid = (topLim - bottomLim) * 0.5f
        fun ndcY(d: Float, dy: Float) = dy * sp / ((d + dy * cp) * t)
        fun fits(d: Float, off: Float): Boolean {
            var sy = -1
            while (sy <= 1) {
                val dy = sy * h * 0.5f - off
                val z = d + dy * cp
                if (z <= 1f) return false
                if (w * 0.5f / (z * t * aspect) > xLim) return false
                val yN = dy * sp / (z * t)
                if (yN > topLim || -yN > bottomLim) return false
                sy += 2
            }
            return true
        }
        val steps = 24
        val ds = FloatArray(steps + 1)
        val offs = FloatArray(steps + 1)
        var minD = Float.MAX_VALUE
        for (i in 0..steps) {
            val off = h * (-0.3f + 0.6f * i / steps)
            offs[i] = off
            ds[i] = Float.MAX_VALUE
            var lo = 1f
            var hi = 20000f
            if (!fits(hi, off)) continue
            repeat(36) {
                val m = (lo + hi) * 0.5f
                if (fits(m, off)) hi = m else lo = m
            }
            ds[i] = hi
            if (hi < minD) minD = hi
        }
        if (minD == Float.MAX_VALUE) {
            fitOffset = 0f
            return distToFit(maxOf(w, h))
        }
        // among framings that are nearly as tight as the tightest, take the best centred one
        var best = -1
        var bestErr = Float.MAX_VALUE
        for (i in 0..steps) {
            val d = ds[i]
            if (d > minD * 1.12f) continue
            val far = ndcY(d, h * 0.5f - offs[i])
            val near = ndcY(d, -h * 0.5f - offs[i])
            val err = kotlin.math.abs((far + near) * 0.5f - mid)
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        fitOffset = offs[best]
        return ds[best]
    }

    fun snap(px: Float, py: Float, d: Float) {
        x = px; y = py; dist = d
        targetX = px; targetY = py; targetDist = d
        z = targetZ
        vx = 0f; vy = 0f
    }

    fun shake(amount: Float, time: Float) {
        if (amount > shake) shake = amount
        if (time > shakeTime) shakeTime = time
    }

    /** Springs toward the target; [stiff] ~ 6 for following, lower for slow cinematic moves. */
    fun update(dt: Float, stiff: Float = 6f, zoomRate: Float = 2.5f) {
        clock += dt
        val k = stiff
        // critically damped spring
        val ax = (targetX - x) * k * k - 2f * k * vx
        val ay = (targetY - y) * k * k - 2f * k * vy
        vx += ax * dt
        vy += ay * dt
        x += vx * dt
        y += vy * dt
        dist += (targetDist - dist) * (1f - exp(-zoomRate * dt))
        z += (targetZ - z) * (1f - exp(-5f * dt))
        if (shakeTime > 0f) {
            shakeTime -= dt
            if (shakeTime <= 0f) shake = 0f
        }
        build()
    }

    fun build() {
        Matrix.perspectiveM(proj, 0, fovY, aspect, 1f, dist * 4f + 200f)
        val p = Math.toRadians(pitch.toDouble())
        val yw = Math.toRadians(yaw.toDouble())
        val horiz = (cos(p) * dist).toFloat()
        val sx = if (shake > 0f) (sin(clock * 61.0) * shake).toFloat() else 0f
        val sy = if (shake > 0f) (cos(clock * 47.0) * shake).toFloat() else 0f
        val cx = x + sx
        val cy = y + sy
        eye[0] = cx - (sin(yw) * horiz).toFloat()
        eye[1] = cy - (cos(yw) * horiz).toFloat()
        eye[2] = z + (sin(p) * dist).toFloat()
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], cx, cy, z, 0f, 0f, 1f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
    }

    private val vin = FloatArray(4)
    private val vout = FloatArray(4)

    /** Projects a world point to normalized screen (0..1, y down); returns false if behind. */
    fun project(wx: Float, wy: Float, wz: Float, out: FloatArray): Boolean {
        vin[0] = wx; vin[1] = wy; vin[2] = wz; vin[3] = 1f
        Matrix.multiplyMV(vout, 0, vp, 0, vin, 0)
        if (vout[3] <= 0f) return false
        out[0] = (vout[0] / vout[3]) * 0.5f + 0.5f
        out[1] = 1f - ((vout[1] / vout[3]) * 0.5f + 0.5f)
        return true
    }

    /** Camera right and up vectors in world space (for billboards). */
    fun billboardAxes(right: FloatArray, up: FloatArray) {
        right[0] = view[0]; right[1] = view[4]; right[2] = view[8]
        up[0] = view[1]; up[1] = view[5]; up[2] = view[9]
    }

    companion object {
        const val DEG = (180.0 / PI).toFloat()
    }
}
