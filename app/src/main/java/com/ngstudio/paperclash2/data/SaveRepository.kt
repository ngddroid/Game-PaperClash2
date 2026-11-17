package com.ngstudio.paperclash2.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The save file: JSON written atomically (AtomicFile) after a short debounce, with the previous
 * good copy kept as a backup that is used if the main file is ever unreadable.
 */
class SaveRepository(context: Context) {
    private val dir = context.filesDir
    private val main = AtomicFile(File(dir, FILE))
    private val backup = File(dir, "$FILE.bak")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private val lock = Any()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SaveData> = _state

    val value: SaveData get() = _state.value

    fun update(f: (SaveData) -> SaveData) {
        _state.update(f)
        schedule()
    }

    private fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(350)
            write()
        }
    }

    /** Writes now (call when the app goes to the background). */
    fun flush() {
        pending?.cancel()
        write()
    }

    private fun write() {
        synchronized(lock) {
            try {
                val text = json.encodeToString(SaveData.serializer(), _state.value)
                // keep the last good file as a backup before replacing it
                val current = main.baseFile
                if (current.exists() && current.length() > 0) current.copyTo(backup, overwrite = true)
                val out = main.startWrite()
                try {
                    out.write(text.toByteArray(Charsets.UTF_8))
                    main.finishWrite(out)
                } catch (e: Exception) {
                    main.failWrite(out)
                    throw e
                }
            } catch (e: Exception) {
                Log.e("Save", "write failed", e)
            }
        }
    }

    private fun load(): SaveData {
        val fromMain = read { String(main.readFully(), Charsets.UTF_8) }
        if (fromMain != null) return fromMain
        val fromBackup = read { backup.readText() }
        if (fromBackup != null) {
            Log.w("Save", "main save unreadable, restored backup")
            return fromBackup
        }
        return SaveData()
    }

    private fun read(text: () -> String): SaveData? = try {
        val t = text()
        if (t.isBlank()) null else migrate(json.decodeFromString(SaveData.serializer(), t))
    } catch (e: Exception) {
        null
    }

    private fun migrate(d: SaveData): SaveData = d // version 1: nothing to migrate yet

    /** Wipes progress (Settings → Reset progress), keeping the player's settings. */
    fun reset() {
        val keep = _state.value.settings
        _state.value = SaveData(settings = keep, onboarded = true, nickname = _state.value.nickname)
        flush()
    }

    companion object {
        const val FILE = "paperclash_save.json"
    }
}
