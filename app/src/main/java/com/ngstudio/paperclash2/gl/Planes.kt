package com.ngstudio.paperclash2.gl

import android.opengl.GLES30
import android.opengl.Matrix
import com.ngstudio.paperclash2.game.PlaneModel
import com.ngstudio.paperclash2.game.Palette
import kotlin.math.sqrt

/**
 * Low-poly origami planes, defined as triangles in model space (x forward, y left, z up,
 * nose at x ≈ 1). Flat shaded, double sided.
 */
object PlaneMeshes {
    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun my(p: FloatArray) = floatArrayOf(p[0], -p[1], p[2])

    fun triangles(model: PlaneModel): List<Array<FloatArray>> {
        val t = ArrayList<Array<FloatArray>>()
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray) { t += arrayOf(a, b, c) }
        fun pair(a: FloatArray, b: FloatArray, c: FloatArray) { tri(a, b, c); tri(my(a), my(c), my(b)) }
        when (model) {
            PlaneModel.DART -> {
                val n = v(1f, 0f, 0f); val l = v(-0.8f, 0.6f, 0.26f); val c = v(-0.8f, 0f, 0.02f); val k = v(-0.72f, 0f, -0.3f)
                pair(n, l, c)
                tri(n, k, c)
            }
            PlaneModel.GLIDER -> {
                val n = v(0.9f, 0f, 0f); val w = v(-0.25f, 0.9f, 0.3f); val tl = v(-0.82f, 0.36f, 0.14f)
                val c = v(-0.82f, 0f, 0.02f); val k = v(-0.6f, 0f, -0.22f)
                pair(n, w, tl)
                pair(n, tl, c)
                tri(n, k, c)
            }
            PlaneModel.SWALLOW -> {
                val n = v(1f, 0f, 0f); val tip = v(-0.38f, 0.66f, 0.26f); val fork = v(-0.95f, 0.34f, 0.16f)
                val notch = v(-0.58f, 0f, 0.03f); val k = v(-0.5f, 0f, -0.24f)
                pair(n, tip, fork)
                pair(n, fork, notch)
                tri(n, k, notch)
            }
            PlaneModel.HAWK -> {
                val n = v(1f, 0f, 0f); val l = v(-0.78f, 0.56f, 0.24f); val c = v(-0.78f, 0f, 0.02f); val k = v(-0.7f, 0f, -0.3f)
                val wl = v(-0.72f, 0.6f, 0.44f); val wb = v(-0.38f, 0.42f, 0.1f)
                pair(n, l, c)
                tri(n, k, c)
                pair(l, wb, wl)
            }
            PlaneModel.CRANE -> {
                val bf = v(0.32f, 0f, 0.08f); val bb = v(-0.32f, 0f, 0.08f); val bot = v(0f, 0f, -0.18f)
                val wl = v(-0.05f, 0.98f, 0.52f); val nt = v(0.92f, 0f, 0.62f); val head = v(1.08f, 0f, 0.48f)
                val tt = v(-0.95f, 0f, 0.55f)
                pair(bf, wl, bb)
                tri(bf, bb, bot)
                tri(bf, nt, v(0.22f, 0f, -0.06f))
                tri(nt, head, v(0.82f, 0f, 0.48f))
                tri(bb, tt, v(-0.22f, 0f, -0.06f))
            }
            PlaneModel.JET -> {
                val n = v(1.05f, 0f, 0.02f); val w = v(-0.55f, 0.74f, 0.22f); val tl = v(-0.85f, 0.26f, 0.12f)
                val c = v(-0.85f, 0f, 0.12f); val fin = v(-0.78f, 0f, 0.5f); val finBase = v(-0.32f, 0f, 0.06f)
                pair(n, w, tl)
                pair(n, tl, c)
                pair(v(0.62f, 0.05f, 0.04f), v(0.38f, 0.34f, 0.05f), v(0.3f, 0.06f, 0.05f))
                tri(c, fin, finBase)
            }
        }
        return t
    }

    /** Interleaved position(3) normal(3) uv(2) for every triangle corner. */
    fun build(model: PlaneModel): FloatArray {
        val tris = triangles(model)
        val out = FloatArray(tris.size * 3 * 8)
        var o = 0
        for (t in tris) {
            val a = t[0]; val b = t[1]; val c = t[2]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= l; ny /= l; nz /= l
            if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            for (p in t) {
                out[o++] = p[0]; out[o++] = p[1]; out[o++] = p[2]
                out[o++] = nx; out[o++] = ny; out[o++] = nz
                out[o++] = p[0]; out[o++] = p[1]
            }
        }
        return out
    }
}

/** Draws planes with banking, bobbing and per-skin patterns. */
class PlaneRenderer {
    private lateinit var program: GlProgram
    private val vao = IntArray(PlaneModel.entries.size)
    private val vbo = IntArray(PlaneModel.entries.size)
    private val counts = IntArray(PlaneModel.entries.size)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)

    fun create() {
        program = GlProgram(Shaders.PLANE_VS, Shaders.PLANE_FS, "plane")
        for (m in PlaneModel.entries) {
            val data = PlaneMeshes.build(m)
            val fb = Gl.floats(data.size)
            fb.put(data).position(0)
            vao[m.ordinal] = Gl.genVao()
            vbo[m.ordinal] = Gl.genBuffer()
            GLES30.glBindVertexArray(vao[m.ordinal])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[m.ordinal])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
            counts[m.ordinal] = data.size / 8
        }
        GLES30.glBindVertexArray(0)
    }

    fun begin(vp: FloatArray, camera: Camera, grain: Int) {
        program.use()
        GLES30.glUniformMatrix4fv(program.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform3f(program.u("uLight"), Board.LX, Board.LY, Board.LZ)
        GLES30.glUniform3f(program.u("uEye"), camera.eye[0], camera.eye[1], camera.eye[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grain)
        GLES30.glUniform1i(program.u("uGrain"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * One plane at (x, y, z) heading [angleDeg], rolled by [bankDeg], scaled by [scale].
     * [flash] tints toward [flashColor] (shield shimmer, spawn pop).
     */
    fun draw(
        m: PlaneModel, x: Float, y: Float, z: Float, angleDeg: Float, bankDeg: Float, pitchDeg: Float, scale: Float,
        base: Int, accent: Int, pattern: Int, flash: Float, flashColor: Int,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.rotateM(model, 0, angleDeg, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, pitchDeg, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, bankDeg, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, scale, scale, scale)
        GLES30.glUniformMatrix4fv(program.u("uModel"), 1, false, model, 0)
        GLES30.glUniform3f(program.u("uBase"), Palette.r(base), Palette.g(base), Palette.b(base))
        GLES30.glUniform3f(program.u("uAccent"), Palette.r(accent), Palette.g(accent), Palette.b(accent))
        GLES30.glUniform1i(program.u("uPattern"), pattern)
        GLES30.glUniform1f(program.u("uFlash"), flash)
        GLES30.glUniform3f(program.u("uFlashColor"), Palette.r(flashColor), Palette.g(flashColor), Palette.b(flashColor))
        GLES30.glBindVertexArray(vao[m.ordinal])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, counts[m.ordinal])
    }

    fun end() {
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
        for (i in vao.indices) {
            Gl.deleteVao(vao[i])
            Gl.deleteBuffer(vbo[i])
        }
    }
}
