package com.ngstudio.paperclash2.ui.splash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.engine.level.RivalNames
import com.ngstudio.paperclash2.game.PlaneModel
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.Screen
import com.ngstudio.paperclash2.ui.art.drawPlane
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.PaperBackground
import com.ngstudio.paperclash2.ui.components.PaperCard
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Animated splash: emblem pops, the wordmark bounces in, scissors cut the loading strip. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val emblem = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    val cut = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { emblem.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
        delay(250)
        launch { logo.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
        cut.animateTo(1f, tween(1700, easing = FastOutSlowInEasing))
        delay(200)
        onDone()
    }
    val wobble = rememberInfiniteTransition(label = "wobble")
    val rot by wobble.animateFloat(-4f, 4f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "r")
    PaperBackground {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painterResource(R.drawable.splash_emblem), null,
                Modifier.size(210.dp).graphicsLayer {
                    scaleX = emblem.value
                    scaleY = emblem.value
                    rotationZ = rot
                },
            )
            Image(
                painterResource(R.drawable.logo_wordmark), "Paper Clash 2",
                Modifier.widthIn(max = 340.dp).fillMaxWidth().graphicsLayer {
                    scaleX = logo.value
                    scaleY = logo.value
                    alpha = logo.value.coerceIn(0f, 1f)
                },
            )
            VSpace(36.dp)
            CutStrip(cut.value, Modifier.fillMaxWidth(0.72f).height(36.dp))
            VSpace(10.dp)
            Text("Folding the paper...", style = Type.small)
        }
    }
}

@Composable
private fun CutStrip(progress: Float, modifier: Modifier) {
    val scissors = painterResource(R.drawable.ic_scissors)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val travel = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val dash = 10.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(PC.InkFaint, Offset(x, y), Offset(minOf(x + dash * 0.6f, size.width), y), 3.dp.toPx(), cap = StrokeCap.Round)
                x += dash
            }
            val cutX = size.width * progress
            drawLine(PC.Coral, Offset(0f, y), Offset(cutX, y), 4.dp.toPx(), cap = StrokeCap.Round)
        }
        Image(scissors, null, Modifier.size(36.dp).offset(x = travel * progress - 18.dp))
    }
}

// ---------------------------------------------------------------------- onboarding

private class Page(val title: String, val body: String)

private val pages = listOf(
    Page("Fly & Draw", "Drag anywhere to steer your paper plane. Leave your land and a ribbon follows you."),
    Page("Close the Loop", "Fly back into your land and everything inside the loop becomes yours."),
    Page("Snip Rival Ribbons", "Cross a rival's ribbon to pop them and take their land. Keep your own ribbon safe!"),
    Page("5,000 Shapes Await", "Hearts, stars, cats, rockets... Claim them all, collect stickers and earn three stars."),
)

/** Four illustrated pages, NEXT then LET'S PLAY. There is deliberately no Skip button. */
@Composable
fun OnboardingScreen(state: AppState, replay: Boolean = false) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val fx = LocalFx.current
    var name by remember { mutableStateOf(state.data.nickname.ifBlank { RivalNames.pick(com.ngstudio.paperclash2.engine.core.Rng(System.nanoTime()), HashSet()) }) }
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    LaunchedEffect(pager.currentPage) { if (pager.currentPage > 0) fx.play(Sfx.UI_SWIPE) }
    PaperBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    PaperCard(Modifier.fillMaxWidth().height(300.dp), tape = listOf(PC.Coral, PC.Sky, PC.Mint, PC.Sun)[i], padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xFF2F8F6B))) {
                            when (i) {
                                0 -> LoopDemo(fill = false)
                                1 -> LoopDemo(fill = true)
                                2 -> SnipDemo()
                                else -> PathDemo()
                            }
                        }
                    }
                    VSpace(26.dp)
                    OutlinedText(pages[i].title, Type.title.copy(color = Color.White, fontSize = 30.sp), outline = PC.Ink, width = 9f)
                    VSpace(10.dp)
                    Text(pages[i].body, style = Type.body.copy(fontSize = 16.sp, lineHeight = 23.sp), textAlign = TextAlign.Center)
                    if (i == pages.size - 1 && !replay) {
                        VSpace(18.dp)
                        Text("Your pilot name", style = Type.small)
                        VSpace(6.dp)
                        NameField(name) { name = it }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                for (k in pages.indices) {
                    val on = k == pager.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = if (on) 26.dp else 10.dp, height = 10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) PC.Coral else PC.InkFaint.copy(alpha = 0.5f)),
                    )
                }
            }
            val last = pager.currentPage == pages.size - 1
            GameButton(
                if (last) (if (replay) "GOT IT!" else "LET'S PLAY!") else "NEXT",
                {
                    if (last && replay) {
                        state.back()
                    } else if (last) {
                        state.save.update { it.copy(onboarded = true, nickname = name.trim().take(16).ifBlank { "Pilot" }) }
                        state.reset(Screen.Main)
                    } else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                color = if (last) PC.Mint else PC.Coral,
                dark = if (last) PC.MintDark else PC.CoralDark,
                pulse = last,
                icon = if (last) R.drawable.ic_play else null,
            )
        }
    }
}

@Composable
fun NameField(value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(16)) },
        singleLine = true,
        textStyle = Type.heading.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(PC.Paper)
            .border(2.dp, PC.Sun, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// ---------------------------------------------------------------------- illustrations

/** Land patch on a cardstock board, a plane drawing a loop and (optionally) the loop filling in. */
@Composable
private fun LoopDemo(fill: Boolean) {
    val t = rememberInfiniteTransition(label = "loop")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "lp")
    Canvas(Modifier.fillMaxSize()) {
        drawMat()
        drawCard()
        val u = size.minDimension
        val land = PC.Coral
        val c = Offset(size.width * 0.33f, size.height * 0.64f)
        val r = u * 0.15f
        val landRect = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r * 0.85f, c.x + r, c.y + r * 0.85f)
        raised(land) { drawRoundRect(it, landRect.topLeft, landRect.size, androidx.compose.ui.geometry.CornerRadius(r * 0.55f)) }
        val start = Offset(c.x + r * 0.35f, landRect.top)
        val top = c.y - r * 2.7f
        val right = c.x + r * 2.6f
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(start.x, top)
            lineTo(right, top)
            lineTo(right, c.y)
            lineTo(landRect.right - r * 0.2f, c.y)
        }
        val drawPhase = (p / 0.68f).coerceIn(0f, 1f)
        val measure = PathMeasure()
        measure.setPath(path, false)
        val len = measure.length
        if (fill && p > 0.68f) {
            val a = ((p - 0.68f) / 0.14f).coerceIn(0f, 1f)
            val rect = androidx.compose.ui.geometry.Rect(start.x, top, right, c.y)
            raised(land.copy(alpha = a)) { drawRect(it, rect.topLeft, rect.size) }
            drawRect(Color.White.copy(alpha = (1f - a) * 0.7f), rect.topLeft, rect.size)
        }
        if (!fill || p <= 0.8f) {
            val seg = Path()
            measure.getSegment(0f, len * drawPhase, seg, true)
            drawPath(seg, Color(0x33101030), style = Stroke(r * 0.3f, cap = StrokeCap.Round), alpha = 1f)
            drawPath(seg, land, style = Stroke(r * 0.28f, cap = StrokeCap.Round))
            drawPath(seg, Color.White.copy(alpha = 0.85f), style = Stroke(r * 0.05f, cap = StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(r * 0.16f, r * 0.12f))))
        }
        val pos = measure.getPosition(len * drawPhase)
        val tan = measure.getTangent(len * drawPhase)
        val deg = Math.toDegrees(atan2(tan.y.toDouble(), tan.x.toDouble())).toFloat()
        drawPlane(PlaneModel.DART, Color(0xFFFFF4F1), PC.Coral, 0, pos, r * 0.7f, deg)
    }
}

/** Draws [shape] as a raised paper piece: darker side underneath, then the face. */
private fun DrawScope.raised(color: Color, shape: DrawScope.(Color) -> Unit) {
    val d = size.minDimension * 0.012f
    translate(d * 0.6f, d * 1.8f) { shape(Color(0x33101030).copy(alpha = 0.25f * color.alpha)) }
    translate(0f, d) { shape(Color(color.red * 0.72f, color.green * 0.72f, color.blue * 0.72f, color.alpha)) }
    shape(color)
}

/** The cream cardstock board the demos play on. */
private fun DrawScope.drawCard() {
    val m = size.minDimension * 0.06f
    val tl = Offset(m, m)
    val sz = Size(size.width - 2 * m, size.height - 2 * m)
    val cr = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.08f)
    drawRoundRect(Color(0x40101030), tl + Offset(m * 0.25f, m * 0.4f), sz, cr)
    drawRoundRect(Color(0xFFE9DCC4), tl + Offset(0f, m * 0.18f), sz, cr)
    drawRoundRect(Color(0xFFFFFBF2), tl, sz, cr)
}

/** Our plane cuts across a rival's ribbon; the rival pops. */
@Composable
private fun SnipDemo() {
    val t = rememberInfiniteTransition(label = "snip")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "sp")
    Canvas(Modifier.fillMaxSize()) {
        drawMat()
        drawCard()
        val w = size.width
        val h = size.height
        val rival = Offset(w * 0.74f, h * 0.7f)
        val rr = size.minDimension * 0.14f
        val popped = p > 0.5f
        val fade = if (popped) (1f - (p - 0.5f) / 0.2f).coerceIn(0f, 1f) else 1f
        raised(PC.Sky.copy(alpha = fade)) { drawCircle(it, rr, rival) }
        val arc = Path().apply {
            moveTo(rival.x - rr * 0.6f, rival.y - rr * 0.8f)
            cubicTo(w * 0.55f, h * 0.15f, w * 0.25f, h * 0.2f, w * 0.18f, h * 0.42f)
        }
        drawPath(arc, PC.Sky.copy(alpha = 0.9f * fade), style = Stroke(rr * 0.34f, cap = StrokeCap.Round))
        // our plane flies left to right across the arc
        val x = w * (0.05f + 0.9f * p)
        val y = h * 0.3f + sin(p * 3.14f) * h * 0.04f
        if (popped && p < 0.75f) {
            val k = (p - 0.5f) / 0.25f
            for (i in 0 until 14) {
                val a = i * 0.45f
                val d = k * rr * 2.2f
                drawRect(listOf(PC.Sky, Color.White, PC.Sun)[i % 3], Offset(w * 0.5f + cos(a) * d, h * 0.3f + sin(a) * d), Size(10f, 6f))
            }
        }
        drawPlane(PlaneModel.SWALLOW, Color(0xFFFFF4F1), PC.Coral, 0, Offset(x, y), size.minDimension * 0.1f, 0f)
        drawPlane(PlaneModel.GLIDER, PC.Sky, Color.White, 0, Offset(w * 0.18f, h * 0.42f), size.minDimension * 0.07f * fade, 200f, shadow = false)
    }
}

/** A winding level path with stars filling in. */
@Composable
private fun PathDemo() {
    val t = rememberInfiniteTransition(label = "path")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "pp")
    val star = painterResource(R.drawable.ic_star)
    Canvas(Modifier.fillMaxSize()) {
        drawMat()
        val n = 6
        val pts = List(n) { i ->
            Offset(size.width * (0.5f + 0.3f * sin(i * 1.3f)), size.height * (0.88f - i * 0.15f))
        }
        for (i in 0 until n - 1) drawLine(Color.White.copy(alpha = 0.7f), pts[i], pts[i + 1], 6.dp.toPx(), cap = StrokeCap.Round)
        val shapes = listOf(PC.Coral, PC.Sky, PC.Sun, PC.Mint, PC.Pink, PC.Grape)
        for (i in 0 until n) {
            val done = p * n > i
            drawCircle(Color.White, 20.dp.toPx(), pts[i])
            drawCircle(if (done) shapes[i] else PC.Locked, 16.dp.toPx(), pts[i])
            if (done) {
                val s = 16.dp.toPx()
                translate(pts[i].x - s / 2, pts[i].y - 30.dp.toPx()) {
                    with(star) { draw(Size(s, s)) }
                }
            }
        }
        val idx = (p * n).toInt().coerceIn(0, n - 1)
        drawPlane(PlaneModel.DART, Color.White, PC.Coral, 0, pts[idx] + Offset(0f, -2f), 16.dp.toPx(), -60f)
    }
}

private fun DrawScope.drawMat() {
    val step = 22.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(Color(0x337FD3AE), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(Color(0x337FD3AE), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        y += step
    }
}
