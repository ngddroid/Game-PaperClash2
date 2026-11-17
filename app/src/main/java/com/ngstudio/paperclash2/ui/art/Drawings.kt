package com.ngstudio.paperclash2.ui.art

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.shape.ArenaBuilder
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.shape.ShapeVariant
import com.ngstudio.paperclash2.game.Pattern
import com.ngstudio.paperclash2.game.PlaneModel
import com.ngstudio.paperclash2.game.Skin
import com.ngstudio.paperclash2.ui.theme.PC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Top-down paper plane outlines (unit size, nose pointing +x) for Compose drawings. */
object PlanePaths {
    private fun poly(vararg p: Float): Path = Path().apply {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) {
            lineTo(p[i], p[i + 1])
            i += 2
        }
        close()
    }

    fun outline(model: PlaneModel): Path = when (model) {
        PlaneModel.DART -> poly(1f, 0f, -0.8f, 0.58f, -0.62f, 0f, -0.8f, -0.58f)
        PlaneModel.GLIDER -> poly(0.9f, 0f, -0.25f, 0.9f, -0.82f, 0.36f, -0.72f, 0f, -0.82f, -0.36f, -0.25f, -0.9f)
        PlaneModel.SWALLOW -> poly(1f, 0f, -0.38f, 0.64f, -0.95f, 0.34f, -0.58f, 0f, -0.95f, -0.34f, -0.38f, -0.64f)
        PlaneModel.HAWK -> poly(1f, 0f, -0.78f, 0.6f, -0.62f, 0.42f, -0.62f, 0f, -0.62f, -0.42f, -0.78f, -0.6f)
        PlaneModel.CRANE -> poly(1.05f, 0f, 0.3f, 0.08f, -0.05f, 0.98f, -0.3f, 0.1f, -0.95f, 0f, -0.3f, -0.1f, -0.05f, -0.98f, 0.3f, -0.08f)
        PlaneModel.JET -> poly(1.05f, 0f, 0.35f, 0.3f, 0.3f, 0.12f, -0.55f, 0.72f, -0.85f, 0.26f, -0.85f, -0.26f, -0.55f, -0.72f, 0.3f, -0.12f, 0.35f, -0.3f)
    }

    /** The centre fold line. */
    fun fold(model: PlaneModel): Path = Path().apply {
        moveTo(if (model == PlaneModel.CRANE) 0.3f else 0.95f, 0f)
        lineTo(if (model == PlaneModel.CRANE) -0.3f else -0.6f, 0f)
    }
}

/** Draws a plane centred at [center] with half length [r], heading [deg] (0 = right). */
fun DrawScope.drawPlane(model: PlaneModel, base: Color, accent: Color, pattern: Int, center: Offset, r: Float, deg: Float, shadow: Boolean = true) {
    val outline = PlanePaths.outline(model)
    val fold = PlanePaths.fold(model)
    if (shadow) {
        withTransform({
            translate(center.x + r * 0.12f, center.y + r * 0.2f)
            rotate(deg, Offset.Zero)
            scale(r, r, Offset.Zero)
        }) { drawPath(outline, Color(0x40102040)) }
    }
    withTransform({
        translate(center.x, center.y)
        rotate(deg, Offset.Zero)
        scale(r, r, Offset.Zero)
    }) {
        drawPath(outline, Brush.linearGradient(listOf(base.copy(alpha = 1f), lerpC(base, Color.Black, 0.12f)), Offset(0f, -1f), Offset(0f, 1f)))
        // pattern hint: a few stripes / dots in the accent colour, clipped by eye (subtle)
        when (pattern) {
            Pattern.STRIPES, Pattern.ZIGZAG, Pattern.WAVES -> for (k in -2..2) drawLine(accent.copy(alpha = 0.55f), Offset(-0.5f + k * 0.18f, -0.3f), Offset(-0.2f + k * 0.18f, 0.3f), 0.06f)
            Pattern.DOTS, Pattern.STARS, Pattern.CONFETTI -> for (k in 0..4) drawCircle(accent.copy(alpha = 0.7f), 0.06f, Offset(-0.5f + k * 0.2f, if (k % 2 == 0) 0.2f else -0.2f))
            Pattern.CHECKER, Pattern.GRID -> for (k in 0..3) drawRect(accent.copy(alpha = 0.5f), Offset(-0.6f + k * 0.25f, -0.1f), androidx.compose.ui.geometry.Size(0.12f, 0.12f))
            Pattern.FOIL -> drawPath(outline, Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), Offset(-0.8f, -0.8f), Offset(0.8f, 0.8f)))
            Pattern.HOLO -> drawPath(outline, Brush.linearGradient(listOf(Color(0x66FF9AE0), Color(0x669AE0FF), Color(0x66FFF59A)), Offset(-1f, 0f), Offset(1f, 0f)))
            else -> {}
        }
        drawPath(fold, Color.White.copy(alpha = 0.6f), style = Stroke(0.05f))
        drawPath(outline, PC.Ink, style = Stroke(0.08f, join = StrokeJoin.Round))
    }
}

private fun lerpC(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha)

@Composable
fun PlaneIcon(skin: Skin, size: Dp, modifier: Modifier = Modifier, deg: Float = -35f) {
    Canvas(modifier.size(size)) {
        drawPlane(skin.model, Color(skin.base), Color(skin.accent), skin.pattern, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.42f, deg)
    }
}

/** Cached shape silhouettes (white on transparent) for thumbnails and stickers. */
object Silhouettes {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun forShape(id: String, res: Int = 128): ImageBitmap = key(id, ShapeVariant(), res)

    fun forLevel(level: Int, res: Int = 128): ImageBitmap {
        val spec = LevelGenerator.spec(level)
        return key(spec.shapeId, spec.variant.copy(holes = 0), res)
    }

    private fun key(id: String, v: ShapeVariant, res: Int): ImageBitmap {
        val k = "$id|${v.rotDeg}|${v.mirror}|${v.aspect}|${v.wobble}|$res"
        cache.get(k)?.let { return it }
        val mask = ArenaBuilder.silhouette(ShapeLibrary.byId(id), v, res)
        val px = IntArray(res * res)
        for (y in 0 until res) for (x in 0 until res) {
            px[y * res + x] = if (mask[(res - 1 - y) * res + x]) -0x1 else 0
        }
        val bmp = Bitmap.createBitmap(px, res, res, Bitmap.Config.ARGB_8888).asImageBitmap()
        cache.put(k, bmp)
        return bmp
    }
}

/** Shape silhouette tinted with [color], built off the main thread. */
@Composable
fun ShapeThumb(level: Int?, shapeId: String?, color: Color, size: Dp, modifier: Modifier = Modifier) {
    var img by remember(level, shapeId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(level, shapeId) {
        img = withContext(Dispatchers.Default) {
            if (level != null) Silhouettes.forLevel(level) else Silhouettes.forShape(shapeId ?: "circle")
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        img?.let {
            Image(it, null, Modifier.size(size), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
        }
    }
}
