package com.ngstudio.paperclash2.engine.level

import com.ngstudio.paperclash2.engine.shape.Arena
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.world.World
import kotlin.math.abs
import kotlin.math.sqrt

/** Static checks every level must pass (run over all 5,000 levels by the test suite). */
object LevelValidator {
    fun validate(spec: LevelSpec, arena: Arena, prev: LevelSpec?): List<String> {
        val issues = ArrayList<String>()
        val players = spec.playerCount
        if (players > World.MAX_PLAYERS) issues += "too many players ($players)"
        if (spec.rivals.isEmpty()) issues += "no rivals"
        if (arena.area < 1800) issues += "arena too small (${arena.area})"
        if (arena.size < ArenaBuilder.MAX_SIZE &&
            (arena.area < spec.targetArea * 0.72f || arena.area > spec.targetArea * 1.3f)
        ) issues += "area ${arena.area} far from target ${spec.targetArea}"
        if (ArenaBuilder.components(arena.mask, arena.size) != 1) issues += "arena not connected"
        val cleaned = ArenaBuilder.clean(arena.mask, arena.size)
        var diff = 0
        for (i in cleaned.indices) if (cleaned[i] != arena.mask[i]) diff++
        if (diff > arena.area * 0.01f) issues += "slivers ($diff cells)"
        if (arena.area.toFloat() / players < 900f) issues += "crowded (${arena.area / players} cells per plane)"

        if (arena.spawns.size != players) {
            issues += "spawns ${arena.spawns.size} != players $players"
        } else {
            for (k in arena.spawns.indices) {
                val c = arena.spawns[k]
                if (!arena.mask[c]) issues += "spawn $k on a wall"
                else if (arena.dist[c] < spec.startRadius + 0.5f) issues += "spawn $k too tight (${arena.dist[c]})"
            }
            var minPair = Float.MAX_VALUE
            for (a in arena.spawns.indices) for (b in a + 1 until arena.spawns.size) {
                val ca = arena.spawns[a]
                val cb = arena.spawns[b]
                val dx = (ca % arena.size - cb % arena.size).toFloat()
                val dy = (ca / arena.size - cb / arena.size).toFloat()
                minPair = minOf(minPair, sqrt(dx * dx + dy * dy))
            }
            if (players > 1 && minPair < 2 * spec.startRadius + 4) issues += "spawns too close ($minPair)"
        }

        val g = spec.goal
        when (g.mode) {
            Mode.CLAIM, Mode.SURVIVE -> {
                if (!(g.t1 > 0f && g.t1 < g.t2 && g.t2 < g.t3 && g.t3 <= 90f)) issues += "bad thresholds $g"
                if (g.t1 > 50f) issues += "goal too high (${g.t1}%)"
            }
            Mode.COIN_RUSH -> {
                if (!(g.t1 < g.t2 && g.t2 < g.t3)) issues += "bad thresholds $g"
                if (spec.starTokens < g.t3) issues += "not enough stars (${spec.starTokens} < ${g.t3})"
            }
            Mode.KNOCKOUT -> {
                if (g.count < 1) issues += "knockout without a count"
                if (spec.rivals.size < g.count + 1) issues += "not enough rivals to snip"
            }
            Mode.DOMINATE -> if (!(g.t2 < g.t3 && g.t3 <= 90f)) issues += "bad thresholds $g"
            Mode.BOSS -> {
                if (!spec.isBoss) issues += "boss level without a boss"
                if (!(g.t2 < g.t3 && g.t3 <= 90f)) issues += "bad thresholds $g"
            }
        }
        if (spec.timeLimit < 60f || spec.timeLimit > 200f) issues += "time limit ${spec.timeLimit}"
        if (prev != null) {
            val worldStart = (spec.number - 1) % Worlds.LEVELS_PER_WORLD == 0
            val afterBoss = prev.number % 50 == 0
            val jump = abs(spec.difficulty - prev.difficulty)
            if (!worldStart && !afterBoss && spec.number % 50 != 0 && spec.number > Handcrafted.COUNT + 1 && jump > 0.08f) {
                issues += "difficulty jump $jump"
            }
        }
        return issues
    }
}
