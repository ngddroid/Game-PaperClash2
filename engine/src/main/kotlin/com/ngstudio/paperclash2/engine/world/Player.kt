package com.ngstudio.paperclash2.engine.world

import com.ngstudio.paperclash2.engine.core.FloatList
import com.ngstudio.paperclash2.engine.core.IntList

/** Why a plane popped. */
enum class Cause { SNIPPED, SURROUNDED, HAZARD }

/**
 * One plane on the board. Positions are in cell units (1 unit = 1 grid cell, y up). [style] is an
 * opaque skin index the app maps to a look; the engine never interprets it.
 */
class Player(val id: Int, var name: String, var style: Int, val isHuman: Boolean) {
    var x = 0f
    var y = 0f
    var prevX = 0f
    var prevY = 0f
    var angle = 0f
    var targetAngle = 0f

    var cellX = 0
    var cellY = 0
    var homeX = 0
    var homeY = 0

    /** Personal speed factor (rivals in early levels fly slower than you). */
    var speedMul = 1f
    var turnRate = World.TURN_RATE

    /** 0..1: builds while gliding along the shape's edge. */
    var glide = 0f

    /** True while someone actively steers this plane (the player's finger is down). */
    var steered = false

    /** Which way (+1 / -1 around the wall normal) the plane last slid along an edge. */
    var slideSide = 1

    /** Grid cells crossed outside own land (the ribbon). */
    val trail = IntList(256)

    /** The path actually flown while outside (x, y pairs), for drawing a smooth ribbon. */
    val trailPoints = FloatList(512)

    /** Bumped every time the ribbon is wiped (renderer cache key). */
    var trailGen = 0

    /** Land held when popped, so a revive can hand back what nobody took. */
    val lostCells = IntList(512)

    var alive = false
    var snips = 0
    var deathCause = Cause.SNIPPED
    /** Id of whoever popped this plane, 0 for hazards or nobody. */
    var killer = 0
    var deathX = 0f
    var deathY = 0f

    var speedTime = 0f
    var shieldTime = 0f
    var frozenTime = 0f

    /** Coin Rush stars collected. */
    var stars = 0
    var respawnTimer = 0f
    var isBoss = false

    /**
     * Id of a plane whose ribbon this plane never snips (early-level "mercy" rivals spare the
     * player unless they are actively chasing). 0 = spares nobody.
     */
    var spares = 0

    /** Rendering size factor (the boss is bigger). */
    var scale = 1f

    val hasTrail: Boolean get() = trail.size > 0
}
