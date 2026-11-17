package com.ngstudio.paperclash2.ads

import androidx.compose.runtime.Composable

/** Release build: no ad network is connected yet, so no rewarded ads are offered. */
object AdProvider {
    val service: AdService = object : AdService {
        override val rewardedAvailable: Boolean = false
        override fun showRewarded(placement: String, onResult: (Boolean) -> Unit) = onResult(false)
    }

    @Composable
    fun Overlay() {
    }
}
