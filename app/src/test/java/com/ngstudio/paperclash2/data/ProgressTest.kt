package com.ngstudio.paperclash2.data

import com.ngstudio.paperclash2.engine.game.EndReason
import com.ngstudio.paperclash2.engine.game.SessionResult
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {

    private fun result(won: Boolean, stars: Int = if (won) 1 else 0) = SessionResult(
        won = won, stars = stars, percent = if (won) 30f else 5f, bestPercent = 30f, snips = 0, time = 60f,
        reason = if (won) EndReason.FINISHED else EndReason.POPPED, killerName = null, starsCollected = 0,
        bossPopped = false, rank = 1, maxCombo = 1, captures = 3, cause = null, hunted = false,
    )

    private fun play(d: SaveData, level: Int, won: Boolean = true): Pair<SaveData, LevelReward> =
        Progress.applyResult(d, LevelGenerator.spec(level), result(won), 60f)

    @Test
    fun freshSaveOnlyOpensLevelOne() {
        val d = SaveData()
        assertTrue(Progress.isUnlocked(d, 1))
        assertFalse(Progress.isUnlocked(d, 2))
        assertEquals(1, Progress.currentLevel(d))
    }

    @Test
    fun winningOpensTheNextLevel() {
        val (d, reward) = play(SaveData(), 1)
        assertTrue(reward.unlockedNext)
        assertTrue(Progress.isUnlocked(d, 2))
        assertFalse(Progress.isUnlocked(d, 3))
        assertEquals(2, Progress.currentLevel(d))
    }

    @Test
    fun losingOpensNothing() {
        val (d, reward) = play(SaveData(), 1, won = false)
        assertFalse(reward.unlockedNext)
        assertFalse(Progress.isUnlocked(d, 2))
        assertEquals(1, Progress.currentLevel(d))
    }

    @Test
    fun winningTheFrontierAdvancesEvenWhenTheNextLevelWasOpenedByAnAd() {
        var d = play(play(SaveData(), 1).first, 2).first
        assertEquals(3, Progress.currentLevel(d))
        d = Progress.unlockByAd(d, 4)
        val (after, reward) = play(d, 3)
        assertFalse("level 4 was already open", reward.unlockedNext)
        assertEquals(4, after.maxUnlocked)
        assertEquals(4, Progress.currentLevel(after))
        assertTrue(after.extraUnlocked.none { it <= after.maxUnlocked })
    }

    @Test
    fun levelsWonAheadOfTheFrontierAreSkippedOnceItCatchesUp() {
        var d = play(play(SaveData(), 1).first, 2).first
        d = Progress.unlockByAd(d, 4)
        val (aheadWon, reward) = play(d, 4)
        assertTrue(reward.unlockedNext)
        assertTrue(Progress.isUnlocked(aheadWon, 5))
        assertEquals("the frontier waits at 3", 3, Progress.currentLevel(aheadWon))
        val caughtUp = play(aheadWon, 3).first
        assertEquals(5, Progress.currentLevel(caughtUp))
        assertEquals(5, caughtUp.maxUnlocked)
    }

    @Test
    fun anAdUnlockFarAheadOpensOnlyTheLevelAfterIt() {
        val d = Progress.unlockByAd(SaveData(), 2001)
        val after = play(d, 2001).first
        assertTrue(Progress.isUnlocked(after, 2002))
        assertFalse(Progress.isUnlocked(after, 2003))
        assertFalse(Progress.isUnlocked(after, 2))
        assertEquals(1, Progress.currentLevel(after))
    }

    @Test
    fun savesStuckByTheOldRuleShowTheRightLevel() {
        // Old rule: winning level 3 with level 4 already ad-unlocked left maxUnlocked at 3.
        val stuck = SaveData(stars = "111", maxUnlocked = 3, extraUnlocked = listOf(4))
        assertEquals(4, Progress.currentLevel(stuck))
        val next = play(stuck, 4).first
        assertEquals(5, Progress.currentLevel(next))
        assertEquals(5, next.maxUnlocked)
    }

    @Test
    fun replayingAnOldLevelChangesNoUnlocks() {
        val d = play(play(SaveData(), 1).first, 2).first
        val (again, reward) = play(d, 1)
        assertFalse(reward.unlockedNext)
        assertEquals(d.maxUnlocked, again.maxUnlocked)
        assertEquals(d.extraUnlocked, again.extraUnlocked)
    }
}
