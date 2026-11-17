package com.ngstudio.paperclash2.ui.levels

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.ads.Placements
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.level.Worlds
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.game.DeskThemes
import com.ngstudio.paperclash2.game.Skins
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.art.PlaneIcon
import com.ngstudio.paperclash2.ui.art.ShapeThumb
import com.ngstudio.paperclash2.ui.components.CoinPill
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.GameDialog
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.RibbonBanner
import com.ngstudio.paperclash2.ui.components.RoundButton
import com.ngstudio.paperclash2.ui.components.StarRow
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.components.darken
import com.ngstudio.paperclash2.ui.components.lighten
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.sin
import com.ngstudio.paperclash2.engine.game.Boosters as GameBoosters

fun modeIcon(m: Mode): Int = when (m) {
    Mode.CLAIM -> R.drawable.ic_mode_claim
    Mode.COIN_RUSH -> R.drawable.ic_mode_rush
    Mode.KNOCKOUT -> R.drawable.ic_mode_knockout
    Mode.DOMINATE -> R.drawable.ic_mode_dominate
    Mode.SURVIVE -> R.drawable.ic_mode_survive
    Mode.BOSS -> R.drawable.ic_mode_boss
}

private fun worldColor(world: Int): Color {
    val t = DeskThemes.forWorld(Worlds.themeOf(world), Worlds.lapOf(world))
    return Color(t.deskA)
}

private fun nodeX(level: Int): Float = 0.5f + 0.27f * sin(level * 0.75f)

/** Index in the list for a level (world headers come before each world's first level). */
private fun indexOf(level: Int): Int = level - 1 + Worlds.worldOf(level)

/** The 5,000-level path: a scrolling map from level 1 at the bottom upward, a banner per world. */
@Composable
fun LevelsTab(state: AppState) {
    val save by state.save.state.collectAsState()
    val current = Progress.currentLevel(save)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (indexOf(current) - 2).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val total = Worlds.TOTAL_LEVELS + Worlds.COUNT
    val far by remember { derivedStateOf { kotlin.math.abs(list.firstVisibleItemIndex - indexOf(current)) > 8 } }
    val skin = Skins.get(save.skin)
    Box(Modifier.fillMaxSize().background(PC.Cream)) {
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 120.dp, bottom = 190.dp)) {
            items(total, key = { it }) { i ->
                // decode i into header / level
                val world = worldForIndex(i)
                val first = Worlds.firstLevel(world)
                val headerIndex = indexOf(first) - 1
                if (i == headerIndex) WorldHeader(world, save)
                else {
                    val level = first + (i - headerIndex - 1)
                    LevelRow(state, level, current, save, skin)
                }
            }
        }
        // top bar
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(PC.Cream, PC.Cream.copy(alpha = 0f)))).statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedText("LEVELS", Type.title.copy(color = Color.White), outline = PC.Ink, width = 9f)
            Spacer(Modifier.weight(1f))
            CoinPill(save.coins)
        }
        if (far) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 104.dp, end = 16.dp), contentAlignment = Alignment.BottomEnd) {
                RoundButton(R.drawable.ic_target, { scope.launch { list.animateScrollToItem((indexOf(current) - 2).coerceAtLeast(0)) } }, size = 56.dp, color = PC.Sun, dark = PC.SunDark, contentDescription = "Current level")
            }
        }
    }
}

private fun worldForIndex(i: Int): Int {
    // each world takes 51 rows: header + 50 levels
    return (i / (Worlds.LEVELS_PER_WORLD + 1)) + 1
}

@Composable
private fun WorldHeader(world: Int, save: com.ngstudio.paperclash2.data.SaveData) {
    val color = worldColor(world)
    val first = Worlds.firstLevel(world)
    var stars = 0
    for (l in first until first + Worlds.LEVELS_PER_WORLD) stars += Progress.starsOf(save, l)
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.18f)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RibbonBanner("WORLD $world", Modifier.width(260.dp), color = color.lighten(0.05f), height = 58.dp, style = Type.heading.copy(fontSize = 24.sp))
        Text(Worlds.name(world), style = Type.heading.copy(color = color.darken(0.35f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_star), null, Modifier.size(20.dp))
            Text(" $stars / ${Worlds.LEVELS_PER_WORLD * 3}   ·   Levels $first-${first + Worlds.LEVELS_PER_WORLD - 1}", style = Type.small)
        }
    }
}

@Composable
private fun LevelRow(state: AppState, level: Int, current: Int, save: com.ngstudio.paperclash2.data.SaveData, skin: com.ngstudio.paperclash2.game.Skin) {
    val fx = LocalFx.current
    val world = Worlds.worldOf(level)
    val color = worldColor(world)
    val unlocked = Progress.isUnlocked(save, level)
    val stars = Progress.starsOf(save, level)
    val isCurrent = level == current
    val mode = remember(level) { LevelGenerator.modeFor(level) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp).background(color.copy(alpha = 0.10f))) {
        val w = maxWidth
        val x = nodeX(level)
        val xNext = nodeX(level + 1)
        val xPrev = nodeX(level - 1)
        val nextUnlocked = Progress.isUnlocked(save, level + 1)
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * x
            val cy = size.height / 2
            val topX = size.width * (x + xNext) / 2
            val botX = size.width * (x + xPrev) / 2
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
            if (level < Worlds.TOTAL_LEVELS) {
                val up = Path().apply { moveTo(cx, cy); quadraticTo(cx, cy - size.height * 0.3f, topX, 0f) }
                drawPath(up, Color.White, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                drawPath(up, if (nextUnlocked) color.darken(0.1f) else PC.Locked, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
            }
            if (level > 1) {
                val down = Path().apply { moveTo(cx, cy); quadraticTo(cx, cy + size.height * 0.3f, botX, size.height) }
                drawPath(down, Color.White, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                drawPath(down, if (unlocked) color.darken(0.1f) else PC.Locked, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
            }
        }
        val nodeSize = if (isCurrent) 68.dp else 58.dp
        Box(
            Modifier
                .offset(x = w * x - nodeSize / 2, y = (96.dp - nodeSize) / 2)
                .size(nodeSize),
        ) {
            LevelNode(level, unlocked, stars, isCurrent, mode, color) {
                fx.play(if (unlocked) Sfx.UI_TAP else Sfx.UI_ERROR)
                state.openLevel(level)
            }
        }
        if (isCurrent) {
            val t = rememberInfiniteTransition(label = "me")
            val bob by t.animateFloat(-5f, 5f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
            PlaneIcon(skin, 46.dp, Modifier.offset(x = w * x + 26.dp, y = 4.dp).graphicsLayer { translationY = bob * density }, deg = -30f)
        }
    }
}

@Composable
private fun LevelNode(level: Int, unlocked: Boolean, stars: Int, current: Boolean, mode: Mode, color: Color, onClick: () -> Unit) {
    val pulse = if (current) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 1.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pl").value
    } else 1f
    val boss = mode == Mode.BOSS
    val face = when {
        !unlocked -> PC.Locked
        boss -> PC.Grape
        else -> color
    }
    Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        if (current) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = 1.6f - pulse }.clip(CircleShape).background(PC.Sun.copy(alpha = 0.5f)))
        }
        Box(
            Modifier
                .fillMaxSize(0.92f)
                .drawBehind { drawCircle(PC.Shadow, center = Offset(size.width / 2, size.height / 2 + 4.dp.toPx())) }
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(face.lighten(0.25f), face)))
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (!unlocked) Image(painterResource(R.drawable.ic_lock), "Locked", Modifier.size(28.dp))
            else OutlinedText(level.toString(), Type.number.copy(color = Color.White, fontSize = if (level >= 1000) 15.sp else 18.sp), outline = face.darken(0.45f), width = 6f)
        }
        if (unlocked && stars > 0) StarRow(stars, Modifier.align(Alignment.BottomCenter).offset(y = 14.dp), size = 16.dp, spacing = 0.dp, raiseMiddle = true)
        if (mode != Mode.CLAIM) {
            Image(painterResource(modeIcon(mode)), mode.title, Modifier.size(24.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
        }
    }
}

// ---------------------------------------------------------------------- level sheet

/** Level details before playing: shape, goal, stars, rivals, boosters, PLAY. */
@Composable
fun LevelSheet(state: AppState, level: Int) {
    val save by state.save.state.collectAsState()
    val spec = remember(level) { LevelGenerator.spec(level) }
    val shape = ShapeLibrary.byId(spec.shapeId)
    var useBig by remember { mutableStateOf(false) }
    var useShield by remember { mutableStateOf(false) }
    var useInk by remember { mutableStateOf(false) }
    val world = Worlds.worldOf(level)
    val color = worldColor(world)
    GameDialog(onDismiss = { state.levelSheet = null }, tape = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                ShapeThumb(level, null, color.darken(0.1f), 80.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("LEVEL $level", style = Type.title)
                Text("${shape.name} · ${Worlds.name(world)}", style = Type.small)
                VSpace(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(modeIcon(spec.mode)), null, Modifier.size(26.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(spec.mode.title, style = Type.label)
                    Spacer(Modifier.width(10.dp))
                    Difficulty(spec.difficulty)
                }
            }
        }
        VSpace(14.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PC.Cream).padding(12.dp)) {
            Column {
                Text(spec.goal.summary(), style = Type.heading.copy(fontSize = 18.sp))
                VSpace(6.dp)
                spec.goal.starLines().forEachIndexed { i, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StarRow(i + 1, size = 16.dp, spacing = 0.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(line, style = Type.body)
                    }
                }
                VSpace(6.dp)
                Text("${spec.rivals.size} rival${if (spec.rivals.size > 1) "s" else ""} · ${spec.timeLimit.toInt()} s on the clock" +
                    (if (spec.hazards > 0) " · scissor spinners!" else ""), style = Type.small)
            }
        }
        VSpace(12.dp)
        Text("Boosters", style = Type.label)
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoosterChip(R.drawable.ic_boost_big, "Big Start", save.boosters.big, useBig, Modifier.weight(1f)) { useBig = it }
            BoosterChip(R.drawable.ic_boost_shield, "Shield", save.boosters.shield, useShield, Modifier.weight(1f)) { useShield = it }
            if (spec.inkEnabled) BoosterChip(R.drawable.ic_boost_ink, "Full Ink", save.boosters.ink, useInk, Modifier.weight(1f)) { useInk = it }
        }
        VSpace(16.dp)
        StarRow(Progress.starsOf(save, level), Modifier.align(Alignment.CenterHorizontally), size = 30.dp, raiseMiddle = true)
        VSpace(10.dp)
        GameButton("PLAY", {
            state.save.update {
                it.copy(boosters = it.boosters.copy(
                    big = it.boosters.big - if (useBig) 1 else 0,
                    shield = it.boosters.shield - if (useShield) 1 else 0,
                    ink = it.boosters.ink - if (useInk) 1 else 0,
                ))
            }
            state.play(level, GameBoosters(useBig, useShield, useInk))
        }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_play, height = 62.dp, pulse = true)
    }
}

@Composable
private fun Difficulty(d: Float) {
    val pips = (1 + (d * 4.99f).toInt()).coerceIn(1, 5)
    Row {
        for (i in 0 until 5) {
            Box(Modifier.padding(1.dp).size(width = 10.dp, height = 12.dp).clip(RoundedCornerShape(3.dp))
                .background(if (i < pips) listOf(PC.Mint, PC.Mint, PC.Sun, PC.Coral, PC.Grape)[pips - 1] else PC.Locked.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun BoosterChip(icon: Int, label: String, count: Int, on: Boolean, modifier: Modifier, onToggle: (Boolean) -> Unit) {
    val fx = LocalFx.current
    val enabled = count > 0
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) PC.Sun.copy(alpha = 0.35f) else PC.Cream)
            .border(2.dp, if (on) PC.SunDark else Color(0xFFEADFC8), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) {
                fx.play(Sfx.UI_TOGGLE)
                onToggle(!on)
            }
            .padding(vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Image(painterResource(icon), label, Modifier.size(38.dp))
            Text("x$count", style = Type.small.copy(fontSize = 11.sp, color = PC.Ink), modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp))
        }
        Text(label, style = Type.small.copy(fontSize = 11.sp), textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------- locked

/**
 * Tapping a locked level. Debug builds offer the simulated rewarded ad that unlocks it on the
 * spot; release builds (no ad network yet) point to the level to beat first.
 */
@Composable
fun UnlockDialog(state: AppState, level: Int) {
    val fx = LocalFx.current
    val ads = state.app.ads
    val need = Progress.currentLevel(state.data)
    GameDialog(onDismiss = { state.unlockFor = null }, tape = PC.Grape) {
        Image(painterResource(R.drawable.ic_lock), null, Modifier.size(72.dp).align(Alignment.CenterHorizontally))
        VSpace(6.dp)
        Text("Level $level is locked", style = Type.heading, modifier = Modifier.align(Alignment.CenterHorizontally))
        VSpace(8.dp)
        if (ads.rewardedAvailable) {
            Text("Watch a short ad to unlock this level right now.", style = Type.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            VSpace(18.dp)
            GameButton("WATCH AD", {
                ads.showRewarded(Placements.UNLOCK_LEVEL) { ok ->
                    if (ok) {
                        state.save.update { Progress.unlockByAd(it, level) }
                        fx.play(Sfx.UNLOCK)
                        fx.haptics.click()
                        state.unlockFor = null
                        state.levelSheet = level
                    }
                }
            }, Modifier.fillMaxWidth(), color = PC.Grape, dark = PC.GrapeDark, icon = R.drawable.ic_ad)
            VSpace(8.dp)
            GameButton("Not now", { state.unlockFor = null }, Modifier.fillMaxWidth(), color = PC.Paper, dark = PC.PaperDim,
                style = Type.button.copy(color = PC.Ink, fontSize = 17.sp), height = 48.dp, sound = Sfx.UI_BACK)
        } else {
            Text("Beat Level $need to open the way. Every win unlocks the next level!", style = Type.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            VSpace(18.dp)
            GameButton("PLAY LEVEL $need", {
                state.unlockFor = null
                state.levelSheet = need
            }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_play)
            VSpace(8.dp)
            GameButton("OK", { state.unlockFor = null }, Modifier.fillMaxWidth(), color = PC.Paper, dark = PC.PaperDim,
                style = Type.button.copy(color = PC.Ink, fontSize = 17.sp), height = 48.dp, sound = Sfx.UI_BACK)
        }
    }
}
