package com.ngstudio.paperclash2.ui.quests

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.platform.LocalContext
import com.ngstudio.paperclash2.ads.Placements
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Achievements
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.data.Quests
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.components.ConfettiRain
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.GameDialog
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.PaperBackground
import com.ngstudio.paperclash2.ui.components.PaperCard
import com.ngstudio.paperclash2.ui.components.ProgressStrip
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
fun QuestsTab(state: AppState) {
    val save0 by state.save.state.collectAsState()
    LaunchedEffect(save0.quests.day) {
        val refreshed = Quests.refresh(state.data)
        if (refreshed !== state.data) state.save.update { Quests.refresh(it) }
    }
    val save = save0
    val fx = LocalFx.current
    PaperBackground(base = Color(0xFFF2F0FF)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            VSpace(12.dp)
            OutlinedText("QUESTS", Type.title.copy(color = Color.White), outline = PC.Ink, width = 9f)
            VSpace(12.dp)
            // daily gift strip
            PaperCard(Modifier.fillMaxWidth(), tape = PC.Coral) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily Gift", style = Type.heading, modifier = Modifier.weight(1f))
                    if (Progress.dailyAvailable(save)) GameButton("OPEN", { state.dailyOpen = true }, Modifier.width(110.dp), height = 40.dp, color = PC.Coral, dark = PC.CoralDark, pulse = true, style = Type.button.copy(fontSize = 16.sp))
                    else Text("Back tomorrow", style = Type.small)
                }
                VSpace(8.dp)
                DayStrip(Progress.dailyIndex(save), Progress.dailyAvailable(save))
            }
            VSpace(14.dp)
            // spin
            PaperCard(Modifier.fillMaxWidth(), tape = PC.Sun) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_spin), null, Modifier.size(56.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Lucky Spin", style = Type.heading)
                        Text(if (Progress.spinAvailable(save)) "Your free spin is ready!" else "Free spin again tomorrow", style = Type.small)
                    }
                    GameButton("SPIN", { state.spinOpen = true }, Modifier.width(96.dp), height = 42.dp, color = PC.Sun, dark = PC.SunDark,
                        pulse = Progress.spinAvailable(save), style = Type.button.copy(fontSize = 16.sp))
                }
            }
            VSpace(14.dp)
            // daily quests
            PaperCard(Modifier.fillMaxWidth(), tape = PC.Mint) {
                Text("Today's Quests", style = Type.heading)
                VSpace(8.dp)
                save.quests.slots.forEachIndexed { i, q ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Quests.title(q), style = Type.label)
                            VSpace(4.dp)
                            ProgressStrip(q.progress.toFloat() / q.target, Modifier.fillMaxWidth(), color = if (q.claimed) PC.InkFaint else PC.Mint)
                            Text("${q.progress}/${q.target}", style = Type.small.copy(fontSize = 11.sp))
                        }
                        Spacer(Modifier.width(10.dp))
                        when {
                            q.claimed -> Image(painterResource(R.drawable.ic_check), "Done", Modifier.size(36.dp))
                            q.progress >= q.target -> GameButton("${q.reward}", {
                                state.save.update { Quests.claim(it, i) }
                                fx.play(Sfx.COIN)
                                fx.haptics.click()
                            }, Modifier.width(92.dp), height = 40.dp, icon = R.drawable.ic_coin, color = PC.Mint, dark = PC.MintDark, pulse = true, style = Type.button.copy(fontSize = 15.sp))
                            !save.quests.rerolled && state.app.ads.rewardedAvailable -> GameButton("", {
                                state.app.ads.showRewarded(Placements.QUEST_REROLL) { ok -> if (ok) state.save.update { Quests.reroll(it, i) } }
                            }, Modifier.width(52.dp), height = 40.dp, icon = R.drawable.ic_restart, color = PC.Grape, dark = PC.GrapeDark)
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(R.drawable.ic_coin), null, Modifier.size(20.dp))
                                Text("${q.reward}", style = Type.label)
                            }
                        }
                    }
                }
            }
            VSpace(14.dp)
            PaperCard(Modifier.fillMaxWidth(), tape = PC.Grape) {
                Text("Achievements", style = Type.heading)
                VSpace(8.dp)
                Achievements.all.forEach { a ->
                    val claimed = Achievements.claimedTiers(save, a)
                    val reached = Achievements.reachedTiers(save, a)
                    val nextTier = a.tiers.getOrNull(claimed)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_trophy), null, Modifier.size(38.dp).graphicsLayer { alpha = if (claimed > 0) 1f else 0.5f })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${a.title}  ${"★".repeat(claimed)}", style = Type.label)
                            if (nextTier != null) {
                                val v = a.value(save)
                                ProgressStrip(v.toFloat() / nextTier, Modifier.fillMaxWidth(), color = PC.Grape)
                                Text("${v.coerceAtMost(nextTier)}/$nextTier", style = Type.small.copy(fontSize = 11.sp))
                            } else Text("All tiers complete!", style = Type.small)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (reached > claimed) {
                            GameButton("CLAIM", {
                                val (s, label) = Achievements.claim(state.data, a)
                                state.save.update { s }
                                fx.play(Sfx.REWARD_BIG)
                                state.toast = label
                            }, Modifier.width(92.dp), height = 40.dp, color = PC.Grape, dark = PC.GrapeDark, pulse = true, style = Type.button.copy(fontSize = 15.sp))
                        }
                    }
                }
            }
            VSpace(120.dp)
        }
    }
}

@Composable
private fun DayStrip(today: Int, available: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in 0 until 7) {
            val done = d < today
            val now = d == today && available
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (now) PC.Sun.copy(alpha = 0.45f) else if (done) PC.Mint.copy(alpha = 0.25f) else PC.Cream)
                    .border(2.dp, if (now) PC.SunDark else Color(0xFFEADFC8), RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("D${d + 1}", style = Type.small.copy(fontSize = 10.sp))
                Image(
                    painterResource(when (d) { 2 -> R.drawable.ic_boost_big; 5 -> R.drawable.ic_sticker; 6 -> R.drawable.ic_chest; else -> R.drawable.ic_coin }),
                    null, Modifier.size(24.dp).graphicsLayer { alpha = if (done) 0.45f else 1f },
                )
                if (done) Image(painterResource(R.drawable.ic_check), null, Modifier.size(14.dp))
            }
        }
    }
}

/** 7-day gift calendar with the claim animation. */
@Composable
fun DailyGiftDialog(state: AppState) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    var claimed by remember { mutableStateOf<String?>(null) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(claimed) { if (claimed != null) pop.animateTo(1f, tween(500)) }
    Box(Modifier.fillMaxSize()) {
        GameDialog(onDismiss = { state.dailyOpen = false }, tape = PC.Coral) {
            Text("Daily Gift", style = Type.title, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("Come back every day for bigger gifts. Missing a day just pauses your streak.", style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            VSpace(12.dp)
            DayStrip(Progress.dailyIndex(save), claimed == null && Progress.dailyAvailable(save))
            VSpace(16.dp)
            if (claimed != null) {
                Image(painterResource(R.drawable.ic_gift), null, Modifier.size(84.dp).align(Alignment.CenterHorizontally).graphicsLayer { scaleX = 0.6f + 0.4f * pop.value; scaleY = 0.6f + 0.4f * pop.value })
                Text(claimed!!, style = Type.heading, modifier = Modifier.align(Alignment.CenterHorizontally))
                VSpace(12.dp)
                GameButton("NICE!", { state.dailyOpen = false }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark)
            } else if (Progress.dailyAvailable(save)) {
                GameButton("CLAIM", {
                    val (s, label) = Progress.claimDaily(state.data)
                    state.save.update { s }
                    claimed = label
                    fx.play(Sfx.DAILY_GIFT)
                    fx.haptics.click()
                }, Modifier.fillMaxWidth(), color = PC.Coral, dark = PC.CoralDark, icon = R.drawable.ic_gift, pulse = true)
            } else {
                Text("Today's gift is collected. See you tomorrow!", style = Type.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                VSpace(12.dp)
                GameButton("OK", { state.dailyOpen = false }, Modifier.fillMaxWidth(), color = PC.Sky, dark = PC.SkyDark)
            }
        }
        if (claimed != null) ConfettiRain()
    }
}

/** The Lucky Spin wheel: free once a day (plus a simulated-ad spin in debug builds). */
@Composable
fun SpinDialog(state: AppState) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    val scope = rememberCoroutineScope()
    val rot = remember { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }
    var result by remember { mutableIntStateOf(-1) }
    val prizes = Progress.spinPrizes
    val seg = 360f / prizes.size
    LaunchedEffect(Unit) {
        var last = 0
        snapshotFlow { rot.value }.collect { v ->
            val idx = (v / seg).toInt()
            if (idx != last && spinning) {
                last = idx
                fx.play(Sfx.SPIN_TICK, rate = 0.9f + (idx % 3) * 0.08f)
            }
        }
    }
    fun spin(free: Boolean) {
        if (spinning) return
        spinning = true
        result = -1
        val idx = Progress.rollSpin()
        // pointer at the top: segment i spans [i*seg, (i+1)*seg) measured clockwise from the top
        val target = rot.value - (rot.value % 360f) + 360f * 5 + (360f - (idx * seg + seg / 2))
        scope.launch {
            rot.animateTo(target, tween(3600, easing = FastOutSlowInEasing))
            state.save.update { Progress.applySpin(it, idx, free) }
            result = idx
            spinning = false
            fx.play(Sfx.SPIN_WIN)
            fx.haptics.click()
        }
    }
    val colors = listOf(PC.Coral, PC.Sky, PC.Sun, PC.Mint, PC.Pink, PC.Grape, PC.Kraft, PC.SkyDark)
    val ctx = LocalContext.current
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = ResourcesCompat.getFont(ctx, R.font.fredoka_bold)
            setShadowLayer(4f, 0f, 2f, android.graphics.Color.argb(120, 20, 20, 60))
        }
    }
    Box(Modifier.fillMaxSize()) {
        GameDialog(onDismiss = { if (!spinning) state.spinOpen = false }, tape = PC.Sun) {
            Text("Lucky Spin", style = Type.title, modifier = Modifier.align(Alignment.CenterHorizontally))
            VSpace(10.dp)
            Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(250.dp).graphicsLayer { rotationZ = rot.value }) {
                    val r = size.minDimension / 2
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(Color(0x33101030), r, c + Offset(0f, 8f))
                    for (i in prizes.indices) {
                        drawArc(colors[i % colors.size], -90f + i * seg, seg, true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
                    }
                    drawCircle(Color.White, r, c, style = Stroke(8.dp.toPx()))
                    labelPaint.textSize = 17.sp.toPx()
                    for (i in prizes.indices) {
                        rotate(i * seg + seg / 2, c) {
                            drawContext.canvas.nativeCanvas.drawText(prizes[i].label, c.x, c.y - r * 0.62f, labelPaint)
                        }
                        // little pegs between the slices
                        rotate(i * seg, c) { drawCircle(Color.White, 3.5.dp.toPx(), Offset(c.x, c.y - r + 9.dp.toPx())) }
                    }
                    drawCircle(PC.Sun, r * 0.16f, c)
                    drawCircle(Color.White.copy(alpha = 0.55f), r * 0.07f, c + Offset(-r * 0.04f, -r * 0.05f))
                    drawCircle(PC.Ink, r * 0.16f, c, style = Stroke(3.dp.toPx()))
                }
                // pointer
                Canvas(Modifier.size(34.dp).align(Alignment.TopCenter).offset(y = 2.dp)) {
                    val p = Path().apply { moveTo(size.width / 2, size.height); lineTo(0f, 0f); lineTo(size.width, 0f); close() }
                    drawPath(p, PC.Ink)
                    drawPath(p, PC.Coral, style = Stroke(3.dp.toPx()))
                }
            }
            VSpace(8.dp)
            if (result >= 0) {
                val p = prizes[result]
                Text(if (p.coins > 0) "You won ${p.coins} coins!" else "You won a ${p.label} booster!", style = Type.heading, modifier = Modifier.align(Alignment.CenterHorizontally))
                VSpace(8.dp)
            }
            when {
                Progress.spinAvailable(save) -> GameButton("SPIN FREE", { spin(true) }, Modifier.fillMaxWidth(), color = PC.Coral, dark = PC.CoralDark, enabled = !spinning, pulse = !spinning)
                state.app.ads.rewardedAvailable -> GameButton("SPIN AGAIN", {
                    state.app.ads.showRewarded(Placements.EXTRA_SPIN) { ok -> if (ok) spin(false) }
                }, Modifier.fillMaxWidth(), color = PC.Grape, dark = PC.GrapeDark, icon = R.drawable.ic_ad, enabled = !spinning)
                else -> GameButton("CLOSE", { state.spinOpen = false }, Modifier.fillMaxWidth(), color = PC.Sky, dark = PC.SkyDark, enabled = !spinning)
            }
        }
        if (result >= 0 && !spinning) ConfettiRain(pieces = 60)
    }
}
