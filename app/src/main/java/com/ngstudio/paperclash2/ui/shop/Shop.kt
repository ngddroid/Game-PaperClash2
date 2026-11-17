package com.ngstudio.paperclash2.ui.shop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.ads.Placements
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.game.PlayerLook
import com.ngstudio.paperclash2.game.Rarity
import com.ngstudio.paperclash2.game.Skins
import com.ngstudio.paperclash2.game.Unlock
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.art.PlaneIcon
import com.ngstudio.paperclash2.ui.components.CoinPill
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.PaperCard
import com.ngstudio.paperclash2.ui.components.Segmented
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type

private fun rarityColor(r: Rarity) = when (r) {
    Rarity.COMMON -> PC.Sky
    Rarity.RARE -> PC.Mint
    Rarity.EPIC -> PC.Grape
    Rarity.LEGEND -> PC.Sun
}

private fun unlockHint(u: Unlock) = when (u) {
    Unlock.STAR_ROAD -> "Star Road reward"
    Unlock.DAILY_GIFT -> "Daily gift, day 7"
    Unlock.ALBUM -> "Sticker album page reward"
    Unlock.ACHIEVEMENT -> "Achievement reward"
    else -> ""
}

/** Plane skins, ribbon styles and boosters; the selected item flies live in the GL preview above. */
@Composable
fun ShopTab(state: AppState) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    var tab by remember { mutableIntStateOf(0) }
    var selSkin by remember { mutableIntStateOf(save.skin) }
    var selRibbon by remember { mutableIntStateOf(save.ribbon) }
    LaunchedEffect(selSkin, selRibbon) {
        state.controller.updatePreview(PlayerLook(save.nickname, selSkin, selRibbon))
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // top: transparent window onto the live preview
        Box(Modifier.fillMaxWidth().weight(0.42f)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedText("SHOP", Type.title.copy(color = Color.White), outline = PC.Ink, width = 9f)
                Spacer(Modifier.weight(1f))
                CoinPill(save.coins)
            }
            val skin = Skins.get(selSkin)
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (tab == 0) {
                    OutlinedText(skin.name, Type.heading.copy(color = Color.White), outline = PC.Ink, width = 8f)
                    Text(skin.rarity.title.uppercase(), style = Type.small.copy(color = Color.White, fontSize = 11.sp),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(rarityColor(skin.rarity)).padding(horizontal = 10.dp, vertical = 2.dp))
                } else if (tab == 1) {
                    OutlinedText(Skins.ribbons[selRibbon].name, Type.heading.copy(color = Color.White), outline = PC.Ink, width = 8f)
                }
            }
        }
        PaperCard(Modifier.fillMaxWidth().weight(0.58f), corner = 28.dp, tape = PC.Pink, padding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp)) {
            Segmented(listOf("Planes", "Ribbons", "Boosters"), tab, { tab = it }, Modifier.fillMaxWidth())
            VSpace(10.dp)
            if (tab != 2) {
                ActionRow(state, tab, selSkin, selRibbon)
                VSpace(10.dp)
            }
            when (tab) {
                0 -> LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(bottom = 130.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(Skins.all, key = { it.style }) { s ->
                        val owned = s.style in save.ownedSkins
                        ItemCard(
                            selected = s.style == selSkin, equipped = s.style == save.skin, owned = owned,
                            price = if (s.unlock == Unlock.COINS) s.price else null, accent = rarityColor(s.rarity),
                            onClick = { selSkin = s.style; fx.play(Sfx.UI_TAP) },
                        ) { PlaneIcon(s, 64.dp) }
                    }
                }
                1 -> LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(bottom = 130.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(Skins.ribbons, key = { it.id }) { r ->
                        ItemCard(
                            selected = r.id == selRibbon, equipped = r.id == save.ribbon, owned = r.id in save.ownedRibbons,
                            price = if (r.unlock == Unlock.COINS) r.price else null, accent = PC.Coral,
                            onClick = { selRibbon = r.id; fx.play(Sfx.UI_TAP) },
                        ) { RibbonSwatch(r.id, Color(Skins.get(save.skin).base)) }
                    }
                }
                else -> BoostersShop(state)
            }
        }
    }
    }
}

@Composable
private fun ActionRow(state: AppState, tab: Int, selSkin: Int, selRibbon: Int) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
            if (tab == 0) {
                val s = Skins.get(selSkin)
                val owned = s.style in save.ownedSkins
                when {
                    owned && save.skin == s.style -> GameButton("EQUIPPED", {}, Modifier.fillMaxWidth(), enabled = false, height = 48.dp)
                    owned -> GameButton("EQUIP", { state.save.update { it.copy(skin = s.style) }; fx.play(Sfx.UI_PURCHASE) }, Modifier.fillMaxWidth(), color = PC.Sky, dark = PC.SkyDark, height = 48.dp)
                    s.unlock == Unlock.COINS -> GameButton("BUY  ${s.price}", {
                        val n = Progress.buySkin(state.data, s.style)
                        if (n != null) { state.save.update { n }; fx.play(Sfx.UI_PURCHASE); fx.haptics.click(); state.toast = "${s.name} is yours!" }
                        else { fx.play(Sfx.UI_ERROR); state.toast = "Not enough coins yet" }
                    }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_coin, height = 48.dp, enabled = save.coins >= s.price, sound = Sfx.UI_TAP)
                    state.app.ads.rewardedAvailable -> GameButton("UNLOCK WITH AD", {
                        state.app.ads.showRewarded(Placements.SKIN_UNLOCK) { ok ->
                            if (ok) { state.save.update { it.copy(ownedSkins = it.ownedSkins + s.style, skin = s.style) }; fx.play(Sfx.UNLOCK) }
                        }
                    }, Modifier.fillMaxWidth(), color = PC.Grape, dark = PC.GrapeDark, icon = R.drawable.ic_ad, height = 48.dp)
                    else -> GameButton(unlockHint(s.unlock), {}, Modifier.fillMaxWidth(), enabled = false, height = 48.dp, style = Type.button.copy(fontSize = 16.sp))
                }
            } else {
                val r = Skins.ribbons[selRibbon]
                val owned = r.id in save.ownedRibbons
                when {
                    owned && save.ribbon == r.id -> GameButton("EQUIPPED", {}, Modifier.fillMaxWidth(), enabled = false, height = 48.dp)
                    owned -> GameButton("EQUIP", { state.save.update { it.copy(ribbon = r.id) }; fx.play(Sfx.UI_PURCHASE) }, Modifier.fillMaxWidth(), color = PC.Sky, dark = PC.SkyDark, height = 48.dp)
                    r.unlock == Unlock.COINS -> GameButton("BUY  ${r.price}", {
                        val n = Progress.buyRibbon(state.data, r.id)
                        if (n != null) { state.save.update { n }; fx.play(Sfx.UI_PURCHASE); fx.haptics.click() } else fx.play(Sfx.UI_ERROR)
                    }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_coin, height = 48.dp, enabled = save.coins >= r.price)
                    else -> GameButton(unlockHint(r.unlock), {}, Modifier.fillMaxWidth(), enabled = false, height = 48.dp, style = Type.button.copy(fontSize = 16.sp))
                }
            }
}

@Composable
private fun ItemCard(selected: Boolean, equipped: Boolean, owned: Boolean, price: Int?, accent: Color, onClick: () -> Unit, art: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .aspectRatio(0.82f)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else PC.Cream)
            .border(if (selected) 3.dp else 2.dp, if (selected) accent else Color(0xFFEADFC8), shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            art()
            if (equipped) Image(painterResource(R.drawable.ic_check), "Equipped", Modifier.size(24.dp).align(Alignment.TopEnd))
            else if (!owned && price == null) Image(painterResource(R.drawable.ic_lock), "Locked", Modifier.size(22.dp).align(Alignment.TopEnd))
        }
        when {
            owned -> Text(if (equipped) "In use" else "Owned", style = Type.small.copy(fontSize = 11.sp, color = PC.MintDark))
            price != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_coin), null, Modifier.size(16.dp))
                Text(price.toString(), style = Type.label.copy(fontSize = 12.sp))
            }
            else -> Text("Reward", style = Type.small.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RibbonSwatch(id: Int, color: Color) {
    Canvas(Modifier.size(64.dp)) {
        val y0 = size.height * 0.75f
        val y1 = size.height * 0.25f
        val c2 = Color.White
        val w = 12.dp.toPx()
        val start = Offset(size.width * 0.12f, y0)
        val end = Offset(size.width * 0.88f, y1)
        drawLine(Color(0x33101030), start + Offset(2f, 5f), end + Offset(2f, 5f), w, StrokeCap.Round)
        when (id) {
            5 -> drawLine(Brush.linearGradient(listOf(PC.Coral, PC.Sun, PC.Mint, PC.Sky, PC.Grape), start, end), start, end, w, StrokeCap.Round)
            11 -> {
                drawLine(c2, start, end, w, StrokeCap.Round)
                for (k in 0..6) {
                    val t = k / 6f
                    drawCircle(color, w * 0.32f, Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t))
                }
            }
            7 -> {
                drawLine(color.copy(alpha = 0.5f), start, end, w * 1.6f, StrokeCap.Round)
                drawLine(Color.White, start, end, w * 0.45f, StrokeCap.Round)
            }
            else -> {
                drawLine(color, start, end, w, StrokeCap.Round)
                val n = 7
                for (k in 0 until n) {
                    val t = (k + 0.5f) / n
                    val p = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
                    when (id) {
                        1, 8, 10 -> drawLine(c2.copy(alpha = 0.8f), p + Offset(-3f, 5f), p + Offset(3f, -5f), 3.dp.toPx())
                        2, 6 -> drawCircle(c2.copy(alpha = 0.85f), 2.5f.dp.toPx(), p)
                        3, 9 -> if (k % 2 == 0) drawLine(c2, p + Offset(-4f, 3f), p + Offset(4f, -3f), 2.dp.toPx())
                        4 -> drawCircle(color.copy(alpha = 0.6f), 3.dp.toPx(), p + Offset(0f, 6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoostersShop(state: AppState) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("big", R.drawable.ic_boost_big, "Big Start" to "Start with a much bigger patch of land."),
            Triple("shield", R.drawable.ic_boost_shield, "Shield Start" to "Your ribbon can't be snipped for the first 6 seconds."),
            Triple("ink", R.drawable.ic_boost_ink, "Full Ink" to "Begin with the Ink Burst ready to splash."),
        ).forEach { (kind, icon, txt) ->
            val owned = when (kind) { "big" -> save.boosters.big; "shield" -> save.boosters.shield; else -> save.boosters.ink }
            val price = Progress.boosterPrices[kind] ?: 0
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PC.Cream).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(icon), null, Modifier.size(52.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${txt.first}  ×$owned", style = Type.label)
                    Text(txt.second, style = Type.small)
                }
                GameButton(price.toString(), {
                    val n = Progress.buyBooster(state.data, kind)
                    if (n != null) { state.save.update { n }; fx.play(Sfx.UI_PURCHASE) } else fx.play(Sfx.UI_ERROR)
                }, Modifier.width(96.dp), height = 42.dp, icon = R.drawable.ic_coin, color = PC.Mint, dark = PC.MintDark,
                    enabled = save.coins >= price, style = Type.button.copy(fontSize = 16.sp))
            }
        }
        Text("Boosters are chosen on the level card before you play.", style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
