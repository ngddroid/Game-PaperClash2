package com.ngstudio.paperclash2.engine.core

/** Growable int list without boxing; the game loop reuses these instead of allocating. */
class IntList(capacity: Int = 16) {
    var data = IntArray(maxOf(4, capacity))
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    operator fun set(i: Int, v: Int) {
        data[i] = v
    }

    fun clear() {
        size = 0
    }

    fun isEmpty() = size == 0
    fun isNotEmpty() = size > 0
    fun last(): Int = data[size - 1]

    fun contains(v: Int): Boolean {
        for (i in 0 until size) if (data[i] == v) return true
        return false
    }

    fun toIntArray(): IntArray = data.copyOf(size)

    fun addAll(other: IntList) {
        for (i in 0 until other.size) add(other.data[i])
    }
}

/** Growable float list (x, y pairs for ribbon points) without boxing. */
class FloatList(capacity: Int = 32) {
    var data = FloatArray(maxOf(4, capacity))
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(maxOf(size + 2, size * 2))
        data[size++] = x
        data[size++] = y
    }

    operator fun get(i: Int): Float = data[i]

    operator fun set(i: Int, v: Float) {
        data[i] = v
    }

    fun clear() {
        size = 0
    }

    fun isEmpty() = size == 0
    fun isNotEmpty() = size > 0
}
