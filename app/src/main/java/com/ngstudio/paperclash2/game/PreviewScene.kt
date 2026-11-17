package com.ngstudio.paperclash2.game

import android.opengl.GLES30
import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.engine.world.World
import com.ngstudio.paperclash2.gl.Board
import com.ngstudio.paperclash2.gl.BoardGeometry
import com.ngstudio.paperclash2.gl.Camera
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shop preview: the selected plane circles over a round paper pedestal on the craft desk, drawing
 * its ribbon, over a disc of land in its colour and pattern.
 */
class PreviewScene(c: GameController, @Volatile var look: PlayerLook) : Scene(c) {
    private lateinit var world: World
    private lateinit var player: Player
    private var board: Board? = null
    private var clock = 0f
    private var shownStyle = -1
    private val headX = FloatArray(16)
    private val headY = FloatArray(16)
    private val colors = IntArray(16)
    private val accents = IntArray(16)
    private val ribbons = IntArray(16)
    private var cx = 0f
    private var cy = 0f

    private var geometry: BoardGeometry? = null

    override fun prepare() {
        if (::world.isInitialized) return
        val arena = ArenaBuilder.build(ShapeLibrary.byId("circle"), ShapeVariant(), 1500, 1, 6, 7)
        world = World(arena, Rng(3))
        player = Player(1, look.name, look.style, true)
        world.addPlayer(player)
        world.spawnAt(player, arena.spawns[0], 5)
        cx = arena.centerX
        cy = arena.centerY
        geometry = BoardGeometry(world)
    }

    override fun create() {
        prepare()
        board?.release()
        val b = Board(world, DeskThemes.forWorld(0, 0), geometry ?: BoardGeometry(world))
        b.create(c.atlas.grain)
        board = b
        c.particles.ground = b
        shownStyle = -1
        c.camera.yaw = 0f
        c.camera.targetZ = b.heightAt(cx, cy)
        c.camera.snap(cx, cy - 15f, c.camera.distForWidth(31f))
    }

    override fun frame(dt: Float) {
        val b = board ?: return
        clock += dt
        val l = look
        val skin = Skins.get(l.style)
        if (shownStyle != l.style) {
            shownStyle = l.style
            b.setOwnerLook(1, skin.base, skin.accent, skin.pattern, 0.3f)
        }
        colors[1] = skin.base
        accents[1] = skin.accent
        ribbons[1] = l.ribbon

        // fly in a circle and fake the ribbon as the last part of that circle
        val r = 7f
        val a = clock * 0.9f
        val px = cx + cos(a) * r
        val py = cy + sin(a) * r
        player.trailPoints.clear()
        var k = 0
        while (k <= 40) {
            val t = a - 2.6f + k * (2.6f / 40f)
            player.trailPoints.add(cx + cos(t) * r, cy + sin(t) * r)
            k++
        }
        player.trail.add(0)
        headX[1] = px
        headY[1] = py

        val cam = c.camera
        cam.yaw = sin(clock * 0.25f) * 14f
        cam.targetX = cx
        cam.targetY = cy - 15f
        cam.targetZ = b.heightAt(cx, cy)
        cam.targetDist = cam.distForWidth(31f)
        cam.update(dt, 2f, 2f)

        GLES30.glClearColor(0.16f, 0.48f, 0.37f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        b.sync(c.width, c.height)
        b.drawDepthPrepass(cam.vp)
        b.drawDesk(cam.vp, cx, cy, 14f)
        b.endDepthStage()
        b.drawArena(cam.vp, cam)
        b.drawTerritory(cam.vp)
        b.drawRibbons(cam.vp, headX, headY, colors, accents, ribbons, clock)
        val planes = c.planes
        planes.begin(cam.vp, cam, c.atlas.grain)
        val heading = a + 1.5708f
        val z = b.groundZ(px, py) + 1.6f + 0.15f * sin(clock * 2.6f)
        val ink = 0xFF1F2A4A.toInt()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        planes.draw(skin.model, px, py, z - 0.05f, heading * Camera.DEG, 24f, -3f, 2.6f * 1.12f, ink, ink, 0, 1f, ink)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        planes.draw(skin.model, px, py, z, heading * Camera.DEG, 24f, -3f, 2.6f,
            com.ngstudio.paperclash2.game.Palette.mix(skin.base, 0xFFFFFFFF.toInt(), 0.3f), skin.accent, skin.pattern, 0f, 0xFFFFFFFF.toInt())
        planes.end()
        player.trail.clear()
    }

    override fun release() {
        board?.release()
        board = null
    }
}
