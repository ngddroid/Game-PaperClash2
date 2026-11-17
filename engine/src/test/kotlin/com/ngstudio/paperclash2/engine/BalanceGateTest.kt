package com.ngstudio.paperclash2.engine

import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.sim.Balance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Let the new player win": levels 1–10 must be won by the Novice autopilot almost every time.
 * Seeds come from -Pseeds (default 40; use 200 for the report-grade run).
 */
class BalanceGateTest {
    private val seeds = (System.getProperty("balance.seeds") ?: "40").toInt()

    @Test
    fun firstTenLevelsAreWinnableForANovice() {
        val problems = ArrayList<String>()
        for (n in 1..10) {
            val s = Balance.stats(n, BotProfile.NOVICE, seeds)
            val need = if (n <= 5) 0.97f else 0.92f
            val c = Balance.stats(n, BotProfile.CASUAL, seeds)
            println("L$n novice win ${(s.winRate * 100).toInt()}% (median ${s.medianWinTime}s, popped ${s.popped}/${s.runs}) casual ${(c.winRate * 100).toInt()}% (median ${c.medianWinTime}s)")
            if (s.winRate < need) problems += "L$n novice win rate ${s.winRate} < $need"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
