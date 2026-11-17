package com.ngstudio.paperclash2.ads

/**
 * Rewarded-ad placements. Debug builds show a simulated ad (src/debug); release builds have no
 * ad network yet (src/release), so [rewardedAvailable] is false and every "Watch ad" button hides.
 * A real network (e.g. AdMob) plugs in by implementing this in a new AdProvider.
 */
interface AdService {
    val rewardedAvailable: Boolean

    /** Shows a rewarded ad; [onResult] gets true only if it was watched to the end. */
    fun showRewarded(placement: String, onResult: (Boolean) -> Unit)
}

object Placements {
    const val UNLOCK_LEVEL = "unlock_level"
    const val REVIVE = "revive"
    const val DOUBLE_COINS = "double_coins"
    const val EXTRA_SPIN = "extra_spin"
    const val QUEST_REROLL = "quest_reroll"
    const val SKIN_UNLOCK = "skin_unlock"
}
