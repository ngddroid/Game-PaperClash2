package com.ngstudio.paperclash2.engine.level

import com.ngstudio.paperclash2.engine.core.Rng

/** World (chapter) layout: 100 worlds of 50 levels, cycling through 10 desk themes. */
object Worlds {
    const val LEVELS_PER_WORLD = 50
    const val COUNT = 100
    const val TOTAL_LEVELS = LEVELS_PER_WORLD * COUNT
    const val THEMES = 10

    val themeNames = listOf(
        "Craft Desk", "Kraft Studio", "Notebook Lane", "Blueprint Bay", "Corkboard Park",
        "Watercolor Isle", "Sketchbook Hills", "Gift Wrap Plaza", "Origami Night", "Gold Foil Palace",
    )

    fun worldOf(level: Int): Int = (level - 1) / LEVELS_PER_WORLD + 1
    fun themeOf(world: Int): Int = (world - 1) % THEMES
    fun lapOf(world: Int): Int = (world - 1) / THEMES
    fun firstLevel(world: Int): Int = (world - 1) * LEVELS_PER_WORLD + 1

    fun name(world: Int): String {
        val base = themeNames[themeOf(world)]
        val lap = lapOf(world)
        return if (lap == 0) base else "$base ${roman(lap + 1)}"
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(10, 9, 5, 4, 1)
        val s = arrayOf("X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }
}

/** Playful rival names (word lists, never real people). */
object RivalNames {
    private val first = listOf(
        "Origami", "Crayon", "Glitter", "Paper", "Scissor", "Sticky", "Doodle", "Confetti", "Velvet", "Marker",
        "Pencil", "Ribbon", "Washi", "Kraft", "Sketchy", "Inky", "Pastel", "Neon", "Folded", "Cardboard",
        "Glue", "Stencil", "Sparkle", "Bubble", "Comet", "Pixel", "Lucky", "Turbo", "Mellow", "Zippy",
    )
    private val second = listOf(
        "Otter", "Fox", "Ninja", "Panda", "Pilot", "Koala", "Gecko", "Moth", "Penguin", "Llama",
        "Raccoon", "Badger", "Owl", "Wizard", "Sparrow", "Bunny", "Turtle", "Captain", "Rocket", "Kitten",
        "Puffin", "Narwhal", "Hedgehog", "Dragon", "Yeti", "Robin", "Beetle", "Lemur", "Walrus", "Toucan",
    )

    fun pick(rng: Rng, used: MutableSet<String>): String {
        repeat(20) {
            val n = first[rng.nextInt(first.size)] + " " + second[rng.nextInt(second.size)]
            if (used.add(n)) return n
        }
        val n = first[rng.nextInt(first.size)] + " " + second[rng.nextInt(second.size)] + " " + (used.size + 2)
        used.add(n)
        return n
    }
}
