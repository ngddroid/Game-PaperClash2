package com.ngstudio.paperclash2.engine.game

import com.ngstudio.paperclash2.engine.ai.BotBrain
import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.ai.PathFinder
import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.level.LevelSpec
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.shape.Arena
import com.ngstudio.paperclash2.engine.world.Cause
import com.ngstudio.paperclash2.engine.world.Hazard
import com.ngstudio.paperclash2.engine.world.PickupType
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.engine.world.World
import com.ngstudio.paperclash2.engine.world.WorldEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Optional pre-level boosts the player can bring. */
data class Boosters(val bigStart: Boolean = false, val shieldStart: Boolean = false, val fullInk: Boolean = false)

enum class EndReason { GOAL, TIME, POPPED, FINISHED }

class SessionResult(
    val won: Boolean,
    val stars: Int,
    val percent: Float,
    val bestPercent: Float,
    val snips: Int,
    val time: Float,
    val reason: EndReason,
    val killerName: String?,
    val starsCollected: Int,
    val bossPopped: Boolean,
    val rank: Int,
    val maxCombo: Int,
    val captures: Int,
    /** How the player popped (null if they didn't), and whether the popper was hunting them. */
    val cause: Cause?,
    val hunted: Boolean,
)

/** What the HUD, sound and effects need to know about; drained by the app every frame. */
sealed class SessionEvent {
    class Capture(val player: Int, val gained: Int, val serial: Int, val x: Float, val y: Float, val combo: Int, val human: Boolean) : SessionEvent()
    class Snip(val victim: Int, val killer: Int, val cause: Cause, val x: Float, val y: Float, val human: Boolean) : SessionEvent()
    class HumanDown(val killerName: String?, val cause: Cause, val canRevive: Boolean) : SessionEvent()
    object Revived : SessionEvent()
    class StarEarned(val stars: Int) : SessionEvent()
    object GoalReached : SessionEvent()
    class Picked(val player: Int, val type: PickupType, val x: Float, val y: Float, val human: Boolean) : SessionEvent()
    class PickupSpawned(val type: PickupType, val x: Float, val y: Float) : SessionEvent()
    class TimeWarning(val seconds: Int) : SessionEvent()
    object InkReady : SessionEvent()
    class InkBurst(val x: Float, val y: Float) : SessionEvent()
    object LeftLand : SessionEvent()
    object BossDown : SessionEvent()
    class Respawn(val player: Int) : SessionEvent()
    class Ended(val result: SessionResult) : SessionEvent()
}

/**
 * One attempt at a level: the board, the rivals' brains, the clock, goals and stars, power-up
 * drops, respawns, combos and the Ink Burst meter. The app and the balance simulator both drive
 * it with fixed ticks of [STEP] seconds.
 */
class GameSession(
    val spec: LevelSpec,
    val arena: Arena,
    humanName: String,
    humanStyle: Int,
    val boosters: Boosters,
    seed: Long,
) {
    enum class State { READY, PLAYING, DOWN, ENDED }

    private val rng = Rng(seed)
    val world = World(arena, rng.fork(11))
    private val paths = PathFinder(world)
    val human: Player
    private val brains = arrayOfNulls<BotBrain>(World.MAX_PLAYERS + 1)
    private var autopilot: BotBrain? = null

    var state = State.READY
        private set
    var time = 0f
        private set
    val timeLeft: Float get() = max(0f, spec.timeLimit - time)

    /** Stars earned so far (never goes down). */
    var stars = 0
        private set
    var bestPercent = 0f
        private set
    var humanSnips = 0
        private set
    var bossPopped = false
        private set
    var ink = 0f
        private set
    var combo = 0
        private set
    var maxCombo = 0
        private set
    var captures = 0
        private set
    var revivesUsed = 0
        private set
    var result: SessionResult? = null
        private set
    var killerName: String? = null
        private set
    private var humanCause: Cause? = null
    private var hunted = false

    /** Steering request from the input (radians), NaN when the finger is up. */
    @Volatile
    var steerAngle = Float.NaN

    val events = ArrayList<SessionEvent>()

    private var lastCapture = -10f
    private var pickupTimer = spec.firstPowerUpAt
    private var warned10 = false
    private val inkCapacity = max(60f, arena.area * 0.05f)

    init {
        world.allowRivalPickups = spec.rivalsUsePowerUps
        world.bombRadius = 4f
        val radius = spec.startRadius + (if (boosters.bigStart) 2 else 0)
        human = Player(1, humanName, humanStyle, true)
        world.addPlayer(human)
        var styleSwap = humanStyle
        for ((i, r) in spec.rivals.withIndex()) {
            var style = r.style
            if (style == humanStyle) {
                styleSwap = (styleSwap + 7) % 45 + 1
                style = styleSwap
            }
            val p = Player(i + 2, r.name, style, false)
            p.speedMul = r.profile.speedMul
            p.turnRate = r.profile.turnRate
            if (r.boss) {
                p.isBoss = true
                p.scale = 1.45f
            }
            world.addPlayer(p)
            val brain = BotBrain(p, world, rng.fork(100L + i), r.profile, paths)
            brain.humanId = human.id
            brains[p.id] = brain
        }
        val spawns = arena.spawns
        world.spawnAt(human, spawns[0], radius)
        for (k in 1 until world.players.size) {
            val p = world.players[k]
            val cell = if (k < spawns.size) spawns[k] else world.findSpawnCell(spec.startRadius, human.x, human.y, 14f)
            world.spawnAt(p, cell, if (p.isBoss) spec.startRadius + 1 else spec.startRadius)
        }
        if (boosters.shieldStart) human.shieldTime = 6f
        if (boosters.fullInk && spec.inkEnabled) ink = 1f
        if (spec.starTokens > 0) placeStars(spec.starTokens)
        repeat(spec.hazards) { makeHazard()?.let { world.hazards.add(it) } }
        world.events.clear()
        world.markAllDirty()
    }

    /** Puts a [BotBrain] in the player's seat (balance simulation and the Home showcase). */
    fun enableAutopilot(profile: BotProfile) {
        val b = BotBrain(human, world, rng.fork(999), profile, paths)
        b.humanId = -1
        autopilot = b
    }

    fun begin() {
        if (state == State.READY) state = State.PLAYING
    }

    fun step(dt: Float) {
        if (state != State.PLAYING) return
        time += dt
        val a = steerAngle
        human.steered = !a.isNaN()
        if (human.steered) human.targetAngle = a
        autopilot?.update(dt)
        for (id in 2..world.players.size) brains[id]?.update(dt)
        world.tick(dt)
        drainWorld()
        if (state != State.PLAYING) return
        updateRespawns(dt)
        updatePickups(dt)
        updateGoal()
        if (state != State.PLAYING) return
        val left = spec.timeLimit - time
        if (left <= 10f && !warned10) {
            warned10 = true
            events.add(SessionEvent.TimeWarning(10))
        }
        if (left <= 0f) onTimeUp()
    }

    // ------------------------------------------------------------------ world events

    private fun drainWorld() {
        val list = world.events
        var i = 0
        while (i < list.size) {
            when (val e = list[i]) {
                is WorldEvent.Captured -> onCaptured(e)
                is WorldEvent.Snipped -> onSnipped(e)
                is WorldEvent.Picked -> events.add(SessionEvent.Picked(e.player, e.type, e.x, e.y, e.player == human.id))
                is WorldEvent.LeftLand -> if (e.player == human.id) events.add(SessionEvent.LeftLand)
                is WorldEvent.Spawned -> {}
            }
            i++
        }
        list.clear()
    }

    private fun onCaptured(e: WorldEvent.Captured) {
        val mine = e.player == human.id
        if (mine && e.gained > 0) {
            combo = if (time - lastCapture < COMBO_WINDOW) combo + 1 else 1
            maxCombo = max(maxCombo, combo)
            lastCapture = time
            captures++
            if (spec.inkEnabled && ink < 1f) {
                ink = min(1f, ink + e.gained / inkCapacity)
                if (ink >= 1f) events.add(SessionEvent.InkReady)
            }
        }
        events.add(SessionEvent.Capture(e.player, e.gained, e.serial, e.x, e.y, if (mine) combo else 0, mine))
    }

    private fun onSnipped(e: WorldEvent.Snipped) {
        val victim = world.player(e.victim)
        val humanInvolved = e.victim == human.id || e.killer == human.id
        events.add(SessionEvent.Snip(e.victim, e.killer, e.cause, e.x, e.y, humanInvolved))
        if (victim.isHuman) {
            humanCause = e.cause
            hunted = e.killer > 1 && brains[e.killer]?.isChasing == true
            killerName = when {
                e.killer > 0 -> world.player(e.killer).name
                e.cause == Cause.HAZARD -> "a Scissor Spinner"
                else -> null
            }
            if (spec.goal.bankable && stars >= 1) {
                end(true, EndReason.POPPED)
            } else {
                state = State.DOWN
                events.add(SessionEvent.HumanDown(killerName, e.cause, revivesUsed == 0))
            }
            return
        }
        if (e.killer == human.id) {
            humanSnips++
            if (victim.isBoss) {
                bossPopped = true
                events.add(SessionEvent.BossDown)
                return
            }
        }
        // Rivals come back; a boss popped by someone else returns too, so the goal stays possible.
        victim.respawnTimer = if (victim.isBoss) RESPAWN_TIME * 0.75f else RESPAWN_TIME
    }

    private fun updateRespawns(dt: Float) {
        for (k in 1 until world.players.size) {
            val p = world.players[k]
            if (p.alive || p.respawnTimer <= 0f) continue
            if (p.isBoss && bossPopped) continue
            p.respawnTimer -= dt
            if (p.respawnTimer > 0f || timeLeft < 6f) continue
            p.respawnTimer = 0f
            val r = if (p.isBoss) spec.startRadius + 1 else 3
            val cell = world.findSpawnCell(r, human.x, human.y, 16f)
            if (cell < 0) continue
            world.spawnAt(p, cell, r)
            brains[p.id]?.reset()
            world.events.clear()
            events.add(SessionEvent.Respawn(p.id))
        }
    }

    // ------------------------------------------------------------------ pickups and placement

    private fun updatePickups(dt: Float) {
        if (spec.powerUps.isEmpty()) return
        pickupTimer -= dt
        if (pickupTimer > 0f) return
        pickupTimer = spec.powerUpEvery * rng.range(0.8f, 1.2f)
        if (world.activePickups() >= spec.maxPowerUps) return
        val type = spec.powerUps[rng.nextInt(spec.powerUps.size)]
        val roomy = arena.roomy
        if (roomy.isEmpty()) return
        var best = -1
        var bestScore = -Float.MAX_VALUE
        for (t in 0 until 40) {
            val c = roomy[rng.nextInt(roomy.size)]
            if (world.trail[c] != 0) continue
            val x = c % arena.size + 0.5f
            val y = c / arena.size + 0.5f
            val dh = sqrt(MathX.dist2(x, y, human.x, human.y))
            var near = 99f
            for (pk in world.pickups) if (pk.alive) near = min(near, sqrt(MathX.dist2(x, y, pk.x, pk.y)))
            if (near < 6f) continue
            var score = -kotlin.math.abs(dh - 14f) + rng.nextFloat() * 4f
            if (world.owner[c] == human.id) score -= 6f
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        if (best < 0) return
        val x = best % arena.size + 0.5f
        val y = best / arena.size + 0.5f
        world.addPickup(type, x, y)
        events.add(SessionEvent.PickupSpawned(type, x, y))
    }

    private fun placeStars(count: Int) {
        val size = arena.size
        val cand = ArrayList<Int>()
        for (i in arena.mask.indices) if (arena.mask[i] && arena.dist[i] >= 2f && world.owner[i] == 0) cand.add(i)
        if (cand.isEmpty()) return
        val chosen = ArrayList<Int>()
        val minD = FloatArray(cand.size) { Float.MAX_VALUE }
        // Seed distances with every plane's start so stars sit out in the open.
        for (p in world.players) {
            for (k in cand.indices) {
                val c = cand[k]
                val d = MathX.dist2(c % size + 0.5f, c / size + 0.5f, p.x, p.y)
                if (d < minD[k]) minD[k] = d
            }
        }
        repeat(count) {
            var best = -1
            var bestD = -1f
            for (k in cand.indices) {
                val s = minD[k] * (0.85f + 0.3f * Rng.hash01(cand[k], it))
                if (s > bestD) {
                    bestD = s
                    best = k
                }
            }
            if (best < 0) return
            val c = cand[best]
            chosen += c
            val cx = c % size + 0.5f
            val cy = c / size + 0.5f
            world.addPickup(PickupType.STAR, cx, cy)
            for (k in cand.indices) {
                val o = cand[k]
                val d = MathX.dist2(o % size + 0.5f, o / size + 0.5f, cx, cy)
                if (d < minD[k]) minD[k] = d
            }
        }
    }

    private fun makeHazard(): Hazard? {
        val roomy = arena.roomy
        if (roomy.isEmpty()) return null
        val size = arena.size
        for (attempt in 0 until 80) {
            val c = roomy[rng.nextInt(roomy.size)]
            if (arena.dist[c] < 7f) continue
            val cx = c % size + 0.5f
            val cy = c / size + 0.5f
            if (MathX.dist2(cx, cy, human.x, human.y) < 16f * 16f) continue
            val r = rng.range(5f, 10f)
            val n = 4 + rng.nextInt(3)
            val pts = FloatArray(n * 2)
            var ok = true
            val phase = rng.nextFloat() * 6.28f
            for (k in 0 until n) {
                val a = phase + k * 2.0 * PI / n
                val rr = r * rng.range(0.75f, 1.25f)
                val x = cx + (cos(a) * rr).toFloat()
                val y = cy + (sin(a) * rr).toFloat()
                if (!world.isOpen(x, y) || arena.dist[world.idx(x.toInt(), y.toInt())] < 2.5f) {
                    ok = false
                    break
                }
                pts[k * 2] = x
                pts[k * 2 + 1] = y
            }
            if (!ok) continue
            for (k in 0 until n) {
                val ax = pts[k * 2]
                val ay = pts[k * 2 + 1]
                val bx = pts[((k + 1) % n) * 2]
                val by = pts[((k + 1) % n) * 2 + 1]
                val len = sqrt(MathX.dist2(ax, ay, bx, by))
                val steps = max(1, (len / 0.7f).toInt())
                for (s in 0..steps) {
                    val t = s / steps.toFloat()
                    val x = ax + (bx - ax) * t
                    val y = ay + (by - ay) * t
                    if (!world.isOpen(x, y) || arena.dist[world.idx(x.toInt(), y.toInt())] < 1.5f) ok = false
                }
            }
            if (!ok) continue
            return Hazard(pts, spec.hazardSpeed)
        }
        return null
    }

    // ------------------------------------------------------------------ goals

    fun percent(): Float = world.percent(human.id)

    fun rankOf(p: Player): Int {
        var rank = 1
        val mine = world.counts[p.id]
        for (o in world.players) if (o !== p && (o.alive || world.counts[o.id] > 0) && world.counts[o.id] > mine) rank++
        return rank
    }

    /** Stars the player would get if the level ended right now (for the live goal bar). */
    fun previewStars(): Int {
        val g = spec.goal
        val pct = percent()
        return when (g.mode) {
            Mode.CLAIM, Mode.COIN_RUSH, Mode.BOSS, Mode.KNOCKOUT -> stars
            Mode.DOMINATE -> if (rankOf(human) == 1) 1 + (if (pct >= g.t2) 1 else 0) + (if (pct >= g.t3) 1 else 0) else 0
            Mode.SURVIVE -> if (pct >= g.t1) 1 + (if (pct >= g.t2) 1 else 0) + (if (pct >= g.t3) 1 else 0) else 0
        }
    }

    private fun updateGoal() {
        val g = spec.goal
        val pct = percent()
        if (pct > bestPercent) bestPercent = pct
        when (g.mode) {
            Mode.CLAIM -> {
                val s = (if (bestPercent >= g.t1) 1 else 0) + (if (bestPercent >= g.t2) 1 else 0) + (if (bestPercent >= g.t3) 1 else 0)
                award(s)
                if (stars >= 3) end(true, EndReason.GOAL)
            }
            Mode.COIN_RUSH -> {
                val n = human.stars.toFloat()
                val s = (if (n >= g.t1) 1 else 0) + (if (n >= g.t2) 1 else 0) + (if (n >= g.t3) 1 else 0)
                award(s)
                if (stars >= 3 || world.pickups.none { it.alive && it.type == PickupType.STAR }) {
                    if (stars >= 1) end(true, EndReason.GOAL)
                }
            }
            Mode.KNOCKOUT -> if (humanSnips >= g.count) {
                val frac = timeLeft / spec.timeLimit
                award(1 + (if (frac >= g.t2) 1 else 0) + (if (frac >= g.t3) 1 else 0))
                end(true, EndReason.GOAL)
            }
            Mode.BOSS -> if (bossPopped) {
                award(1 + (if (bestPercent >= g.t2) 1 else 0) + (if (bestPercent >= g.t3) 1 else 0))
                if (stars >= 3) end(true, EndReason.GOAL)
            }
            Mode.DOMINATE, Mode.SURVIVE -> {}
        }
    }

    private fun award(s: Int) {
        if (s <= stars) return
        val first = stars == 0
        stars = s
        events.add(SessionEvent.StarEarned(s))
        if (first && spec.goal.bankable) events.add(SessionEvent.GoalReached)
    }

    private fun onTimeUp() {
        val g = spec.goal
        val pct = percent()
        when (g.mode) {
            Mode.CLAIM, Mode.COIN_RUSH, Mode.BOSS -> end(stars >= 1, EndReason.TIME)
            Mode.KNOCKOUT -> end(false, EndReason.TIME)
            Mode.DOMINATE -> if (rankOf(human) == 1) {
                stars = 1 + (if (pct >= g.t2) 1 else 0) + (if (pct >= g.t3) 1 else 0)
                end(true, EndReason.TIME)
            } else end(false, EndReason.TIME)
            Mode.SURVIVE -> if (pct >= g.t1) {
                stars = 1 + (if (pct >= g.t2) 1 else 0) + (if (pct >= g.t3) 1 else 0)
                end(true, EndReason.TIME)
            } else end(false, EndReason.TIME)
        }
    }

    // ------------------------------------------------------------------ player actions

    /** Banks the win once the first star is earned (FINISH button). */
    fun finish(): Boolean {
        if (state != State.PLAYING || stars < 1 || !spec.goal.bankable) return false
        end(true, EndReason.FINISHED)
        return true
    }

    val canFinish: Boolean get() = state == State.PLAYING && stars >= 1 && spec.goal.bankable

    fun useInk(): Boolean {
        if (!spec.inkEnabled || ink < 1f || state != State.PLAYING || !human.alive) return false
        ink = 0f
        world.claimDisc(human, human.x, human.y, INK_RADIUS)
        human.shieldTime = max(human.shieldTime, 2f)
        events.add(SessionEvent.InkBurst(human.x, human.y))
        drainWorld()
        updateGoal()
        return true
    }

    fun revive(): Boolean {
        if (state != State.DOWN) return false
        revivesUsed++
        world.revive(human, human.killer)
        drainWorld()
        state = State.PLAYING
        events.add(SessionEvent.Revived)
        return true
    }

    fun giveUp() {
        if (state == State.DOWN) end(false, EndReason.POPPED)
    }

    /** Ends a level from outside (e.g. the player quits): counts as a loss. */
    fun abort() {
        if (state != State.ENDED) end(false, EndReason.POPPED)
    }

    private fun end(won: Boolean, reason: EndReason) {
        if (state == State.ENDED) return
        state = State.ENDED
        val r = SessionResult(
            won = won,
            stars = if (won) max(1, stars) else 0,
            percent = percent(),
            bestPercent = bestPercent,
            snips = humanSnips,
            time = time,
            reason = reason,
            killerName = killerName,
            starsCollected = human.stars,
            bossPopped = bossPopped,
            rank = rankOf(human),
            maxCombo = maxCombo,
            captures = captures,
            cause = humanCause,
            hunted = hunted,
        )
        result = r
        events.add(SessionEvent.Ended(r))
    }

    companion object {
        const val STEP = 1f / 60f
        const val RESPAWN_TIME = 4f
        const val COMBO_WINDOW = 2.5f
        const val INK_RADIUS = 3.6f
    }
}
