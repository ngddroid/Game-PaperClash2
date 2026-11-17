package com.ngstudio.paperclash2.engine.level

import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import com.ngstudio.paperclash2.engine.world.PickupType

enum class Mode(val title: String) {
    CLAIM("Claim"),
    COIN_RUSH("Coin Rush"),
    KNOCKOUT("Knockout"),
    DOMINATE("Dominate"),
    SURVIVE("Survive"),
    BOSS("Boss"),
}

/**
 * Win condition and star thresholds.
 * - CLAIM / SURVIVE: t1..t3 are land percentages.
 * - COIN_RUSH: t1..t3 are paper-star counts.
 * - KNOCKOUT: [count] snips win; stars from time left (t2, t3 = fractions of the timer).
 * - DOMINATE: be #1 at the end; t2, t3 are land percentages for extra stars.
 * - BOSS: snip the boss for 1 star; t2, t3 are land percentages for extra stars.
 */
data class Goal(val mode: Mode, val t1: Float, val t2: Float, val t3: Float, val count: Int = 0) {
    fun summary(): String = when (mode) {
        Mode.CLAIM -> "Claim ${t1.toInt()}% of the shape"
        Mode.COIN_RUSH -> "Collect ${t1.toInt()} paper stars"
        Mode.KNOCKOUT -> "Snip $count rival ribbons"
        Mode.DOMINATE -> "Be #1 when time runs out"
        Mode.SURVIVE -> "Stay alive holding ${t1.toInt()}%"
        Mode.BOSS -> "Snip the Boss plane"
    }

    /** Text for the three star rows on the level sheet. */
    fun starLines(): List<String> = when (mode) {
        Mode.CLAIM, Mode.SURVIVE -> listOf("${t1.toInt()}%", "${t2.toInt()}%", "${t3.toInt()}%")
        Mode.COIN_RUSH -> listOf("${t1.toInt()} stars", "${t2.toInt()} stars", "${t3.toInt()} stars")
        Mode.KNOCKOUT -> listOf("$count snips", "with ${(t2 * 100).toInt()}% time left", "with ${(t3 * 100).toInt()}% time left")
        Mode.DOMINATE -> listOf("Rank #1", "#1 and ${t2.toInt()}%", "#1 and ${t3.toInt()}%")
        Mode.BOSS -> listOf("Boss snipped", "+ ${t2.toInt()}% land", "+ ${t3.toInt()}% land")
    }

    /** Whether the player may bank the win with FINISH once one star is earned. */
    val bankable: Boolean get() = mode == Mode.CLAIM || mode == Mode.COIN_RUSH || mode == Mode.BOSS
}

data class RivalSpec(
    val name: String,
    val style: Int,
    val profile: BotProfile,
    val boss: Boolean = false,
)

/** Bit flags for the tutorial hints shown in levels 1–10. */
object Hints {
    const val STEER = 1
    const val LEAVE = 2
    const val CLOSE = 4
    const val SNIP = 8
    const val SPEED = 16
    const val SHIELD = 32
    const val GLIDE = 64
    const val INK = 128
    const val BOMB = 256
    const val RADAR = 512
    const val KING = 1024
    const val COINS = 2048
    const val BIGGER = 4096
}

/** Everything needed to build and play one level. Pure data, identical on every device. */
data class LevelSpec(
    val number: Int,
    val world: Int,
    val theme: Int,
    val lap: Int,
    val shapeId: String,
    val variant: ShapeVariant,
    val targetArea: Int,
    val startRadius: Int,
    val rivals: List<RivalSpec>,
    val goal: Goal,
    val timeLimit: Float,
    val powerUps: List<PickupType>,
    val powerUpEvery: Float,
    val firstPowerUpAt: Float,
    val maxPowerUps: Int,
    val rivalsUsePowerUps: Boolean,
    val hazards: Int,
    val hazardSpeed: Float,
    val starTokens: Int,
    val difficulty: Float,
    val seed: Long,
    val hints: Int,
    val inkEnabled: Boolean,
) {
    val mode: Mode get() = goal.mode
    val playerCount: Int get() = rivals.size + 1
    val isBoss: Boolean get() = rivals.any { it.boss }
}
