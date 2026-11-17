package com.ngstudio.paperclash2.ui.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.engine.shape.ShapeCategory
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.art.ShapeThumb
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.PaperCard
import com.ngstudio.paperclash2.ui.components.PaperBackground
import com.ngstudio.paperclash2.ui.components.ProgressStrip
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.components.darken
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type

private fun catColor(c: ShapeCategory) = when (c) {
    ShapeCategory.GEOMETRY -> PC.Sky
    ShapeCategory.NATURE -> PC.Mint
    ShapeCategory.ANIMALS -> PC.Coral
    ShapeCategory.OBJECTS -> PC.Grape
    ShapeCategory.SPACE -> PC.SkyDark
}

/** Sticker album: one sticker per shape; paper, then silver (3★) and gold (3★ on five levels). */
@Composable
fun AlbumTab(state: AppState) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    var cat by remember { mutableStateOf(ShapeCategory.GEOMETRY) }
    val shapes = ShapeLibrary.all.filter { it.category == cat }
    val got = shapes.count { (save.album[it.id] ?: 0) > 0 }
    PaperBackground(base = Color(0xFFF7EEDC)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedText("STICKER ALBUM", Type.title.copy(color = Color.White), outline = PC.Ink, width = 9f)
                Spacer(Modifier.weight(1f))
                Text("${save.album.size}/${ShapeLibrary.all.size}", style = Type.heading)
            }
            VSpace(10.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShapeCategory.entries.forEach { c ->
                    val on = c == cat
                    Text(
                        c.title, style = Type.label.copy(color = if (on) Color.White else PC.Ink),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) catColor(c) else PC.Paper)
                            .border(2.dp, catColor(c), RoundedCornerShape(50))
                            .clickable { cat = c; fx.play(Sfx.UI_SWIPE) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            VSpace(10.dp)
            PaperCard(Modifier.fillMaxWidth().weight(1f), tape = catColor(cat), padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${cat.title} page", style = Type.heading, modifier = Modifier.weight(1f))
                    Text("$got / ${shapes.size}", style = Type.label)
                }
                VSpace(6.dp)
                ProgressStrip(got.toFloat() / shapes.size, Modifier.fillMaxWidth(), color = catColor(cat))
                VSpace(10.dp)
                val complete = Progress.pageComplete(save, cat)
                val claimed = cat.name in save.albumPagesClaimed
                if (complete && !claimed) {
                    GameButton("CLAIM PAGE REWARD", {
                        val (s, label) = Progress.claimPage(state.data, cat)
                        state.save.update { s }
                        fx.play(Sfx.REWARD_BIG)
                        state.toast = label
                    }, Modifier.fillMaxWidth(), color = PC.Sun, dark = PC.SunDark, icon = R.drawable.ic_gift, height = 48.dp, pulse = true)
                    VSpace(8.dp)
                }
                LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shapes, key = { it.id }) { s ->
                        val tier = save.album[s.id] ?: 0
                        Sticker(s.id, s.name, tier, catColor(cat))
                    }
                }
            }
        }
    }
}

@Composable
private fun Sticker(id: String, name: String, tier: Int, color: Color) {
    val tilt = remember(id) { ((id.hashCode() % 7) - 3).toFloat() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .aspectRatio(1f)
                .rotate(if (tier > 0) tilt else 0f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when (tier) {
                        0 -> Brush.linearGradient(listOf(Color(0xFFECE5D6), Color(0xFFE2DACA)))
                        1 -> Brush.linearGradient(listOf(Color.White, Color(0xFFFFF7EA)))
                        2 -> Brush.linearGradient(listOf(Color(0xFFF4F6FA), Color(0xFFC9D0DD), Color(0xFFF1F3F8)))
                        else -> Brush.linearGradient(listOf(Color(0xFFFFF1B8), Color(0xFFF2C14E), Color(0xFFFFE89A)))
                    },
                )
                .border(if (tier > 0) 3.dp else 2.dp, if (tier > 0) Color.White else Color(0xFFCFC6B4), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ShapeThumb(null, id, if (tier == 0) Color(0xFFC9C0AE) else color, 70.dp)
            if (tier == 0) Text("?", style = Type.title.copy(color = Color.White, fontSize = 30.sp))
            if (tier >= 2) Image(painterResource(R.drawable.ic_star), null, Modifier.size(22.dp).align(Alignment.TopEnd).padding(2.dp))
        }
        Text(if (tier == 0) "???" else name, style = Type.small.copy(fontSize = 11.sp, color = if (tier == 0) PC.InkFaint else PC.Ink),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Suppress("unused")
private fun tierColor(c: Color) = c.darken(0.2f)
