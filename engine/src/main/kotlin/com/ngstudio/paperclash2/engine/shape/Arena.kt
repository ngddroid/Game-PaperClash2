package com.ngstudio.paperclash2.engine.shape

import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.core.Rng
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a level bends its base shape: tilt, mirror, stretch, torn-paper wobble and holes. */
data class ShapeVariant(
    val rotDeg: Float = 0f,
    val mirror: Boolean = false,
    val aspect: Float = 1f,
    val wobble: Float = 0f,
    val holes: Int = 0,
    val seed: Int = 0,
)

/**
 * A ready-to-play board: a square grid (y up) where [mask] marks playable cells. [dist] holds each
 * cell's distance to the nearest wall in cells, [spawns] the starting cells (index 0 = the player).
 */
class Arena(
    val size: Int,
    val mask: BooleanArray,
    val dist: FloatArray,
    val spawns: IntArray,
    val shapeId: String,
    /** Cells per shape unit, used to size decorations relative to the silhouette. */
    val scale: Float,
) {
    val area: Int = mask.count { it }
    val centerX: Float
    val centerY: Float

    /** Roomy cells (≥ 4 from a wall) used to respawn rivals and drop pickups. */
    val roomy: IntArray

    /** Bounding box of the open cells, in cells (max is exclusive). */
    val minX: Int
    val minY: Int
    val maxX: Int
    val maxY: Int

    init {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        var x0 = size
        var y0 = size
        var x1 = 0
        var y1 = 0
        val rl = ArrayList<Int>()
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = i % size
            val y = i / size
            sx += x
            sy += y
            n++
            if (x < x0) x0 = x
            if (y < y0) y0 = y
            if (x + 1 > x1) x1 = x + 1
            if (y + 1 > y1) y1 = y + 1
            if (dist[i] >= 4f) rl.add(i)
        }
        centerX = (sx / max(1, n)).toFloat() + 0.5f
        centerY = (sy / max(1, n)).toFloat() + 0.5f
        roomy = rl.toIntArray()
        if (n == 0) { x0 = 0; y0 = 0; x1 = size; y1 = size }
        minX = x0
        minY = y0
        maxX = x1
        maxY = y1
    }

    fun idx(x: Int, y: Int) = y * size + x
    fun isOpen(x: Int, y: Int) = x >= 0 && y >= 0 && x < size && y < size && mask[y * size + x]
}

/** Turns a [ShapeDef] + [ShapeVariant] into an [Arena] of a requested playable area. */
object ArenaBuilder {
    const val MARGIN = 3
    const val OPEN_R = 2
    const val MAX_SIZE = 256

    /** The variant applied to the base SDF (evaluated in shape units). */
    fun transformed(shape: ShapeDef, v: ShapeVariant): Sdf {
        val a = (-v.rotDeg * PI / 180.0)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val base = shape.sdf
        val asp = v.aspect
        return Sdf { x, y ->
            // undo aspect, then rotation, then mirror
            val ux = x / asp
            val uy = y * asp
            var rx = ux * c - uy * s
            val ry = ux * s + uy * c
            if (v.mirror) rx = -rx
            var d = base.d(rx, ry)
            if (v.wobble > 0f) {
                val n = MathX.valueNoise(x * 3.1f + 11f, y * 3.1f - 7f, v.seed) * 0.65f +
                    MathX.valueNoise(x * 7.3f - 3f, y * 7.3f + 5f, v.seed + 17) * 0.35f
                d += (n - 0.5f) * 2f * v.wobble
            }
            d
        }
    }

    private class Extent(val minU: Float, val minV: Float, val maxU: Float, val maxV: Float, val unitArea: Float)

    private fun measure(sdf: Sdf): Extent {
        val n = 160
        val ext = 1.7f
        val step = 2f * ext / n
        var minU = Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var count = 0
        for (j in 0 until n) {
            val v = -ext + (j + 0.5f) * step
            for (i in 0 until n) {
                val u = -ext + (i + 0.5f) * step
                if (sdf.d(u, v) < 0f) {
                    count++
                    if (u < minU) minU = u
                    if (u > maxU) maxU = u
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
            }
        }
        if (count == 0) return Extent(-1f, -1f, 1f, 1f, 4f)
        return Extent(minU - step / 2, minV - step / 2, maxU + step / 2, maxV + step / 2, count * step * step)
    }

    /** The largest playable area this variant can reach on a [MAX_SIZE] grid. */
    fun capacity(shape: ShapeDef, variant: ShapeVariant): Int {
        val ex = measure(transformed(shape, variant))
        val k = (MAX_SIZE - 2 * MARGIN - 2) / max(ex.maxU - ex.minU, ex.maxV - ex.minV)
        return (ex.unitArea * k * k * 0.9f).toInt()
    }

    /**
     * Builds the board. [players] spawn discs of radius [startRadius] are placed; if the shape is
     * too tight the spawn spacing relaxes, and the caller's validator reports it.
     */
    fun build(shape: ShapeDef, variant: ShapeVariant, targetArea: Int, players: Int, startRadius: Int, seed: Long): Arena {
        val sdf = transformed(shape, variant)
        val ex = measure(sdf)
        var k = sqrt(targetArea / ex.unitArea)
        val spanU = ex.maxU - ex.minU
        val spanV = ex.maxV - ex.minV
        var size = ceil(max(spanU, spanV) * k).toInt() + 2 * MARGIN + 2
        if (size > MAX_SIZE) {
            size = MAX_SIZE
            k = (MAX_SIZE - 2 * MARGIN - 2) / max(spanU, spanV)
        }
        size = max(size, 24)
        val offX = (size - spanU * k) / 2f
        val offY = (size - spanV * k) / 2f
        val n = size * size
        var mask = BooleanArray(n)
        for (y in MARGIN until size - MARGIN) {
            val v = ex.minV + (y + 0.5f - offY) / k
            for (x in MARGIN until size - MARGIN) {
                val u = ex.minU + (x + 0.5f - offX) / k
                mask[y * size + x] = sdf.d(u, v) < 0f
            }
        }
        val rng = Rng(seed * 31 + 7)
        mask = clean(mask, size)
        if (variant.holes > 0) mask = carveHoles(mask, size, variant.holes, rng)
        val dist = distanceToWall(mask, size)
        val spawns = placeSpawns(mask, dist, size, players, startRadius, rng)
        return Arena(size, mask, dist, spawns, shape.id, k)
    }

    /** Opening (removes slivers thinner than ~2·OPEN_R+1 cells) and keeps the largest piece. */
    fun clean(mask: BooleanArray, size: Int): BooleanArray {
        val d = chamfer(size) { !mask[it] }
        val r3 = 3 * OPEN_R
        val de = chamfer(size) { mask[it] && d[it] > r3 }
        val opened = BooleanArray(mask.size) { mask[it] && de[it] <= r3 }
        return largestComponent(opened, size)
    }

    private fun carveHoles(mask: BooleanArray, size: Int, holes: Int, rng: Rng): BooleanArray {
        val before = mask.count { it }
        val d = chamfer(size) { !mask[it] }
        val hr = MathX.clamp(size / 24, 4, 8)
        val need = 3 * (hr + 7)
        val cand = ArrayList<Int>()
        for (i in mask.indices) if (mask[i] && d[i] >= need) cand.add(i)
        if (cand.isEmpty()) return mask
        val out = mask.copyOf()
        val centers = ArrayList<Int>()
        var tries = 0
        while (centers.size < holes && tries < 60) {
            tries++
            val c = cand[rng.nextInt(cand.size)]
            val cx = c % size
            val cy = c / size
            var ok = true
            for (o in centers) {
                val dx = o % size - cx
                val dy = o / size - cy
                if (dx * dx + dy * dy < (2 * hr + 10) * (2 * hr + 10)) ok = false
            }
            if (!ok) continue
            centers.add(c)
            val wob = rng.range(0.85f, 1.15f)
            for (y in cy - hr - 1..cy + hr + 1) for (x in cx - hr - 1..cx + hr + 1) {
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                val dx = (x - cx) * wob
                val dy = (y - cy) / wob
                if (dx * dx + dy * dy <= hr * hr) out[y * size + x] = false
            }
        }
        val cleaned = clean(out, size)
        val carved = before - out.count { it }
        // A hole must never split the board or eat more than it should.
        return if (cleaned.count { it } >= (before - carved) * 0.93f) cleaned else mask
    }

    /** Chamfer (3-4) distance to the nearest cell where [source] is true; units of 1/3 cell. */
    fun chamfer(size: Int, source: (Int) -> Boolean): IntArray {
        val inf = 1 shl 28
        val n = size * size
        val d = IntArray(n) { if (source(it)) 0 else inf }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                var v = d[i]
                if (v == 0) continue
                if (x > 0) v = min(v, d[i - 1] + 3)
                if (y > 0) {
                    v = min(v, d[i - size] + 3)
                    if (x > 0) v = min(v, d[i - size - 1] + 4)
                    if (x < size - 1) v = min(v, d[i - size + 1] + 4)
                }
                d[i] = v
            }
        }
        for (y in size - 1 downTo 0) {
            for (x in size - 1 downTo 0) {
                val i = y * size + x
                var v = d[i]
                if (v == 0) continue
                if (x < size - 1) v = min(v, d[i + 1] + 3)
                if (y < size - 1) {
                    v = min(v, d[i + size] + 3)
                    if (x < size - 1) v = min(v, d[i + size + 1] + 4)
                    if (x > 0) v = min(v, d[i + size - 1] + 4)
                }
                d[i] = v
            }
        }
        return d
    }

    fun distanceToWall(mask: BooleanArray, size: Int): FloatArray {
        val d = chamfer(size) { !mask[it] }
        return FloatArray(mask.size) { if (mask[it]) d[it] / 3f else 0f }
    }

    fun largestComponent(mask: BooleanArray, size: Int): BooleanArray {
        val label = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var best = 0
        var bestCount = 0
        var next = 0
        for (s in mask.indices) {
            if (!mask[s] || label[s] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = s
            label[s] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % size
                val y = i / size
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; queue[tail++] = i - 1 }
                if (x < size - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; queue[tail++] = i + 1 }
                if (y > 0 && mask[i - size] && label[i - size] == 0) { label[i - size] = next; queue[tail++] = i - size }
                if (y < size - 1 && mask[i + size] && label[i + size] == 0) { label[i + size] = next; queue[tail++] = i + size }
            }
            if (tail > bestCount) {
                bestCount = tail
                best = next
            }
        }
        return BooleanArray(mask.size) { label[it] == best && best != 0 }
    }

    /** Counts 4-connected pieces of [mask] (the validator wants exactly one). */
    fun components(mask: BooleanArray, size: Int): Int {
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var count = 0
        for (s in mask.indices) {
            if (!mask[s] || seen[s]) continue
            count++
            var head = 0
            var tail = 0
            queue[tail++] = s
            seen[s] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % size
                val y = i / size
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < size - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && mask[i - size] && !seen[i - size]) { seen[i - size] = true; queue[tail++] = i - size }
                if (y < size - 1 && mask[i + size] && !seen[i + size]) { seen[i + size] = true; queue[tail++] = i + size }
            }
        }
        return count
    }

    /**
     * Farthest-point spawn placement over roomy cells. The player's spot is roomy and near the
     * middle; every rival then takes the candidate farthest from everyone already placed.
     */
    private fun placeSpawns(mask: BooleanArray, dist: FloatArray, size: Int, players: Int, startRadius: Int, rng: Rng): IntArray {
        var clear = startRadius + 2.5f
        var cand = IntArray(0)
        while (clear >= startRadius + 0.5f) {
            val list = ArrayList<Int>()
            for (i in mask.indices) if (mask[i] && dist[i] >= clear) list.add(i)
            if (list.size >= players * 3 || clear <= startRadius + 0.5f) {
                cand = list.toIntArray()
                break
            }
            clear -= 1f
        }
        if (cand.isEmpty()) {
            // Degenerate shape: fall back to the roomiest cells there are.
            val order = mask.indices.filter { mask[it] }.sortedByDescending { dist[it] }
            cand = order.take(max(players * 4, 1)).toIntArray()
        }
        var cx = 0.0
        var cy = 0.0
        for (i in cand) { cx += i % size; cy += i / size }
        cx /= cand.size
        cy /= cand.size
        // Player: best of a few random roomy candidates close to the middle.
        var human = cand[0]
        var bestScore = -Float.MAX_VALUE
        for (t in 0 until 80) {
            val c = cand[rng.nextInt(cand.size)]
            val dx = (c % size - cx).toFloat()
            val dy = (c / size - cy).toFloat()
            val score = min(dist[c], 12f) * 0.6f - sqrt(dx * dx + dy * dy) * 0.12f + rng.nextFloat()
            if (score > bestScore) { bestScore = score; human = c }
        }
        val chosen = IntArray(players)
        chosen[0] = human
        val minD = FloatArray(cand.size) { Float.MAX_VALUE }
        for (count in 1 until players) {
            val last = chosen[count - 1]
            val lx = last % size
            val ly = last / size
            var best = -1
            var bestD = -1f
            for (k in cand.indices) {
                val c = cand[k]
                val dx = (c % size - lx).toFloat()
                val dy = (c / size - ly).toFloat()
                val d = dx * dx + dy * dy
                if (d < minD[k]) minD[k] = d
                // Small jitter so equal layouts don't always give the same spots.
                val score = minD[k] * (0.92f + 0.16f * Rng.hash01(c, count))
                if (score > bestD) { bestD = score; best = c }
            }
            chosen[count] = if (best >= 0) best else cand[rng.nextInt(cand.size)]
        }
        return chosen
    }

    /** A [res]×[res] silhouette of the variant fitted with a 1-cell border (for thumbnails). */
    fun silhouette(shape: ShapeDef, variant: ShapeVariant, res: Int): BooleanArray {
        val sdf = transformed(shape, variant)
        val ex = measure(sdf)
        val span = max(ex.maxU - ex.minU, ex.maxV - ex.minV)
        val k = (res - 2) / span
        val offX = (res - (ex.maxU - ex.minU) * k) / 2f
        val offY = (res - (ex.maxV - ex.minV) * k) / 2f
        val out = BooleanArray(res * res)
        for (y in 0 until res) {
            val v = ex.minV + (y + 0.5f - offY) / k
            for (x in 0 until res) {
                val u = ex.minU + (x + 0.5f - offX) / k
                out[y * res + x] = sdf.d(u, v) < 0f
            }
        }
        return out
    }
}
