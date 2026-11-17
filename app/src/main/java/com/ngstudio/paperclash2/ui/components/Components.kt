package com.ngstudio.paperclash2.ui.components

import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun Color.lighten(k: Float) = lerp(this, Color.White, k)
fun Color.darken(k: Float) = lerp(this, Color.Black, k)

/** Text with a soft ink outline, used on buttons, banners and big numbers. */
@Composable
fun OutlinedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    outline: Color = Color(0xB31F2A4A),
    width: Float = 7f,
    align: TextAlign = TextAlign.Center,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, style = style.copy(color = outline, drawStyle = Stroke(width = width, join = StrokeJoin.Round)), textAlign = align)
        Text(text, style = style, textAlign = align)
    }
}

/**
 * Chunky game button: a coloured face on a darker lip that presses down, glossy top, outlined
 * label, optional sticker icon and idle pulse.
 */
@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = PC.Coral,
    dark: Color = PC.CoralDark,
    icon: Int? = null,
    height: Dp = 58.dp,
    style: TextStyle = Type.button,
    enabled: Boolean = true,
    pulse: Boolean = false,
    sound: Sfx = Sfx.UI_TAP,
    corner: Dp = 18.dp,
) {
    val fx = LocalFx.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 900f), label = "press")
    val pulseScale = if (pulse && enabled) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 1.045f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p").value
    } else 1f
    val face = if (enabled) color else PC.Locked
    val lipColor = if (enabled) dark else Color(0xFF8E95A8)
    val shape = RoundedCornerShape(corner)
    val lip = 5.dp
    Box(
        modifier
            .height(height + lip)
            .graphicsLayer {
                scaleX = pulseScale * (1f - 0.03f * press)
                scaleY = pulseScale * (1f - 0.03f * press)
            }
            .clickable(interaction, indication = null, enabled = enabled) {
                fx.play(sound)
                fx.haptics.tick()
                onClick()
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = lip)
                .drawBehind {
                    drawRoundRect(PC.Shadow, topLeft = Offset(0f, 4.dp.toPx()), size = size, cornerRadius = CornerRadius(corner.toPx()))
                }
                .clip(shape)
                .background(lipColor),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = lip * press)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(face.lighten(0.16f), face, face.darken(0.04f))))
                .drawWithContent {
                    drawContent()
                    // glossy band
                    drawRoundRect(
                        Color.White.copy(alpha = 0.22f),
                        topLeft = Offset(6.dp.toPx(), 4.dp.toPx()),
                        size = Size(size.width - 12.dp.toPx(), size.height * 0.36f),
                        cornerRadius = CornerRadius(corner.toPx() * 0.7f),
                    )
                }
                .border(2.dp, face.darken(0.18f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) {
                    Image(painterResource(icon), null, Modifier.size(height * 0.56f))
                    if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
                }
                if (text.isNotEmpty()) {
                    val light = face.red * 0.3f + face.green * 0.59f + face.blue * 0.11f > 0.82f
                    if (light) Text(text, style = style.copy(color = PC.Ink))
                    else OutlinedText(text, style.copy(shadow = Shadow(face.darken(0.45f), Offset(0f, 3f), 0f)), outline = face.darken(0.45f), width = 6f)
                }
            }
        }
    }
}

/** Round sticker button with a lip (top bars, HUD). */
@Composable
fun RoundButton(
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = PC.Paper,
    dark: Color = PC.PaperDim,
    sound: Sfx = Sfx.UI_TAP,
    badge: Int = 0,
    contentDescription: String? = null,
) {
    val fx = LocalFx.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 900f), label = "rb")
    Box(
        modifier
            .size(size + 4.dp)
            .clickable(interaction, indication = null) {
                fx.play(sound)
                fx.haptics.tick()
                onClick()
            },
    ) {
        Box(
            Modifier
                .size(size)
                .offset(y = 4.dp)
                .clip(CircleShape)
                .background(dark.darken(0.08f)),
        )
        Box(
            Modifier
                .size(size)
                .offset(y = 4.dp * press)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(color.lighten(0.2f), color)))
                .border(2.dp, dark.darken(0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), contentDescription, Modifier.size(size * 0.72f))
        }
        if (badge > 0) Badge(badge, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
fun Badge(count: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "badge")
    val s by t.animateFloat(1f, 1.15f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "bs")
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .size(20.dp)
            .clip(CircleShape)
            .background(PC.Coral)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else count.toString(), style = Type.small.copy(color = Color.White, fontSize = 10.sp))
    }
}

/** A sheet of paper with a soft shadow and optional washi tape on its top corners. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    color: Color = PC.Paper,
    tape: Color? = null,
    corner: Dp = 22.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    border: Color = Color(0xFFEFE3CC),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .drawBehind {
                drawRoundRect(PC.Shadow, topLeft = Offset(0f, 5.dp.toPx()), size = size, cornerRadius = CornerRadius(corner.toPx()))
            }
            .clip(shape)
            .background(color)
            .border(2.dp, border, shape)
            .drawWithContent {
                drawContent()
                if (tape != null) {
                    val w = 54.dp.toPx()
                    val h = 18.dp.toPx()
                    washi(tape, Offset(-6.dp.toPx(), 4.dp.toPx()), w, h, -32f)
                    washi(tape, Offset(size.width - w + 6.dp.toPx(), 4.dp.toPx()), w, h, 32f)
                }
            }
            .padding(padding),
        content = content,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.washi(color: Color, at: Offset, w: Float, h: Float, deg: Float) {
    rotate(deg, pivot = Offset(at.x + w / 2, at.y + h / 2)) {
        drawRect(color.copy(alpha = 0.78f), topLeft = at, size = Size(w, h))
        var x = at.x
        while (x < at.x + w) {
            drawRect(Color.White.copy(alpha = 0.28f), topLeft = Offset(x, at.y), size = Size(w / 12f, h))
            x += w / 6f
        }
    }
}

/** Folded ribbon banner with a title (results, world headers). */
@Composable
fun RibbonBanner(text: String, modifier: Modifier = Modifier, color: Color = PC.Coral, height: Dp = 56.dp, style: TextStyle = Type.title) {
    Box(modifier.height(height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSizeCompat()) {
            val w = size.width
            val h = size.height
            val fold = h * 0.28f
            val dark = color.darken(0.3f)
            // tails
            val tail = Path().apply {
                moveTo(0f, fold); lineTo(w * 0.14f, fold); lineTo(w * 0.14f, h); lineTo(0f, h); lineTo(w * 0.05f, (fold + h) / 2); close()
            }
            drawPath(tail, color.darken(0.12f))
            val tail2 = Path().apply {
                moveTo(w, fold); lineTo(w * 0.86f, fold); lineTo(w * 0.86f, h); lineTo(w, h); lineTo(w * 0.95f, (fold + h) / 2); close()
            }
            drawPath(tail2, color.darken(0.12f))
            // folds
            drawPath(Path().apply { moveTo(w * 0.1f, h - fold); lineTo(w * 0.14f, h); lineTo(w * 0.14f, h - fold); close() }, dark)
            drawPath(Path().apply { moveTo(w * 0.9f, h - fold); lineTo(w * 0.86f, h); lineTo(w * 0.86f, h - fold); close() }, dark)
            // body
            drawRoundRect(color, topLeft = Offset(w * 0.1f, 0f), size = Size(w * 0.8f, h - fold), cornerRadius = CornerRadius(8.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = 0.2f), topLeft = Offset(w * 0.12f, 4.dp.toPx()), size = Size(w * 0.76f, (h - fold) * 0.35f), cornerRadius = CornerRadius(6.dp.toPx()))
        }
        OutlinedText(text, style.copy(color = Color.White), Modifier.offset(y = -(height * 0.14f)), outline = color.darken(0.45f))
    }
}

@Composable
private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxSize()

/** Pill showing coins with a counting animation. */
@Composable
fun CoinPill(coins: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shown by animateIntAsState(coins, tween(700), label = "coins")
    Pill(R.drawable.ic_coin, shown.toString(), modifier, onClick)
}

@Composable
fun Pill(icon: Int, text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, color: Color = PC.Paper) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(38.dp)
            .drawBehind { drawRoundRect(PC.Shadow, topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = CornerRadius(size.height / 2)) }
            .clip(shape)
            .background(color)
            .border(2.dp, Color(0xFFEADFC8), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 4.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(icon), null, Modifier.size(32.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = Type.number)
    }
}

/** Paper progress strip. */
@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier, color: Color = PC.Mint, height: Dp = 14.dp, track: Color = Color(0x261F2A4A)) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500), label = "strip")
    val shape = RoundedCornerShape(50)
    Box(modifier.height(height).clip(shape).background(track)) {
        if (p > 0.001f) {
            Box(
                Modifier
                    .fillMaxWidth(p)
                    .height(height)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(color.lighten(0.25f), color)))
                    .drawWithContent {
                        drawContent()
                        drawRoundRect(Color.White.copy(alpha = 0.3f), topLeft = Offset(3.dp.toPx(), 2.dp.toPx()),
                            size = Size(size.width - 6.dp.toPx(), size.height * 0.32f), cornerRadius = CornerRadius(size.height))
                    },
            )
        }
    }
}

/** Three stars; earned ones pop in. */
@Composable
fun StarRow(stars: Int, modifier: Modifier = Modifier, size: Dp = 28.dp, spacing: Dp = 2.dp, raiseMiddle: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 3) {
            val on = i < stars
            val scale by animateFloatAsState(if (on) 1f else 0.85f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "star$i")
            val s = if (raiseMiddle && i == 1) size * 1.2f else size
            Image(
                painterResource(if (on) R.drawable.ic_star else R.drawable.ic_star_empty), null,
                Modifier
                    .size(s)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .offset(y = if (raiseMiddle && i == 1) (-6).dp else 0.dp),
            )
        }
    }
}

/** Pill-shaped on/off switch with a paper knob. */
@Composable
fun GameToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val fx = LocalFx.current
    val t by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "tg")
    val track = lerp(Color(0xFFCBD0DC), PC.Mint, t)
    Box(
        modifier
            .width(58.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .border(2.dp, track.darken(0.15f), RoundedCornerShape(50))
            .clickable {
                fx.play(Sfx.UI_TOGGLE)
                fx.haptics.tick()
                onChange(!checked)
            }
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = 24.dp * t)
                .size(26.dp)
                .drawBehind { drawCircle(PC.Shadow, center = Offset(size.width / 2, size.height / 2 + 2.dp.toPx())) }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** Segmented choice (quality, controls). */
/** Chunky candy-track slider with a round paper knob. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    color: Color = PC.Coral,
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = modifier,
        interactionSource = interaction,
        thumb = {
            Box(
                Modifier
                    .size(30.dp)
                    .drawBehind { drawCircle(PC.Shadow, size.minDimension / 2f, center + Offset(0f, 3.dp.toPx())) }
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(4.dp, color, CircleShape),
            )
        },
        track = { st ->
            val span = st.valueRange.endInclusive - st.valueRange.start
            val frac = if (span <= 0f) 0f else ((st.value - st.valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(PC.Locked, cornerRadius = r)
                val w = maxOf(size.height, size.width * frac)
                drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
                drawRoundRect(
                    Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(size.height * 0.35f, size.height * 0.2f),
                    size = Size(maxOf(0f, w - size.height * 0.7f), size.height * 0.24f),
                    cornerRadius = r,
                )
            }
        },
    )
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val fx = LocalFx.current
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1A1F2A4A))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) PC.Paper else Color.Transparent)
                    .clickable {
                        fx.play(Sfx.UI_TOGGLE)
                        onSelect(i)
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = Type.label.copy(fontSize = 13.sp, color = if (on) PC.Ink else PC.InkSoft))
            }
        }
    }
}

/** Dialog: dimmed backdrop and a card that pops in. */
@Composable
fun GameDialog(
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tape: Color? = PC.Sun,
    content: @Composable ColumnScope.() -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x991F2A4A).copy(alpha = 0.6f * appear.value.coerceIn(0f, 1f)))
            .clickable(MutableInteractionSource(), indication = null) { onDismiss?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        PaperCard(
            modifier
                .padding(horizontal = 22.dp)
                .widthIn(max = 420.dp)
                .graphicsLayer {
                    val s = 0.75f + 0.25f * appear.value
                    scaleX = s
                    scaleY = s
                    alpha = appear.value.coerceIn(0f, 1f)
                }
                .clickable(MutableInteractionSource(), indication = null) {},
            tape = tape,
            padding = PaddingValues(20.dp),
            content = content,
        )
    }
}

/** Celebration confetti drawn by Compose (results, unlocks). */
@Composable
fun ConfettiRain(modifier: Modifier = Modifier, pieces: Int = 90, colors: List<Color> = listOf(PC.Coral, PC.Sun, PC.Sky, PC.Mint, PC.Pink, PC.Grape)) {
    val seeds = remember {
        List(pieces) {
            floatArrayOf(Random.nextFloat(), Random.nextFloat() * -1f, 0.5f + Random.nextFloat(), Random.nextFloat() * 6.28f, (Random.nextFloat() - 0.5f) * 6f, it.toFloat())
        }
    }
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.longValue = it - start }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = time.longValue / 1e9f
        for (s in seeds) {
            val y = (s[1] + t * 0.22f * s[2]) % 1.25f
            if (y < -0.05f) continue
            val x = s[0] + sin(t * 1.5f + s[3]) * 0.04f
            val px = x * size.width
            val py = y * size.height
            val c = colors[(s[5].toInt()) % colors.size]
            val flip = cos(t * 5f * s[2] + s[3])
            translate(px, py) {
                rotate(t * 90f * s[4], pivot = Offset.Zero) {
                    drawRect(c, topLeft = Offset(-6f * flip, -4f), size = Size(12f * kotlin.math.abs(flip) + 1f, 8f))
                }
            }
        }
    }
}

/** Cream backdrop with a faint cutting-mat grid and drifting paper shapes (menus). */
@Composable
fun PaperBackground(modifier: Modifier = Modifier, base: Color = PC.Cream, ink: Color = Color(0x141F2A4A), content: @Composable BoxScope.() -> Unit = {}) {
    val t = rememberInfiniteTransition(label = "bg")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "drift")
    Box(modifier.fillMaxSize().background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 28.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                y += step
            }
            val shapes = listOf(PC.Coral, PC.Sky, PC.Sun, PC.Mint, PC.Pink, PC.Grape)
            for (i in 0 until 9) {
                val fx = ((i * 0.37f + drift * (0.3f + i * 0.04f)) % 1.2f) - 0.1f
                val fy = (i * 0.23f + 0.08f) % 1f
                val c = shapes[i % shapes.size].copy(alpha = 0.10f)
                val r = (18 + (i * 7) % 22).dp.toPx()
                translate(fx * size.width, fy * size.height) {
                    rotate(drift * 360f * (if (i % 2 == 0) 1f else -1f), pivot = Offset.Zero) {
                        when (i % 3) {
                            0 -> drawCircle(c, r)
                            1 -> drawRect(c, Offset(-r, -r), Size(r * 2, r * 2))
                            else -> drawPath(Path().apply { moveTo(0f, -r); lineTo(r, r); lineTo(-r, r); close() }, c)
                        }
                    }
                }
            }
        }
        content()
    }
}

/** Title + optional subtitle block for screens. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedText(title, Type.title.copy(color = Color.White), outline = PC.Ink.copy(alpha = 0.85f), width = 9f)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = Type.small.copy(color = PC.InkSoft), textAlign = TextAlign.Center)
        }
    }
}

/** Simple vertical space helper. */
@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
