package com.ngstudio.paperclash2.gl

import android.opengl.GLES30
import kotlin.math.cos
import kotlin.math.sin

/**
 * Batched textured quads (premultiplied colour): flat decals lying on the board, and billboards
 * facing the camera. One draw call per texture switch.
 */
class SpriteBatch(private val maxQuads: Int = 3000) {
    private lateinit var program: GlProgram
    private var vbo = 0
    private var ibo = 0
    private var vao = 0
    private val data = FloatArray(maxQuads * 4 * STRIDE)
    private val buffer = Gl.floats(maxQuads * 4 * STRIDE)
    private var count = 0
    private var texture = 0
    private var vp: FloatArray? = null

    val right = FloatArray(3)
    val up = FloatArray(3)

    fun create() {
        program = GlProgram(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "sprite")
        vao = Gl.genVao()
        vbo = Gl.genBuffer()
        ibo = Gl.genBuffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val idx = Gl.shorts(maxQuads * 6)
        for (q in 0 until maxQuads) {
            val b = (q * 4).toShort()
            idx.put(b); idx.put((b + 1).toShort()); idx.put((b + 2).toShort())
            idx.put((b + 2).toShort()); idx.put((b + 3).toShort()); idx.put(b)
        }
        idx.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, maxQuads * 12, idx, GLES30.GL_STATIC_DRAW)
        val s = STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, s, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, s, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, s, 20)
        GLES30.glBindVertexArray(0)
    }

    fun begin(viewProj: FloatArray, camera: Camera) {
        vp = viewProj
        camera.billboardAxes(right, up)
        count = 0
        texture = 0
    }

    private fun bind(tex: Int) {
        if (tex != texture) {
            flush()
            texture = tex
        }
        if (count >= maxQuads) flush()
    }

    private fun put(o: Int, x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) {
        data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = u; data[o + 4] = v
        data[o + 5] = r * a; data[o + 6] = g * a; data[o + 7] = b * a; data[o + 8] = a
    }

    /** A decal lying flat at height [z], centred at (x, y), rotated by [angle] radians. */
    fun flat(
        tex: Int, s: Sprite, x: Float, y: Float, z: Float, halfW: Float, halfH: Float, angle: Float,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
    ) {
        bind(tex)
        val c = cos(angle)
        val sn = sin(angle)
        val ax = c * halfW
        val ay = sn * halfW
        val bx = -sn * halfH
        val by = c * halfH
        val o = count * 4 * STRIDE
        // texture v grows downward in the bitmap; map top of sprite to +halfH
        put(o, x - ax - bx, y - ay - by, z, s.u0, s.v1, r, g, b, a)
        put(o + STRIDE, x + ax - bx, y + ay - by, z, s.u1, s.v1, r, g, b, a)
        put(o + 2 * STRIDE, x + ax + bx, y + ay + by, z, s.u1, s.v0, r, g, b, a)
        put(o + 3 * STRIDE, x - ax + bx, y - ay + by, z, s.u0, s.v0, r, g, b, a)
        count++
    }

    /** A camera-facing quad centred at (x, y, z), optionally rotated in the screen plane. */
    fun billboard(
        tex: Int, u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, z: Float,
        halfW: Float, halfH: Float, rot: Float = 0f, r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
    ) {
        bind(tex)
        val c = cos(rot)
        val sn = sin(rot)
        // rotated in-plane axes
        val rx = right[0] * c + up[0] * sn
        val ry = right[1] * c + up[1] * sn
        val rz = right[2] * c + up[2] * sn
        val ux = -right[0] * sn + up[0] * c
        val uy = -right[1] * sn + up[1] * c
        val uz = -right[2] * sn + up[2] * c
        val o = count * 4 * STRIDE
        put(o, x - rx * halfW - ux * halfH, y - ry * halfW - uy * halfH, z - rz * halfW - uz * halfH, u0, v1, r, g, b, a)
        put(o + STRIDE, x + rx * halfW - ux * halfH, y + ry * halfW - uy * halfH, z + rz * halfW - uz * halfH, u1, v1, r, g, b, a)
        put(o + 2 * STRIDE, x + rx * halfW + ux * halfH, y + ry * halfW + uy * halfH, z + rz * halfW + uz * halfH, u1, v0, r, g, b, a)
        put(o + 3 * STRIDE, x - rx * halfW + ux * halfH, y - ry * halfW + uy * halfH, z - rz * halfW + uz * halfH, u0, v0, r, g, b, a)
        count++
    }

    fun billboard(tex: Int, s: Sprite, x: Float, y: Float, z: Float, halfW: Float, halfH: Float, rot: Float = 0f,
                  r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) =
        billboard(tex, s.u0, s.v0, s.u1, s.v1, x, y, z, halfW, halfH, rot, r, g, b, a)

    fun flush() {
        val m = vp ?: return
        if (count == 0) return
        program.use()
        GLES30.glUniformMatrix4fv(program.u("uVP"), 1, false, m, 0)
        GLES30.glUniform1i(program.u("uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        buffer.position(0)
        buffer.put(data, 0, count * 4 * STRIDE)
        buffer.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 4 * STRIDE * 4, buffer)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count * 6, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        count = 0
    }

    fun end() = flush()

    fun release() {
        program.release()
        Gl.deleteBuffer(vbo)
        Gl.deleteBuffer(ibo)
        Gl.deleteVao(vao)
    }

    companion object {
        const val STRIDE = 9
    }
}
