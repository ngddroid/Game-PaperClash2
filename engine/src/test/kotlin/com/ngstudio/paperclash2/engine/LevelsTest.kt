package com.ngstudio.paperclash2.engine

import com.ngstudio.paperclash2.engine.level.Handcrafted
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.level.LevelValidator
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.level.Worlds
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.stream.IntStream

class LevelsTest {
    @Test
    fun everyShapeBuildsAPlayableBoardSmallAndLarge() {
        val problems = ArrayList<String>()
        for (shape in ShapeLibrary.all) {
            for ((area, players) in listOf(2600 to 2, 9000 to 6, 24000 to 12)) {
                for (rot in listOf(0f, 37f)) {
                    val v = ShapeVariant(rotDeg = if (shape.upright) rot / 4f else rot)
                    val a = ArenaBuilder.build(shape, v, area, players, 5, 99)
                    if (ArenaBuilder.components(a.mask, a.size) != 1) problems += "${shape.id}@$area not connected"
                    if (a.size < ArenaBuilder.MAX_SIZE && (a.area < area * 0.72f || a.area > area * 1.3f)) {
                        problems += "${shape.id}@$area area ${a.area}"
                    }
                    if (a.spawns.size != players) problems += "${shape.id}@$area spawns ${a.spawns.size}"
                    for (s in a.spawns) if (a.dist[s] < 4.5f) problems += "${shape.id}@$area tight spawn ${a.dist[s]}"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun shapeLibraryHasSeventyPlusUniqueShapes() {
        assertTrue(ShapeLibrary.all.size >= 70)
        assertEquals(ShapeLibrary.all.size, ShapeLibrary.all.map { it.id }.toSet().size)
    }

    @Test
    fun scheduleNeverRepeatsAShapeWithinEightLevels() {
        val s = LevelGenerator.schedule
        for (n in 2..Worlds.TOTAL_LEVELS) {
            for (back in 1..7) {
                if (n - back < 1) break
                assertTrue("level $n repeats ${s[n]} from ${n - back}", s[n] != s[n - back])
            }
        }
    }

    @Test
    fun specsAreDeterministic() {
        for (n in listOf(1, 5, 10, 11, 37, 50, 499, 1250, 3333, 5000)) {
            val a = LevelGenerator.spec(n)
            LevelGenerator.clearCaches()
            val b = LevelGenerator.spec(n)
            assertEquals("level $n", a, b)
            val ma = LevelGenerator.buildArena(a)
            val mb = LevelGenerator.buildArena(b)
            assertTrue("level $n arena", ma.mask.contentEquals(mb.mask) && ma.spawns.contentEquals(mb.spawns))
        }
    }

    @Test
    fun modeCadence() {
        assertEquals(Mode.BOSS, LevelGenerator.modeFor(50))
        assertEquals(Mode.COIN_RUSH, LevelGenerator.modeFor(25))
        assertEquals(Mode.KNOCKOUT, LevelGenerator.modeFor(15))
        assertEquals(Mode.DOMINATE, LevelGenerator.modeFor(18))
        assertEquals(Mode.SURVIVE, LevelGenerator.modeFor(33))
        assertEquals(Mode.CLAIM, LevelGenerator.modeFor(11))
        assertEquals(Mode.BOSS, Handcrafted.spec(10).mode)
        assertEquals(Mode.COIN_RUSH, Handcrafted.spec(5).mode)
    }

    /** The big one: all 5,000 levels build and pass every static check. */
    @Test
    fun allFiveThousandLevelsAreValid() {
        val problems = Collections.synchronizedList(ArrayList<String>())
        IntStream.rangeClosed(1, Worlds.TOTAL_LEVELS).parallel().forEach { n ->
            val spec = LevelGenerator.spec(n)
            val prev = if (n > 1) LevelGenerator.spec(n - 1) else null
            val arena = LevelGenerator.buildArena(spec)
            val issues = LevelValidator.validate(spec, arena, prev)
            if (issues.isNotEmpty()) problems += "L$n (${spec.shapeId}, ${spec.mode}): ${issues.joinToString("; ")}"
        }
        val sorted = problems.sortedBy { it.substringAfter("L").substringBefore(" ").toIntOrNull() ?: 0 }
        assertTrue("${sorted.size} invalid levels:\n" + sorted.take(60).joinToString("\n"), sorted.isEmpty())
    }
}
