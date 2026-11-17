package com.ngstudio.paperclash2.engine.level

import com.ngstudio.paperclash2.engine.ai.Archetype
import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.shape.Arena
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.shape.ShapeDef
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import com.ngstudio.paperclash2.engine.world.PickupType
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic generator for levels 11–5000 (1–10 come from [Handcrafted]). A level is rebuilt
 * on demand from its number in a few milliseconds, so nothing large ships in the APK.
 *
 * [VERSION] is part of every seed: bump it only together with a migration, or players would see
 * their finished levels change shape.
 */
object LevelGenerator {
    const val VERSION = 1
    private const val SALT = 0x5EEDL

    /** Fraction of a capable player's raw claim rate expected to stick (rivals contest the rest). */
    var claimFactor = 0.55f

    private val cache = object : LinkedHashMap<Int, LevelSpec>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LevelSpec>?): Boolean = size > 512
    }
    private val arenaCache = object : LinkedHashMap<Int, Arena>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Arena>?): Boolean = size > 6
    }

    fun spec(n: Int): LevelSpec {
        require(n in 1..Worlds.TOTAL_LEVELS) { "level $n out of range" }
        synchronized(cache) { cache[n]?.let { return it } }
        val s = if (n <= Handcrafted.COUNT) Handcrafted.spec(n) else generate(n)
        synchronized(cache) { cache[n] = s }
        return s
    }

    /** Builds (or reuses) the board for a level. */
    fun arena(n: Int): Arena {
        synchronized(arenaCache) { arenaCache[n]?.let { return it } }
        val a = buildArena(spec(n))
        synchronized(arenaCache) { arenaCache[n] = a }
        return a
    }

    fun buildArena(s: LevelSpec): Arena =
        ArenaBuilder.build(ShapeLibrary.byId(s.shapeId), s.variant, s.targetArea, s.playerCount, s.startRadius + 1, s.seed)

    fun clearCaches() {
        synchronized(cache) { cache.clear() }
        synchronized(arenaCache) { arenaCache.clear() }
    }

    // ------------------------------------------------------------------ curve and cadence

    /** Difficulty 0..1: smooth rise, a saw-tooth inside each world (breather at its start), jitter. */
    fun difficulty(n: Int): Float {
        if (n <= Handcrafted.COUNT) return Handcrafted.difficulty(n)
        val base = 0.12f + 0.72f * (1f - exp(-(n - 10) / 1400f))
        val pos = (n - 1) % Worlds.LEVELS_PER_WORLD
        val saw = 0.05f * (pos / 49f) - 0.02f
        val jitter = (Rng.hash01(n, 77) - 0.5f) * 0.05f
        val bossBump = if (n % 50 == 0) 0.04f else 0f
        return MathX.clamp(base + saw + jitter + bossBump, 0.08f, 0.92f)
    }

    fun modeFor(n: Int): Mode = when {
        n <= Handcrafted.COUNT -> Handcrafted.spec(n).goal.mode
        n % 50 == 0 -> Mode.BOSS
        n % 25 == 0 -> Mode.COIN_RUSH
        n % 10 == 5 -> Mode.KNOCKOUT
        n % 10 == 8 && n >= 18 -> Mode.DOMINATE
        n % 20 == 13 && n >= 33 -> Mode.SURVIVE
        else -> Mode.CLAIM
    }

    /** Shape for every level, built once: tiers follow difficulty and nothing repeats within 8 levels. */
    val schedule: Array<String> by lazy { buildSchedule() }

    private fun buildSchedule(): Array<String> {
        val out = Array(Worlds.TOTAL_LEVELS + 1) { "" }
        val recent = ArrayDeque<String>()
        for (n in 1..Handcrafted.COUNT) {
            out[n] = Handcrafted.shapeOf(n)
            recent.addLast(out[n])
            if (recent.size > 8) recent.removeFirst()
        }
        val rng = Rng(SALT xor 0xABCDEFL)
        for (n in Handcrafted.COUNT + 1..Worlds.TOTAL_LEVELS) {
            val d = difficulty(n)
            val maxTier = if (d < 0.22f) 0 else if (d < 0.5f) 1 else 2
            val pool = ShapeLibrary.all.filter { it.tier <= maxTier && it.id !in recent }
            var total = 0f
            for (s in pool) total += weight(s, maxTier)
            var r = rng.nextFloat() * total
            var pick = pool[pool.size - 1]
            for (s in pool) {
                r -= weight(s, maxTier)
                if (r <= 0f) {
                    pick = s
                    break
                }
            }
            out[n] = pick.id
            recent.addLast(pick.id)
            if (recent.size > 8) recent.removeFirst()
        }
        return out
    }

    private fun weight(s: ShapeDef, maxTier: Int) = if (s.tier == maxTier) 2f else 1f

    // ------------------------------------------------------------------ generation

    private val bossNames = listOf(
        "King Crumple", "Duchess Doodle", "Baron Von Fold", "Captain Cutter", "General Glue",
        "Lord Laminate", "Queen Quilling", "Sir Staple", "Count Crayon", "Empress Emboss",
    )

    private fun generate(n: Int): LevelSpec {
        val rng = Rng(SALT * 1_000_003L + n * 0x9E3779B97L + VERSION)
        val d = difficulty(n)
        val world = Worlds.worldOf(n)
        val mode = modeFor(n)
        val shape = ShapeLibrary.byId(schedule[n])
        val variant = variantFor(shape, d, rng)

        val knockouts = 2 + (2.5f * d).roundToInt()
        var rivalCount = when (mode) {
            Mode.CLAIM -> (2.2f + 7f * d).roundToInt().coerceIn(2, 9)
            Mode.COIN_RUSH -> (1f + 3f * d).roundToInt().coerceIn(1, 4)
            Mode.KNOCKOUT -> max(knockouts + 1, (3f + 5f * d).roundToInt()).coerceIn(3, 9)
            Mode.DOMINATE, Mode.SURVIVE -> (3f + 7f * d).roundToInt().coerceIn(3, 10)
            Mode.BOSS -> (2f + 5f * d).roundToInt().coerceIn(2, 7)
        }
        val perPlayer = 1350f + 550f * d
        val roomFactor = if (mode == Mode.COIN_RUSH) 1.25f else 1f
        // Long, thin silhouettes can't grow forever on the grid: fewer rivals rather than a crowd.
        val capacity = ArenaBuilder.capacity(shape, variant)
        val minRivals = if (mode == Mode.KNOCKOUT) knockouts + 1 else 1
        while (rivalCount > minRivals && perPlayer * roomFactor * (rivalCount + 1 + (if (mode == Mode.BOSS) 1 else 0)) > capacity) rivalCount--
        val players = rivalCount + 1 + (if (mode == Mode.BOSS) 1 else 0)
        val area = (perPlayer * players * roomFactor).toInt().coerceIn(2600, minOf(24000, capacity))
        val time = when (mode) {
            Mode.CLAIM -> 165f + (if (rivalCount > 7) 15f else 0f)
            Mode.COIN_RUSH -> 70f
            Mode.KNOCKOUT -> 110f + 10f * knockouts
            Mode.DOMINATE -> 120f
            Mode.SURVIVE -> 90f
            Mode.BOSS -> 180f
        }
        val rate = 14f + 26f * d
        val claim1 = MathX.clamp(100f * claimFactor * rate * time / area, 15f, 50f).roundToInt().toFloat()
        val goal = when (mode) {
            Mode.CLAIM -> Goal(mode, claim1, claim1 + max(8f, (claim1 * 0.35f).roundToInt().toFloat()),
                minOf(85f, claim1 + max(15f, (claim1 * 0.7f).roundToInt().toFloat())))
            Mode.COIN_RUSH -> {
                val t1 = 10f + (4f * d).roundToInt()
                Goal(mode, t1, t1 + 4f, t1 + 8f)
            }
            Mode.KNOCKOUT -> Goal(mode, 0f, 0.25f, 0.5f, count = knockouts)
            Mode.DOMINATE -> {
                val t2 = MathX.clamp(claim1 + 5f, 15f, 55f)
                Goal(mode, 0f, t2, t2 + 10f)
            }
            Mode.SURVIVE -> {
                val t1 = MathX.clamp((claim1 * 0.5f).roundToInt().toFloat(), 10f, 30f)
                Goal(mode, t1, t1 + 10f, t1 + 20f)
            }
            Mode.BOSS -> Goal(mode, 0f, claim1, claim1 + 10f, count = 1)
        }

        val used = HashSet<String>()
        val styles = (1..45).toMutableList()
        rng.shuffle(styles)
        val rivals = ArrayList<RivalSpec>()
        if (mode == Mode.BOSS) {
            val bossName = bossNames[(n / 50 - 1).coerceAtLeast(0) % bossNames.size]
            used.add(bossName)
            rivals += RivalSpec(bossName, 46 + (n / 50) % 2, BotProfile.rival(d, Archetype.BOSS), boss = true)
        }
        for (i in 0 until rivalCount) {
            val rd = MathX.clamp(d + rng.range(-0.08f, 0.08f), 0f, 1f)
            rivals += RivalSpec(RivalNames.pick(rng, used), styles[i % styles.size], BotProfile.rival(rd, archetype(rng, d)))
        }
        val hazards = if (n >= 51 && mode != Mode.COIN_RUSH && rng.chance(0.15f + 0.35f * d)) {
            1 + (if (d > 0.8f && rng.chance(0.3f)) 1 else 0)
        } else 0

        return LevelSpec(
            number = n,
            world = world,
            theme = Worlds.themeOf(world),
            lap = Worlds.lapOf(world),
            shapeId = shape.id,
            variant = variant,
            targetArea = area,
            startRadius = 4,
            rivals = rivals,
            goal = goal,
            timeLimit = time,
            powerUps = listOf(PickupType.SPEED, PickupType.SHIELD, PickupType.BOMB, PickupType.FREEZE),
            powerUpEvery = MathX.clamp(15f - 5f * d, 9f, 15f),
            firstPowerUpAt = rng.range(6f, 10f),
            maxPowerUps = 2 + (if (d > 0.5f) 1 else 0),
            rivalsUsePowerUps = n >= 101,
            hazards = hazards,
            hazardSpeed = 2.6f + 1f * d,
            starTokens = if (mode == Mode.COIN_RUSH) goal.t3.toInt() + 4 + (2f * d).roundToInt() else 0,
            difficulty = d,
            seed = rng.nextLong(),
            hints = 0,
            inkEnabled = true,
        )
    }

    private fun archetype(rng: Rng, d: Float): Archetype {
        val r = rng.nextFloat()
        return when {
            r < 0.30f - 0.1f * d -> Archetype.TIMID
            r < 0.55f - 0.05f * d -> Archetype.BUILDER
            r < 0.80f -> Archetype.HUNTER
            else -> Archetype.TRICKSTER
        }
    }

    private fun variantFor(shape: ShapeDef, d: Float, rng: Rng): ShapeVariant {
        val rot = if (shape.upright) rng.range(-12f, 12f) else rng.range(0f, 360f)
        val mirror = rng.chance(0.5f)
        val aspect = rng.range(0.9f, 1.12f)
        val wobble = if (rng.chance(0.12f + 0.25f * d)) rng.range(0.015f, 0.04f) else 0f
        val holes = if (shape.tier < 2 && d > 0.3f && rng.chance(0.12f + 0.3f * d)) {
            1 + (if (d > 0.7f && rng.chance(0.4f)) 1 else 0)
        } else 0
        return ShapeVariant(rot, mirror, aspect, wobble, holes, rng.nextInt())
    }
}
