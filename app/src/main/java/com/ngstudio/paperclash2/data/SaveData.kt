package com.ngstudio.paperclash2.data

import kotlinx.serialization.Serializable

/** Everything the game remembers. Serialized as JSON (see [SaveRepository]). */
@Serializable
data class SaveData(
    val version: Int = 1,
    val onboarded: Boolean = false,
    val nickname: String = "",
    val coins: Int = 200,
    /** One char per level: '0'..'3' stars (missing = 0). */
    val stars: String = "",
    /** Highest level opened by normal progression. */
    val maxUnlocked: Int = 1,
    /** Levels opened out of order (debug "watch ad" unlocks and the level after them). */
    val extraUnlocked: List<Int> = emptyList(),
    val skin: Int = 0,
    val ribbon: Int = 0,
    val ownedSkins: List<Int> = listOf(0, 9),
    val ownedRibbons: List<Int> = listOf(0),
    val boosters: Boosters = Boosters(),
    val settings: Settings = Settings(),
    val daily: DailyState = DailyState(),
    val quests: QuestState = QuestState(),
    /** Achievement id -> tiers already claimed. */
    val achievementTiers: Map<String, Int> = emptyMap(),
    /** Shape id -> sticker tier (1 paper, 2 silver, 3 gold). */
    val album: Map<String, Int> = emptyMap(),
    /** Shape id -> number of 3-star clears (gold at 5). */
    val shapeThreeStars: Map<String, Int> = emptyMap(),
    val albumPagesClaimed: List<String> = emptyList(),
    val starRoadClaimed: Int = 0,
    val spinDay: Long = -1,
    val stats: Stats = Stats(),
)

@Serializable
data class Boosters(val big: Int = 1, val shield: Int = 1, val ink: Int = 1)

@Serializable
data class Settings(
    val sound: Boolean = true,
    val music: Boolean = true,
    val vibration: Boolean = true,
    /** 0 battery, 1 balanced, 2 high, 3 auto. */
    val quality: Int = 3,
    /** 0 floating stick, 1 fixed stick. */
    val controls: Int = 0,
    val sensitivity: Float = 1f,
    val leftHanded: Boolean = false,
    val reduceMotion: Boolean = false,
    val colorAssist: Boolean = false,
    val showFps: Boolean = false,
)

@Serializable
data class DailyState(val lastClaimDay: Long = -1, val claims: Int = 0)

@Serializable
data class QuestState(val day: Long = -1, val slots: List<QuestSlot> = emptyList(), val rerolled: Boolean = false)

@Serializable
data class QuestSlot(val type: String, val target: Int, val reward: Int, val progress: Int = 0, val claimed: Boolean = false)

@Serializable
data class Stats(
    val levelsWon: Int = 0,
    val levelsPlayed: Int = 0,
    val snips: Int = 0,
    val captures: Int = 0,
    val pops: Int = 0,
    val bestCombo: Int = 0,
    val bossesBeaten: Int = 0,
    val powerUps: Int = 0,
    val playSeconds: Long = 0,
    val bestPercent: Float = 0f,
    val threeStarLevels: Int = 0,
)
