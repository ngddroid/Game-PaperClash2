package com.ngstudio.paperclash2.engine.ai

import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.world.World
import kotlin.math.max
import kotlin.math.min

enum class Archetype { PASSIVE, TIMID, BUILDER, HUNTER, TRICKSTER, BOSS, KING }

/**
 * How a plane flies when a [BotBrain] steers it. Rivals get one per level; the balance simulator
 * also puts one in the player's seat (Novice / Casual / Expert) to measure how winnable a level is.
 */
data class BotProfile(
    val speedMul: Float = 1f,
    val turnRate: Float = World.TURN_RATE,
    /** Seconds between decisions (reaction time). */
    val think: Float = 0.4f,
    val loopMin: Float = 6f,
    val loopMax: Float = 14f,
    /** Loop width as a multiple of its depth. */
    val widthMul: Float = 0.85f,
    /** ≥ 1: how early it heads home when someone could reach its ribbon. */
    val caution: Float = 1.3f,
    /** Chance per decision to go for a reachable ribbon. */
    val chase: Float = 0.15f,
    val chaseRange: Float = 9f,
    val huntsHuman: Boolean = true,
    /** Treats the player's ribbon as a wall unless chasing (early levels). */
    val mercy: Boolean = false,
    /** Steering wobble in radians. */
    val noise: Float = 0.15f,
    /** Chance a loop overextends (bigger than planned). */
    val overreach: Float = 0.06f,
    val maxTrail: Int = 70,
    /** Goes for Coin Rush stars (simulator autopilot). */
    val collector: Boolean = false,
    val wantsPickups: Boolean = false,
    /** Simulator autopilot in boss levels: only chases the boss. */
    val targetBoss: Boolean = false,
    /** Plans loops toward its prey (boss or nearest rival) so chances to snip come up. */
    val huntBias: Boolean = false,
    /** Multiplier on [chase] when the prey would be the human player. */
    val humanChase: Float = 1f,
    /** Steers around the player's ribbon unless hunting it (no accidental pops). */
    val avoidRibbon: Boolean = false,
) {
    companion object {
        /** A rival for difficulty [d] (0..1) with a personality. */
        fun rival(d: Float, arch: Archetype, mercy: Boolean = false): BotProfile {
            val dd = MathX.clamp(d, 0f, 1f)
            val base = BotProfile(
                speedMul = 0.88f + 0.12f * dd,
                turnRate = 15f + 6f * dd,
                think = 0.62f - 0.42f * dd,
                loopMin = 4f + 3f * dd,
                loopMax = 9f + 9f * dd,
                caution = 1.7f - 0.55f * dd,
                chase = 0.04f + 0.3f * dd,
                chaseRange = 6f + 8f * dd,
                huntsHuman = true,
                mercy = mercy,
                noise = 0.3f - 0.22f * dd,
                overreach = 0.14f - 0.1f * dd,
                maxTrail = (35 + 65 * dd).toInt(),
                wantsPickups = dd > 0.35f,
                humanChase = 0.1f + 0.25f * dd,
            )
            return when (arch) {
                Archetype.PASSIVE -> base.copy(chase = 0f, huntsHuman = false, loopMin = 3.5f, loopMax = 7f, caution = 2f, maxTrail = 30, avoidRibbon = true)
                Archetype.TIMID -> base.copy(chase = base.chase * 0.5f, loopMax = base.loopMax * 0.75f, caution = base.caution + 0.3f, avoidRibbon = true)
                Archetype.BUILDER -> base.copy(
                    loopMin = base.loopMin * 1.3f, loopMax = base.loopMax * 1.35f, chase = base.chase * 0.6f,
                    maxTrail = (base.maxTrail * 1.3f).toInt(), avoidRibbon = true,
                )
                Archetype.HUNTER -> base.copy(
                    chase = min(0.8f, base.chase * 1.4f + 0.04f), chaseRange = base.chaseRange * 1.3f,
                    loopMax = base.loopMax * 0.85f, avoidRibbon = true,
                )
                Archetype.TRICKSTER -> base.copy(
                    loopMin = 3f, loopMax = 6f + 4f * dd, widthMul = 2f, chase = min(0.9f, base.chase * 1.2f), avoidRibbon = true,
                )
                Archetype.BOSS -> base.copy(
                    speedMul = 0.95f, turnRate = 22f, think = max(0.15f, base.think * 0.8f), chase = 0.3f + 0.3f * dd, chaseRange = 12f,
                    loopMin = 9f, loopMax = base.loopMax * 1.4f, widthMul = 1.1f, caution = 1.05f, maxTrail = 130,
                    huntsHuman = true, mercy = false, humanChase = 0.6f + 0.4f * dd,
                )
                Archetype.KING -> base.copy(
                    speedMul = 0.72f, chase = 0f, huntsHuman = false, loopMin = 8f, loopMax = 15f, widthMul = 1.1f,
                    caution = 1.25f, maxTrail = 70, noise = 0.12f, think = 0.5f, avoidRibbon = true,
                )
            }
        }

        /** Player stand-ins for the balance simulation. */
        val NOVICE = BotProfile(
            speedMul = 1f, turnRate = 20f, think = 0.42f, loopMin = 5f, loopMax = 11f, caution = 1.55f, chase = 0.04f,
            chaseRange = 6f, huntsHuman = false, noise = 0.2f, overreach = 0.08f, maxTrail = 50,
        )
        val CASUAL = BotProfile(
            speedMul = 1f, turnRate = 20f, think = 0.3f, loopMin = 7f, loopMax = 15f, caution = 1.35f, chase = 0.12f,
            chaseRange = 8f, huntsHuman = false, noise = 0.14f, overreach = 0.06f, maxTrail = 70,
        )
        val EXPERT = BotProfile(
            speedMul = 1f, turnRate = 20f, think = 0.18f, loopMin = 9f, loopMax = 22f, widthMul = 1.1f, caution = 1.18f,
            chase = 0.4f, chaseRange = 12f, huntsHuman = false, noise = 0.07f, overreach = 0.03f, maxTrail = 110,
        )
    }
}
