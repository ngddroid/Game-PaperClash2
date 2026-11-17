package com.ngstudio.paperclash2.gl

import android.opengl.GLES30
import com.ngstudio.paperclash2.engine.world.World
import com.ngstudio.paperclash2.game.DeskTheme
import com.ngstudio.paperclash2.game.Palette
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Height of the surface something rests on at a board point (particles, shadows). */
fun interface GroundHeight {
    fun at(x: Float, y: Float): Float
}

/**
 * Everything drawn on the board for one [World]:
 * - the desk;
 * - the shape as a solid, puffy 3D slab: a domed top with a rounded lip, and real side walls that
 *   land paint drips down;
 * - the territory: a GPU-baked smooth field drawn as shadow + side + top on the 3D surface;
 * - the ribbons, which follow the surface.
 *
 * [heightAt] gives the surface height anywhere, so planes, shadows, pickups and labels sit on the
 * shape. Created and used on the GL thread only.
 */
class Board(val world: World, private val theme: DeskTheme, private val geo: BoardGeometry = BoardGeometry(world)) : GroundHeight {
    private val size = world.size
    private val n = size * RES

    private lateinit var deskProgram: GlProgram
    private lateinit var shadowProgram: GlProgram
    private lateinit var arenaProgram: GlProgram
    private lateinit var wallProgram: GlProgram
    private lateinit var bakeProgram: GlProgram
    private lateinit var territoryProgram: GlProgram
    private lateinit var ribbonProgram: GlProgram

    private var gridVao = 0
    private var gridVbo = 0
    private var deskVao = 0
    private var deskVbo = 0
    private var ndcVao = 0
    private var ndcVbo = 0
    private var wallVao = 0
    private var wallVbo = 0
    private var wallVerts = 0
    private var meshVao = 0
    private var meshVbo = 0
    private var meshIbo = 0
    private var meshIndices = 0
    private var ribbonVao = 0
    private var ribbonVbo = 0

    private var arenaTex = 0
    private var arenaShadowTex = 0
    private var ownerTex = 0
    private var stampTex = 0
    private var fieldTex = 0
    private var idsTex = 0
    private var fbo = 0
    var bakeOk = false
        private set

    private val ownerBuf: ByteBuffer = Gl.bytes(size * size)
    private val stampBuf: ShortBuffer = Gl.shorts(size * size)

    /** How high the dome rises above the rim, in cells. */
    val dome: Float get() = geo.dome

    /** Mean surface height over the shape (camera framing). */
    val midZ: Float get() = geo.midZ

    /** Per-player colours (index = player id), floats rgb. */
    private val top = FloatArray(16 * 3)
    private val side = FloatArray(16 * 3)
    private val acc = FloatArray(16 * 3)
    private val pat = FloatArray(16 * 2)

    // capture waves
    private val waveId = IntArray(8)
    private val wave = FloatArray(8 * 4)
    private var waveCount = 0

    private val ribbonData = FloatArray(MAX_RIBBON_VERTS * RIBBON_STRIDE)
    private val ribbonBuf: FloatBuffer = Gl.floats(MAX_RIBBON_VERTS * RIBBON_STRIDE)
    private val ribbonStart = IntArray(16)
    private val ribbonCount = IntArray(16)
    private val pts = FloatArray(4096)

    var showShadows = true
    val deskZ = -SLAB
    private var grain = 0
    private val eye = FloatArray(3)

    fun create(grain: Int) {
        this.grain = grain
        val pr = BoardPrograms.get()
        deskProgram = pr.desk
        shadowProgram = pr.shadow
        arenaProgram = pr.arena
        wallProgram = pr.wall
        bakeProgram = pr.bake
        territoryProgram = pr.territory
        ribbonProgram = pr.ribbon

        val s = size.toFloat()
        gridVao = Gl.genVao(); gridVbo = Gl.genBuffer()
        quad(gridVao, gridVbo, -6f, -6f, s + 6f, s + 6f)
        val m = s * 2.6f + 120f
        deskVao = Gl.genVao(); deskVbo = Gl.genBuffer()
        quad(deskVao, deskVbo, s / 2 - m, s / 2 - m, s / 2 + m, s / 2 + m)
        ndcVao = Gl.genVao(); ndcVbo = Gl.genBuffer()
        quad(ndcVao, ndcVbo, -1f, -1f, 1f, 1f)

        upload()
        createTerritory()

        ribbonVao = Gl.genVao()
        ribbonVbo = Gl.genBuffer()
        GLES30.glBindVertexArray(ribbonVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, ribbonData.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, RIBBON_STRIDE * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, RIBBON_STRIDE * 4, 12)
        GLES30.glBindVertexArray(0)
        Gl.check("board.create")
    }

    private fun quad(vao: Int, vbo: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val fb = Gl.floats(8)
        fb.put(floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y1)).position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
    }

    // ------------------------------------------------------------------ the 3D shape

    /** Surface height of the shape at a board point. */
    fun heightAt(x: Float, y: Float): Float = geo.heightAt(x, y)

    /** Top of the land paint at a board point. */
    fun groundZ(x: Float, y: Float): Float = geo.heightAt(x, y) + LAND_H

    override fun at(x: Float, y: Float): Float = groundZ(x, y)

    /** Slope of the surface along a heading (rise per cell), for pitching planes on the dome. */
    fun slopeAlong(x: Float, y: Float, c: Float, s: Float): Float =
        (geo.heightAt(x + c * 0.6f, y + s * 0.6f) - geo.heightAt(x - c * 0.6f, y - s * 0.6f)) / 1.2f

    /** Uploads the worker-built geometry: edge and shadow textures, walls, surface mesh. */
    private fun upload() {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        arenaTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arenaTex)
        Gl.params(linear = true, repeat = false)
        val eb = Gl.bytes(n * n)
        eb.put(geo.edge).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, n, n, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, eb)

        arenaShadowTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arenaShadowTex)
        Gl.params(linear = true, repeat = false)
        val sb = Gl.bytes(size * size)
        sb.put(geo.shadow).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, size, size, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, sb)

        val wd = geo.walls
        wallVerts = wd.size / 6
        val wb = Gl.floats(max(6, wd.size))
        wb.put(wd).position(0)
        wallVao = Gl.genVao()
        wallVbo = Gl.genBuffer()
        GLES30.glBindVertexArray(wallVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, wallVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, max(6, wd.size) * 4, wb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)

        val md = geo.mesh
        val mi = geo.meshIndex
        meshIndices = mi.size
        val fb = Gl.floats(md.size)
        fb.put(md).position(0)
        val ib = Gl.shorts(mi.size)
        ib.put(mi).position(0)
        meshVao = Gl.genVao()
        meshVbo = Gl.genBuffer()
        meshIbo = Gl.genBuffer()
        GLES30.glBindVertexArray(meshVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, md.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mi.size * 2, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawMesh() {
        GLES30.glBindVertexArray(meshVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, meshIndices, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    // ------------------------------------------------------------------ territory

    private fun createTerritory() {
        ownerTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ownerTex)
        Gl.params(linear = false, repeat = false)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8UI, size, size)
        stampTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stampTex)
        Gl.params(linear = false, repeat = false)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, size, size)
        fieldTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex)
        Gl.params(linear = true, repeat = false)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, n, n)
        idsTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, idsTex)
        Gl.params(linear = false, repeat = false)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RG8UI, n, n)

        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fieldTex, 0)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, idsTex, 0)
        GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
        bakeOk = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (!bakeOk) android.util.Log.e("PaperGL", "territory FBO incomplete")
        // start empty
        GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, floatArrayOf(0f, 0.5f, 0f, 0f), 0)
        GLES30.glClearBufferuiv(GLES30.GL_COLOR, 1, intArrayOf(0, 0, 0, 0), 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        world.markAllDirty()
    }

    /** Uploads changed cells and re-bakes the smooth field around them. */
    fun sync(viewW: Int, viewH: Int) {
        if (!world.hasDirty()) return
        val x0 = world.dirtyX0
        val y0 = world.dirtyY0
        val x1 = world.dirtyX1
        val y1 = world.dirtyY1
        world.clearDirty()
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        ownerBuf.clear()
        stampBuf.clear()
        val owner = world.owner
        val stamp = world.stamp
        for (y in y0..y1) {
            val row = y * size
            for (x in x0..x1) {
                ownerBuf.put(owner[row + x].toByte())
                stampBuf.put((stamp[row + x] and 0xFFFF).toShort())
            }
        }
        ownerBuf.position(0)
        stampBuf.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ownerTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, x0, y0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, ownerBuf)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stampTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, x0, y0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, stampBuf)
        if (!bakeOk) return

        // bake the field for the changed cells plus the kernel reach (2 cells)
        val bx0 = max(0, x0 - 2) * RES
        val by0 = max(0, y0 - 2) * RES
        val bx1 = min(size, x1 + 3) * RES
        val by1 = min(size, y1 + 3) * RES
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, n, n)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(bx0, by0, bx1 - bx0, by1 - by0)
        bakeProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ownerTex)
        GLES30.glUniform1i(bakeProgram.u("uOwner"), 0)
        GLES30.glUniform1f(bakeProgram.u("uRes"), RES.toFloat())
        GLES30.glUniform1i(bakeProgram.u("uSize"), size)
        GLES30.glBindVertexArray(ndcVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glEnable(GLES30.GL_BLEND)
        Gl.check("board.bake")
    }

    /** Sets a player's land look: colour, side shade, pattern and its strength. */
    fun setOwnerLook(id: Int, color: Int, accent: Int, pattern: Int, strength: Float) {
        top[id * 3] = Palette.r(color); top[id * 3 + 1] = Palette.g(color); top[id * 3 + 2] = Palette.b(color)
        val sd = Palette.shade(color, 0.7f)
        side[id * 3] = Palette.r(sd); side[id * 3 + 1] = Palette.g(sd); side[id * 3 + 2] = Palette.b(sd)
        acc[id * 3] = Palette.r(accent); acc[id * 3 + 1] = Palette.g(accent); acc[id * 3 + 2] = Palette.b(accent)
        pat[id * 2] = pattern.toFloat()
        pat[id * 2 + 1] = strength
    }

    /** Starts the capture wave for a capture serial at (x, y). */
    fun addWave(serial: Int, x: Float, y: Float, strength: Float) {
        if (waveCount == 8) {
            // drop the oldest
            for (k in 1 until 8) {
                waveId[k - 1] = waveId[k]
                for (c in 0..3) wave[(k - 1) * 4 + c] = wave[k * 4 + c]
            }
            waveCount = 7
        }
        waveId[waveCount] = serial and 0xFFFF
        wave[waveCount * 4] = x
        wave[waveCount * 4 + 1] = y
        wave[waveCount * 4 + 2] = 0f
        wave[waveCount * 4 + 3] = strength
        waveCount++
    }

    fun updateWaves(dt: Float) {
        var k = 0
        while (k < waveCount) {
            wave[k * 4 + 2] += dt
            if (wave[k * 4 + 2] > WAVE_LIFE) {
                for (j in k + 1 until waveCount) {
                    waveId[j - 1] = waveId[j]
                    for (c in 0..3) wave[(j - 1) * 4 + c] = wave[j * 4 + c]
                }
                waveCount--
            } else k++
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Depth-only pass of the walls and the fully covered top, drawn before the desk so the GPU
     * skips every desk pixel the shape hides. Leaves depth test on, depth writes and colour on.
     */
    fun drawDepthPrepass(vp: FloatArray) {
        val pr = BoardPrograms.get()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glDisable(GLES30.GL_BLEND)
        pr.wallDepth.use()
        GLES30.glUniformMatrix4fv(pr.wallDepth.u("uVP"), 1, false, vp, 0)
        GLES30.glBindVertexArray(wallVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, wallVerts)
        pr.depth.use()
        GLES30.glUniformMatrix4fv(pr.depth.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform1f(pr.depth.u("uZ"), 0f)
        GLES30.glUniform1f(pr.depth.u("uSize"), size.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arenaTex)
        GLES30.glUniform1i(pr.depth.u("uField"), 0)
        drawMesh()
        GLES30.glBindVertexArray(0)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    /** Ends the desk stage: back to plain painter's order for the board layers. */
    fun endDepthStage() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
    }

    fun drawDesk(vp: FloatArray, centerX: Float, centerY: Float, radius: Float) {
        GLES30.glDisable(GLES30.GL_BLEND)
        deskProgram.use()
        GLES30.glUniformMatrix4fv(deskProgram.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform1f(deskProgram.u("uZ"), deskZ)
        uniform3(deskProgram, "uA", theme.deskA)
        uniform3(deskProgram, "uB", theme.deskB)
        uniform3(deskProgram, "uInk", theme.ink)
        GLES30.glUniform1i(deskProgram.u("uPattern"), theme.pattern)
        GLES30.glUniform2f(deskProgram.u("uCenter"), centerX, centerY)
        GLES30.glUniform1f(deskProgram.u("uRadius"), radius)
        bindGrain(deskProgram, 1)
        GLES30.glBindVertexArray(deskVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glEnable(GLES30.GL_BLEND)
        // the slab's soft shadow on the desk, thrown away from the light
        shadowProgram.use()
        GLES30.glUniformMatrix4fv(shadowProgram.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform1f(shadowProgram.u("uZ"), deskZ + 0.01f)
        GLES30.glUniform1f(shadowProgram.u("uSize"), size.toFloat())
        GLES30.glUniform2f(shadowProgram.u("uOffset"), SLAB * 0.55f, -SLAB * 0.7f)
        GLES30.glUniform1f(shadowProgram.u("uStrength"), 0.5f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arenaShadowTex)
        GLES30.glUniform1i(shadowProgram.u("uField"), 0)
        GLES30.glBindVertexArray(gridVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    /** The shape: side walls (with paint drips) first, then the lit dome on top. */
    fun drawArena(vp: FloatArray, camera: Camera, skip: Int = 0) {
        eye[0] = camera.eye[0]; eye[1] = camera.eye[1]; eye[2] = camera.eye[2]
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        val w = wallProgram
        w.use()
        GLES30.glUniformMatrix4fv(w.u("uVP"), 1, false, vp, 0)
        uniform3(w, "uSide", theme.cardSide)
        GLES30.glUniform3f(w.u("uLight"), LX, LY, LZ)
        GLES30.glUniform1f(w.u("uSize"), size.toFloat())
        GLES30.glUniform1f(w.u("uRes"), RES.toFloat())
        GLES30.glUniform1i(w.u("uDrips"), if (bakeOk) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex)
        GLES30.glUniform1i(w.u("uTerr"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, idsTex)
        GLES30.glUniform1i(w.u("uIds"), 1)
        GLES30.glUniform3fv(w.u("uTop"), 16, top, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindVertexArray(wallVao)
        if (skip and 8 == 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, wallVerts)
        // top
        val a = arenaProgram
        a.use()
        GLES30.glUniformMatrix4fv(a.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform1f(a.u("uZ"), 0f)
        GLES30.glUniform1f(a.u("uSize"), size.toFloat())
        uniform3(a, "uCard", theme.card)
        GLES30.glUniform3f(a.u("uLight"), LX, LY, LZ)
        GLES30.glUniform3f(a.u("uEye"), eye[0], eye[1], eye[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arenaTex)
        GLES30.glUniform1i(a.u("uField"), 0)
        bindGrain(a, 1)
        if (skip and 16 == 0) drawMesh()
        GLES30.glBindVertexArray(0)
    }

    fun drawTerritory(vp: FloatArray, skip: Int = 0) {
        if (!bakeOk) return
        val p = territoryProgram
        p.use()
        GLES30.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform1f(p.u("uSize"), size.toFloat())
        GLES30.glUniform1f(p.u("uRes"), RES.toFloat())
        GLES30.glUniform3f(p.u("uLight"), LX, LY, LZ)
        GLES30.glUniform3f(p.u("uEye"), eye[0], eye[1], eye[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex)
        GLES30.glUniform1i(p.u("uField"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, idsTex)
        GLES30.glUniform1i(p.u("uIds"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stampTex)
        GLES30.glUniform1i(p.u("uStamp"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grain)
        GLES30.glUniform1i(p.u("uGrain"), 3)
        GLES30.glUniform3fv(p.u("uTop"), 16, top, 0)
        GLES30.glUniform3fv(p.u("uSide"), 16, side, 0)
        GLES30.glUniform3fv(p.u("uAcc"), 16, acc, 0)
        GLES30.glUniform2fv(p.u("uPat"), 16, pat, 0)
        GLES30.glUniform1i(p.u("uWaveCount"), waveCount)
        if (waveCount > 0) {
            GLES30.glUniform1uiv(p.u("uWaveId"), 8, waveId, 0)
            GLES30.glUniform4fv(p.u("uWave"), 8, wave, 0)
        }
        if (showShadows && skip and 32 == 0) {
            GLES30.glUniform1i(p.u("uMode"), 0)
            GLES30.glUniform1f(p.u("uZ"), 0.006f)
            GLES30.glUniform2f(p.u("uOffset"), 0.3f, -0.4f)
            GLES30.glUniform1f(p.u("uSoft"), 0.14f)
            drawMesh()
        }
        GLES30.glUniform1i(p.u("uMode"), 1)
        GLES30.glUniform1f(p.u("uZ"), 0.012f)
        GLES30.glUniform2f(p.u("uOffset"), 0f, 0f)
        GLES30.glUniform1f(p.u("uSoft"), 0f)
        if (skip and 64 == 0) drawMesh()
        GLES30.glUniform1i(p.u("uMode"), 2)
        GLES30.glUniform1f(p.u("uZ"), LAND_H)
        drawMesh()
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Builds and draws every ribbon. [headX]/[headY] are the interpolated plane positions (by id),
     * [colors]/[accents]/[styles] the look per id. Each ribbon is a raised strip: a darker side
     * layer under a top layer, both riding the surface height.
     */
    fun drawRibbons(vp: FloatArray, headX: FloatArray, headY: FloatArray, colors: IntArray, accents: IntArray, styles: IntArray, time: Float) {
        var total = 0
        for (p in world.players) {
            ribbonCount[p.id] = 0
            if (!p.alive || p.trailPoints.size < 2) continue
            val start = total
            val made = buildStrip(p.trailPoints.data, p.trailPoints.size, headX[p.id], headY[p.id], total)
            if (made > 0) {
                ribbonStart[p.id] = start
                ribbonCount[p.id] = made
                total += made
            }
        }
        if (total == 0) return
        ribbonBuf.position(0)
        ribbonBuf.put(ribbonData, 0, total * RIBBON_STRIDE)
        ribbonBuf.position(0)
        GLES30.glBindVertexArray(ribbonVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, total * RIBBON_STRIDE * 4, ribbonBuf)
        val p = ribbonProgram
        p.use()
        GLES30.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform1f(p.u("uTime"), time)
        GLES30.glUniform1f(p.u("uAlpha"), 1f)
        // contact shadow, then the darker side layer, then the top
        GLES30.glUniform1i(p.u("uShadow"), 1)
        GLES30.glUniform1f(p.u("uZ"), LAND_H + 0.012f)
        for (pl in world.players) {
            if (ribbonCount[pl.id] == 0) continue
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, ribbonStart[pl.id], ribbonCount[pl.id])
        }
        GLES30.glUniform1i(p.u("uShadow"), 2)
        GLES30.glUniform1f(p.u("uZ"), LAND_H + 0.03f)
        for (pl in world.players) {
            if (ribbonCount[pl.id] == 0) continue
            val c = colors[pl.id]
            GLES30.glUniform3f(p.u("uCol"), Palette.r(c), Palette.g(c), Palette.b(c))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, ribbonStart[pl.id], ribbonCount[pl.id])
        }
        GLES30.glUniform1i(p.u("uShadow"), 0)
        GLES30.glUniform1f(p.u("uZ"), LAND_H + RIBBON_H)
        for (pl in world.players) {
            if (ribbonCount[pl.id] == 0) continue
            val c = colors[pl.id]
            val c2 = accents[pl.id]
            GLES30.glUniform3f(p.u("uCol"), Palette.r(c), Palette.g(c), Palette.b(c))
            GLES30.glUniform3f(p.u("uCol2"), Palette.r(c2), Palette.g(c2), Palette.b(c2))
            GLES30.glUniform1i(p.u("uStyle"), styles[pl.id])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, ribbonStart[pl.id], ribbonCount[pl.id])
        }
        GLES30.glBindVertexArray(0)
    }

    /** Mitred triangle strip along the points (+ head), on the surface; returns the vertex count. */
    private fun buildStrip(src: FloatArray, srcSize: Int, hx: Float, hy: Float, startVertex: Int): Int {
        var np = 0
        var lx = Float.NaN
        var ly = Float.NaN
        var i = 0
        while (i <= srcSize && np < pts.size / 2 - 1) {
            val x = if (i < srcSize) src[i] else hx
            val y = if (i < srcSize) src[i + 1] else hy
            if (lx.isNaN() || (x - lx) * (x - lx) + (y - ly) * (y - ly) > 1e-3f) {
                pts[np * 2] = x
                pts[np * 2 + 1] = y
                np++
                lx = x
                ly = y
            }
            i += 2
        }
        if (np < 2) return 0
        if (startVertex + np * 2 > MAX_RIBBON_VERTS) return 0
        var o = startVertex * RIBBON_STRIDE
        var along = 0f
        val half = RIBBON_HALF
        for (k in 0 until np) {
            val x = pts[k * 2]
            val y = pts[k * 2 + 1]
            var d1x = 0f; var d1y = 0f; var d2x = 0f; var d2y = 0f
            if (k > 0) {
                d1x = x - pts[k * 2 - 2]; d1y = y - pts[k * 2 - 1]
                val l = sqrt(d1x * d1x + d1y * d1y)
                along += l
                d1x /= l; d1y /= l
            }
            if (k < np - 1) {
                d2x = pts[k * 2 + 2] - x; d2y = pts[k * 2 + 3] - y
                val l = sqrt(d2x * d2x + d2y * d2y)
                d2x /= l; d2y /= l
            }
            if (k == 0) { d1x = d2x; d1y = d2y }
            if (k == np - 1) { d2x = d1x; d2y = d1y }
            var tx = d1x + d2x
            var ty = d1y + d2y
            var tl = sqrt(tx * tx + ty * ty)
            if (tl < 1e-3f) { tx = d1x; ty = d1y; tl = 1f }
            tx /= tl; ty /= tl
            val cosHalf = (tx * d1x + ty * d1y).coerceAtLeast(0.45f)
            val m = half / cosHalf
            val nx = -ty * m
            val ny = tx * m
            val ax = x + nx
            val ay = y + ny
            val bx = x - nx
            val by = y - ny
            ribbonData[o] = ax; ribbonData[o + 1] = ay; ribbonData[o + 2] = heightAt(ax, ay)
            ribbonData[o + 3] = along; ribbonData[o + 4] = 1f
            ribbonData[o + 5] = bx; ribbonData[o + 6] = by; ribbonData[o + 7] = heightAt(bx, by)
            ribbonData[o + 8] = along; ribbonData[o + 9] = -1f
            o += RIBBON_STRIDE * 2
        }
        return np * 2
    }

    private fun bindGrain(p: GlProgram, unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grain)
        GLES30.glUniform1i(p.u("uGrain"), unit)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun uniform3(p: GlProgram, name: String, c: Int) =
        GLES30.glUniform3f(p.u(name), Palette.r(c), Palette.g(c), Palette.b(c))

    fun release() {
        listOf(gridVbo, deskVbo, ndcVbo, wallVbo, meshVbo, meshIbo, ribbonVbo).forEach { Gl.deleteBuffer(it) }
        listOf(gridVao, deskVao, ndcVao, wallVao, meshVao, ribbonVao).forEach { Gl.deleteVao(it) }
        listOf(arenaTex, arenaShadowTex, ownerTex, stampTex, fieldTex, idsTex).forEach { Gl.deleteTexture(it) }
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    }

    companion object {
        const val RES = 4
        /** Desk depth below the rim: the slab's visible thickness. */
        const val SLAB = 3.0f
        /** Height the wall cells sit at before blurring: how far the lip rounds over. */
        const val RIM_DROP = 2.0f
        /** Thickness of the land paint above the surface. */
        const val LAND_H = 0.42f
        const val RIBBON_HALF = 0.42f
        /** Ribbon top above the land top. */
        const val RIBBON_H = 0.16f
        const val WAVE_LIFE = 1.5f
        private const val RIBBON_STRIDE = 5
        private const val MAX_RIBBON_VERTS = 24000
        // light from the upper left, a bit in front
        val LX = -0.42f
        val LY = 0.38f
        val LZ = 0.82f
    }
}

/** Board shader programs, compiled once per GL context and shared by every board. */
class BoardPrograms private constructor() {
    val desk = GlProgram(Shaders.QUAD_VS, Shaders.DESK_FS, "desk")
    val shadow = GlProgram(Shaders.QUAD_VS, Shaders.SHADOW_FS, "shadow")
    val arena = GlProgram(Shaders.SURFACE_VS, Shaders.ARENA_FS, "arena")
    val wall = GlProgram(Shaders.WALL_VS, Shaders.WALL_FS, "wall")
    val bake = GlProgram(Shaders.BAKE_VS, Shaders.BAKE_FS, "bake")
    val territory = GlProgram(Shaders.SURFACE_VS, Shaders.TERRITORY_FS, "territory")
    val ribbon = GlProgram(Shaders.RIBBON_VS, Shaders.RIBBON_FS, "ribbon")
    val depth = GlProgram(Shaders.SURFACE_VS, Shaders.DEPTH_FS, "depth")
    val wallDepth = GlProgram(Shaders.WALL_VS, Shaders.PLAIN_FS, "wallDepth")

    companion object {
        private var current: BoardPrograms? = null

        /** GL thread: the programs for the current context. */
        fun get(): BoardPrograms = current ?: BoardPrograms().also { current = it }

        /** A new GL context was created: the old programs died with the old one. */
        fun contextLost() {
            current = null
        }
    }
}
