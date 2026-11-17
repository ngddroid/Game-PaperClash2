package com.ngstudio.paperclash2.data

import com.ngstudio.paperclash2.engine.game.SessionResult
import com.ngstudio.paperclash2.engine.level.LevelSpec
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.level.Worlds
import com.ngstudio.paperclash2.engine.shape.ShapeCategory
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.game.Skins
import com.ngstudio.paperclash2.game.Unlock
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What a finished level paid out (shown on the results screen). */
data class LevelReward(
    val coins: Int,
    val baseCoins: Int,
    val starBonus: Int,
    val snipBonus: Int,
    val comboBonus: Int,
    val newStars: Int,
    val firstClear: Boolean,
    val sticker: String?,
    val stickerTier: Int,
    val unlockedNext: Boolean,
)

/** Pure rules on [SaveData]: unlocking, rewards, quests, achievements, album, gifts. */
object Progress {
    fun today(): Long = LocalDate.now().toEpochDay()

    fun starsOf(d: SaveData, level: Int): Int {
        val i = level - 1
        return if (i < d.stars.length) d.stars[i] - '0' else 0
    }

    fun totalStars(d: SaveData): Int = d.stars.sumOf { it - '0' }

    fun isUnlocked(d: SaveData, level: Int): Boolean = level <= d.maxUnlocked || level in d.extraUnlocked

    /**
     * The frontier: the first level from [SaveData.maxUnlocked] upward that hasn't been won.
     * Winning the frontier opens the next level even when an ad opened it already, and levels
     * already won through ad unlocks are skipped.
     */
    fun frontier(d: SaveData): Int {
        var m = d.maxUnlocked.coerceIn(1, Worlds.TOTAL_LEVELS)
        while (m < Worlds.TOTAL_LEVELS && starsOf(d, m) > 0) m++
        return m
    }

    /** The next level to play (the frontier). */
    fun currentLevel(d: SaveData): Int = frontier(d)

    fun unlockByAd(d: SaveData, level: Int): SaveData =
        if (isUnlocked(d, level)) d else d.copy(extraUnlocked = (d.extraUnlocked + level).distinct())

    private fun setStars(s: String, level: Int, stars: Int): String {
        val sb = StringBuilder(s)
        while (sb.length < level) sb.append('0')
        if (sb[level - 1] - '0' < stars) sb.setCharAt(level - 1, ('0' + stars))
        return sb.toString()
    }

    fun coinsFor(spec: LevelSpec, r: SessionResult): Triple<Int, Int, Int> {
        val base = 40 + min(200, spec.number / 10)
        val starBonus = if (r.won) 25 * (r.stars - 1).coerceAtLeast(0) else 0
        val snipBonus = 10 * r.snips + (if (spec.mode == Mode.COIN_RUSH) 3 * r.starsCollected else 0)
        return Triple(base, starBonus, snipBonus)
    }

    /** Applies a finished level; returns the new save and the reward summary. */
    fun applyResult(d: SaveData, spec: LevelSpec, r: SessionResult, playSeconds: Float): Pair<SaveData, LevelReward> {
        val level = spec.number
        val oldStars = starsOf(d, level)
        val stats0 = d.stats
        var save = d
        var unlockedNext = false
        var sticker: String? = null
        var stickerTier = 0
        val coins: Int
        val (base, starBonus, snipBonus) = coinsFor(spec, r)
        val comboBonus = if (r.won) 5 * (r.maxCombo - 1).coerceAtLeast(0) else 0
        if (r.won) {
            coins = base + starBonus + snipBonus + comboBonus
            save = save.copy(stars = setStars(save.stars, level, r.stars))
            if (level < Worlds.TOTAL_LEVELS) {
                val wasOpen = isUnlocked(d, level + 1)
                // A level ahead of the frontier (opened by an ad) opens just the one after it.
                if (level > save.maxUnlocked && !wasOpen) {
                    save = save.copy(extraUnlocked = (save.extraUnlocked + (level + 1)).distinct())
                }
                // Winning the frontier moves it on, past any levels already won.
                val m = frontier(save)
                if (m != save.maxUnlocked) save = save.copy(maxUnlocked = m, extraUnlocked = save.extraUnlocked.filter { it > m })
                unlockedNext = !wasOpen && isUnlocked(save, level + 1)
            }
            // sticker album
            val shape = spec.shapeId
            val oldTier = save.album[shape] ?: 0
            var threes = save.shapeThreeStars[shape] ?: 0
            if (r.stars == 3 && oldStars < 3) threes++
            val tier = when {
                threes >= 5 -> 3
                threes >= 1 -> 2
                else -> 1
            }
            if (tier > oldTier) {
                sticker = shape
                stickerTier = tier
            }
            save = save.copy(album = save.album + (shape to max(tier, oldTier)), shapeThreeStars = save.shapeThreeStars + (shape to threes))
        } else {
            coins = 8 + (r.bestPercent / 3f).toInt() + 5 * r.snips
        }
        val stats = stats0.copy(
            levelsWon = stats0.levelsWon + if (r.won) 1 else 0,
            levelsPlayed = stats0.levelsPlayed + 1,
            snips = stats0.snips + r.snips,
            captures = stats0.captures + r.captures,
            pops = stats0.pops + if (!r.won && r.cause != null) 1 else 0,
            bestCombo = max(stats0.bestCombo, r.maxCombo),
            bossesBeaten = stats0.bossesBeaten + if (r.won && spec.isBoss) 1 else 0,
            playSeconds = stats0.playSeconds + playSeconds.toLong(),
            bestPercent = max(stats0.bestPercent, r.bestPercent),
            threeStarLevels = stats0.threeStarLevels + if (r.won && r.stars == 3 && oldStars < 3) 1 else 0,
        )
        save = save.copy(coins = save.coins + coins, stats = stats)
        save = Quests.progress(save, r, spec)
        val reward = LevelReward(
            coins = coins, baseCoins = if (r.won) base else coins, starBonus = starBonus, snipBonus = snipBonus, comboBonus = comboBonus,
            newStars = max(0, r.stars - oldStars), firstClear = r.won && oldStars == 0, sticker = sticker, stickerTier = stickerTier,
            unlockedNext = unlockedNext,
        )
        return save to reward
    }

    fun countPickup(d: SaveData) = d.copy(stats = d.stats.copy(powerUps = d.stats.powerUps + 1))

    // ------------------------------------------------------------------ Star Road

    const val STAR_ROAD_STEP = 30

    fun starRoadNext(d: SaveData): Int = (d.starRoadClaimed + 1) * STAR_ROAD_STEP

    fun canClaimStarRoad(d: SaveData): Boolean = totalStars(d) >= starRoadNext(d)

    /** Reward for milestone k (1-based): coins, booster packs, Star Road skins and ribbons. */
    fun starRoadReward(k: Int): String = when {
        k % 5 == 0 -> "skin"
        k % 5 == 3 -> "boosters"
        else -> "coins:${min(1500, 200 + 50 * k)}"
    }

    fun claimStarRoad(d: SaveData): Pair<SaveData, String> {
        if (!canClaimStarRoad(d)) return d to ""
        val k = d.starRoadClaimed + 1
        var save = d.copy(starRoadClaimed = k)
        val label: String
        when (val r = starRoadReward(k)) {
            "skin" -> {
                val skin = Skins.all.firstOrNull { it.unlock == Unlock.STAR_ROAD && it.style !in save.ownedSkins }
                if (skin != null) {
                    save = save.copy(ownedSkins = save.ownedSkins + skin.style)
                    label = "New plane: ${skin.name}"
                } else {
                    val rib = Skins.ribbons.firstOrNull { it.unlock == Unlock.STAR_ROAD && it.id !in save.ownedRibbons }
                    if (rib != null) {
                        save = save.copy(ownedRibbons = save.ownedRibbons + rib.id)
                        label = "New ribbon: ${rib.name}"
                    } else {
                        save = save.copy(coins = save.coins + 1500)
                        label = "+1500 coins"
                    }
                }
            }
            "boosters" -> {
                save = save.copy(boosters = save.boosters.copy(big = save.boosters.big + 1, shield = save.boosters.shield + 1, ink = save.boosters.ink + 1))
                label = "Booster pack (+1 each)"
            }
            else -> {
                val c = r.substringAfter(":").toInt()
                save = save.copy(coins = save.coins + c)
                label = "+$c coins"
            }
        }
        return save to label
    }

    // ------------------------------------------------------------------ daily gift

    val dailyRewards = listOf("coins:100", "coins:150", "boosters", "coins:250", "coins:300", "ribbon", "skin")

    fun dailyAvailable(d: SaveData): Boolean = today() > d.daily.lastClaimDay

    fun dailyIndex(d: SaveData): Int = d.daily.claims % 7

    fun claimDaily(d: SaveData): Pair<SaveData, String> {
        if (!dailyAvailable(d)) return d to ""
        val i = dailyIndex(d)
        var save = d.copy(daily = DailyState(today(), d.daily.claims + 1))
        val label = when (val r = dailyRewards[i]) {
            "boosters" -> {
                save = save.copy(boosters = save.boosters.copy(big = save.boosters.big + 1, shield = save.boosters.shield + 1, ink = save.boosters.ink + 1))
                "Booster pack"
            }
            "ribbon" -> {
                val rib = Skins.ribbons.firstOrNull { it.unlock == Unlock.DAILY_GIFT && it.id !in save.ownedRibbons }
                if (rib != null) {
                    save = save.copy(ownedRibbons = save.ownedRibbons + rib.id)
                    "New ribbon: ${rib.name}"
                } else {
                    save = save.copy(coins = save.coins + 500)
                    "+500 coins"
                }
            }
            "skin" -> {
                val skin = Skins.all.firstOrNull { it.unlock == Unlock.DAILY_GIFT && it.style !in save.ownedSkins }
                if (skin != null) {
                    save = save.copy(ownedSkins = save.ownedSkins + skin.style)
                    "New plane: ${skin.name}"
                } else {
                    save = save.copy(coins = save.coins + 800)
                    "+800 coins"
                }
            }
            else -> {
                val c = r.substringAfter(":").toInt()
                save = save.copy(coins = save.coins + c)
                "+$c coins"
            }
        }
        return save to label
    }

    // ------------------------------------------------------------------ lucky spin

    class SpinPrize(val label: String, val coins: Int, val booster: String?, val weight: Int)

    val spinPrizes = listOf(
        SpinPrize("50", 50, null, 22),
        SpinPrize("100", 100, null, 20),
        SpinPrize("Big Start", 0, "big", 12),
        SpinPrize("150", 150, null, 14),
        SpinPrize("Shield", 0, "shield", 12),
        SpinPrize("250", 250, null, 9),
        SpinPrize("Full Ink", 0, "ink", 8),
        SpinPrize("500", 500, null, 3),
    )

    fun spinAvailable(d: SaveData): Boolean = today() > d.spinDay

    fun rollSpin(): Int {
        val total = spinPrizes.sumOf { it.weight }
        var r = Random.nextInt(total)
        for ((i, p) in spinPrizes.withIndex()) {
            r -= p.weight
            if (r < 0) return i
        }
        return 0
    }

    fun applySpin(d: SaveData, index: Int, free: Boolean): SaveData {
        val p = spinPrizes[index]
        var save = if (free) d.copy(spinDay = today()) else d
        save = save.copy(coins = save.coins + p.coins)
        save = when (p.booster) {
            "big" -> save.copy(boosters = save.boosters.copy(big = save.boosters.big + 1))
            "shield" -> save.copy(boosters = save.boosters.copy(shield = save.boosters.shield + 1))
            "ink" -> save.copy(boosters = save.boosters.copy(ink = save.boosters.ink + 1))
            else -> save
        }
        return save
    }

    // ------------------------------------------------------------------ album pages

    fun pageComplete(d: SaveData, cat: ShapeCategory): Boolean =
        ShapeLibrary.all.filter { it.category == cat }.all { (d.album[it.id] ?: 0) >= 1 }

    fun claimPage(d: SaveData, cat: ShapeCategory): Pair<SaveData, String> {
        if (!pageComplete(d, cat) || cat.name in d.albumPagesClaimed) return d to ""
        var save = d.copy(albumPagesClaimed = d.albumPagesClaimed + cat.name, coins = d.coins + 500)
        val skin = Skins.all.firstOrNull { it.unlock == Unlock.ALBUM && it.style !in save.ownedSkins }
        val label = if (skin != null) {
            save = save.copy(ownedSkins = save.ownedSkins + skin.style)
            "+500 coins and ${skin.name}"
        } else "+500 coins"
        return save to label
    }

    // ------------------------------------------------------------------ shop

    fun buySkin(d: SaveData, style: Int): SaveData? {
        val s = Skins.get(style)
        if (style in d.ownedSkins || s.unlock != Unlock.COINS || d.coins < s.price) return null
        return d.copy(coins = d.coins - s.price, ownedSkins = d.ownedSkins + style, skin = style)
    }

    fun buyRibbon(d: SaveData, id: Int): SaveData? {
        val r = Skins.ribbons.first { it.id == id }
        if (id in d.ownedRibbons || r.unlock != Unlock.COINS || d.coins < r.price) return null
        return d.copy(coins = d.coins - r.price, ownedRibbons = d.ownedRibbons + id, ribbon = id)
    }

    val boosterPrices = mapOf("big" to 150, "shield" to 200, "ink" to 250)

    fun buyBooster(d: SaveData, kind: String): SaveData? {
        val price = boosterPrices[kind] ?: return null
        if (d.coins < price) return null
        val b = d.boosters
        val nb = when (kind) {
            "big" -> b.copy(big = b.big + 1)
            "shield" -> b.copy(shield = b.shield + 1)
            else -> b.copy(ink = b.ink + 1)
        }
        return d.copy(coins = d.coins - price, boosters = nb)
    }

    const val REVIVE_COST = 200
}
