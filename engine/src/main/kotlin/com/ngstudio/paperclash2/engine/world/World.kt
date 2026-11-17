package com.ngstudio.paperclash2.engine.world

import com.ngstudio.paperclash2.engine.core.IntList
import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.core.Rng
import com.ngstudio.paperclash2.engine.shape.Arena
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All board rules: flying, ribbons, capturing enclosed land, snipping, spawning and pickups.
 *
 * The board is the arena grid (y up). [owner] holds the land owner of each cell (0 = nobody) and
 * [trail] the id of the plane whose ribbon crosses it. Everything is preallocated; ticks never
 * allocate except for the occasional event object.
 */
class World(val arena: Arena, private val rng: Rng) {
    val size = arena.size
    val playable = arena.mask
    val area = arena.area
    val owner = IntArray(size * size)
    val trail = IntArray(size * size)

    /** Serial of the capture that last changed each cell (drives the renderer's capture wave). */
    val stamp = IntArray(size * size)
    var serial = 0
        private set

    val players = ArrayList<Player>()
    val counts = IntArray(MAX_PLAYERS + 1)
    val pickups = ArrayList<Pickup>()
    val hazards = ArrayList<Hazard>()
    val events = ArrayList<WorldEvent>()

    var time = 0f
        private set

    /** Rivals may take power-ups (later levels). Stars are always for the player only. */
    var allowRivalPickups = false
    var bombRadius = 4f

    /** Rectangle of cells whose owner changed since the renderer last looked (empty when x0 > x1). */
    var dirtyX0 = 0
        private set
    var dirtyY0 = 0
        private set
    var dirtyX1 = size - 1
        private set
    var dirtyY1 = size - 1
        private set

    private val queue = IntArray(size * size)
    private val mark = IntArray(size * size)
    private var markGen = 0
    private var queueTail = 0
    private val changed = IntList(1024)
    private var stolen = 0

    fun idx(x: Int, y: Int) = y * size + x
    fun isOpenCell(x: Int, y: Int) = x >= 0 && y >= 0 && x < size && y < size && playable[y * size + x]
    fun isOpen(x: Float, y: Float) = x >= 0f && y >= 0f && isOpenCell(x.toInt(), y.toInt())
    fun ownerAt(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= size || y >= size) -1 else owner[y * size + x]
    fun percent(id: Int): Float = counts[id] * 100f / area
    fun player(id: Int): Player = players[id - 1]

    fun addPlayer(p: Player) {
        check(p.id == players.size + 1 && p.id <= MAX_PLAYERS) { "player ids must be 1..$MAX_PLAYERS in order" }
        players.add(p)
    }

    fun hasDirty() = dirtyX0 <= dirtyX1

    fun clearDirty() {
        dirtyX0 = size; dirtyY0 = size; dirtyX1 = -1; dirtyY1 = -1
    }

    fun markAllDirty() {
        dirtyX0 = 0; dirtyY0 = 0; dirtyX1 = size - 1; dirtyY1 = size - 1
    }

    private fun markDirty(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (x0 < dirtyX0) dirtyX0 = x0
        if (y0 < dirtyY0) dirtyY0 = y0
        if (x1 > dirtyX1) dirtyX1 = x1
        if (y1 > dirtyY1) dirtyY1 = y1
    }

    // ------------------------------------------------------------------ tick

    fun tick(dt: Float) {
        time += dt
        for (i in players.indices) {
            val p = players[i]
            if (!p.alive) continue
            if (p.speedTime > 0f) p.speedTime -= dt
            if (p.shieldTime > 0f) p.shieldTime -= dt
            if (p.frozenTime > 0f) p.frozenTime -= dt
            move(p, dt)
        }
        updateHazards(dt)
        updatePickups(dt)
    }

    /** Current flying speed of [p] in cells per second. */
    fun speedOf(p: Player): Float {
        var speed = SPEED * p.speedMul * (1f + GLIDE_MAX * p.glide)
        if (p.speedTime > 0f) speed *= SPEED_BOOST
        if (p.frozenTime > 0f) speed *= FREEZE_FACTOR
        return speed
    }

    fun move(p: Player, dt: Float) {
        val diff = MathX.wrapAngle(p.targetAngle - p.angle)
        val maxTurn = p.turnRate * dt
        p.angle = MathX.wrapAngle(p.angle + MathX.clamp(diff * min(1f, dt * TURN_EASE), -maxTurn, maxTurn))
        p.prevX = p.x
        p.prevY = p.y

        val c = cos(p.angle)
        val s = sin(p.angle)
        val along = !openAt(p.x - s * WALL_PROBE, p.y + c * WALL_PROBE) || !openAt(p.x + s * WALL_PROBE, p.y - c * WALL_PROBE)
        p.glide = if (along) min(1f, p.glide + dt / GLIDE_RAMP) else max(0f, p.glide - dt / GLIDE_DECAY)
        val step = speedOf(p) * dt

        var nx = p.x + c * step
        var ny = p.y + s * step
        if (!openAt(nx, ny)) {
            // Glide smoothly along the shape's edge: follow the wall tangent from the distance field.
            var moved = false
            var side = p.slideSide
            val e = 0.35f
            val gx = distAt(p.x + e, p.y) - distAt(p.x - e, p.y)
            val gy = distAt(p.x, p.y + e) - distAt(p.x, p.y - e)
            val gl = sqrt(gx * gx + gy * gy)
            if (gl > 1e-4f) {
                val inX = gx / gl
                val inY = gy / gl
                // Heading along the +side tangent; head-on hits keep the side used last time so
                // the plane doesn't flip back and forth.
                val dot = -inY * c + inX * s
                if (abs(dot) > HEAD_ON) side = if (dot > 0f) 1 else -1
                val tx = -inY * side
                val ty = inX * side
                val k = max(WALL_SLIDE, abs(dot))
                val sx = p.x + (tx * k + inX * 0.12f) * step
                val sy = p.y + (ty * k + inY * 0.12f) * step
                if (openAt(sx, sy)) {
                    nx = sx
                    ny = sy
                    moved = true
                }
            }
            if (!moved) {
                // Dips and corners: both tangents run into the wall. Swing the heading toward the
                // slide side (then the other way) until a step is free, up to a full reverse. A plane
                // squeezed inside the edge margin may also take any step that gets it away from it.
                val here = distAt(p.x, p.y)
                var r = 1
                search@ while (r <= ESCAPE_STEPS) {
                    var k = 0
                    while (k < 2) {
                        val sgn = if (k == 0) side else -side
                        val a = p.angle + sgn * r * ESCAPE_ANGLE
                        val ex = p.x + cos(a) * step
                        val ey = p.y + sin(a) * step
                        if (openAt(ex, ey) || (isOpen(ex, ey) && distAt(ex, ey) > here + 1e-4f)) {
                            nx = ex
                            ny = ey
                            side = sgn
                            moved = true
                            break@search
                        }
                        k++
                    }
                    r++
                }
                if (!moved) {
                    nx = p.x
                    ny = p.y
                }
            }
            p.slideSide = side
            if (moved) {
                // A wall that forces a big turn also turns the plane, so it flies out along the
                // edge instead of pushing back into it. A held finger still wins.
                val off = MathX.wrapAngle(atan2(ny - p.y, nx - p.x) - p.angle)
                if (abs(off) > DEFLECT) {
                    p.angle = MathX.wrapAngle(p.angle + off * min(1f, dt * DEFLECT_RATE))
                    if (!p.steered) p.targetAngle = p.angle
                }
            }
        }
        p.x = nx
        p.y = ny

        val cx = p.x.toInt()
        val cy = p.y.toInt()
        // Diagonal cell change: step through an orthogonal neighbour so ribbons stay 4-connected
        // (a 4-connected path can never slip through another ribbon without touching it).
        if (cx != p.cellX && cy != p.cellY) {
            when {
                isOpenCell(cx, p.cellY) -> enterCell(p, cx, p.cellY)
                isOpenCell(p.cellX, cy) -> enterCell(p, p.cellX, cy)
            }
            if (!p.alive) return
        }
        if (cx != p.cellX || cy != p.cellY) {
            enterCell(p, cx, cy)
            if (!p.alive) return
        }
        if (p.trail.isNotEmpty()) appendTrailPoint(p)
    }

    /** Distance to the nearest wall at a continuous point (bilinear over cell centres). */
    fun distAt(x: Float, y: Float): Float {
        val gx = x - 0.5f
        val gy = y - 0.5f
        val x0 = kotlin.math.floor(gx).toInt()
        val y0 = kotlin.math.floor(gy).toInt()
        val fx = gx - x0
        val fy = gy - y0
        val d00 = cellDist(x0, y0)
        val d10 = cellDist(x0 + 1, y0)
        val d01 = cellDist(x0, y0 + 1)
        val d11 = cellDist(x0 + 1, y0 + 1)
        return (d00 + (d10 - d00) * fx) * (1f - fy) + (d01 + (d11 - d01) * fx) * fy
    }

    private fun cellDist(x: Int, y: Int): Float = if (x < 0 || y < 0 || x >= size || y >= size) 0f else arena.dist[y * size + x]

    /** Where a plane may fly: playable and a little inside the paper's edge. */
    fun openAt(x: Float, y: Float): Boolean = isOpen(x, y) && distAt(x, y) >= WALL_INSET

    private fun slideDirection(lean: Float, x: Float, y: Float, ax: Float, ay: Float): Float {
        if (abs(lean) > 0.05f) return if (lean > 0f) 1f else -1f
        var plus = 0
        var minus = 0
        for (d in 1..6) {
            if (isOpen(x + ax * d, y + ay * d)) plus++
            if (isOpen(x - ax * d, y - ay * d)) minus++
        }
        return if (plus >= minus) 1f else -1f
    }

    private fun appendTrailPoint(p: Player) {
        val pts = p.trailPoints
        if (pts.size < 2) {
            pts.add(p.x, p.y)
            return
        }
        val dx = p.x - pts[pts.size - 2]
        val dy = p.y - pts[pts.size - 1]
        if (dx * dx + dy * dy <= TRAIL_POINT_SPACING * TRAIL_POINT_SPACING) return
        val n = pts.size
        if (n >= 4) {
            // On a straight stretch, slide the last point forward instead of adding one.
            val ax = pts[n - 2] - pts[n - 4]
            val ay = pts[n - 1] - pts[n - 3]
            val cross = ax * dy - ay * dx
            val dot = ax * dx + ay * dy
            val la = ax * ax + ay * ay
            val lb = dx * dx + dy * dy
            if (dot > 0f && cross * cross < 0.0009f * la * lb) {
                pts[n - 2] = p.x
                pts[n - 1] = p.y
                return
            }
        }
        pts.add(p.x, p.y)
    }

    private fun enterCell(p: Player, x: Int, y: Int) {
        p.cellX = x
        p.cellY = y
        val i = idx(x, y)
        val t = trail[i]
        if (t != 0 && t != p.id) {
            val v = player(t)
            if (v.alive && v.shieldTime <= 0f && p.spares != v.id) snip(v, p, Cause.SNIPPED)
        }
        if (owner[i] == p.id) {
            if (p.trail.isNotEmpty()) capture(p, x, y)
            return
        }
        // Crossing your own ribbon is safe; the cell is already part of it.
        if (trail[i] == p.id) return
        if (p.trail.isEmpty()) {
            p.trailPoints.clear()
            p.trailPoints.add(p.prevX, p.prevY)
            events.add(WorldEvent.LeftLand(p.id))
        }
        p.trail.add(i)
        // A shielded rival's ribbon keeps its cell; ours just runs across it.
        if (trail[i] == 0) trail[i] = p.id
    }

    // ------------------------------------------------------------------ capture

    private fun capture(p: Player, x: Int, y: Int) {
        val id = p.id
        val before = counts[id]
        stolen = 0
        changed.clear()
        serial++
        for (k in 0 until p.trail.size) {
            val c = p.trail[k]
            if (trail[c] == id) trail[c] = 0
            if (claim(c, id)) changed.add(c)
        }
        p.trail.clear()
        p.trailPoints.clear()
        p.trailGen++
        fillEnclosed(id)
        stampAndMark(changed, true)
        events.add(WorldEvent.Captured(id, counts[id] - before, serial, x + 0.5f, y + 0.5f, stolen))
        killLandless(p)
    }

    /**
     * Claims for [id] every playable cell its land now encloses: whatever a 4-connected flood from
     * outside the land's bounding box cannot reach. Walls count as outside, so the arena edge never
     * closes a loop for you.
     */
    private fun fillEnclosed(id: Int) {
        var minX = size
        var minY = size
        var maxX = -1
        var maxY = -1
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                if (owner[row + x] == id) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return
        minX = max(0, minX - 1)
        minY = max(0, minY - 1)
        maxX = min(size - 1, maxX + 1)
        maxY = min(size - 1, maxY + 1)

        markGen++
        queueTail = 0
        for (x in minX..maxX) {
            seed(idx(x, minY), id)
            seed(idx(x, maxY), id)
        }
        for (y in minY..maxY) {
            seed(idx(minX, y), id)
            seed(idx(maxX, y), id)
        }
        var head = 0
        while (head < queueTail) {
            val i = queue[head++]
            val x = i % size
            val y = i / size
            if (x > minX) seed(i - 1, id)
            if (x < maxX) seed(i + 1, id)
            if (y > minY) seed(i - size, id)
            if (y < maxY) seed(i + size, id)
        }
        for (y in minY..maxY) {
            val row = y * size
            for (x in minX..maxX) {
                val i = row + x
                if (playable[i] && owner[i] != id && mark[i] != markGen && claim(i, id)) changed.add(i)
            }
        }
    }

    private fun seed(i: Int, id: Int) {
        if (owner[i] != id && mark[i] != markGen) {
            mark[i] = markGen
            queue[queueTail++] = i
        }
    }

    private fun claim(i: Int, id: Int): Boolean {
        val old = owner[i]
        if (old == id) return false
        if (old > 0) {
            counts[old]--
            stolen++
        }
        owner[i] = id
        counts[id]++
        return true
    }

    private fun stampAndMark(list: IntList, stampIt: Boolean) {
        if (list.size == 0) return
        var x0 = size
        var y0 = size
        var x1 = -1
        var y1 = -1
        for (k in 0 until list.size) {
            val c = list[k]
            if (stampIt) stamp[c] = serial
            val x = c % size
            val y = c / size
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        markDirty(x0, y0, x1, y1)
    }

    private fun killLandless(by: Player?) {
        for (i in players.indices) {
            val o = players[i]
            if (o !== by && o.alive && counts[o.id] == 0) snip(o, by, Cause.SURROUNDED)
        }
    }

    /** Claims a disc of land for [p] (Paint Bomb, Ink Burst). Returns the cells gained. */
    fun claimDisc(p: Player, fx: Float, fy: Float, r: Float): Int {
        if (!p.alive) return 0
        val id = p.id
        val before = counts[id]
        stolen = 0
        changed.clear()
        serial++
        val cx = fx.toInt()
        val cy = fy.toInt()
        val ri = ceil(r).toInt()
        for (dy in -ri..ri) {
            for (dx in -ri..ri) {
                if (dx * dx + dy * dy > r * r) continue
                val x = cx + dx
                val y = cy + dy
                if (!isOpenCell(x, y)) continue
                val i = idx(x, y)
                // Ribbons stay ribbons: ours closes normally once we fly onto the new land.
                if (trail[i] != 0) continue
                if (claim(i, id)) changed.add(i)
            }
        }
        fillEnclosed(id)
        stampAndMark(changed, true)
        val gained = counts[id] - before
        events.add(WorldEvent.Captured(id, gained, serial, fx, fy, stolen))
        killLandless(p)
        return gained
    }

    // ------------------------------------------------------------------ popping

    /** Pops [victim]. A living [killer] inherits its ribbon, its land and anything that now encloses. */
    fun snip(victim: Player, killer: Player?, cause: Cause) {
        if (!victim.alive) return
        victim.alive = false
        victim.deathCause = cause
        victim.killer = killer?.id ?: 0
        victim.deathX = victim.x
        victim.deathY = victim.y
        val heir = if (killer != null && killer !== victim && killer.alive) killer else null
        val before = if (heir != null) counts[heir.id] else 0
        stolen = 0
        changed.clear()
        if (heir != null) serial++
        for (k in 0 until victim.trail.size) {
            val c = victim.trail[k]
            if (trail[c] == victim.id) trail[c] = 0
            if (heir != null && playable[c] && claim(c, heir.id)) changed.add(c)
        }
        victim.trail.clear()
        victim.trailPoints.clear()
        victim.trailGen++
        if (victim.isHuman) victim.lostCells.clear()
        val vid = victim.id
        for (i in owner.indices) {
            if (owner[i] != vid) continue
            if (victim.isHuman) victim.lostCells.add(i)
            if (heir != null) {
                owner[i] = heir.id
                counts[heir.id]++
            } else {
                owner[i] = 0
            }
            changed.add(i)
        }
        counts[vid] = 0
        if (heir != null) fillEnclosed(heir.id)
        stampAndMark(changed, heir != null)
        if (killer != null && killer !== victim) killer.snips++
        val gained = if (heir != null) counts[heir.id] - before else 0
        events.add(WorldEvent.Snipped(vid, killer?.id ?: 0, cause, victim.x, victim.y, gained))
        if (heir != null && gained > 0) events.add(WorldEvent.Captured(heir.id, gained, serial, victim.x, victim.y, gained))
        if (heir != null) killLandless(heir)
    }

    // ------------------------------------------------------------------ spawning

    /** Puts [p] down on a fresh disc of land centred on [cell]. */
    fun spawnAt(p: Player, cell: Int, radius: Int, faceX: Float = arena.centerX, faceY: Float = arena.centerY) {
        val cx = cell % size
        val cy = cell / size
        changed.clear()
        serial++
        stolen = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                // (r + 0.5)^2 gives a round digital disc instead of a diamond-ish octagon
                if (dx * dx + dy * dy > radius * radius + radius) continue
                val x = cx + dx
                val y = cy + dy
                if (!isOpenCell(x, y)) continue
                val i = idx(x, y)
                if (trail[i] != 0) continue
                if (claim(i, p.id)) changed.add(i)
            }
        }
        stampAndMark(changed, true)
        place(p, cx, cy)
        p.angle = atan2(faceY - p.y, faceX - p.x)
        p.targetAngle = p.angle
        events.add(WorldEvent.Spawned(p.id))
        killLandless(p)
    }

    private fun place(p: Player, cx: Int, cy: Int) {
        p.x = cx + 0.5f
        p.y = cy + 0.5f
        p.prevX = p.x
        p.prevY = p.y
        p.cellX = cx
        p.cellY = cy
        p.homeX = cx
        p.homeY = cy
        p.trail.clear()
        p.trailPoints.clear()
        p.trailGen++
        p.killer = 0
        p.glide = 0f
        p.slideSide = 1
        p.speedTime = 0f
        p.frozenTime = 0f
        p.alive = true
    }

    /**
     * A free, roomy cell for a respawn: no land or ribbon around it, away from every plane and at
     * least [avoidDist] from (avoidX, avoidY). Constraints relax if the board is crowded.
     */
    fun findSpawnCell(radius: Int, avoidX: Float, avoidY: Float, avoidDist: Float): Int {
        val roomy = arena.roomy
        if (roomy.isEmpty()) return -1
        var best = -1
        var bestScore = -Float.MAX_VALUE
        for (attempt in 0 until 160) {
            val c = roomy[rng.nextInt(roomy.size)]
            if (arena.dist[c] < radius + 1.5f) continue
            val cx = c % size
            val cy = c / size
            if (!areaFree(cx, cy, radius + 1)) continue
            val ax = cx + 0.5f - avoidX
            val ay = cy + 0.5f - avoidY
            val da = sqrt(ax * ax + ay * ay)
            val need = if (attempt < 110) avoidDist else avoidDist * 0.5f
            if (da < need) continue
            var near = 60f
            for (o in players) {
                if (!o.alive) continue
                val dx = o.x - cx
                val dy = o.y - cy
                near = min(near, sqrt(dx * dx + dy * dy))
            }
            if (near < 8f && attempt < 110) continue
            val score = min(da, 40f) * 0.5f + near + rng.nextFloat() * 4f
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        if (best >= 0) return best
        for (attempt in 0 until 300) {
            val c = roomy[rng.nextInt(roomy.size)]
            if (trail[c] == 0 && owner[c] == 0) return c
        }
        return roomy[rng.nextInt(roomy.size)]
    }

    private fun areaFree(cx: Int, cy: Int, r: Int): Boolean {
        for (y in cy - r..cy + r) {
            for (x in cx - r..cx + r) {
                if (!isOpenCell(x, y)) return false
                val i = idx(x, y)
                if (owner[i] != 0 || trail[i] != 0) return false
            }
        }
        return true
    }

    /**
     * Brings the player back with the land they held, minus what others took since (what the
     * popper inherited comes back). Falls back to a fresh spawn if almost nothing is left.
     */
    fun revive(p: Player, killerId: Int) {
        changed.clear()
        serial++
        stolen = 0
        var restored = 0
        for (k in 0 until p.lostCells.size) {
            val i = p.lostCells[k]
            if (!playable[i] || trail[i] != 0) continue
            if (owner[i] != 0 && owner[i] != killerId) continue
            if (claim(i, p.id)) {
                changed.add(i)
                restored++
            }
        }
        stampAndMark(changed, true)
        if (restored < 12) {
            val cell = findSpawnCell(4, p.deathX, p.deathY, 0f)
            spawnAt(p, cell, 4)
        } else {
            var best = -1
            var bestScore = -1
            for (k in 0 until p.lostCells.size) {
                val i = p.lostCells[k]
                if (owner[i] != p.id) continue
                val x = i % size
                val y = i / size
                var score = 0
                for (dy in -2..2) for (dx in -2..2) if (ownerAt(x + dx, y + dy) == p.id) score++
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            place(p, best % size, best / size)
            p.angle = atan2(arena.centerY - p.y, arena.centerX - p.x)
            p.targetAngle = p.angle
            events.add(WorldEvent.Spawned(p.id))
        }
        p.shieldTime = 3f
        killLandless(p)
    }

    // ------------------------------------------------------------------ pickups and hazards

    fun addPickup(type: PickupType, x: Float, y: Float): Pickup {
        val pk = Pickup(type, x, y)
        pickups.add(pk)
        return pk
    }

    fun activePickups(): Int {
        var n = 0
        for (pk in pickups) if (pk.alive && pk.type != PickupType.STAR) n++
        return n
    }

    private fun updatePickups(dt: Float) {
        var i = pickups.size - 1
        while (i >= 0) {
            val pk = pickups[i]
            pk.age += dt
            if (!pk.alive) {
                pk.takenAge += dt
                if (pk.takenAge > 0.7f) pickups.removeAt(i)
                i--
                continue
            }
            for (p in players) {
                if (!p.alive) continue
                if (!p.isHuman && (pk.type == PickupType.STAR || !allowRivalPickups)) continue
                val dx = p.x - pk.x
                val dy = p.y - pk.y
                if (dx * dx + dy * dy < PICK_R2) {
                    take(pk, p)
                    break
                }
            }
            i--
        }
    }

    private fun take(pk: Pickup, p: Player) {
        pk.alive = false
        pk.takenAge = 0f
        pk.takenBy = p.id
        events.add(WorldEvent.Picked(p.id, pk.type, pk.x, pk.y))
        when (pk.type) {
            PickupType.SPEED -> p.speedTime = 5f
            PickupType.SHIELD -> p.shieldTime = 5f
            PickupType.BOMB -> claimDisc(p, p.x, p.y, bombRadius)
            PickupType.FREEZE -> for (o in players) if (o !== p && o.alive) o.frozenTime = 4f
            PickupType.STAR -> p.stars++
        }
    }

    private fun updateHazards(dt: Float) {
        for (h in hazards) {
            var remaining = h.speed * dt
            var guard = 0
            while (remaining > 0f && guard++ < 8) {
                val n = h.points
                val ax = h.path[h.segment * 2]
                val ay = h.path[h.segment * 2 + 1]
                val nb = (h.segment + 1) % n
                val bx = h.path[nb * 2]
                val by = h.path[nb * 2 + 1]
                val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                if (len < 1e-3f) {
                    h.segment = nb
                    h.segmentT = 0f
                    continue
                }
                val left = len * (1f - h.segmentT)
                if (remaining < left) {
                    h.segmentT += remaining / len
                    remaining = 0f
                } else {
                    remaining -= left
                    h.segment = nb
                    h.segmentT = 0f
                }
                h.heading = atan2(by - ay, bx - ax)
            }
            val n = h.points
            val ax = h.path[h.segment * 2]
            val ay = h.path[h.segment * 2 + 1]
            val nb = (h.segment + 1) % n
            h.x = ax + (h.path[nb * 2] - ax) * h.segmentT
            h.y = ay + (h.path[nb * 2 + 1] - ay) * h.segmentT
            h.spin += dt * 9f

            val cx = h.x.toInt()
            val cy = h.y.toInt()
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= size || y >= size) continue
                    val t = trail[idx(x, y)]
                    if (t == 0) continue
                    val ddx = x + 0.5f - h.x
                    val ddy = y + 0.5f - h.y
                    if (ddx * ddx + ddy * ddy > HAZARD_R2) continue
                    val v = player(t)
                    if (v.alive && v.shieldTime <= 0f) snip(v, null, Cause.HAZARD)
                }
            }
        }
    }

    // ------------------------------------------------------------------ test and tooling hooks

    /** Moves [p] straight into the given cell sequence (tests): each step enters one cell. */
    fun walkForTest(p: Player, cells: IntArray) {
        var k = 0
        while (k < cells.size && p.alive) {
            val x = cells[k]
            val y = cells[k + 1]
            if (!isOpenCell(x, y)) {
                k += 2
                continue
            }
            p.prevX = p.x
            p.prevY = p.y
            p.x = x + 0.5f
            p.y = y + 0.5f
            enterCell(p, x, y)
            if (p.alive && p.trail.isNotEmpty()) appendTrailPoint(p)
            k += 2
        }
    }

    companion object {
        const val MAX_PLAYERS = 12
        /** Cells per second for everyone at speed factor 1. */
        const val SPEED = 6.5f
        /** Top turn speed in radians per second. */
        const val TURN_RATE = 20f
        const val TURN_EASE = 26f
        const val GLIDE_RAMP = 1.1f
        const val GLIDE_DECAY = 0.5f
        const val GLIDE_MAX = 0.4f
        const val SPEED_BOOST = 1.35f
        const val FREEZE_FACTOR = 0.4f
        private const val WALL_PROBE = 1.3f
        private const val WALL_SLIDE = 0.7f
        /** Below this tangent share a wall hit counts as head-on. */
        private const val HEAD_ON = 0.25f
        /** Escape search: 12 steps of 15° each way, up to a full reverse. */
        private const val ESCAPE_STEPS = 12
        private const val ESCAPE_ANGLE = 0.2618f
        /** Wall-forced turns larger than this (radians, ~35°) turn the plane itself. */
        private const val DEFLECT = 0.61f
        private const val DEFLECT_RATE = 14f
        /** Planes keep this far (in cells, from wall-cell centres) inside the edge. */
        private const val WALL_INSET = 0.8f
        private const val TRAIL_POINT_SPACING = 0.35f
        private const val PICK_R2 = 1.15f * 1.15f
        private const val HAZARD_R2 = 1.05f * 1.05f
    }
}
