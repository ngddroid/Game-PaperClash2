package com.ngstudio.paperclash2.engine

import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.shape.Arena
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.world.Cause
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.engine.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldRulesTest {
    private fun squareArena(size: Int = 48, holeAt: Pair<Int, Int>? = null): Arena {
        val mask = BooleanArray(size * size) { i ->
            val x = i % size
            val y = i / size
            x in 3 until size - 3 && y in 3 until size - 3
        }
        if (holeAt != null) {
            for (y in holeAt.second - 1..holeAt.second + 1) for (x in holeAt.first - 1..holeAt.first + 1) mask[y * size + x] = false
        }
        val dist = ArenaBuilder.distanceToWall(mask, size)
        return Arena(size, mask, dist, intArrayOf(24 * size + 24, 10 * size + 10), "test", 10f)
    }

    private fun path(vararg xy: Int) = xy

    /** Cells walked in a straight line from (x0,y0) to (x1,y1) inclusive of the end, exclusive of the start. */
    private fun line(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val out = ArrayList<Int>()
        var x = x0
        var y = y0
        while (x != x1 || y != y1) {
            if (x != x1) x += if (x1 > x) 1 else -1 else y += if (y1 > y) 1 else -1
            out += x
            out += y
        }
        return out.toIntArray()
    }

    private fun walk(world: World, p: Player, vararg corners: Int) {
        var cx = p.cellX
        var cy = p.cellY
        var k = 0
        while (k < corners.size) {
            world.walkForTest(p, line(cx, cy, corners[k], corners[k + 1]))
            cx = corners[k]
            cy = corners[k + 1]
            k += 2
        }
    }

    /** Independent check: no unowned playable cell may be enclosed by [id]'s land. */
    private fun assertNoEnclosedHoles(world: World, id: Int) {
        val n = world.size
        val reach = BooleanArray(n * n)
        val queue = ArrayDeque<Int>()
        for (i in 0 until n * n) {
            val x = i % n
            val y = i / n
            if ((x == 0 || y == 0 || x == n - 1 || y == n - 1) && world.owner[i] != id) {
                reach[i] = true
                queue.add(i)
            }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % n
            val y = i / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                val j = ny * n + nx
                if (!reach[j] && world.owner[j] != id) {
                    reach[j] = true
                    queue.add(j)
                }
            }
        }
        for (i in 0 until n * n) {
            if (world.playable[i] && world.owner[i] != id) assertTrue("cell ${i % n},${i / n} is enclosed but not owned", reach[i])
        }
    }

    private fun assertCountsConsistent(world: World) {
        val counted = IntArray(World.MAX_PLAYERS + 1)
        for (i in world.owner.indices) {
            val o = world.owner[i]
            if (o > 0) {
                assertTrue("land on a wall at $i", world.playable[i])
                counted[o]++
            }
        }
        for (id in 1..World.MAX_PLAYERS) assertEquals("count for $id", counted[id], world.counts[id])
    }

    @Test
    fun loopCapturesRectangleAndInside() {
        val arena = squareArena()
        val world = World(arena, Rng(1))
        val p = Player(1, "A", 0, true)
        world.addPlayer(p)
        world.spawnAt(p, 24 * arena.size + 24, 3)
        val before = world.counts[1]
        walk(world, p, 24, 34, 34, 34, 34, 24, 26, 24)
        assertTrue(p.alive)
        assertEquals(0, p.trail.size)
        for (y in 24..34) for (x in 24..34) assertEquals("cell $x,$y", 1, world.owner[y * arena.size + x])
        assertTrue(world.counts[1] > before + 100)
        assertNoEnclosedHoles(world, 1)
        assertCountsConsistent(world)
    }

    @Test
    fun crossingOwnRibbonIsSafe() {
        val arena = squareArena()
        val world = World(arena, Rng(2))
        val p = Player(1, "A", 0, true)
        world.addPlayer(p)
        world.spawnAt(p, 24 * arena.size + 24, 3)
        // Out, a figure that crosses itself, then home.
        walk(world, p, 24, 36, 30, 36, 30, 32, 20, 32, 20, 24, 22, 24)
        assertTrue(p.alive)
        assertNoEnclosedHoles(world, 1)
        assertCountsConsistent(world)
    }

    @Test
    fun arenaEdgeDoesNotCloseALoop() {
        val arena = squareArena()
        val world = World(arena, Rng(3))
        val p = Player(1, "A", 0, true)
        world.addPlayer(p)
        world.spawnAt(p, 14 * arena.size + 24, 3)
        // The ribbon touches the bottom wall twice. The pocket between ribbon and wall is open
        // to the outside through the wall, so it stays unclaimed; the pocket closed by ribbon
        // and land is claimed.
        walk(world, p, 24, 3, 24, 7, 30, 7, 30, 3, 30, 11, 25, 11)
        assertTrue(p.alive)
        assertEquals(0, p.trail.size)
        assertEquals("pocket against the wall", 0, world.owner[5 * arena.size + 27])
        assertEquals("pocket closed by land", 1, world.owner[9 * arena.size + 27])
        assertNoEnclosedHoles(world, 1)
        assertCountsConsistent(world)
    }

    @Test
    fun snippingARibbonPopsItsOwnerAndStealsTheLand() {
        val arena = squareArena()
        val world = World(arena, Rng(4))
        val a = Player(1, "A", 0, true)
        val b = Player(2, "B", 1, false)
        world.addPlayer(a)
        world.addPlayer(b)
        world.spawnAt(a, 24 * arena.size + 24, 3)
        world.spawnAt(b, 10 * arena.size + 10, 3)
        val bLand = world.counts[2]
        walk(world, b, 10, 17, 18, 17)
        assertTrue(b.trail.size > 5)
        // A flies down across B's ribbon.
        walk(world, a, 15, 24, 15, 15)
        assertFalse(b.alive)
        assertEquals(Cause.SNIPPED, b.deathCause)
        assertEquals(1, b.killer)
        assertEquals(0, world.counts[2])
        assertTrue(world.counts[1] >= bLand)
        assertCountsConsistent(world)
    }

    @Test
    fun shieldBlocksASnip() {
        val arena = squareArena()
        val world = World(arena, Rng(5))
        val a = Player(1, "A", 0, true)
        val b = Player(2, "B", 1, false)
        world.addPlayer(a)
        world.addPlayer(b)
        world.spawnAt(a, 24 * arena.size + 24, 3)
        world.spawnAt(b, 10 * arena.size + 10, 3)
        walk(world, b, 10, 17, 18, 17)
        b.shieldTime = 3f
        walk(world, a, 15, 24, 15, 15)
        assertTrue(b.alive)
        assertCountsConsistent(world)
    }

    @Test
    fun surroundingAllLandPopsTheOwner() {
        val arena = squareArena()
        val world = World(arena, Rng(6))
        val a = Player(1, "A", 0, true)
        val b = Player(2, "B", 1, false)
        world.addPlayer(a)
        world.addPlayer(b)
        world.spawnAt(a, 24 * arena.size + 24, 2)
        world.spawnAt(b, 24 * arena.size + 34, 1)
        // A draws a big box around B's small patch.
        walk(world, a, 24, 29, 39, 29, 39, 19, 24, 19, 24, 23)
        assertFalse(b.alive)
        assertEquals(Cause.SURROUNDED, b.deathCause)
        assertNoEnclosedHoles(world, 1)
        assertCountsConsistent(world)
    }

    @Test
    fun randomLoopsKeepInvariants() {
        val rng = Rng(77)
        repeat(30) { round ->
            val arena = squareArena(64, if (round % 3 == 0) 40 to 40 else null)
            val world = World(arena, Rng(round.toLong()))
            val p = Player(1, "A", 0, true)
            world.addPlayer(p)
            world.spawnAt(p, 32 * arena.size + 32, 3)
            repeat(12) {
                if (!p.alive) return@repeat
                // From the current cell, wander out in a random rectangle and come back to the start cell.
                val sx = p.cellX
                val sy = p.cellY
                val w = rng.nextInt(3, 14) * (if (rng.chance(0.5f)) 1 else -1)
                val h = rng.nextInt(3, 14) * (if (rng.chance(0.5f)) 1 else -1)
                val x1 = (sx + w).coerceIn(4, arena.size - 5)
                val y1 = (sy + h).coerceIn(4, arena.size - 5)
                walk(world, p, sx, y1, x1, y1, x1, sy, sx, sy)
                assertNoEnclosedHoles(world, 1)
                assertCountsConsistent(world)
            }
        }
    }
}
