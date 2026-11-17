package com.ngstudio.paperclash2.engine.shape

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ShapeCategory(val title: String) {
    GEOMETRY("Geometry"),
    NATURE("Nature"),
    ANIMALS("Animals"),
    OBJECTS("Objects"),
    SPACE("Space"),
}

/**
 * One arena silhouette. [tier] 0 = compact and easy, 1 = some arms or narrow parts, 2 = complex
 * (holes, thin limbs). [upright] shapes only get small tilts so a cat never ends up upside down.
 */
class ShapeDef(
    val id: String,
    val name: String,
    val category: ShapeCategory,
    val tier: Int,
    val upright: Boolean,
    val sdf: Sdf,
)

/** Every arena shape in the game, authored inside roughly [-1, 1] with y up. */
object ShapeLibrary {
    private fun def(id: String, name: String, cat: ShapeCategory, tier: Int, upright: Boolean, sdf: Sdf) =
        ShapeDef(id, name, cat, tier, upright, sdf)

    private fun rays(n: Int, from: Float, to: Float, r: Float, rotDeg: Float = 0f): Array<Sdf> = Array(n) { i ->
        val a = rotDeg * PI / 180.0 + i * 2.0 * PI / n
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        S.capsule(c * from, s * from, c * to, s * to, r)
    }

    private fun petals(n: Int, dist: Float, r: Float, rotDeg: Float): Array<Sdf> = Array(n) { i ->
        val a = rotDeg * PI / 180.0 + i * 2.0 * PI / n
        S.circle((cos(a) * dist).toFloat(), (sin(a) * dist).toFloat(), r)
    }

    private val G = ShapeCategory.GEOMETRY
    private val N = ShapeCategory.NATURE
    private val A = ShapeCategory.ANIMALS
    private val O = ShapeCategory.OBJECTS
    private val P = ShapeCategory.SPACE

    val all: List<ShapeDef> = listOf(
        // ---------------------------------------------------------------- geometry
        def("circle", "Circle", G, 0, false, S.circle(0f, 0f, 1f)),
        def("oval", "Oval", G, 0, false, S.ellipse(0f, 0f, 1f, 0.68f)),
        def("tile", "Paper Tile", G, 0, false, S.box(0f, 0f, 0.9f, 0.9f, 0.28f)),
        def("diamond", "Diamond", G, 1, false, S.polygon(0f, 1f, 0.78f, 0f, 0f, -1f, -0.78f, 0f)),
        def("triangle", "Triangle", G, 1, false, S.regular(3, 0f, -0.2f, 1.1f, 90f)),
        def("pentagon", "Pentagon", G, 0, false, S.regular(5, 0f, -0.05f, 1f, 90f)),
        def("hexagon", "Hexagon", G, 0, false, S.regular(6, 0f, 0f, 1f, 0f)),
        def("octagon", "Octagon", G, 0, false, S.regular(8, 0f, 0f, 1f, 22.5f)),
        def("star4", "Twinkle", G, 1, false, S.star(4, 0f, 0f, 1f, 0.48f, 90f)),
        def("star5", "Star", G, 1, false, S.star(5, 0f, -0.08f, 1f, 0.52f, 90f)),
        def("star6", "Hexa Star", G, 0, false, S.star(6, 0f, 0f, 1f, 0.6f, 90f)),
        def("plus", "Plus", G, 1, false, S.union(S.box(0f, 0f, 0.95f, 0.33f, 0.1f), S.box(0f, 0f, 0.33f, 0.95f, 0.1f))),
        def("ring", "Donut", G, 2, false, S.ring(0f, 0f, 0.68f, 0.32f)),
        def("capsule", "Capsule", G, 0, false, S.capsule(-0.55f, 0f, 0.55f, 0f, 0.45f)),
        def("trapezoid", "Trapezoid", G, 1, false, S.polygon(-1f, -0.6f, 1f, -0.6f, 0.55f, 0.6f, -0.55f, 0.6f)),
        def(
            "arch", "Arch", G, 2, true,
            S.subtract(
                S.union(S.box(0f, -0.35f, 0.9f, 0.55f), S.circle(0f, 0.2f, 0.9f)),
                S.union(S.box(0f, -0.62f, 0.36f, 0.4f), S.circle(0f, -0.22f, 0.36f)),
            ),
        ),
        def(
            "hourglass", "Hourglass", G, 1, false,
            S.smooth(
                0.15f,
                S.polygon(-0.8f, 1f, 0.8f, 1f, 0.2f, 0f, -0.2f, 0f),
                S.polygon(-0.8f, -1f, 0.8f, -1f, 0.2f, 0f, -0.2f, 0f),
            ),
        ),
        def(
            "infinity", "Infinity", G, 1, false,
            S.subtract(
                S.smooth(0.3f, S.circle(-0.52f, 0f, 0.5f), S.circle(0.52f, 0f, 0.5f)),
                S.union(S.circle(-0.55f, 0f, 0.17f), S.circle(0.55f, 0f, 0.17f)),
            ),
        ),
        def("blob", "Blob", G, 0, false, S.blob(0f, 0f, 0.9f, 0.12f, 3, 0.07f, 5, 0.7f)),
        def(
            "bean", "Bean", G, 0, false,
            S.subtract(
                S.smooth(0.35f, S.circle(-0.42f, 0.05f, 0.58f), S.circle(0.45f, -0.05f, 0.52f)),
                S.circle(0.05f, 0.85f, 0.42f),
            ),
        ),

        // ---------------------------------------------------------------- nature
        def("heart", "Heart", N, 0, true, S.heart(0f, 0f, 1.65f)),
        def(
            "cloud", "Cloud", N, 0, true,
            S.smooth(
                0.12f,
                S.circle(-0.58f, -0.12f, 0.42f), S.circle(-0.12f, 0.18f, 0.55f), S.circle(0.45f, 0.08f, 0.5f),
                S.circle(0.78f, -0.2f, 0.32f), S.box(0.05f, -0.35f, 0.75f, 0.2f, 0.2f),
            ),
        ),
        def("crescent", "Crescent Moon", N, 1, false, S.subtract(S.circle(0f, 0f, 1f), S.circle(0.42f, 0.28f, 0.82f))),
        def("sun", "Sun", N, 1, false, S.smooth(0.1f, S.circle(0f, 0f, 0.6f), *rays(8, 0.5f, 0.9f, 0.15f))),
        def("flower", "Flower", N, 0, false, S.union(S.circle(0f, 0f, 0.42f), *petals(5, 0.55f, 0.42f, 90f))),
        def(
            "clover", "Lucky Clover", N, 1, true,
            S.union(*petals(4, 0.42f, 0.42f, 45f), S.capsule(0f, 0f, 0.35f, -0.98f, 0.13f)),
        ),
        def(
            "leaf", "Leaf", N, 1, false,
            S.rotate(S.intersect(S.circle(-0.45f, 0f, 1.05f), S.circle(0.45f, 0f, 1.05f)), 35f),
        ),
        def(
            "raindrop", "Raindrop", N, 1, true,
            S.smooth(0.12f, S.circle(0f, -0.3f, 0.66f), S.polygon(-0.45f, -0.05f, 0.45f, -0.05f, 0f, 1f)),
        ),
        def(
            "snowflake", "Snowflake", N, 2, false,
            S.union(S.regular(6, 0f, 0f, 0.45f, 0f), *rays(6, 0f, 0.82f, 0.17f, 90f), *petals(6, 0.82f, 0.2f, 90f)),
        ),
        def(
            "pine", "Pine Tree", N, 2, true,
            S.union(
                S.polygon(-0.95f, -0.45f, 0.95f, -0.45f, 0f, 0.2f),
                S.polygon(-0.75f, -0.05f, 0.75f, -0.05f, 0f, 0.6f),
                S.polygon(-0.55f, 0.35f, 0.55f, 0.35f, 0f, 1f),
                S.box(0f, -0.72f, 0.18f, 0.3f),
            ),
        ),
        def(
            "mushroom", "Mushroom", N, 1, true,
            S.union(S.intersect(S.circle(0f, -0.1f, 1f), S.halfPlane(0f, -1f, 0f)), S.box(0f, -0.45f, 0.3f, 0.48f, 0.12f)),
        ),
        def(
            "apple", "Apple", N, 0, true,
            S.smooth(
                0.15f,
                S.circle(-0.32f, -0.08f, 0.62f), S.circle(0.32f, -0.08f, 0.62f),
                S.rotate(S.ellipse(0.32f, 0.72f, 0.3f, 0.15f), -25f, 0.32f, 0.72f),
            ),
        ),
        def(
            "cactus", "Cactus", N, 2, true,
            S.smooth(
                0.08f,
                S.capsule(0f, -0.95f, 0f, 0.72f, 0.3f),
                S.capsule(-0.28f, -0.1f, -0.68f, -0.1f, 0.19f), S.capsule(-0.68f, -0.1f, -0.68f, 0.45f, 0.19f),
                S.capsule(0.28f, 0.15f, 0.66f, 0.15f, 0.19f), S.capsule(0.66f, 0.15f, 0.66f, 0.62f, 0.19f),
            ),
        ),
        def("bolt", "Lightning", N, 2, false, S.polygon(0.18f, 1f, -0.62f, -0.08f, -0.06f, -0.08f, -0.3f, -1f, 0.62f, 0.14f, 0.06f, 0.14f)),

        // ---------------------------------------------------------------- animals
        def(
            "cat", "Cat", A, 1, true,
            S.smooth(0.1f, S.circle(0f, -0.12f, 0.8f), S.mirrorX(S.polygon(0.22f, 0.5f, 0.78f, 0.25f, 0.7f, 1.02f))),
        ),
        def(
            "bunny", "Bunny", A, 1, true,
            S.smooth(0.1f, S.circle(0f, -0.38f, 0.62f), S.mirrorX(S.capsule(0.26f, 0.05f, 0.36f, 0.95f, 0.19f))),
        ),
        def("bear", "Bear", A, 0, true, S.smooth(0.1f, S.circle(0f, -0.1f, 0.78f), S.mirrorX(S.circle(0.58f, 0.55f, 0.3f)))),
        def(
            "fox", "Fox", A, 1, true,
            S.smooth(
                0.06f,
                S.polygon(-0.95f, 0.3f, -0.3f, -0.55f, 0f, -0.9f, 0.3f, -0.55f, 0.95f, 0.3f),
                S.mirrorX(S.polygon(0.25f, 0.25f, 0.92f, 0.25f, 0.82f, 1f)),
            ),
        ),
        def(
            "fish", "Fish", A, 1, true,
            S.smooth(0.12f, S.ellipse(-0.18f, 0f, 0.78f, 0.52f), S.polygon(0.45f, 0f, 1.02f, 0.55f, 1.02f, -0.55f)),
        ),
        def(
            "whale", "Whale", A, 1, true,
            S.smooth(
                0.12f,
                S.ellipse(-0.18f, -0.12f, 0.82f, 0.55f), S.capsule(0.5f, 0f, 0.78f, 0.36f, 0.17f),
                S.rotate(S.ellipse(0.82f, 0.5f, 0.32f, 0.13f), 20f, 0.82f, 0.5f),
            ),
        ),
        def(
            "turtle", "Turtle", A, 1, true,
            S.smooth(
                0.12f,
                S.ellipse(-0.05f, 0f, 0.72f, 0.58f), S.circle(0.85f, 0.05f, 0.27f),
                S.circle(-0.5f, 0.55f, 0.2f), S.circle(0.4f, 0.55f, 0.2f), S.circle(-0.5f, -0.55f, 0.2f), S.circle(0.4f, -0.55f, 0.2f),
            ),
        ),
        def(
            "bird", "Bird", A, 1, true,
            S.smooth(
                0.12f,
                S.circle(-0.1f, -0.12f, 0.62f), S.circle(0.45f, 0.42f, 0.36f),
                S.polygon(0.75f, 0.52f, 1.05f, 0.4f, 0.75f, 0.28f), S.polygon(-0.55f, -0.05f, -1.02f, 0.35f, -0.95f, -0.32f),
            ),
        ),
        def(
            "butterfly", "Butterfly", A, 2, true,
            S.smooth(
                0.08f,
                S.mirrorX(S.ellipse(0.5f, 0.35f, 0.48f, 0.42f)), S.mirrorX(S.ellipse(0.42f, -0.42f, 0.38f, 0.32f)),
                S.capsule(0f, -0.75f, 0f, 0.62f, 0.13f),
            ),
        ),
        def(
            "owl", "Owl", A, 1, true,
            S.smooth(0.1f, S.ellipse(0f, -0.15f, 0.7f, 0.85f), S.mirrorX(S.polygon(0.2f, 0.5f, 0.68f, 0.42f, 0.62f, 0.98f))),
        ),
        def(
            "penguin", "Penguin", A, 1, true,
            S.smooth(
                0.08f,
                S.ellipse(0f, -0.05f, 0.58f, 0.9f),
                S.mirrorX(S.rotate(S.ellipse(0.6f, -0.1f, 0.18f, 0.42f), -15f, 0.6f, -0.1f)),
                S.mirrorX(S.ellipse(0.24f, -0.95f, 0.25f, 0.12f)),
            ),
        ),
        def(
            "dino", "Dino", A, 2, true,
            S.smooth(
                0.12f,
                S.ellipse(-0.05f, -0.22f, 0.62f, 0.42f), S.capsule(0.35f, -0.05f, 0.58f, 0.55f, 0.19f),
                S.ellipse(0.72f, 0.68f, 0.3f, 0.2f), S.polygon(-0.5f, -0.02f, -1.05f, -0.4f, -0.45f, -0.45f),
                S.box(-0.28f, -0.68f, 0.12f, 0.22f), S.box(0.22f, -0.68f, 0.12f, 0.22f),
            ),
        ),
        def(
            "crab", "Crab", A, 2, true,
            S.smooth(
                0.1f,
                S.ellipse(0f, -0.15f, 0.62f, 0.42f), S.mirrorX(S.capsule(0.42f, 0.1f, 0.7f, 0.42f, 0.14f)),
                S.mirrorX(S.circle(0.75f, 0.55f, 0.25f)), S.mirrorX(S.capsule(0.5f, -0.35f, 0.85f, -0.6f, 0.12f)),
            ),
        ),
        def(
            "snail", "Snail", A, 2, true,
            S.smooth(
                0.1f,
                S.circle(-0.12f, 0.15f, 0.6f), S.capsule(-0.85f, -0.52f, 0.82f, -0.52f, 0.22f),
                S.capsule(0.72f, -0.5f, 0.88f, 0.05f, 0.17f),
            ),
        ),
        def(
            "pig", "Piggy", A, 0, true,
            S.smooth(0.08f, S.circle(0f, -0.05f, 0.78f), S.mirrorX(S.polygon(0.28f, 0.55f, 0.8f, 0.4f, 0.78f, 0.95f))),
        ),
        def(
            "octopus", "Octopus", A, 2, true,
            S.smooth(
                0.12f,
                S.ellipse(0f, 0.3f, 0.66f, 0.62f),
                S.mirrorX(S.capsule(0.42f, -0.1f, 0.78f, -0.92f, 0.16f)),
                S.mirrorX(S.capsule(0.14f, -0.15f, 0.26f, -1f, 0.16f)),
            ),
        ),

        // ---------------------------------------------------------------- objects
        def(
            "house", "House", O, 1, true,
            S.union(S.box(0f, -0.38f, 0.72f, 0.6f), S.polygon(-0.98f, 0.2f, 0.98f, 0.2f, 0f, 1f), S.box(0.48f, 0.55f, 0.13f, 0.3f)),
        ),
        def(
            "rocket", "Rocket", O, 1, true,
            S.smooth(
                0.06f,
                S.capsule(0f, -0.55f, 0f, 0.4f, 0.38f), S.polygon(-0.36f, 0.38f, 0.36f, 0.38f, 0f, 1.05f),
                S.mirrorX(S.polygon(0.3f, -0.2f, 0.78f, -0.85f, 0.3f, -0.75f)),
            ),
        ),
        def(
            "crown", "Crown", O, 2, true,
            S.smooth(
                0.05f,
                S.box(0f, -0.45f, 0.85f, 0.35f, 0.06f),
                S.polygon(-0.85f, -0.2f, -0.38f, -0.12f, -0.85f, 0.72f),
                S.polygon(-0.45f, -0.2f, 0.45f, -0.2f, 0f, 0.9f),
                S.polygon(0.38f, -0.12f, 0.85f, -0.2f, 0.85f, 0.72f),
                S.circle(-0.85f, 0.72f, 0.15f), S.circle(0f, 0.9f, 0.15f), S.circle(0.85f, 0.72f, 0.15f),
            ),
        ),
        def(
            "key", "Key", O, 2, false,
            S.union(
                S.subtract(S.circle(-0.55f, 0.2f, 0.45f), S.circle(-0.55f, 0.2f, 0.17f)),
                S.box(0.28f, 0.2f, 0.62f, 0.16f), S.box(0.58f, -0.06f, 0.09f, 0.2f), S.box(0.82f, -0.06f, 0.09f, 0.2f),
            ),
        ),
        def(
            "umbrella", "Umbrella", O, 2, true,
            S.union(
                S.subtract(
                    S.intersect(S.circle(0f, -0.15f, 1f), S.halfPlane(0f, -1f, -0.1f)),
                    S.union(S.circle(-0.6f, 0f, 0.2f), S.circle(0f, 0f, 0.2f), S.circle(0.6f, 0f, 0.2f)),
                ),
                S.capsule(0f, 0.3f, 0f, -0.82f, 0.12f), S.capsule(0f, -0.85f, 0.32f, -0.85f, 0.12f),
                S.capsule(0.32f, -0.85f, 0.32f, -0.62f, 0.12f),
            ),
        ),
        def(
            "bulb", "Light Bulb", O, 1, true,
            S.smooth(0.1f, S.circle(0f, 0.25f, 0.68f), S.box(0f, -0.45f, 0.32f, 0.35f, 0.08f), S.box(0f, -0.86f, 0.24f, 0.12f, 0.06f)),
        ),
        def("cup", "Mug", O, 1, true, S.union(S.box(-0.18f, -0.1f, 0.6f, 0.75f, 0.14f), S.ring(0.5f, 0f, 0.33f, 0.11f))),
        def(
            "icecream", "Ice Cream", O, 1, true,
            S.smooth(
                0.08f,
                S.polygon(-0.52f, 0.02f, 0f, -1.05f, 0.52f, 0.02f),
                S.circle(0f, 0.38f, 0.56f), S.circle(-0.38f, 0.12f, 0.32f), S.circle(0.38f, 0.12f, 0.32f),
            ),
        ),
        def(
            "cupcake", "Cupcake", O, 1, true,
            S.smooth(
                0.08f,
                S.polygon(-0.62f, -0.95f, 0.62f, -0.95f, 0.78f, -0.1f, -0.78f, -0.1f),
                S.circle(-0.45f, 0.05f, 0.38f), S.circle(0.45f, 0.05f, 0.38f), S.circle(0f, 0.3f, 0.5f), S.circle(0f, 0.78f, 0.24f),
            ),
        ),
        def(
            "gift", "Gift Box", O, 1, true,
            S.union(
                S.box(0f, -0.22f, 0.8f, 0.7f, 0.06f),
                S.smooth(
                    0.1f,
                    S.rotate(S.ellipse(-0.32f, 0.66f, 0.34f, 0.2f), 20f, -0.32f, 0.66f),
                    S.rotate(S.ellipse(0.32f, 0.66f, 0.34f, 0.2f), -20f, 0.32f, 0.66f),
                    S.circle(0f, 0.58f, 0.16f),
                ),
            ),
        ),
        def(
            "bell", "Bell", O, 1, true,
            S.smooth(0.1f, S.ellipse(0f, 0.12f, 0.6f, 0.72f), S.box(0f, -0.48f, 0.88f, 0.17f, 0.1f), S.circle(0f, -0.78f, 0.2f), S.circle(0f, 0.9f, 0.15f)),
        ),
        def(
            "anchor", "Anchor", O, 2, true,
            S.union(
                S.capsule(0f, -0.72f, 0f, 0.62f, 0.15f), S.circle(0f, 0.78f, 0.22f),
                S.capsule(-0.45f, 0.38f, 0.45f, 0.38f, 0.13f),
                S.intersect(S.ring(0f, -0.12f, 0.72f, 0.15f), S.halfPlane(0f, 1f, -0.35f)),
                S.mirrorX(S.polygon(0.5f, -0.3f, 0.95f, -0.12f, 0.82f, -0.52f)),
            ),
        ),
        def(
            "guitar", "Guitar", O, 2, true,
            S.smooth(
                0.08f,
                S.circle(0f, -0.45f, 0.5f), S.circle(0f, 0.05f, 0.38f), S.box(0f, 0.62f, 0.13f, 0.42f), S.box(0f, 1f, 0.2f, 0.13f, 0.04f),
            ),
        ),
        def(
            "note", "Music Note", O, 2, true,
            S.union(
                S.rotate(S.ellipse(-0.28f, -0.6f, 0.42f, 0.3f), -20f, -0.28f, -0.6f),
                S.box(0.1f, 0.12f, 0.12f, 0.78f), S.polygon(0f, 0.9f, 0f, 0.52f, 0.68f, 0.15f, 0.75f, 0.42f),
            ),
        ),
        def(
            "gamepad", "Gamepad", O, 0, false,
            S.smooth(0.15f, S.capsule(-0.5f, 0.05f, 0.5f, 0.05f, 0.42f), S.mirrorX(S.circle(0.6f, -0.3f, 0.36f))),
        ),
        def(
            "trophy", "Trophy", O, 1, true,
            S.smooth(
                0.06f,
                S.polygon(-0.72f, 0.9f, -0.42f, 0.05f, 0.42f, 0.05f, 0.72f, 0.9f),
                S.box(0f, -0.28f, 0.13f, 0.36f), S.box(0f, -0.76f, 0.5f, 0.15f, 0.06f),
            ),
        ),
        def("shield", "Shield", O, 0, true, S.polygon(-0.85f, 0.9f, -0.85f, 0.05f, 0f, -1f, 0.85f, 0.05f, 0.85f, 0.9f)),

        // ---------------------------------------------------------------- space
        def(
            "planet", "Ringed Planet", P, 1, false,
            S.union(S.circle(0f, 0f, 0.6f), S.rotate(S.subtract(S.ellipse(0f, 0f, 1.05f, 0.42f), S.ellipse(0f, 0f, 0.76f, 0.2f)), -18f)),
        ),
        def(
            "ufo", "UFO", P, 1, true,
            S.smooth(0.1f, S.intersect(S.circle(0f, 0.12f, 0.45f), S.halfPlane(0f, -1f, -0.05f)), S.ellipse(0f, 0f, 1f, 0.32f)),
        ),
        def(
            "comet", "Comet", P, 1, false,
            S.smooth(0.2f, S.circle(0.42f, 0.35f, 0.42f), S.polygon(0.12f, 0.62f, -1f, -0.62f, 0.68f, 0.02f)),
        ),
        def("starburst", "Starburst", P, 0, false, S.star(12, 0f, 0f, 1f, 0.74f, 0f)),
        def(
            "satellite", "Satellite", P, 2, false,
            S.union(S.box(0f, 0f, 0.3f, 0.32f, 0.06f), S.box(-0.72f, 0f, 0.34f, 0.22f, 0.04f), S.box(0.72f, 0f, 0.34f, 0.22f, 0.04f), S.box(0f, 0f, 0.6f, 0.11f)),
        ),
    )

    private val byIdMap = all.associateBy { it.id }

    fun byId(id: String): ShapeDef = byIdMap[id] ?: all[0]

    fun ids(maxTier: Int): List<String> = all.filter { it.tier <= maxTier }.map { it.id }
}
