package com.ngstudio.paperclash2.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Colour helpers shared by the GL renderer and Compose (ARGB ints, floats 0..1). */
object Palette {
    /** Distinct coloured-paper tones for land; rivals pick from these. */
    val land: IntArray = intArrayOf(
        0xFFFF6B57.toInt(), 0xFF4BA3FF.toInt(), 0xFF2EC4A0.toInt(), 0xFFFFC83D.toInt(),
        0xFF7A5CFF.toInt(), 0xFFFF7AB6.toInt(), 0xFFFF9F43.toInt(), 0xFF1FB5C9.toInt(),
        0xFF9BD53C.toInt(), 0xFFD14D8C.toInt(), 0xFF3D63E0.toInt(), 0xFFB39DFF.toInt(),
        0xFFE53950.toInt(), 0xFF6FA83A.toInt(), 0xFFB0784F.toInt(), 0xFF5B7083.toInt(),
    )

    fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    fun b(c: Int) = (c and 0xFF) / 255f

    fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

    fun shade(c: Int, k: Float): Int = rgb(r(c) * k, g(c) * k, b(c) * k)

    fun mix(a: Int, b: Int, t: Float): Int =
        rgb(r(a) + (r(b) - r(a)) * t, g(a) + (g(b) - g(a)) * t, b(a) + (b(b) - b(a)) * t)

    /** Hue in degrees (0..360) of an ARGB colour. */
    fun hue(c: Int): Float {
        val rr = r(c)
        val gg = g(c)
        val bb = b(c)
        val mx = max(rr, max(gg, bb))
        val mn = min(rr, min(gg, bb))
        val d = mx - mn
        if (d < 1e-4f) return 0f
        val h = when (mx) {
            rr -> ((gg - bb) / d) % 6f
            gg -> (bb - rr) / d + 2f
            else -> (rr - gg) / d + 4f
        } * 60f
        return if (h < 0f) h + 360f else h
    }

    fun hueDistance(a: Int, b: Int): Float {
        val d = abs(hue(a) - hue(b))
        return min(d, 360f - d)
    }

    /** Rotates a colour's hue (used for the remixed desk palettes on later world laps). */
    fun hueShift(c: Int, deg: Float): Int {
        if (deg == 0f) return c
        val rr = r(c)
        val gg = g(c)
        val bb = b(c)
        val rad = Math.toRadians(deg.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()
        val k = 1f / 3f
        val sq = kotlin.math.sqrt(k)
        val m00 = cosA + (1 - cosA) * k
        val m01 = k * (1 - cosA) - sq * sinA
        val m02 = k * (1 - cosA) + sq * sinA
        val m10 = k * (1 - cosA) + sq * sinA
        val m11 = cosA + k * (1 - cosA)
        val m12 = k * (1 - cosA) - sq * sinA
        val m20 = k * (1 - cosA) - sq * sinA
        val m21 = k * (1 - cosA) + sq * sinA
        val m22 = cosA + k * (1 - cosA)
        return rgb(rr * m00 + gg * m01 + bb * m02, rr * m10 + gg * m11 + bb * m12, rr * m20 + gg * m21 + bb * m22)
    }

    /**
     * Land colours for a match: the player keeps their skin colour; every rival gets the most
     * distinct remaining palette tone so territories never look alike.
     */
    fun assignLand(humanColor: Int, rivals: Int): IntArray {
        val out = IntArray(rivals + 1)
        out[0] = humanColor
        val used = ArrayList<Int>()
        used += humanColor
        for (i in 1..rivals) {
            var best = land[0]
            var bestScore = -1f
            for (c in land) {
                if (c in used) continue
                var near = 360f
                for (u in used) near = min(near, hueDistance(c, u) + if (abs(lum(c) - lum(u)) > 0.18f) 25f else 0f)
                if (near > bestScore) {
                    bestScore = near
                    best = c
                }
            }
            out[i] = best
            used += best
        }
        return out
    }

    fun lum(c: Int) = 0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)
}

/** One desk theme (world look). Colours are shifted in hue on later laps. */
class DeskTheme(
    val index: Int,
    val pattern: Int,
    val deskA: Int,
    val deskB: Int,
    val ink: Int,
    val card: Int,
    val cardSide: Int,
    val props: List<String>,
    val sky: Int,
)

object DeskThemes {
    private fun c(hex: Long) = hex.toInt()

    private val base = listOf(
        DeskTheme(0, 0, c(0xFF2F8F6B), c(0xFF27795A), c(0xFF7FD3AE), c(0xFFFFFBF2), c(0xFFE9DCC4),
            listOf("scissors_big", "pencil_yellow", "paper_clip", "button_red", "tape_roll"), c(0xFF2A7D5E)),
        DeskTheme(1, 1, c(0xFFC89B6D), c(0xFFB5865A), c(0xFF8A5E37), c(0xFFFFF7EA), c(0xFFE4D2B6),
            listOf("twine_spool", "rubber_stamp", "kraft_tag", "wax_seal"), c(0xFFB18256)),
        DeskTheme(2, 2, c(0xFFF7F4EC), c(0xFFEDE8DB), c(0xFF9CC3E6), c(0xFFFFFFFF), c(0xFFE6E2D8),
            listOf("eraser_pink", "pen_blue", "sticky_note", "pencil_green"), c(0xFFDDD6C4)),
        DeskTheme(3, 3, c(0xFF1E5AA8), c(0xFF194C8F), c(0xFFA9CBF5), c(0xFFFDFDFB), c(0xFFDCE6F0),
            listOf("ruler_wood", "compass_tool", "set_square", "pencil_mech"), c(0xFF174787)),
        DeskTheme(4, 4, c(0xFFC79A63), c(0xFFB88A55), c(0xFF6E4A2A), c(0xFFFFF9EE), c(0xFFE7D7BD),
            listOf("push_pin_red", "push_pin_blue", "polaroid", "note_card"), c(0xFFA87A47)),
        DeskTheme(5, 5, c(0xFFF4F0F7), c(0xFFDCE9F5), c(0xFFF7C6D9), c(0xFFFFFFFF), c(0xFFE8E2EA),
            listOf("brush_round", "paint_pan", "water_cup"), c(0xFFD5DCE8)),
        DeskTheme(6, 6, c(0xFFF2EFE8), c(0xFFE4E0D6), c(0xFF6A6A72), c(0xFFFFFEFA), c(0xFFE2DED2),
            listOf("charcoal_stick", "sharpener", "eraser_kneaded"), c(0xFFCFCAC0)),
        DeskTheme(7, 7, c(0xFFE84A5F), c(0xFFD33E52), c(0xFFFFE07A), c(0xFFFFFBF5), c(0xFFF0DCD0),
            listOf("bow_red", "ribbon_spool", "gift_tag"), c(0xFFC93B4F)),
        DeskTheme(8, 8, c(0xFF1F2A4A), c(0xFF19223D), c(0xFFFFE9A8), c(0xFFF9F6EF), c(0xFFD9D4C8),
            listOf("crane_paper", "moon_paper", "star_paper"), c(0xFF161E36)),
        DeskTheme(9, 9, c(0xFFF6EEDB), c(0xFFEADFC6), c(0xFFD6AE3C), c(0xFFFFFDF7), c(0xFFEBDFC7),
            listOf("crown_paper", "gem_paper", "gold_seal"), c(0xFFE2D3B1)),
    )

    fun forWorld(theme: Int, lap: Int): DeskTheme {
        val t = base[theme.coerceIn(0, base.size - 1)]
        if (lap == 0) return t
        val shift = (lap * 37f) % 360f
        // Remix the desk colours on later laps; the paper card stays paper-white.
        return DeskTheme(
            t.index, t.pattern, Palette.hueShift(t.deskA, shift), Palette.hueShift(t.deskB, shift),
            Palette.hueShift(t.ink, shift), t.card, t.cardSide, t.props, Palette.hueShift(t.sky, shift),
        )
    }
}
