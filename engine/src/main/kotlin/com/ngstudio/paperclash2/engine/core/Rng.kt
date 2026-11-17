package com.ngstudio.paperclash2.engine.core

/**
 * Deterministic SplitMix64 generator. Levels, bots and simulations all draw from seeded instances
 * so the same level number always builds the same board on every device and in every test.
 */
class Rng(seed: Long) {
    private var state: Long = seed xor 0x2545F4914F6CDD1DL

    fun nextLong(): Long {
        state += -0x61C8864680B583EBL
        var z = state
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        return z xor (z ushr 31)
    }

    fun nextInt(): Int = (nextLong() ushr 32).toInt()

    /** Uniform integer in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 33) % bound).toInt()
    }

    /** Uniform integer in [from, until). */
    fun nextInt(from: Int, until: Int): Int = if (until <= from) from else from + nextInt(until - from)

    /** Uniform float in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / 16777216f

    fun range(a: Float, b: Float): Float = a + (b - a) * nextFloat()

    fun chance(p: Float): Boolean = nextFloat() < p

    fun sign(): Float = if (nextLong() < 0L) -1f else 1f

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    /** An independent stream derived from this one, so adding draws here never shifts the other. */
    fun fork(salt: Long): Rng = Rng(nextLong() xor (salt * -0x61C8864680B583EBL))

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = list[i]
            list[i] = list[j]
            list[j] = t
        }
    }

    companion object {
        /** Stateless hash of two ints to a float in [0, 1): used for per-level jitter. */
        fun hash01(a: Int, b: Int): Float {
            var h = a.toLong() * 0x9E3779B1L + b.toLong() * 0x85EBCA77L
            h = h xor (h ushr 15)
            h *= 0x2C1B3C6DL
            h = h xor (h ushr 12)
            h *= 0x297A2D39L
            h = h xor (h ushr 15)
            return ((h ushr 8) and 0xFFFFFF).toFloat() / 16777216f
        }
    }
}
