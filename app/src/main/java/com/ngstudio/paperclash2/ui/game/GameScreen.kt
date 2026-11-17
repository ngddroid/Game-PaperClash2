package com.ngstudio.paperclash2.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.data.Quests
import com.ngstudio.paperclash2.engine.level.Hints
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.world.PickupType
import com.ngstudio.paperclash2.game.GameEvent
import com.ngstudio.paperclash2.game.HudState
import com.ngstudio.paperclash2.game.MinimapFrame
import com.ngstudio.paperclash2.game.Phase
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.Screen
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.RoundButton
import com.ngstudio.paperclash2.ui.components.StarRow
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.components.darken
import com.ngstudio.paperclash2.ui.levels.modeIcon
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private class Popup(val id: Long, val text: String, val x: Float, val y: Float, val color: Color, val big: Boolean)

@Composable
fun GameScreen(state: AppState, screen: Screen.Game) {
    val c = state.controller
    val fx = LocalFx.current
    val hud by c.hud.collectAsState()
    val map by c.minimap.collectAsState()
    val save by state.save.state.collectAsState()
    val spec = remember(screen.level) { LevelGenerator.spec(screen.level) }
    var paused by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf<GameEvent.HumanDown?>(null) }
    var result by remember { mutableStateOf<ResultInfo?>(null) }
    val popups = remember { mutableStateListOf<Popup>() }
    var banner by remember { mutableStateOf<String?>(null) }
    var goTime by remember { mutableLongStateOf(0L) }
    var picked by remember { mutableIntStateOf(0) }
    var steered by remember { mutableStateOf(false) }
    var everOutside by remember { mutableStateOf(false) }
    val startMs = remember { System.currentTimeMillis() }
    val hudNow by rememberUpdatedState(hud)

    LaunchedEffect(screen.nonce) {
        c.events.collect { e ->
            when (e) {
                is GameEvent.Popup -> {
                    val p = Popup(System.nanoTime(), e.text, e.x, e.y, Color(e.color), e.big)
                    popups += p
                    if (popups.size > 8) popups.removeAt(0)
                }
                is GameEvent.Go -> {
                    goTime = System.currentTimeMillis()
                    banner = "GO!"
                }
                is GameEvent.StarEarned -> banner = "★".repeat(e.stars)
                is GameEvent.GoalReached -> banner = "GOAL!"
                is GameEvent.HumanDown -> down = e
                is GameEvent.Revived -> down = null
                is GameEvent.Picked -> {
                    picked++
                    banner = when (e.type) {
                        PickupType.SPEED -> "SPEED!"
                        PickupType.SHIELD -> "SHIELD!"
                        PickupType.BOMB -> "PAINT BOMB!"
                        PickupType.FREEZE -> "FREEZE!"
                        PickupType.STAR -> ""
                    }
                }
                is GameEvent.InkReady -> banner = "INK READY!"
                is GameEvent.BossDown -> banner = "BOSS SNIPPED!"
                is GameEvent.Ended -> {
                    down = null
                    val secs = (System.currentTimeMillis() - startMs) / 1000f
                    val (newSave, reward) = Progress.applyResult(state.data, spec, e.result, secs)
                    var s2 = Quests.addMinutes(newSave, (secs / 60f).toInt().coerceAtLeast(if (secs > 30) 1 else 0))
                    s2 = Quests.addPowerUps(s2, picked)
                    s2 = s2.copy(stats = s2.stats.copy(powerUps = s2.stats.powerUps + picked))
                    state.save.update { s2 }
                    delay(if (e.result.won) 1300 else 900)
                    result = ResultInfo(screen.level, e.result, reward, e.shapeId, e.shareMap)
                }
            }
        }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(1100)
            banner = null
        }
    }
    LaunchedEffect(hud.outside) { if (hud.outside) everOutside = true }

    // automatic quality: drop to Balanced if the device struggles
    var slowFrames by remember { mutableIntStateOf(0) }
    LaunchedEffect(hud.fps, hud.phase) {
        if (save.settings.quality == 3 && hud.phase == Phase.PLAYING && hud.fps in 1..44) {
            slowFrames++
            if (slowFrames > 25 && c.settings.quality == 2) c.settings = c.settings.copy(quality = 1)
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_PAUSE && hudNow.phase == Phase.PLAYING && result == null && down == null) {
                paused = true
                c.setPaused(true)
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            c.release()
        }
    }

    BackHandler {
        when {
            result != null -> state.reset(Screen.Main)
            down != null -> {}
            else -> {
                paused = !paused
                c.setPaused(paused)
                fx.play(Sfx.UI_POPUP)
            }
        }
    }

    val inputOn = !paused && down == null && result == null && (hud.phase == Phase.PLAYING || hud.phase == Phase.COUNTDOWN)
    Box(Modifier.fillMaxSize()) {
        SteerLayer(state, inputOn, save.settings.controls == 1, save.settings.sensitivity) { steered = true }
        DangerVignette(hud.danger)
        EdgeArrows(hud)
        PopupLayer(popups)

        if (result == null) Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column {
                    RoundButton(R.drawable.ic_pause, {
                        paused = true
                        c.setPaused(true)
                    }, size = 46.dp, contentDescription = "Pause", sound = Sfx.UI_POPUP)
                    VSpace(8.dp)
                    Minimap(map)
                }
                Spacer(Modifier.width(8.dp))
                GoalPanel(hud, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    TimerPill(hud)
                    VSpace(8.dp)
                    Leaderboard(hud)
                }
            }
            if (save.settings.showFps) Text("${hud.fps} fps", style = Type.small.copy(color = PC.Ink), modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (result == null) BottomBar(state, hud, save.settings.leftHanded)
        IntroCard(hud, spec.number, spec.shapeId, spec.goal.summary())
        Hint(hud, spec.hints, steered, everOutside, goTime)
        BannerText(banner)

        if (paused && result == null) PauseDialog(state, onResume = {
            paused = false
            c.setPaused(false)
        }, onRestart = {
            paused = false
            state.play(screen.level, com.ngstudio.paperclash2.engine.game.Boosters())
        }, onQuit = {
            paused = false
            state.reset(Screen.Main)
        })
        down?.let { DownDialog(state, it) }
        result?.let { ResultScreen(state, it) }
    }
}

// ---------------------------------------------------------------------- steering

/**
 * Drag anywhere to steer (floating stick that re-anchors when the finger turns back or runs far),
 * or a fixed stick at the bottom. The direction maps straight onto the board (camera looks north).
 */
@Composable
private fun SteerLayer(state: AppState, enabled: Boolean, fixed: Boolean, sensitivity: Float, onSteer: () -> Unit) {
    val c = state.controller
    val density = LocalDensity.current
    val radius = with(density) { (30.dp / sensitivity.coerceIn(0.5f, 2f)).toPx() }
    val dead = with(density) { 4.dp.toPx() }
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    val on by rememberUpdatedState(enabled)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fixedCenter = with(density) { Offset(maxWidth.toPx() / 2, maxHeight.toPx() - 150.dp.toPx()) }
        Box(
            Modifier.fillMaxSize().pointerInput(fixed) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    if (!on) return@awaitEachGesture
                    var a = if (fixed) fixedCenter else first.position
                    var last = first.position
                    anchor = a
                    knob = first.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == first.id } ?: break
                        if (!ch.pressed) break
                        val p = ch.position
                        if (!fixed) {
                            // finger turned back: steer from where it turned
                            val mx = p.x - last.x
                            val my = p.y - last.y
                            val ax = last.x - a.x
                            val ay = last.y - a.y
                            val ml = sqrt(mx * mx + my * my)
                            val al = sqrt(ax * ax + ay * ay)
                            if (ml > 2f && al > dead && (mx * ax + my * ay) < 0.82f * ml * al) a = last
                        }
                        val dx = p.x - a.x
                        val dy = p.y - a.y
                        val len = sqrt(dx * dx + dy * dy)
                        if (len > dead && on) {
                            c.steer(atan2(-dy, dx))
                            onSteer()
                        }
                        if (!fixed && len > radius) a = Offset(p.x - dx / len * radius, p.y - dy / len * radius)
                        anchor = a
                        knob = p
                        last = p
                        ch.consume()
                    }
                    anchor = null
                }
            },
        )
        val an = anchor
        if (an != null && enabled) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = 0.22f), radius * 1.6f, an)
                drawCircle(Color.White.copy(alpha = 0.55f), radius * 1.6f, an, style = Stroke(3.dp.toPx()))
                val d = knob - an
                val l = d.getDistance()
                val k = if (l > radius * 1.4f) knob.let { an + d * (radius * 1.4f / l) } else knob
                drawCircle(PC.Coral.copy(alpha = 0.85f), radius * 0.62f, k)
                drawCircle(Color.White, radius * 0.62f, k, style = Stroke(3.dp.toPx()))
            }
        } else if (fixed && enabled) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = 0.18f), radius * 1.6f, fixedCenter)
                drawCircle(Color.White.copy(alpha = 0.45f), radius * 1.6f, fixedCenter, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

// ---------------------------------------------------------------------- HUD pieces

@Composable
private fun GoalPanel(hud: HudState, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .drawBehind { drawRoundRect(PC.Shadow, topLeft = Offset(0f, 4.dp.toPx()), size = size, cornerRadius = CornerRadius(18.dp.toPx())) }
            .clip(shape)
            .background(PC.Paper.copy(alpha = 0.95f))
            .border(2.dp, Color(0xFFEADFC8), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (value, max, label) = when (hud.mode) {
            Mode.CLAIM -> Triple(hud.bestPercent, hud.t3 * 1.1f, String.format("%.1f%%", hud.percent))
            Mode.SURVIVE -> Triple(hud.percent, hud.t3 * 1.1f, String.format("%.1f%%", hud.percent))
            Mode.COIN_RUSH -> Triple(hud.starsCollected.toFloat(), hud.t3 * 1.1f, "${hud.starsCollected} ★")
            Mode.KNOCKOUT -> Triple(hud.snips.toFloat(), hud.goalCount.toFloat(), "Snips ${hud.snips}/${hud.goalCount}")
            Mode.DOMINATE -> Triple(hud.percent, hud.t3 * 1.1f, "#${hud.rank}  ${String.format("%.1f%%", hud.percent)}")
            Mode.BOSS -> Triple(hud.bestPercent, hud.t3 * 1.1f, if (hud.bossPopped) String.format("Boss ✓  %.1f%%", hud.percent) else "Snip the boss!")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(modeIcon(hud.mode)), null, Modifier.size(26.dp))
            Spacer(Modifier.width(4.dp))
            OutlinedText(label, Type.number.copy(color = Color.White, fontSize = 20.sp), outline = Color(hud.myColor).darken(0.35f), width = 7f)
        }
        val ticks = when (hud.mode) {
            Mode.CLAIM, Mode.SURVIVE, Mode.COIN_RUSH -> listOf(hud.t1, hud.t2, hud.t3)
            Mode.DOMINATE, Mode.BOSS -> listOf(hud.t2, hud.t3)
            Mode.KNOCKOUT -> emptyList()
        }
        val frac by animateFloatAsState((value / max.coerceAtLeast(0.01f)).coerceIn(0f, 1f), tween(300), label = "goal")
        Canvas(Modifier.fillMaxWidth().height(16.dp).padding(top = 4.dp)) {
            val h = size.height
            drawRoundRect(Color(0x221F2A4A), size = size, cornerRadius = CornerRadius(h / 2))
            drawRoundRect(Color(hud.myColor), size = size.copy(width = size.width * frac), cornerRadius = CornerRadius(h / 2))
            for ((i, t) in ticks.withIndex()) {
                val x = size.width * (t / max).coerceIn(0f, 1f)
                val got = value >= t
                drawLine(PC.Ink.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), 2.dp.toPx())
                drawCircle(if (got) PC.Sun else Color.White, 5.dp.toPx(), Offset(x, h / 2))
                drawCircle(PC.Ink.copy(alpha = 0.6f), 5.dp.toPx(), Offset(x, h / 2), style = Stroke(1.5f.dp.toPx()))
                if (i == ticks.size - 1 && got) drawCircle(Color.White.copy(alpha = 0.6f), 2.dp.toPx(), Offset(x, h / 2))
            }
        }
        if (hud.stars > 0 || hud.previewStars > 0) StarRow(maxOf(hud.stars, hud.previewStars), size = 14.dp, spacing = 0.dp)
    }
}

@Composable
private fun TimerPill(hud: HudState) {
    val t = hud.timeLeft.coerceAtLeast(0f)
    val urgent = t < 10f && hud.phase == Phase.PLAYING
    val pulse = if (urgent) {
        val inf = rememberInfiniteTransition(label = "timer")
        inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "tp").value
    } else 1f
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .clip(shape)
            .background(if (urgent) PC.Coral else PC.Paper.copy(alpha = 0.95f))
            .border(2.dp, if (urgent) PC.CoralDark else Color(0xFFEADFC8), shape)
            .padding(start = 4.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_clock), null, Modifier.size(28.dp))
        val m = (t / 60).toInt()
        val s = (t % 60).toInt()
        Text(String.format("%d:%02d", m, s), style = Type.number.copy(color = if (urgent) Color.White else PC.Ink))
    }
}

@Composable
private fun Minimap(frame: MinimapFrame?) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(80.dp)
            .clip(shape)
            .background(Color(0xCC2F8F6B))
            .border(2.dp, Color.White.copy(alpha = 0.9f), shape)
            .padding(4.dp),
    ) {
        if (frame != null) {
            val img = remember(frame.version) { frame.bitmap.asImageBitmap() }
            androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            if (frame.alive) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = Offset(size.width * frame.px, size.height * frame.py)
                    drawCircle(Color.White, 4.dp.toPx(), p)
                    drawCircle(PC.Ink, 4.dp.toPx(), p, style = Stroke(1.5f.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun Leaderboard(hud: HudState) {
    Column(
        Modifier
            .widthIn(max = 148.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PC.Paper.copy(alpha = 0.82f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        hud.leaders.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text("${if (p.human) hud.rank else i + 1}", style = Type.small.copy(fontSize = 11.sp, color = PC.InkSoft), modifier = Modifier.width(14.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(p.color)))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (p.human) "You" else p.name, style = Type.small.copy(fontSize = 11.sp, color = if (p.human) PC.Ink else PC.InkSoft),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 78.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(String.format("%.0f%%", p.percent), style = Type.small.copy(fontSize = 11.sp, color = PC.Ink))
            }
        }
    }
}

@Composable
private fun DangerVignette(danger: Float) {
    val a by animateFloatAsState(danger, tween(250), label = "danger")
    if (a < 0.02f) return
    Canvas(Modifier.fillMaxSize()) {
        val r = size.maxDimension * 0.75f
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Transparent, PC.Coral.copy(alpha = 0.5f * a)),
                center = Offset(size.width / 2, size.height / 2), radius = r,
            ),
        )
    }
}

@Composable
private fun EdgeArrows(hud: HudState) {
    if (hud.arrows.isEmpty()) return
    val inf = rememberInfiniteTransition(label = "arrows")
    val pulse by inf.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "ap")
    Canvas(Modifier.fillMaxSize()) {
        for (a in hud.arrows) {
            val p = Offset(size.width * a.x, size.height * a.y)
            val s = 13.dp.toPx() * (if (a.danger) pulse else 1f) * (if (a.boss) 1.3f else 1f)
            rotate(Math.toDegrees(a.angle.toDouble()).toFloat(), p) {
                val path = Path().apply {
                    moveTo(p.x + s, p.y)
                    lineTo(p.x - s * 0.7f, p.y - s * 0.75f)
                    lineTo(p.x - s * 0.35f, p.y)
                    lineTo(p.x - s * 0.7f, p.y + s * 0.75f)
                    close()
                }
                drawPath(path, Color(a.color))
                drawPath(path, if (a.danger) PC.Coral else Color.White, style = Stroke(2.5f.dp.toPx()))
            }
        }
    }
}

@Composable
private fun PopupLayer(popups: List<Popup>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        for (p in popups) {
            key(p.id) {
                val anim = remember { Animatable(0f) }
                LaunchedEffect(p.id) { anim.animateTo(1f, tween(1100)) }
                val t = anim.value
                if (t < 1f) {
                    OutlinedText(
                        p.text,
                        Type.number.copy(color = Color.White, fontSize = if (p.big) 26.sp else 20.sp),
                        Modifier
                            .offset(x = maxWidth * p.x - 70.dp, y = maxHeight * p.y - 50.dp - 60.dp * t)
                            .width(140.dp)
                            .graphicsLayer {
                                alpha = (1f - t) * 1.4f
                                val s = if (t < 0.15f) 0.6f + t / 0.15f * 0.5f else 1.1f - (t - 0.15f) * 0.15f
                                scaleX = s
                                scaleY = s
                            },
                        outline = p.color.darken(0.35f),
                        width = 8f,
                    )
                }
            }
        }
    }
}

@Composable
private fun key(k: Long, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun BottomBar(state: AppState, hud: HudState, leftHanded: Boolean) {
    val c = state.controller
    val fx = LocalFx.current
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp)) {
        // power-up timers
        Row(Modifier.align(if (leftHanded) Alignment.BottomEnd else Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hud.speedTime > 0f) PowerTimer(R.drawable.ic_bolt, hud.speedTime / 5f)
            if (hud.shieldTime > 0f) PowerTimer(R.drawable.ic_shield, (hud.shieldTime / 5f).coerceAtMost(1f))
            if (hud.frozen) PowerTimer(R.drawable.ic_freeze, 1f)
        }
        if (hud.inkEnabled && hud.phase == Phase.PLAYING) {
            InkButton(hud.ink, Modifier.align(if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd)) {
                if (hud.ink >= 1f) c.useInk() else fx.play(Sfx.UI_ERROR)
            }
        }
        AnimatedVisibility(hud.canFinish, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            GameButton("FINISH", { c.finishLevel() }, Modifier.width(170.dp), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_check, pulse = true, height = 52.dp, sound = Sfx.GOAL_REACHED)
        }
    }
}

@Composable
private fun PowerTimer(icon: Int, frac: Float) {
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(PC.Paper.copy(alpha = 0.9f))
            drawArc(PC.Sun, -90f, 360f * frac.coerceIn(0f, 1f), false, style = Stroke(4.dp.toPx()))
        }
        Image(painterResource(icon), null, Modifier.size(32.dp))
    }
}

@Composable
private fun InkButton(ink: Float, modifier: Modifier, onClick: () -> Unit) {
    val ready = ink >= 1f
    val inf = rememberInfiniteTransition(label = "ink")
    val glow by inf.animateFloat(0.9f, 1.12f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "ig")
    Box(
        modifier
            .size(78.dp)
            .graphicsLayer { val s = if (ready) glow else 1f; scaleX = s; scaleY = s }
            .clickable(MutableInteractionSource(), indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(PC.Shadow, center = Offset(size.width / 2, size.height / 2 + 4.dp.toPx()))
            drawCircle(if (ready) PC.Grape else PC.Paper)
            drawArc(if (ready) PC.Sun else PC.Grape, -90f, 360f * ink.coerceIn(0f, 1f), false, style = Stroke(6.dp.toPx()))
        }
        Image(painterResource(R.drawable.ic_ink), "Ink Burst", Modifier.size(46.dp).graphicsLayer { alpha = if (ready) 1f else 0.55f })
    }
}

@Composable
private fun IntroCard(hud: HudState, level: Int, shapeId: String, goal: String) {
    val visible = hud.phase == Phase.INTRO || hud.phase == Phase.COUNTDOWN
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 1.2f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-90).dp)) {
                OutlinedText("LEVEL $level", Type.hero.copy(color = Color.White), outline = PC.Ink, width = 11f)
                Text(ShapeLibrary.byId(shapeId).name, style = Type.heading.copy(color = Color.White), modifier = Modifier
                    .clip(RoundedCornerShape(50)).background(PC.Ink.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 2.dp))
                VSpace(6.dp)
                Text(goal, style = Type.label.copy(color = Color.White), textAlign = TextAlign.Center, modifier = Modifier
                    .clip(RoundedCornerShape(12.dp)).background(PC.Coral.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (hud.phase == Phase.COUNTDOWN && hud.countdown > 0) {
            androidx.compose.runtime.key(hud.countdown) {
                val a = remember { Animatable(0f) }
                LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
                OutlinedText(
                    hud.countdown.toString(), Type.hero.copy(color = Color.White, fontSize = 110.sp, lineHeight = 112.sp),
                    Modifier.offset(y = 60.dp).graphicsLayer { scaleX = 0.4f + 0.6f * a.value; scaleY = 0.4f + 0.6f * a.value },
                    outline = PC.Coral.darken(0.3f), width = 16f,
                )
            }
        }
    }
}

@Composable
private fun BannerText(text: String?) {
    Box(Modifier.fillMaxSize().padding(top = 170.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(!text.isNullOrEmpty(), enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = fadeOut() + scaleOut()) {
            OutlinedText(text ?: "", Type.hero.copy(color = PC.Sun, fontSize = 44.sp), outline = PC.Ink, width = 12f)
        }
    }
}

/** Tutorial hints for levels 1–10. */
@Composable
private fun Hint(hud: HudState, flags: Int, steered: Boolean, everOutside: Boolean, goTime: Long) {
    if (flags == 0 || hud.phase != Phase.PLAYING) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val since = (now - goTime) / 1000f
    val text = when {
        flags and Hints.STEER != 0 && !steered -> "Drag anywhere to steer your plane"
        flags and Hints.LEAVE != 0 && !everOutside -> "Fly out of your land to draw a ribbon"
        flags and Hints.CLOSE != 0 && hud.outside && hud.captures == 0 -> "Now fly back into your land to claim the loop!"
        flags and Hints.BIGGER != 0 && since < 7f -> "Bigger loops claim more land"
        flags and Hints.SNIP != 0 && since < 7f -> "Cross a rival's ribbon to pop them!"
        flags and Hints.SPEED != 0 && since < 7f -> "Grab the lightning badge for a speed boost"
        flags and Hints.SHIELD != 0 && since < 7f -> "A shield badge keeps your ribbon safe for 5 s"
        flags and Hints.GLIDE != 0 && since < 7f -> "Fly along the edge of the shape to glide faster"
        flags and Hints.INK != 0 && hud.ink >= 1f && hud.captures < 6 -> "Ink is full! Tap the ink drop to splash land"
        flags and Hints.INK != 0 && since < 7f -> "Claim land to fill your Ink Burst meter"
        flags and Hints.BOMB != 0 && since < 7f -> "Paint Bombs splash a disc of land around you"
        flags and Hints.RADAR != 0 && since in 7f..13f -> "Edge arrows show where rivals are"
        flags and Hints.KING != 0 && !hud.bossPopped && since < 9f -> "Snip the Paper King's ribbon when he leaves his land!"
        flags and Hints.COINS != 0 && since < 7f -> "Collect the paper stars before time runs out!"
        else -> null
    } ?: return
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 110.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(PC.Ink.copy(alpha = 0.86f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_hand), null, Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = Type.label.copy(color = Color.White), textAlign = TextAlign.Center)
        }
    }
}

@Suppress("unused")
private fun dirOffset(a: Float, r: Float) = Offset(cos(a) * r, sin(a) * r)

@Suppress("unused")
private val unusedFloat = mutableFloatStateOf(0f)
