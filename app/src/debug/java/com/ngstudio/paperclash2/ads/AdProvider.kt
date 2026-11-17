package com.ngstudio.paperclash2.ads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DEBUG BUILDS ONLY: a simulated rewarded ad so every "watch ad" flow can be tested before an ad
 * network is connected. This file lives in src/debug and is not part of release builds.
 */
object AdProvider {
    private class Request(val placement: String, val onResult: (Boolean) -> Unit)

    private val current = MutableStateFlow<Request?>(null)

    val service: AdService = object : AdService {
        override val rewardedAvailable: Boolean = true
        override fun showRewarded(placement: String, onResult: (Boolean) -> Unit) {
            current.value = Request(placement, onResult)
        }
    }

    @Composable
    fun Overlay() {
        val req by current.collectAsState()
        val r = req ?: return
        var left by remember(r) { mutableIntStateOf(3) }
        LaunchedEffect(r) {
            while (left > 0) {
                delay(1000)
                left--
            }
        }
        fun finish(ok: Boolean) {
            current.value = null
            r.onResult(ok)
        }
        BackHandler { finish(false) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1F2A4A), Color(0xFF3A4A75))))
                .clickable(MutableInteractionSource(), indication = null) {},
        ) {
            Text("TEST AD · debug build", style = Type.small.copy(color = Color.White.copy(alpha = 0.7f)), modifier = Modifier.statusBarsPadding().padding(16.dp))
            Box(Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopEnd)) {
                if (left > 0) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text(left.toString(), style = Type.heading.copy(color = Color.White))
                    }
                } else {
                    Image(painterResource(R.drawable.ic_close), "Close ad", Modifier.size(44.dp).clickable { finish(true) })
                }
            }
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.ic_ad), null, Modifier.size(120.dp))
                VSpace(12.dp)
                OutlinedText("Rewarded ad", Type.title.copy(color = Color.White), outline = PC.Grape, width = 8f)
                VSpace(8.dp)
                Text(
                    "A real rewarded ad will play here once an ad network is connected.\nPlacement: ${r.placement}",
                    style = Type.body.copy(color = Color.White), textAlign = TextAlign.Center,
                )
                VSpace(24.dp)
                if (left == 0) {
                    GameButton("COLLECT REWARD", { finish(true) }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, pulse = true)
                }
            }
        }
    }
}
