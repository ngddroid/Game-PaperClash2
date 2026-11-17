package com.ngstudio.paperclash2.ui.main

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Achievements
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.data.Quests
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.level.Worlds
import com.ngstudio.paperclash2.engine.shape.ShapeCategory
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.Screen
import com.ngstudio.paperclash2.ui.Tab
import com.ngstudio.paperclash2.ui.album.AlbumTab
import com.ngstudio.paperclash2.ui.components.CoinPill
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.Pill
import com.ngstudio.paperclash2.ui.components.ProgressStrip
import com.ngstudio.paperclash2.ui.components.RoundButton
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.components.darken
import com.ngstudio.paperclash2.ui.components.lighten
import com.ngstudio.paperclash2.ui.levels.LevelsTab
import com.ngstudio.paperclash2.ui.levels.modeIcon
import com.ngstudio.paperclash2.ui.quests.QuestsTab
import com.ngstudio.paperclash2.ui.shop.ShopTab
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type

@Composable
fun MainScreen(state: AppState) {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(300)) { it / 3 * dir } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally(tween(260)) { -it / 3 * dir } + fadeOut(tween(200)))
            },
            label = "tab",
        ) { tab ->
            when (tab) {
                Tab.HOME -> HomeTab(state)
                Tab.LEVELS -> LevelsTab(state)
                Tab.SHOP -> ShopTab(state)
                Tab.ALBUM -> AlbumTab(state)
                Tab.QUESTS -> QuestsTab(state)
            }
        }
        // A soft paper floor under the dock so scrolled lists don't show through below it.
        val floor by animateFloatAsState(if (state.tab == Tab.HOME) 0f else 1f, tween(250), label = "floor")
        if (floor > 0.01f) {
            val navH = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navH + 58.dp)
                    .graphicsLayer { alpha = floor }
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to PC.Cream.copy(alpha = 0.92f), 1f to PC.Cream)),
            )
        }
        Dock(state, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------------- dock

@Composable
private fun Dock(state: AppState, modifier: Modifier) {
    val save by state.save.state.collectAsState()
    val questBadge = Quests.claimable(save) + Achievements.claimable(save) + (if (Progress.dailyAvailable(save)) 1 else 0)
    val albumBadge = ShapeCategory.entries.count { Progress.pageComplete(save, it) && it.name !in save.albumPagesClaimed }
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
                .drawBehind { drawRoundRect(PC.Shadow, topLeft = Offset(0f, 6.dp.toPx()), size = size, cornerRadius = CornerRadius(26.dp.toPx())) }
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(PC.Paper, PC.Cream)))
                .border(2.dp, Color(0xFFEADFC8), RoundedCornerShape(26.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockItem(state, Tab.SHOP, R.drawable.ic_nav_shop, "Shop", 0, Modifier.weight(1f))
            DockItem(state, Tab.LEVELS, R.drawable.ic_nav_levels, "Levels", 0, Modifier.weight(1f))
            Spacer(Modifier.weight(1.1f))
            DockItem(state, Tab.ALBUM, R.drawable.ic_nav_album, "Album", albumBadge, Modifier.weight(1f))
            DockItem(state, Tab.QUESTS, R.drawable.ic_nav_quests, "Quests", questBadge, Modifier.weight(1f))
        }
        HomeBubble(state, Modifier.offset(y = (-22).dp))
    }
}

@Composable
private fun DockItem(state: AppState, tab: Tab, icon: Int, label: String, badge: Int, modifier: Modifier) {
    val fx = LocalFx.current
    val on = state.tab == tab
    val lift by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "lift")
    Column(
        modifier
            .fillMaxSize()
            .clickable(MutableInteractionSource(), indication = null) {
                if (state.tab != tab) {
                    fx.play(Sfx.UI_TAP)
                    fx.haptics.tick()
                    state.tab = tab
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(46.dp).clip(CircleShape).background(PC.Sun.copy(alpha = 0.35f)))
            Image(
                painterResource(icon), label,
                Modifier.size(40.dp).graphicsLayer {
                    translationY = -10f * lift
                    val s = 1f + 0.12f * lift
                    scaleX = s
                    scaleY = s
                },
            )
            if (badge > 0) com.ngstudio.paperclash2.ui.components.Badge(badge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
        }
        Text(label, style = Type.small.copy(fontSize = 11.sp, color = if (on) PC.Ink else PC.InkFaint))
    }
}

@Composable
private fun HomeBubble(state: AppState, modifier: Modifier) {
    val fx = LocalFx.current
    val on = state.tab == Tab.HOME
    val s by animateFloatAsState(if (on) 1.05f else 0.94f, spring(dampingRatio = 0.5f), label = "home")
    Box(
        modifier
            .size(76.dp)
            .graphicsLayer { scaleX = s; scaleY = s }
            .drawBehind { drawCircle(PC.Shadow, center = Offset(size.width / 2, size.height / 2 + 5.dp.toPx())) }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(PC.Coral.lighten(0.2f), PC.Coral)))
            .border(4.dp, PC.Paper, CircleShape)
            .clickable(MutableInteractionSource(), indication = null) {
                if (state.tab != Tab.HOME) {
                    fx.play(Sfx.UI_TAP)
                    fx.haptics.tick()
                    state.tab = Tab.HOME
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_nav_home), "Home", Modifier.size(48.dp))
    }
}

// ---------------------------------------------------------------------- home

@Composable
fun HomeTab(state: AppState) {
    val save by state.save.state.collectAsState()
    val level = Progress.currentLevel(save)
    val spec = remember(level) { LevelGenerator.spec(level) }
    val totalStars = Progress.totalStars(save)
    Box(Modifier.fillMaxSize()) {
        // readability veils over the live board
        Box(Modifier.fillMaxWidth().height(230.dp).background(Brush.verticalGradient(listOf(PC.Cream.copy(alpha = 0.92f), PC.Cream.copy(alpha = 0f)))))
        Box(Modifier.fillMaxWidth().height(360.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(PC.Cream.copy(alpha = 0f), PC.Cream.copy(alpha = 0.95f)))))

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(R.drawable.ic_profile, { state.go(Screen.Profile) }, contentDescription = "Profile")
                Spacer(Modifier.width(8.dp))
                CoinPill(save.coins)
                Spacer(Modifier.weight(1f))
                Pill(R.drawable.ic_star, totalStars.toString())
                Spacer(Modifier.width(8.dp))
                RoundButton(R.drawable.ic_settings, { state.go(Screen.Settings) }, contentDescription = "Settings")
            }
            VSpace(10.dp)
            StarRoad(state)
            VSpace(14.dp)
            Row(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SideButton(R.drawable.ic_gift, "Gift", if (Progress.dailyAvailable(save)) 1 else 0) { state.dailyOpen = true }
                    SideButton(R.drawable.ic_spin, "Spin", if (Progress.spinAvailable(save)) 1 else 0) { state.spinOpen = true }
                }
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
                    SideButton(R.drawable.ic_nav_quests, "Quests", Quests.claimable(save)) { state.tab = Tab.QUESTS }
                    SideButton(R.drawable.ic_sticker, "${save.album.size}/${ShapeLibrary.all.size}", 0) { state.tab = Tab.ALBUM }
                }
            }
            Spacer(Modifier.weight(1f))
            // level card
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 128.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val world = Worlds.worldOf(level)
                Text("WORLD $world · ${Worlds.name(world).uppercase()}", style = Type.small.copy(color = PC.InkSoft, letterSpacing = 1.sp))
                VSpace(2.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedText("LEVEL $level", Type.hero.copy(color = Color.White), outline = PC.Ink, width = 11f)
                    if (spec.mode != Mode.CLAIM) {
                        Spacer(Modifier.width(8.dp))
                        Image(painterResource(modeIcon(spec.mode)), spec.mode.title, Modifier.size(40.dp))
                    }
                }
                Text(spec.goal.summary(), style = Type.label.copy(color = PC.InkSoft), textAlign = TextAlign.Center)
                VSpace(12.dp)
                GameButton(
                    "PLAY", { state.openLevel(level) },
                    Modifier.widthIn(max = 320.dp).fillMaxWidth(0.78f),
                    height = 68.dp, icon = R.drawable.ic_play, pulse = true,
                    color = PC.Mint, dark = PC.MintDark, style = Type.button.copy(fontSize = 28.sp),
                )
            }
        }
    }
}

@Composable
private fun SideButton(icon: Int, label: String, badge: Int, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "side")
    val bob by t.animateFloat(-2f, 2f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { translationY = if (badge > 0) bob * 2f else 0f }) {
        RoundButton(icon, onClick, size = 54.dp, badge = badge, contentDescription = label)
        Text(label, style = Type.small.copy(fontSize = 11.sp, color = PC.Ink))
    }
}

@Composable
private fun StarRoad(state: AppState) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    val stars = Progress.totalStars(save)
    val next = Progress.starRoadNext(save)
    val prev = next - Progress.STAR_ROAD_STEP
    val ready = Progress.canClaimStarRoad(save)
    val t = rememberInfiniteTransition(label = "chest")
    val shake by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "sh")
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRoundRect(PC.Shadow, topLeft = Offset(0f, 4.dp.toPx()), size = size, cornerRadius = CornerRadius(18.dp.toPx())) }
            .clip(RoundedCornerShape(18.dp))
            .background(PC.Paper)
            .border(2.dp, Color(0xFFEADFC8), RoundedCornerShape(18.dp))
            .clickable(enabled = ready) {
                val (s, label) = Progress.claimStarRoad(state.data)
                state.save.update { s }
                fx.play(Sfx.CHEST_OPEN)
                fx.haptics.click()
                state.toast = "Star Road: $label"
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (ready) "Star Road chest ready - tap to open!" else "Star Road", style = Type.label.copy(fontSize = 13.sp))
            VSpace(4.dp)
            ProgressStrip((stars - prev).toFloat() / (next - prev), Modifier.fillMaxWidth(), color = PC.Sun)
            VSpace(2.dp)
            Text("${stars.coerceAtMost(next)} / $next stars", style = Type.small.copy(fontSize = 11.sp))
        }
        Spacer(Modifier.width(10.dp))
        Image(
            painterResource(R.drawable.ic_chest), "Chest",
            Modifier.size(46.dp).graphicsLayer { rotationZ = if (ready) shake else 0f },
        )
    }
}

/** Shared helper: a sticker icon with a label underneath. */
@Composable
fun IconLabel(icon: Int, text: String, size: Dp = 26.dp, color: Color = PC.Ink) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.size(size))
        Spacer(Modifier.width(4.dp))
        Text(text, style = Type.label.copy(color = color))
    }
}

@Suppress("unused")
private fun Color.dim() = this.darken(0.1f)
