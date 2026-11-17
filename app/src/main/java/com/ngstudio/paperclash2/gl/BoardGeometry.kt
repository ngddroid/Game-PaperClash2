package com.ngstudio.paperclash2.gl

import com.ngstudio.paperclash2.engine.world.World
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The CPU half of a [Board]: the 3D shape's height field, the smooth edge field, the side walls,
 * the desk shadow and the surface mesh. It is pure data with no GL calls, so it is built on a worker
 * thread and the GL thread only uploads it (switching levels never stalls the screen).
 */
class BoardGeometry(world: World) {
    val size = world.size
    val n = size * Board.RES

    /** Surface height at every cell centre; walls hold the low rim value. */
    private val height = FloatArray(size * size)

    /** How high the dome rises above the rim, in cells. */
    var dome = 0f
        private set

    /** Mean surface height over the shape (camera framing). */
    var midZ = 0f
        private set

    /** Edge coverage, n×n bytes (cuts the top surface with a smooth edge). */
    val edge: ByteArray

    /** Blurred mask at grid resolution: the slab's soft shadow on the desk. */
    val shadow: ByteArray

    /** Wall triangles: x, y, z, nx, ny, t (t = 0 at the rim, 1 at the desk). */
    val walls: FloatArray

    /** Surface mesh: x, y, z, nx, ny, nz per vertex, and its triangle indices. */
    val mesh: FloatArray
    val meshIndex: ShortArray

    // wall build buffer (declared before init, which uses it)
    private var wallData = FloatArray(1 shl 14)
    private var wallSize = 0

    init {
        buildHeight(world)
        val field = upsample(world.playable)
        edge = ByteArray(n * n) { (field[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
        val blur = FloatArray(size * size) { if (world.playable[it]) 1f else 0f }
        boxBlur(blur, size, 3)
        boxBlur(blur, size, 3)
        boxBlur(blur, size, 2)
        shadow = ByteArray(size * size) { (blur[it] * 255f).toInt().coerceIn(0, 255).toByte() }
        walls = buildWalls(field)
        val a = world.arena
        val x0 = max(0, a.minX - 3)
        val y0 = max(0, a.minY - 3)
        val x1 = min(size, a.maxX + 3)
        val y1 = min(size, a.maxY + 3)
        val step = if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 42000) 1 else 2
        val cols = (x1 - x0 + step - 1) / step + 1
        val rows = (y1 - y0 + step - 1) / step + 1
        mesh = FloatArray(cols * rows * 6)
        var o = 0
        val e = 0.6f
        for (j in 0 until rows) {
            val y = min(y1, y0 + j * step).toFloat()
            for (i in 0 until cols) {
                val x = min(x1, x0 + i * step).toFloat()
                val dx = (heightAt(x + e, y) - heightAt(x - e, y)) / (2f * e)
                val dy = (heightAt(x, y + e) - heightAt(x, y - e)) / (2f * e)
                val inv = 1f / sqrt(dx * dx + dy * dy + 1f)
                mesh[o++] = x
                mesh[o++] = y
                mesh[o++] = heightAt(x, y)
                mesh[o++] = -dx * inv
                mesh[o++] = -dy * inv
                mesh[o++] = inv
            }
        }
        meshIndex = ShortArray((cols - 1) * (rows - 1) * 6)
        var k = 0
        for (j in 0 until rows - 1) {
            for (i in 0 until cols - 1) {
                val v = j * cols + i
                meshIndex[k++] = v.toShort(); meshIndex[k++] = (v + 1).toShort(); meshIndex[k++] = (v + cols).toShort()
                meshIndex[k++] = (v + 1).toShort(); meshIndex[k++] = (v + cols + 1).toShort(); meshIndex[k++] = (v + cols).toShort()
            }
        }
    }

    /**
     * The shape is inflated from its wall distance: a dome that rises fast near the edge and
     * flattens toward the middle. Wall cells sit low, and three blur passes round the rim into a
     * soft lip and smooth the creases along the shape's spine.
     */
    private fun buildHeight(world: World) {
        val mask = world.playable
        val dist = world.arena.dist
        var maxD = 1f
        for (i in mask.indices) if (mask[i] && dist[i] > maxD) maxD = dist[i]
        val r = (maxD * 0.92f).coerceIn(3f, 28f)
        dome = (maxD * 0.22f).coerceIn(1.4f, 6f)
        for (i in height.indices) {
            height[i] = if (mask[i]) {
                val t = min(dist[i] / r, 1f)
                dome * (1f - (1f - t) * (1f - t))
            } else -Board.RIM_DROP
        }
        repeat(3) { boxBlur(height, size, 1) }
        var sum = 0.0
        var count = 0
        for (i in mask.indices) if (mask[i]) {
            sum += height[i]
            count++
        }
        midZ = if (count > 0) (sum / count).toFloat() else 0f
    }

    /** Surface height of the shape at a board point (bilinear over cell centres). */
    fun heightAt(x: Float, y: Float): Float {
        val gx = x - 0.5f
        val gy = y - 0.5f
        val fx0 = floor(gx)
        val fy0 = floor(gy)
        val x0 = fx0.toInt()
        val y0 = fy0.toInt()
        val fx = gx - fx0
        val fy = gy - fy0
        val h00 = hc(x0, y0)
        val h10 = hc(x0 + 1, y0)
        val h01 = hc(x0, y0 + 1)
        val h11 = hc(x0 + 1, y0 + 1)
        return (h00 + (h10 - h00) * fx) * (1f - fy) + (h01 + (h11 - h01) * fx) * fy
    }

    private fun hc(x: Int, y: Int): Float {
        val cx = if (x < 0) 0 else if (x >= size) size - 1 else x
        val cy = if (y < 0) 0 else if (y >= size) size - 1 else y
        return height[cy * size + cx]
    }

    /** Cubic B-spline upsampling of a cell mask to RES texels per cell (matches the GPU bake). */
    private fun upsample(mask: BooleanArray): FloatArray {
        val res = Board.RES
        val w = Array(res) { ph ->
            val p = (ph + 0.5f) / res
            FloatArray(4) { k ->
                val baseRel = floor(p - 0.5f).toInt() - 1
                bs(p - (baseRel + k + 0.5f))
            }
        }
        val baseRel = IntArray(res) { ph -> floor((ph + 0.5f) / res - 0.5f).toInt() - 1 }
        val tmp = FloatArray(size * n)
        for (y in 0 until size) {
            val row = y * size
            for (cx in 0 until size) {
                for (ph in 0 until res) {
                    var s = 0f
                    val ww = w[ph]
                    val b = cx + baseRel[ph]
                    for (k in 0..3) {
                        val i = b + k
                        if (i >= 0 && i < size && mask[row + i]) s += ww[k]
                    }
                    tmp[y * n + cx * res + ph] = s
                }
            }
        }
        val out = FloatArray(n * n)
        for (cy in 0 until size) {
            for (ph in 0 until res) {
                val ty = cy * res + ph
                val ww = w[ph]
                val b = cy + baseRel[ph]
                for (k in 0..3) {
                    val j = b + k
                    if (j < 0 || j >= size) continue
                    val wk = ww[k]
                    if (wk == 0f) continue
                    val src = j * n
                    val dst = ty * n
                    for (tx in 0 until n) out[dst + tx] += tmp[src + tx] * wk
                }
            }
        }
        return out
    }

    private fun bs(t0: Float): Float {
        val t = kotlin.math.abs(t0)
        if (t < 1f) return (4f - 6f * t * t + 3f * t * t * t) / 6f
        if (t < 2f) {
            val u = 2f - t
            return u * u * u / 6f
        }
        return 0f
    }

    private fun boxBlur(a: FloatArray, size: Int, r: Int) {
        val tmp = FloatArray(a.size)
        val inv = 1f / (2 * r + 1)
        val last = size - 1
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                var s = 0f
                for (k in -r..r) {
                    val xx = x + k
                    s += a[row + (if (xx < 0) 0 else if (xx > last) last else xx)]
                }
                tmp[row + x] = s * inv
            }
        }
        for (y in 0 until size) {
            for (x in 0 until size) {
                var s = 0f
                for (k in -r..r) {
                    val yy = y + k
                    s += tmp[(if (yy < 0) 0 else if (yy > last) last else yy) * size + x]
                }
                a[y * size + x] = s * inv
            }
        }
    }

    private fun wallVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, t: Float) {
        if (wallSize + 6 > wallData.size) wallData = wallData.copyOf(wallData.size * 2)
        wallData[wallSize++] = x
        wallData[wallSize++] = y
        wallData[wallSize++] = z
        wallData[wallSize++] = nx
        wallData[wallSize++] = ny
        wallData[wallSize++] = t
    }

    /** One wall quad from the surface rim at a and b down to the desk; normal points outward. */
    private fun wallSegment(ax: Float, ay: Float, bx: Float, by: Float, nx: Float, ny: Float) {
        val az = heightAt(ax, ay) + 0.08f
        val bz = heightAt(bx, by) + 0.08f
        val deskZ = -Board.SLAB
        wallVertex(ax, ay, az, nx, ny, 0f)
        wallVertex(bx, by, bz, nx, ny, 0f)
        wallVertex(bx, by, deskZ, nx, ny, 1f)
        wallVertex(ax, ay, az, nx, ny, 0f)
        wallVertex(bx, by, deskZ, nx, ny, 1f)
        wallVertex(ax, ay, deskZ, nx, ny, 1f)
    }

    /** Marching squares on the smoothed edge field → side walls from the rim down to the desk. */
    private fun buildWalls(field: FloatArray): FloatArray {
        val iso = 0.48f
        val inv = 1f / Board.RES
        val e = FloatArray(8)
        fun interp(ax: Float, ay: Float, av: Float, bx: Float, by: Float, bv: Float, o: Int) {
            val t = ((iso - av) / (bv - av)).coerceIn(0f, 1f)
            e[o] = ax + (bx - ax) * t
            e[o + 1] = ay + (by - ay) * t
        }
        for (ty in 0 until n - 1) {
            for (tx in 0 until n - 1) {
                val v00 = field[ty * n + tx]
                val v10 = field[ty * n + tx + 1]
                val v01 = field[(ty + 1) * n + tx]
                val v11 = field[(ty + 1) * n + tx + 1]
                var case = 0
                if (v00 > iso) case = case or 1
                if (v10 > iso) case = case or 2
                if (v11 > iso) case = case or 4
                if (v01 > iso) case = case or 8
                if (case == 0 || case == 15) continue
                val x0 = (tx + 0.5f) * inv
                val y0 = (ty + 0.5f) * inv
                val x1 = x0 + inv
                val y1 = y0 + inv
                // edge points: bottom (00-10), right (10-11), top (01-11), left (00-01)
                interp(x0, y0, v00, x1, y0, v10, 0)
                interp(x1, y0, v10, x1, y1, v11, 2)
                interp(x0, y1, v01, x1, y1, v11, 4)
                interp(x0, y0, v00, x0, y1, v01, 6)
                // gradient points inward (toward higher coverage); the wall faces the other way
                val gx = (v10 - v00 + v11 - v01) * 0.5f
                val gy = (v01 - v00 + v11 - v10) * 0.5f
                val gl = max(1e-5f, sqrt(gx * gx + gy * gy))
                val nx = -gx / gl
                val ny = -gy / gl
                when (case) {
                    1, 14 -> wallSegment(e[0], e[1], e[6], e[7], nx, ny)
                    2, 13 -> wallSegment(e[0], e[1], e[2], e[3], nx, ny)
                    3, 12 -> wallSegment(e[2], e[3], e[6], e[7], nx, ny)
                    4, 11 -> wallSegment(e[2], e[3], e[4], e[5], nx, ny)
                    6, 9 -> wallSegment(e[0], e[1], e[4], e[5], nx, ny)
                    7, 8 -> wallSegment(e[4], e[5], e[6], e[7], nx, ny)
                    5 -> {
                        wallSegment(e[0], e[1], e[2], e[3], nx, ny)
                        wallSegment(e[4], e[5], e[6], e[7], nx, ny)
                    }
                    10 -> {
                        wallSegment(e[0], e[1], e[6], e[7], nx, ny)
                        wallSegment(e[2], e[3], e[4], e[5], nx, ny)
                    }
                }
            }
        }
        val out = wallData.copyOf(wallSize)
        wallData = FloatArray(0)
        return out
    }
}
