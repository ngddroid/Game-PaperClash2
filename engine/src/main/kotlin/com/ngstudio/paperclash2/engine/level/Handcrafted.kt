package com.ngstudio.paperclash2.engine.level

import com.ngstudio.paperclash2.engine.ai.Archetype
import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import com.ngstudio.paperclash2.engine.world.PickupType

/**
 * Levels 1–10, made by hand so a brand-new player wins them while learning one idea per level.
 * Every rival here shows "mercy": it steers around your ribbon unless it decides to chase, and
 * the chase chances are tiny. The balance tests hold these levels to ≥ 92–97% Novice wins.
 */
object Handcrafted {
    const val COUNT = 10

    private val shapes = arrayOf("", "circle", "heart", "tile", "star5", "cloud", "hexagon", "crescent", "clover", "fish", "crown")

    fun shapeOf(n: Int): String = shapes[n]

    fun difficulty(n: Int): Float = 0.02f + 0.01f * n

    fun spec(n: Int): LevelSpec {
        require(n in 1..COUNT)
        val rng = Rng(0xC0FFEEL + n * 7919L)
        val used = HashSet<String>()
        fun rivals(vararg p: BotProfile) = p.mapIndexed { i, prof -> RivalSpec(RivalNames.pick(rng, used), styleFor(n, i), prof) }
        val claim = Mode.CLAIM
        return when (n) {
            1 -> make(n, ShapeVariant(), 2600, 5, rivals(passive(0.80f)), Goal(claim, 20f, 30f, 40f), 180f,
                hints = Hints.STEER or Hints.LEAVE or Hints.CLOSE)
            2 -> make(n, ShapeVariant(), 2900, 5, rivals(passive(0.80f)), Goal(claim, 22f, 32f, 42f), 180f,
                hints = Hints.BIGGER)
            3 -> make(n, ShapeVariant(rotDeg = 8f), 3500, 5, rivals(passive(0.82f), passive(0.82f)), Goal(claim, 22f, 32f, 42f), 180f,
                hints = Hints.SNIP)
            4 -> make(n, ShapeVariant(), 3900, 5, rivals(timid(0.85f, 0.015f), timid(0.85f, 0.015f)), Goal(claim, 24f, 34f, 44f), 165f,
                powerUps = listOf(PickupType.SPEED), hints = Hints.SPEED)
            5 -> make(n, ShapeVariant(), 3800, 4, rivals(passive(0.80f)), Goal(Mode.COIN_RUSH, 12f, 16f, 20f), 75f,
                starTokens = 24, hints = Hints.COINS)
            6 -> make(n, ShapeVariant(rotDeg = 15f), 4600, 5, rivals(timid(0.85f, 0.05f), timid(0.85f, 0.05f), timid(0.85f, 0.05f)),
                Goal(claim, 22f, 32f, 42f), 180f, powerUps = listOf(PickupType.SPEED, PickupType.SHIELD), hints = Hints.SHIELD)
            7 -> make(n, ShapeVariant(rotDeg = -20f), 4800, 5, rivals(timid(0.87f, 0.06f), timid(0.87f, 0.06f), timid(0.87f, 0.06f)),
                Goal(claim, 22f, 32f, 42f), 180f, powerUps = listOf(PickupType.SPEED, PickupType.SHIELD), hints = Hints.GLIDE)
            8 -> make(n, ShapeVariant(), 5000, 5, rivals(timid(0.88f, 0.06f), timid(0.88f, 0.06f), builder(0.88f, 0.06f)),
                Goal(claim, 22f, 32f, 42f), 180f, powerUps = listOf(PickupType.SPEED, PickupType.SHIELD), ink = true, hints = Hints.INK)
            9 -> make(n, ShapeVariant(mirror = true), 5800, 5,
                rivals(timid(0.9f, 0.08f), builder(0.9f, 0.08f), timid(0.9f, 0.08f), builder(0.9f, 0.08f)),
                Goal(claim, 22f, 32f, 42f), 180f, powerUps = listOf(PickupType.SPEED, PickupType.SHIELD, PickupType.BOMB), ink = true,
                hints = Hints.BOMB or Hints.RADAR)
            else -> {
                val list = ArrayList<RivalSpec>()
                list += RivalSpec("King Crumple", 46, BotProfile.rival(0.1f, Archetype.KING, mercy = true), boss = true)
                list += rivals(timid(0.85f, 0.04f), timid(0.85f, 0.04f), timid(0.85f, 0.04f))
                make(n, ShapeVariant(), 6000, 5, list, Goal(Mode.BOSS, 0f, 20f, 30f, count = 1), 180f,
                    powerUps = listOf(PickupType.SPEED, PickupType.SHIELD), ink = true, hints = Hints.KING)
            }
        }
    }

    private fun styleFor(level: Int, i: Int): Int = (level * 7 + i * 11) % 44 + 1

    private fun passive(speed: Float) = BotProfile.rival(0.05f, Archetype.PASSIVE, mercy = true).copy(speedMul = speed)

    private fun timid(speed: Float, chase: Float) =
        BotProfile.rival(0.1f, Archetype.TIMID, mercy = true).copy(speedMul = speed, chase = chase, huntsHuman = chase > 0f)

    private fun builder(speed: Float, chase: Float) =
        BotProfile.rival(0.1f, Archetype.BUILDER, mercy = true).copy(speedMul = speed, chase = chase, huntsHuman = chase > 0f)

    private fun make(
        n: Int,
        variant: ShapeVariant,
        area: Int,
        start: Int,
        rivals: List<RivalSpec>,
        goal: Goal,
        time: Float,
        powerUps: List<PickupType> = emptyList(),
        starTokens: Int = 0,
        ink: Boolean = false,
        hints: Int = 0,
    ) = LevelSpec(
        number = n,
        world = 1,
        theme = 0,
        lap = 0,
        shapeId = shapes[n],
        variant = variant,
        targetArea = area,
        startRadius = start,
        rivals = rivals,
        goal = goal,
        timeLimit = time,
        powerUps = powerUps,
        powerUpEvery = 11f,
        firstPowerUpAt = 5f,
        maxPowerUps = 2,
        rivalsUsePowerUps = false,
        hazards = 0,
        hazardSpeed = 3f,
        starTokens = starTokens,
        difficulty = difficulty(n),
        seed = 0xA11CEL * n + 17,
        hints = hints,
        inkEnabled = ink,
    )
}
