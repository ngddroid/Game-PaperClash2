package com.ngstudio.paperclash2.engine.ai

import com.ngstudio.paperclash2.engine.core.IntList
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.engine.world.World

/**
 * Breadth-first search over playable cells, shared by all brains on one board (single-threaded).
 * Crossing your own ribbon is safe in this game, so only walls block a path home.
 */
class PathFinder(private val world: World) {
    private val size = world.size
    private val parent = IntArray(size * size)
    private val seen = IntArray(size * size)
    private val queue = IntArray(size * size)
    private var gen = 0

    /** Cells from the first step to the target (inclusive), filled by [toOwnLand]. */
    val path = IntList(256)

    /** Shortest 4-connected path from [p]'s cell to its nearest own land cell; -1 if none in reach. */
    fun toOwnLand(p: Player, maxNodes: Int = 7000): Int {
        gen++
        var head = 0
        var tail = 0
        val start = world.idx(p.cellX, p.cellY)
        queue[tail++] = start
        seen[start] = gen
        parent[start] = -1
        var found = -1
        val own = world.owner
        val open = world.playable
        while (head < tail && head < maxNodes) {
            val i = queue[head++]
            if (i != start && own[i] == p.id) {
                found = i
                break
            }
            val x = i % size
            val y = i / size
            if (x > 0) { val j = i - 1; if (open[j] && seen[j] != gen) { seen[j] = gen; parent[j] = i; queue[tail++] = j } }
            if (x < size - 1) { val j = i + 1; if (open[j] && seen[j] != gen) { seen[j] = gen; parent[j] = i; queue[tail++] = j } }
            if (y > 0) { val j = i - size; if (open[j] && seen[j] != gen) { seen[j] = gen; parent[j] = i; queue[tail++] = j } }
            if (y < size - 1) { val j = i + size; if (open[j] && seen[j] != gen) { seen[j] = gen; parent[j] = i; queue[tail++] = j } }
        }
        path.clear()
        if (found < 0) return -1
        var c = found
        while (c != start && c >= 0) {
            path.add(c)
            c = parent[c]
        }
        // reverse into first-step-first order
        var a = 0
        var b = path.size - 1
        while (a < b) {
            val t = path[a]
            path[a] = path[b]
            path[b] = t
            a++
            b--
        }
        return path.size
    }

    /** Index of the farthest path cell (up to [maxAhead]) reachable in a straight line from [p]. */
    fun farthestVisible(p: Player, maxAhead: Int = 24): Int {
        var best = 0
        val limit = minOf(path.size - 1, maxAhead)
        for (k in limit downTo 0) {
            val c = path[k]
            if (clearLine(p.x, p.y, c % size + 0.5f, c / size + 0.5f)) {
                best = k
                break
            }
        }
        return best
    }

    /** True if the straight line stays on playable cells. */
    fun clearLine(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 0.5f) return true
        val steps = (len / 0.5f).toInt() + 1
        for (s in 1..steps) {
            val t = s / steps.toFloat()
            if (!world.isOpen(ax + dx * t, ay + dy * t)) return false
        }
        return true
    }
}
