package com.ngstudio.paperclash2.data

import com.ngstudio.paperclash2.engine.game.SessionResult
import com.ngstudio.paperclash2.engine.level.LevelSpec
import com.ngstudio.paperclash2.game.Skins
import com.ngstudio.paperclash2.game.Unlock
import kotlin.random.Random

/** Daily quests: three per day, drawn from a pool and scaled a little with progress. */
object Quests {
    class Template(val type: String, val title: String, val min: Int, val max: Int, val reward: Int)

    val pool = listOf(
        Template("win", "Win %d levels", 2, 4, 120),
        Template("claim", "Claim %d%% in one level", 30, 50, 100),
        Template("snips", "Snip %d rival ribbons", 2, 6, 130),
        Template("captures", "Close %d loops", 15, 35, 90),
        Template("stars", "Earn %d stars", 3, 7, 110),
        Template("combo", "Reach a combo of x%d", 2, 4, 100),
        Template("powerups", "Grab %d power-ups", 2, 5, 90),
        Template("minutes", "Play for %d minutes", 5, 12, 80),
    )

    fun title(slot: QuestSlot): String = (pool.firstOrNull { it.type == slot.type }?.title ?: "%d").format(slot.target)

    /** Makes sure today's quests exist. */
    fun refresh(d: SaveData): SaveData {
        val day = Progress.today()
        if (d.quests.day == day && d.quests.slots.isNotEmpty()) return d
        return d.copy(quests = QuestState(day, generate(day, Progress.totalStars(d)), false))
    }

    private fun generate(day: Long, stars: Int): List<QuestSlot> {
        val rng = Random(day * 7919 + 11)
        val picks = pool.shuffled(rng).take(3)
        val scale = (stars / 300f).coerceIn(0f, 1f)
        return picks.map { t ->
            val target = (t.min + (t.max - t.min) * (0.3f + 0.7f * rng.nextFloat()) * (0.6f + 0.4f * scale)).toInt().coerceIn(t.min, t.max)
            QuestSlot(t.type, target, t.reward + 10 * (target - t.min))
        }
    }

    fun reroll(d: SaveData, index: Int): SaveData {
        val q = d.quests
        if (q.rerolled || index !in q.slots.indices || q.slots[index].claimed) return d
        val used = q.slots.map { it.type }.toSet()
        val t = pool.filter { it.type !in used }.random()
        val target = (t.min + t.max) / 2
        val slots = q.slots.toMutableList()
        slots[index] = QuestSlot(t.type, target, t.reward + 10 * (target - t.min))
        return d.copy(quests = q.copy(slots = slots, rerolled = true))
    }

    /** Advances quests after a level. */
    fun progress(d: SaveData, r: SessionResult, spec: LevelSpec): SaveData {
        val q = refresh(d).quests
        val slots = q.slots.map { s ->
            if (s.claimed) return@map s
            val add = when (s.type) {
                "win" -> if (r.won) 1 else 0
                "snips" -> r.snips
                "captures" -> r.captures
                "stars" -> if (r.won) r.stars else 0
                "powerups" -> 0
                "minutes" -> 0
                "claim" -> if (r.bestPercent.toInt() >= s.target) s.target else 0
                "combo" -> if (r.maxCombo >= s.target) s.target else 0
                else -> 0
            }
            val p = if (s.type == "claim" || s.type == "combo") maxOf(s.progress, add) else s.progress + add
            s.copy(progress = p.coerceAtMost(s.target))
        }
        return d.copy(quests = q.copy(slots = slots))
    }

    fun addMinutes(d: SaveData, minutes: Int): SaveData = bump(d, "minutes", minutes)
    fun addPowerUps(d: SaveData, n: Int): SaveData = bump(d, "powerups", n)

    private fun bump(d: SaveData, type: String, n: Int): SaveData {
        val q = refresh(d).quests
        return d.copy(quests = q.copy(slots = q.slots.map { if (it.type == type && !it.claimed) it.copy(progress = (it.progress + n).coerceAtMost(it.target)) else it }))
    }

    fun claim(d: SaveData, index: Int): SaveData {
        val q = d.quests
        val s = q.slots.getOrNull(index) ?: return d
        if (s.claimed || s.progress < s.target) return d
        val slots = q.slots.toMutableList()
        slots[index] = s.copy(claimed = true)
        return d.copy(coins = d.coins + s.reward, quests = q.copy(slots = slots))
    }

    fun claimable(d: SaveData): Int = d.quests.slots.count { !it.claimed && it.progress >= it.target }
}

/** Tiered achievements over lifetime stats. */
object Achievements {
    class Def(val id: String, val title: String, val tiers: IntArray, val value: (SaveData) -> Int, val rewardCoins: Int, val unlock: String? = null)

    val all = listOf(
        Def("won", "Level Climber", intArrayOf(10, 50, 200, 1000), { it.stats.levelsWon }, 200),
        Def("stars", "Star Collector", intArrayOf(30, 150, 600, 3000), { Progress.totalStars(it) }, 250),
        Def("snips", "Sharp Scissors", intArrayOf(10, 100, 500, 2000), { it.stats.snips }, 250, unlock = "skin"),
        Def("captures", "Loop Master", intArrayOf(50, 500, 2500, 10000), { it.stats.captures }, 200, unlock = "ribbon"),
        Def("bosses", "Boss Breaker", intArrayOf(1, 5, 20, 50), { it.stats.bossesBeaten }, 400),
        Def("album", "Sticker Fan", intArrayOf(5, 20, 40, 70), { it.album.size }, 300),
        Def("combo", "Combo Artist", intArrayOf(3, 5, 8, 12), { it.stats.bestCombo }, 200),
        Def("three", "Perfectionist", intArrayOf(5, 50, 250, 1000), { it.stats.threeStarLevels }, 300),
    )

    fun claimedTiers(d: SaveData, a: Def) = d.achievementTiers[a.id] ?: 0

    fun reachedTiers(d: SaveData, a: Def): Int = a.tiers.count { a.value(d) >= it }

    fun claimable(d: SaveData): Int = all.count { reachedTiers(d, it) > claimedTiers(d, it) }

    fun claim(d: SaveData, a: Def): Pair<SaveData, String> {
        val claimed = claimedTiers(d, a)
        if (reachedTiers(d, a) <= claimed) return d to ""
        val tier = claimed + 1
        var save = d.copy(achievementTiers = d.achievementTiers + (a.id to tier), coins = d.coins + a.rewardCoins * tier)
        var label = "+${a.rewardCoins * tier} coins"
        if (tier == 2 && a.unlock == "skin") {
            Skins.all.firstOrNull { it.unlock == Unlock.ACHIEVEMENT && it.style !in save.ownedSkins }?.let {
                save = save.copy(ownedSkins = save.ownedSkins + it.style)
                label += " and ${it.name}"
            }
        }
        if (tier == 2 && a.unlock == "ribbon") {
            Skins.ribbons.firstOrNull { it.unlock == Unlock.ACHIEVEMENT && it.id !in save.ownedRibbons }?.let {
                save = save.copy(ownedRibbons = save.ownedRibbons + it.id)
                label += " and the ${it.name} ribbon"
            }
        }
        return save to label
    }
}
