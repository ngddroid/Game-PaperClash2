package com.ngstudio.paperclash2

import android.app.Application
import com.ngstudio.paperclash2.ads.AdProvider
import com.ngstudio.paperclash2.ads.AdService
import com.ngstudio.paperclash2.audio.AudioManager
import com.ngstudio.paperclash2.audio.Haptics
import com.ngstudio.paperclash2.data.SaveRepository

/** App-wide services, created once. */
class PaperClashApp : Application() {
    lateinit var save: SaveRepository
        private set
    lateinit var audio: AudioManager
        private set
    lateinit var haptics: Haptics
        private set
    val ads: AdService get() = AdProvider.service

    override fun onCreate() {
        super.onCreate()
        save = SaveRepository(this)
        audio = AudioManager(this)
        haptics = Haptics(this)
        val s = save.value.settings
        audio.sfxOn = s.sound
        audio.musicOn = s.music
        haptics.enabled = s.vibration
        Thread { audio.load() }.start()
    }
}
