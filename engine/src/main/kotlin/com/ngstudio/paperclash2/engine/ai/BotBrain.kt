package com.ngstudio.paperclash2.engine.ai

import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.world.Pickup
import com.ngstudio.paperclash2.engine.world.PickupType
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.engine.world.World
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Steers one plane: plans loops out of its land, heads home along a BFS path when anyone could
 * reach its ribbon first, and chases ribbons it can reach before their owner gets home.
 *
 * Decisions happen every [BotProfile.think] seconds (reaction time); between decisions it just
 * follows waypoints, so bots react like people instead of snapping onto ribbons.
 */
class BotBrain(
    private val p: Player,
    private val world: World,
    private val rng: Rng,
    var profile: BotProfile,
    private val paths: PathFinder,
) {
    private enum class Mode { IDLE, OUT, RETURN, CHASE, COLLECT, PICKUP }

    private var mode = Mode.IDLE
    private val wpX = FloatArray(8)
    private val wpY = FloatArray(8)
    private var wpCount = 0
    private var wpIndex = 0
    private var wpTime = 0f
    private var thinkTimer = 0f
    private var chaseId = 0
    private var avoidTimer = 0f
    private var avoidAngle = 0f
    private var noisePhase = rng.nextFloat() * 10f

    /** Id of the human player (mercy and hunting rules look at it). */
    var humanId = 1

    val isChasing: Boolean get() = mode == Mode.CHASE

    fun reset() {
        mode = Mode.IDLE
        wpCount = 0
        wpIndex = 0
        thinkTimer = 0f
        chaseId = 0
        avoidTimer = 0f
    }

    fun update(dt: Float) {
        if (!p.alive) return
        thinkTimer -= dt
        if (thinkTimer <= 0f) {
            think()
            thinkTimer = profile.think * (0.7f + 0.6f * rng.nextFloat())
        }
        // Rivals that steer around the player's ribbon only cut it when they are actually hunting.
        p.spares = if ((profile.mercy || profile.avoidRibbon) && mode != Mode.CHASE) humanId else 0
        steer(dt)
    }

    // ------------------------------------------------------------------ steering

    private fun steer(dt: Float) {
        if (avoidTimer > 0f) {
            avoidTimer -= dt
            p.targetAngle = avoidAngle
            return
        }
        if (wpIndex < wpCount) {
            wpTime += dt
            val dx = wpX[wpIndex] - p.x
            val dy = wpY[wpIndex] - p.y
            if (dx * dx + dy * dy < 0.81f || wpTime > 7f) {
                wpIndex++
                wpTime = 0f
                if (wpIndex >= wpCount) {
                    think()
                    thinkTimer = profile.think
                }
            } else {
                noisePhase += dt * 1.7f
                val n = (sin(noisePhase) + 0.6f * sin(noisePhase * 2.3f + 1.1f)) / 1.6f * profile.noise
                p.targetAngle = atan2(dy, dx) + n
            }
        }
        if ((profile.mercy || profile.avoidRibbon) && mode != Mode.CHASE) mercyReflex()
    }

    /** Early-level rivals steer around the player's ribbon instead of snipping it by accident. */
    private fun mercyReflex() {
        // Check where the plane is really heading (it may still be turning) and where it wants to go.
        if (!hitsHumanRibbon(p.angle) && !hitsHumanRibbon(p.targetAngle)) return
        for (k in 1..4) {
            val off = 0.55f * k
            if (!hitsHumanRibbon(p.targetAngle + off) && openAhead(p.targetAngle + off)) {
                avoidAngle = p.targetAngle + off
                avoidTimer = 0.25f
                return
            }
            if (!hitsHumanRibbon(p.targetAngle - off) && openAhead(p.targetAngle - off)) {
                avoidAngle = p.targetAngle - off
                avoidTimer = 0.25f
                return
            }
        }
        avoidAngle = p.angle + MathX.PI
        avoidTimer = 0.3f
    }

    private fun hitsHumanRibbon(a: Float): Boolean {
        val c = cos(a)
        val s = sin(a)
        var d = 0.5f
        while (d <= 3.2f) {
            // three parallel probes so diagonal neighbours count too
            for (side in -1..1) {
                val x = (p.x + c * d - s * side * 0.55f).toInt()
                val y = (p.y + s * d + c * side * 0.55f).toInt()
                if (x >= 0 && y >= 0 && x < world.size && y < world.size && world.trail[world.idx(x, y)] == humanId) return true
            }
            d += 0.6f
        }
        return false
    }

    private fun openAhead(a: Float): Boolean = world.isOpen(p.x + cos(a) * 1.6f, p.y + sin(a) * 1.6f)

    // ------------------------------------------------------------------ decisions

    private fun think() {
        if (!p.alive) return
        val trailLen = p.trail.size
        if (trailLen > 0) {
            when (mode) {
                Mode.CHASE -> {
                    if (!continueChase()) goHome()
                    return
                }
                Mode.COLLECT, Mode.PICKUP -> {
                    if (!continueTargetRun(outside = true)) goHome()
                    return
                }
                else -> {}
            }
            val home = paths.toOwnLand(p)
            if (home < 0) {
                fallbackHome()
                return
            }
            val threat = threatDistance()
            if (threat < home * 0.85f * profile.caution + 2.5f || trailLen > profile.maxTrail) {
                setReturnFromPath()
                return
            }
            if (mode != Mode.OUT) {
                setReturnFromPath()
                return
            }
            if (trailLen < 10 && tryChase()) return
            if (wpIndex >= wpCount) setReturnFromPath()
        } else {
            if (mode == Mode.RETURN || mode == Mode.OUT) {
                mode = Mode.IDLE
                wpCount = 0
                wpIndex = 0
            }
            if (mode == Mode.CHASE) {
                if (continueChase()) return
                mode = Mode.IDLE
            }
            if ((mode == Mode.COLLECT || mode == Mode.PICKUP) && continueTargetRun(outside = false)) return
            if (profile.collector && tryTargetRun(PickupType.STAR)) return
            if (profile.wantsPickups && world.allowRivalPickups && !p.isHuman && tryTargetRun(null)) return
            if (p.isHuman && profile.wantsPickups && tryTargetRun(null)) return
            if (tryChase()) return
            if (mode == Mode.IDLE || wpIndex >= wpCount) planLoop()
        }
    }

    private fun goHome() {
        val home = paths.toOwnLand(p)
        if (home < 0) fallbackHome() else setReturnFromPath()
    }

    private fun setReturnFromPath() {
        mode = Mode.RETURN
        wpCount = 0
        wpIndex = 0
        wpTime = 0f
        if (paths.path.size == 0) {
            fallbackHome()
            return
        }
        val k = paths.farthestVisible(p)
        val c = paths.path[k]
        addWaypoint(c % world.size + 0.5f, c / world.size + 0.5f)
        val last = paths.path[paths.path.size - 1]
        if (k < paths.path.size - 1) addWaypoint(last % world.size + 0.5f, last / world.size + 0.5f)
        // Aim a little past the border so the plane lands properly inside.
        val lx = last % world.size + 0.5f
        val ly = last / world.size + 0.5f
        val dx = lx - p.x
        val dy = ly - p.y
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0.1f) {
            val ix = lx + dx / len * 1.5f
            val iy = ly + dy / len * 1.5f
            if (world.ownerAt(ix.toInt(), iy.toInt()) == p.id) addWaypoint(ix, iy)
        }
    }

    private fun fallbackHome() {
        mode = Mode.RETURN
        wpCount = 0
        wpIndex = 0
        addWaypoint(p.homeX + 0.5f, p.homeY + 0.5f)
    }

    /**
     * Effective distance (cells, scaled by relative speed) from the nearest threat to this plane's
     * ribbon: any other plane, or a scissor hazard.
     */
    private fun threatDistance(): Float {
        var best = Float.MAX_VALUE
        val n = p.trail.size
        val step = if (n > 60) 3 else if (n > 20) 2 else 1
        val mySpeed = max(0.5f, world.speedOf(p))
        for (o in world.players) {
            if (o === p || !o.alive) continue
            // A rival that ignores the player and is told to show mercy is no threat to... nobody;
            // everyone still treats every plane as a threat to stay believable.
            val ratio = max(0.35f, world.speedOf(o) / mySpeed)
            var dmin = Float.MAX_VALUE
            var k = 0
            while (k < n) {
                val c = p.trail[k]
                val dx = c % world.size + 0.5f - o.x
                val dy = c / world.size + 0.5f - o.y
                val d = dx * dx + dy * dy
                if (d < dmin) dmin = d
                k += step
            }
            val hx = p.x - o.x
            val hy = p.y - o.y
            dmin = min(dmin, hx * hx + hy * hy)
            val eff = sqrt(dmin) / ratio
            if (eff < best) best = eff
        }
        for (h in world.hazards) {
            val ratio = max(0.35f, h.speed / mySpeed)
            var dmin = Float.MAX_VALUE
            var k = 0
            while (k < n) {
                val c = p.trail[k]
                val dx = c % world.size + 0.5f - h.x
                val dy = c / world.size + 0.5f - h.y
                dmin = min(dmin, dx * dx + dy * dy)
                k += step
            }
            val eff = sqrt(dmin) / ratio
            if (eff < best) best = eff
        }
        return best
    }

    // ------------------------------------------------------------------ chasing

    private fun tryChase(): Boolean {
        if (profile.chase <= 0f || !rng.chance(profile.chase)) return false
        // The human is hunted less often than other rivals (scaled with difficulty).
        val spareHuman = profile.humanChase < 1f && !rng.chance(profile.humanChase)
        var anyBoss = false
        if (profile.targetBoss) for (o in world.players) if (o.alive && o.isBoss) anyBoss = true
        var bestId = 0
        var bestCell = -1
        var bestD = profile.chaseRange
        for (o in world.players) {
            if (o === p || !o.alive || o.trail.size == 0) continue
            if (o.id == humanId && (!profile.huntsHuman || spareHuman)) continue
            if (profile.targetBoss && anyBoss && !o.isBoss) continue
            if (o.shieldTime > 0f) continue
            var cell = -1
            var dmin = Float.MAX_VALUE
            var k = 0
            while (k < o.trail.size) {
                val c = o.trail[k]
                val dx = c % world.size + 0.5f - p.x
                val dy = c / world.size + 0.5f - p.y
                val d = dx * dx + dy * dy
                if (d < dmin) {
                    dmin = d
                    cell = c
                }
                k += if (o.trail.size > 40) 2 else 1
            }
            val d = sqrt(dmin)
            if (d > bestD || cell < 0) continue
            val theirs = homeEstimate(o) * world.speedOf(p) / max(0.5f, world.speedOf(o))
            if (d < theirs * (0.8f + profile.chase) + 1.5f) {
                bestD = d
                bestId = o.id
                bestCell = cell
            }
        }
        if (bestId == 0) return false
        mode = Mode.CHASE
        chaseId = bestId
        wpCount = 0
        wpIndex = 0
        wpTime = 0f
        addWaypoint(bestCell % world.size + 0.5f, bestCell / world.size + 0.5f)
        return true
    }

    private fun homeEstimate(o: Player): Float {
        val first = o.trail[0]
        val fx = first % world.size + 0.5f - o.x
        val fy = first / world.size + 0.5f - o.y
        val hx = o.homeX + 0.5f - o.x
        val hy = o.homeY + 0.5f - o.y
        return min(sqrt(fx * fx + fy * fy), sqrt(hx * hx + hy * hy))
    }

    private fun continueChase(): Boolean {
        if (chaseId == 0) return false
        val o = world.player(chaseId)
        if (!o.alive || o.trail.size == 0 || o.shieldTime > 0f) return false
        if (p.trail.size > profile.maxTrail * 0.6f) return false
        var cell = -1
        var dmin = Float.MAX_VALUE
        for (k in 0 until o.trail.size) {
            val c = o.trail[k]
            val dx = c % world.size + 0.5f - p.x
            val dy = c / world.size + 0.5f - p.y
            val d = dx * dx + dy * dy
            if (d < dmin) {
                dmin = d
                cell = c
            }
        }
        if (cell < 0 || sqrt(dmin) > profile.chaseRange * 1.6f) return false
        if (p.trail.size > 0 && threatDistance() < 2.5f) return false
        wpCount = 0
        wpIndex = 0
        addWaypoint(cell % world.size + 0.5f, cell / world.size + 0.5f)
        return true
    }

    // ------------------------------------------------------------------ pickups and stars

    private var targetPickup: Pickup? = null

    private fun tryTargetRun(type: PickupType?): Boolean {
        var best: Pickup? = null
        var bestD = if (type == PickupType.STAR) 60f else 16f
        for (pk in world.pickups) {
            if (!pk.alive) continue
            if (type != null && pk.type != type) continue
            if (type == null && pk.type == PickupType.STAR) continue
            val dx = pk.x - p.x
            val dy = pk.y - p.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestD) {
                bestD = d
                best = pk
            }
        }
        if (best == null) return false
        targetPickup = best
        mode = if (type == PickupType.STAR) Mode.COLLECT else Mode.PICKUP
        wpCount = 0
        wpIndex = 0
        wpTime = 0f
        addWaypoint(best.x, best.y)
        return true
    }

    private fun continueTargetRun(outside: Boolean): Boolean {
        val pk = targetPickup ?: return false
        if (!pk.alive) {
            targetPickup = null
            // Grab the next star straight away if it's close and safe.
            if (profile.collector && (!outside || p.trail.size < profile.maxTrail / 2)) {
                if (tryTargetRun(PickupType.STAR)) {
                    if (!outside || threatDistance() > 6f) return true
                }
            }
            return false
        }
        if (outside) {
            if (p.trail.size > profile.maxTrail) return false
            val home = paths.toOwnLand(p)
            if (home < 0 || threatDistance() < home * 0.85f * profile.caution + 2.5f) return false
        }
        wpCount = 0
        wpIndex = 0
        addWaypoint(pk.x, pk.y)
        return true
    }

    // ------------------------------------------------------------------ loops

    private fun planLoop() {
        for (attempt in 0 until 10) {
            val a = chooseDirection(attempt)
            if (tryLoop(a, shrink = attempt >= 6)) return
        }
        mode = Mode.IDLE
        wpCount = 0
        wpIndex = 0
        addWaypoint(world.arena.centerX + rng.range(-6f, 6f), world.arena.centerY + rng.range(-6f, 6f))
    }

    private fun chooseDirection(attempt: Int): Float {
        if (profile.huntBias && attempt < 6 && rng.chance(0.7f)) {
            val prey = preyTarget()
            if (prey != null) return atan2(prey.y - p.y, prey.x - p.x) + rng.range(-0.6f, 0.6f)
        }
        var bestA = rng.nextFloat() * MathX.TAU
        var bestScore = -1f
        val probes = if (attempt < 6) 4 else 1
        for (k in 0 until probes) {
            val a = rng.nextFloat() * MathX.TAU
            val score = openness(a) + rng.nextFloat() * 3f
            if (score > bestScore) {
                bestScore = score
                bestA = a
            }
        }
        return bestA
    }

    private fun preyTarget(): Player? {
        var best: Player? = null
        var bestD = Float.MAX_VALUE
        for (o in world.players) {
            if (o === p || !o.alive) continue
            if (profile.targetBoss && !o.isBoss) continue
            val dx = o.x - p.x
            val dy = o.y - p.y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = o
            }
        }
        return best
    }

    private fun openness(a: Float): Float {
        val c = cos(a)
        val s = sin(a)
        var score = 0f
        var d = 2f
        while (d < profile.loopMax + 8f) {
            val x = (p.x + c * d).toInt()
            val y = (p.y + s * d).toInt()
            if (!world.isOpenCell(x, y)) break
            val o = world.owner[world.idx(x, y)]
            if (profile.mercy && o == humanId) break
            if (o != p.id) score += if (o == 0) 1f else 0.7f
            d += 1.5f
        }
        return score
    }

    private fun tryLoop(a: Float, shrink: Boolean): Boolean {
        val dirX = cos(a)
        val dirY = sin(a)
        var ex = p.x
        var ey = p.y
        var steps = 0
        while (steps < 200 && world.ownerAt(ex.toInt(), ey.toInt()) == p.id) {
            ex += dirX * 0.5f
            ey += dirY * 0.5f
            steps++
        }
        if (!world.isOpen(ex, ey)) return false
        val k = if (shrink) 0.55f else 1f
        var depth = rng.range(profile.loopMin, profile.loopMax) * k
        if (rng.chance(profile.overreach)) depth *= 1.6f
        val width = depth * profile.widthMul * rng.range(0.65f, 1.3f)
        val side = rng.sign()
        val px = -dirY * side
        val py = dirX * side
        wpCount = 0
        wpIndex = 0
        wpTime = 0f
        if (rng.chance(0.3f)) {
            val p1x = ex + dirX * depth + px * width * 0.5f
            val p1y = ey + dirY * depth + py * width * 0.5f
            val p2x = ex + px * width - dirX * 2.5f
            val p2y = ey + py * width - dirY * 2.5f
            if (!segmentOk(ex, ey, p1x, p1y) || !segmentOk(p1x, p1y, p2x, p2y)) return false
            addWaypoint(ex, ey)
            addWaypoint(p1x, p1y)
            addWaypoint(p2x, p2y)
        } else {
            val p1x = ex + dirX * depth
            val p1y = ey + dirY * depth
            val p2x = p1x + px * width
            val p2y = p1y + py * width
            val p3x = ex + px * width - dirX * 2.5f
            val p3y = ey + py * width - dirY * 2.5f
            if (!segmentOk(ex, ey, p1x, p1y) || !segmentOk(p1x, p1y, p2x, p2y) || !segmentOk(p2x, p2y, p3x, p3y)) return false
            addWaypoint(ex, ey)
            addWaypoint(p1x, p1y)
            addWaypoint(p2x, p2y)
            addWaypoint(p3x, p3y)
        }
        mode = Mode.OUT
        return true
    }

    private fun segmentOk(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        val steps = max(1, (len / 0.8f).toInt())
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val x = ax + dx * t
            val y = ay + dy * t
            if (!world.isOpen(x, y)) return false
            val i = world.idx(x.toInt(), y.toInt())
            if (world.arena.dist[i] < 1.2f) return false
            // Polite early rivals keep out of the player's ribbon and land.
            if (profile.mercy && (world.trail[i] == humanId || world.owner[i] == humanId)) return false
            if (profile.avoidRibbon && world.trail[i] == humanId) return false
        }
        // Keep loops off every scissor's patrol path.
        for (h in world.hazards) {
            for (s in 0..steps step 2) {
                val t = s / steps.toFloat()
                if (nearPath(h.path, ax + dx * t, ay + dy * t, 2.6f)) return false
            }
        }
        return true
    }

    private fun nearPath(path: FloatArray, x: Float, y: Float, r: Float): Boolean {
        val n = path.size / 2
        for (k in 0 until n) {
            val ax = path[k * 2]
            val ay = path[k * 2 + 1]
            val bx = path[((k + 1) % n) * 2]
            val by = path[((k + 1) % n) * 2 + 1]
            val ex = bx - ax
            val ey = by - ay
            val t = (((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey + 1e-6f)).coerceIn(0f, 1f)
            val qx = ax + ex * t - x
            val qy = ay + ey * t - y
            if (qx * qx + qy * qy < r * r) return true
        }
        return false
    }

    private fun addWaypoint(x: Float, y: Float) {
        if (wpCount >= wpX.size) return
        var wx = x.coerceIn(1.5f, world.size - 1.5f)
        var wy = y.coerceIn(1.5f, world.size - 1.5f)
        if (!world.isOpen(wx, wy)) {
            // nudge onto the nearest open cell around it
            var found = false
            var r = 1
            while (!found && r < 8) {
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        if (!found && world.isOpen(wx + dx, wy + dy)) {
                            wx += dx
                            wy += dy
                            found = true
                        }
                    }
                }
                r++
            }
        }
        wpX[wpCount] = wx
        wpY[wpCount] = wy
        wpCount++
    }
}
