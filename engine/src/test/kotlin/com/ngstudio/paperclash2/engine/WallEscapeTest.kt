package com.ngstudio.paperclash2.engine

import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.engine.world.World
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * A plane that nobody steers must never get stuck on the shape's edge: it glides along walls,
 * out of dips and around corners on every shape, including torn-edge and holed variants.
 */
class WallEscapeTest {

    @Test
    fun unsteeredPlanesNeverStallOnAnyShape() {
        val variants = listOf(
            ShapeVariant(),
            ShapeVariant(rotDeg = 23f, wobble = 0.04f, seed = 7),
            ShapeVariant(rotDeg = -41f, mirror = true, aspect = 1.2f, wobble = 0.03f, holes = 2, seed = 11),
        )
        val failures = ArrayList<String>()
        var seed = 1L
        for (shape in ShapeLibrary.all) {
            for ((vi, v) in variants.withIndex()) {
                seed++
                val arena = ArenaBuilder.build(shape, v, 1800, 1, 3, seed)
                val world = World(arena, Rng(seed))
                val p = Player(1, "test", 0, true)
                world.addPlayer(p)
                // own the whole shape, so the flight never leaves land and no ribbon can end it
                world.spawnAt(p, arena.spawns[0], arena.size)
                val rng = Rng(seed * 31)
                val dt = 1f / 60f
                // A new heading every 1.5 s. Each window runs from 0.5 s to 1.5 s after the change,
                // when the plane has turned: it must keep flying (path) and get somewhere (no
                // jittering in a dip).
                var startX = p.x
                var startY = p.y
                var path = 0f
                var worstPath = Float.MAX_VALUE
                var worstNet = Float.MAX_VALUE
                for (tick in 0 until 60 * 40) {
                    val phase = tick % 90
                    if (phase == 0) p.targetAngle = rng.nextFloat() * 6.2831855f
                    if (phase == 30) {
                        startX = p.x
                        startY = p.y
                        path = 0f
                    }
                    val bx = p.x
                    val by = p.y
                    world.tick(dt)
                    if (!p.alive) break
                    path += sqrt((p.x - bx) * (p.x - bx) + (p.y - by) * (p.y - by))
                    if (phase == 89) {
                        val dx = p.x - startX
                        val dy = p.y - startY
                        worstPath = minOf(worstPath, path)
                        worstNet = minOf(worstNet, sqrt(dx * dx + dy * dy))
                    }
                }
                if (!p.alive || worstPath < 3f || worstNet < 1f) {
                    failures += "${shape.id}#$vi (worst 1 s: path ${"%.2f".format(worstPath)}, net ${"%.2f".format(worstNet)}, alive=${p.alive})"
                }
            }
        }
        assertTrue("Planes stalled on: ${failures.joinToString()}", failures.isEmpty())
    }
}
