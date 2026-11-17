package com.ngstudio.paperclash2.game

import android.graphics.Bitmap
import com.ngstudio.paperclash2.engine.game.SessionResult
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.world.Cause
import com.ngstudio.paperclash2.engine.world.PickupType

enum class Phase { NONE, INTRO, COUNTDOWN, PLAYING, DOWN, ENDED }

data class HudPlayer(val id: Int, val name: String, val color: Int, val percent: Float, val human: Boolean, val boss: Boolean)

/** An off-screen rival marker: [x], [y] on the screen edge (0..1), [angle] radians toward it. */
data class EdgeArrow(val x: Float, val y: Float, val angle: Float, val color: Int, val danger: Boolean, val boss: Boolean)

/** Snapshot of the running level for the Compose HUD (published ~12 times a second). */
data class HudState(
    val level: Int = 0,
    val phase: Phase = Phase.NONE,
    val countdown: Int = 0,
    val mode: Mode = Mode.CLAIM,
    val goal: String = "",
    val t1: Float = 0f,
    val t2: Float = 0f,
    val t3: Float = 0f,
    val goalCount: Int = 0,
    val timeLeft: Float = 0f,
    val timeLimit: Float = 1f,
    val percent: Float = 0f,
    val bestPercent: Float = 0f,
    val stars: Int = 0,
    val previewStars: Int = 0,
    val snips: Int = 0,
    val starsCollected: Int = 0,
    val starTokens: Int = 0,
    val bossPopped: Boolean = false,
    val rank: Int = 1,
    val ink: Float = 0f,
    val inkEnabled: Boolean = false,
    val canFinish: Boolean = false,
    val leaders: List<HudPlayer> = emptyList(),
    val arrows: List<EdgeArrow> = emptyList(),
    val danger: Float = 0f,
    val speedTime: Float = 0f,
    val shieldTime: Float = 0f,
    val frozen: Boolean = false,
    val hints: Int = 0,
    val outside: Boolean = false,
    val captures: Int = 0,
    val myColor: Int = 0,
    val fps: Int = 0,
)

/** One-off things the HUD reacts to (popups, banners, dialogs). */
sealed class GameEvent {
    class Popup(val text: String, val x: Float, val y: Float, val color: Int, val big: Boolean) : GameEvent()
    class StarEarned(val stars: Int) : GameEvent()
    object GoalReached : GameEvent()
    class HumanDown(val killer: String?, val cause: Cause, val canRevive: Boolean) : GameEvent()
    object Revived : GameEvent()
    class Picked(val type: PickupType) : GameEvent()
    object InkReady : GameEvent()
    object BossDown : GameEvent()
    object Go : GameEvent()
    class Ended(val level: Int, val result: SessionResult, val shapeId: String, val shareMap: Bitmap?) : GameEvent()
}

/** Minimap image plus where the player is on it (0..1). */
class MinimapFrame(val bitmap: Bitmap, val version: Int, val px: Float, val py: Float, val alive: Boolean)
