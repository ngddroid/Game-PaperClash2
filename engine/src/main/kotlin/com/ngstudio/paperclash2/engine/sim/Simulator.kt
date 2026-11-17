package com.ngstudio.paperclash2.engine.sim

import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.game.Boosters
import com.ngstudio.paperclash2.engine.game.EndReason
import com.ngstudio.paperclash2.engine.game.GameSession
import com.ngstudio.paperclash2.engine.level.LevelSpec
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.shape.Arena
import kotlin.math.max
import kotlin.math.min

/** Plays levels headless with an autopilot in the player's seat (the balance R&D tool). */
object Simulator {
    class Outcome(
        val won: Boolean, val stars: Int, val time: Float, val percent: Float, val reason: EndReason, val popped: Boolean,
        val cause: com.ngstudio.paperclash2.engine.world.Cause?, val hunted: Boolean,
    )

    /** Autopilot tuned for the level's mode (collects stars in Coin Rush, hunts in Knockout/Boss). */
    fun pilotFor(spec: LevelSpec, base: BotProfile): BotProfile = when (spec.mode) {
        Mode.COIN_RUSH -> base.copy(collector = true)
        Mode.KNOCKOUT -> base.copy(chase = min(0.85f, base.chase * 3f + 0.15f), chaseRange = base.chaseRange + 5f, huntBias = true)
        Mode.BOSS -> base.copy(targetBoss = true, chase = max(0.5f, base.chase * 2f), chaseRange = base.chaseRange + 6f, huntBias = true)
        Mode.SURVIVE -> base.copy(caution = base.caution + 0.2f)
        else -> base
    }

    /**
     * Runs one attempt. With [bankAtFirstStar] the autopilot banks the win as soon as it can,
     * which is what the "can a new player win this" gate measures.
     */
    fun run(spec: LevelSpec, arena: Arena, pilot: BotProfile, seed: Long, bankAtFirstStar: Boolean = true): Outcome {
        val s = GameSession(spec, arena, "Pilot", 0, Boosters(), seed)
        s.enableAutopilot(pilotFor(spec, pilot))
        s.begin()
        val maxTicks = ((spec.timeLimit + 5f) / GameSession.STEP).toInt()
        var ticks = 0
        var popped = false
        while (s.state == GameSession.State.PLAYING || s.state == GameSession.State.DOWN) {
            if (s.state == GameSession.State.DOWN) {
                popped = true
                s.giveUp()
                break
            }
            s.step(GameSession.STEP)
            s.events.clear()
            if (bankAtFirstStar && s.canFinish) s.finish()
            if (++ticks > maxTicks) {
                s.abort()
                break
            }
        }
        val r = s.result!!
        return Outcome(r.won, r.stars, r.time, r.percent, r.reason, popped, r.cause, r.hunted)
    }
}
