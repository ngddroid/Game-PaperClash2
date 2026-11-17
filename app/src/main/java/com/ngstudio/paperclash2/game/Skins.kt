package com.ngstudio.paperclash2.game

/** Paper-plane models (low-poly meshes in [com.ngstudio.paperclash2.gl.PlaneMeshes]). */
enum class PlaneModel(val title: String) {
    DART("Dart"), GLIDER("Glider"), SWALLOW("Swallow"), HAWK("Hawk"), CRANE("Crane"), JET("Star Jet"),
}

/** Procedural surface patterns shared by planes and territory (ids match the shaders). */
object Pattern {
    const val SOLID = 0
    const val STRIPES = 1
    const val DOTS = 2
    const val CHECKER = 3
    const val ZIGZAG = 4
    const val STARS = 5
    const val WAVES = 6
    const val CONFETTI = 7
    const val FOIL = 8
    const val HOLO = 9
    const val GRID = 10
    const val CAMO = 11
}

enum class Rarity(val title: String) { COMMON("Common"), RARE("Rare"), EPIC("Epic"), LEGEND("Legend") }

/** How a skin is obtained besides buying it. */
enum class Unlock { FREE, COINS, STAR_ROAD, DAILY_GIFT, ALBUM, ACHIEVEMENT }

/**
 * One plane skin. [style] is the index used everywhere (save file, engine `Player.style`).
 * Colours are ARGB ints: [base] paints the plane and your land, [accent] the pattern.
 */
class Skin(
    val style: Int,
    val name: String,
    val model: PlaneModel,
    val base: Int,
    val accent: Int,
    val pattern: Int,
    val rarity: Rarity,
    val unlock: Unlock,
    val price: Int,
)

/** Ribbon (trail) styles; ids match the ribbon shader. */
class RibbonStyle(val id: Int, val name: String, val price: Int, val unlock: Unlock)

object Skins {
    private fun c(hex: Long) = hex.toInt()

    private val models = PlaneModel.entries

    /** 8 looks per model, varied in colour and pattern. */
    private val looks = arrayOf(
        // name suffix, base, accent, pattern, rarity
        arrayOf("Paper", c(0xFFFF6B57), c(0xFFFFFFFF), Pattern.SOLID, Rarity.COMMON),
        arrayOf("Sky", c(0xFF4BA3FF), c(0xFFFFFFFF), Pattern.STRIPES, Rarity.COMMON),
        arrayOf("Mint", c(0xFF2EC4A0), c(0xFFFFF6E6), Pattern.DOTS, Rarity.COMMON),
        arrayOf("Sunny", c(0xFFFFC83D), c(0xFFFF9F43), Pattern.ZIGZAG, Rarity.RARE),
        arrayOf("Grape", c(0xFF7A5CFF), c(0xFFFFD6F0), Pattern.STARS, Rarity.RARE),
        arrayOf("Bubblegum", c(0xFFFF7AB6), c(0xFFFFFFFF), Pattern.CHECKER, Rarity.RARE),
        arrayOf("Gold Foil", c(0xFFE8B931), c(0xFFFFF3B0), Pattern.FOIL, Rarity.EPIC),
        arrayOf("Holo", c(0xFF9EC9FF), c(0xFFFFB3E6), Pattern.HOLO, Rarity.LEGEND),
    )

    val all: List<Skin> = buildList {
        var style = 0
        for (m in models) {
            for ((i, look) in looks.withIndex()) {
                val rarity = look[4] as Rarity
                val unlock = when {
                    style == 0 || style == 9 -> Unlock.FREE
                    i == 7 && m.ordinal % 2 == 0 -> Unlock.STAR_ROAD
                    i == 6 && m.ordinal == 1 -> Unlock.DAILY_GIFT
                    i == 7 && m.ordinal % 2 == 1 -> Unlock.ALBUM
                    i == 6 && m.ordinal == 3 -> Unlock.ACHIEVEMENT
                    else -> Unlock.COINS
                }
                val price = when (rarity) {
                    Rarity.COMMON -> 300 + 100 * m.ordinal
                    Rarity.RARE -> 1200 + 250 * m.ordinal
                    Rarity.EPIC -> 3000 + 500 * m.ordinal
                    Rarity.LEGEND -> 6000
                }
                add(Skin(style, "${look[0]} ${m.title}", m, look[1] as Int, look[2] as Int, look[3] as Int, rarity, unlock, price))
                style++
            }
        }
    }

    fun get(style: Int): Skin = all[((style % all.size) + all.size) % all.size]

    val ribbons: List<RibbonStyle> = listOf(
        RibbonStyle(0, "Paper Ribbon", 0, Unlock.FREE),
        RibbonStyle(1, "Washi Stripes", 400, Unlock.COINS),
        RibbonStyle(2, "Washi Dots", 400, Unlock.COINS),
        RibbonStyle(3, "Stitched", 800, Unlock.COINS),
        RibbonStyle(4, "Crayon", 800, Unlock.COINS),
        RibbonStyle(5, "Rainbow", 1500, Unlock.COINS),
        RibbonStyle(6, "Glitter", 2000, Unlock.COINS),
        RibbonStyle(7, "Neon", 2500, Unlock.STAR_ROAD),
        RibbonStyle(8, "Zigzag", 1000, Unlock.COINS),
        RibbonStyle(9, "Dashed", 600, Unlock.COINS),
        RibbonStyle(10, "Chevrons", 1200, Unlock.ACHIEVEMENT),
        RibbonStyle(11, "Candy Stripe", 1800, Unlock.DAILY_GIFT),
    )
}
