package com.ngstudio.paperclash2.engine.world

enum class PickupType { SPEED, SHIELD, BOMB, FREEZE, STAR }

/** A power-up badge or a Coin Rush star lying on the board. */
class Pickup(val type: PickupType, val x: Float, val y: Float) {
    var alive = true
    var age = 0f
    /** Seconds since it was taken (renderer plays a pop), -1 while still lying there. */
    var takenAge = -1f
    var takenBy = 0
}

/**
 * Scissor Spinner: patrols a closed loop of waypoints and snips any ribbon it touches.
 * [path] holds x, y pairs in cell units.
 */
class Hazard(val path: FloatArray, val speed: Float) {
    var x = path[0]
    var y = path[1]
    var segment = 0
    var segmentT = 0f
    var spin = 0f
    var heading = 0f

    val points: Int get() = path.size / 2
}

/** Things that happened during a tick; drained by the session (and turned into sounds and effects). */
sealed class WorldEvent {
    class Captured(val player: Int, val gained: Int, val serial: Int, val x: Float, val y: Float, val stolen: Int) : WorldEvent()
    class Snipped(val victim: Int, val killer: Int, val cause: Cause, val x: Float, val y: Float, val gained: Int) : WorldEvent()
    class Picked(val player: Int, val type: PickupType, val x: Float, val y: Float) : WorldEvent()
    class LeftLand(val player: Int) : WorldEvent()
    class Spawned(val player: Int) : WorldEvent()
}
