package com.saad.snake

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

// ---------------------------------------------------------------------------
// >>> CHANGE THIS to your GitHub repo in the form "username/repository"
//     Example: "ahmed123/snake-arena"
// ---------------------------------------------------------------------------
const val GITHUB_REPO = "saadzulfiqar55/Snake"

// ---------------------------------------------------------------------------
// Saved settings (survive app restarts)
// ---------------------------------------------------------------------------
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("snake_settings", Context.MODE_PRIVATE)

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) { prefs.edit().putFloat("speed", v).apply() }

    var volume: Float
        get() = prefs.getFloat("volume", 0.7f)
        set(v) { prefs.edit().putFloat("volume", v).apply() }
}

// ---------------------------------------------------------------------------
// Sound effects. No audio files needed: short tones are generated in code.
// ---------------------------------------------------------------------------
class Sfx(context: Context) {
    var volume = 0.7f

    private var pool: SoundPool? = null
    private var eatId = 0
    private var dieId = 0
    private var lastEat = 0L

    init {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            eatId = p.load(writeWav(context, "eat.wav", tone(660f, 1100f, 0.07f)), 1)
            dieId = p.load(writeWav(context, "die.wav", tone(320f, 70f, 0.5f)), 1)
            pool = p
        } catch (e: Exception) {
            pool = null // game still works, just silent
        }
    }

    fun eat() {
        val now = System.currentTimeMillis()
        if (now - lastEat < 70 || volume <= 0f) return
        lastEat = now
        val rate = 0.9f + Math.random().toFloat() * 0.4f
        pool?.play(eatId, volume * 0.5f, volume * 0.5f, 1, 0, rate)
    }

    fun die() {
        if (volume <= 0f) return
        pool?.play(dieId, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
    }

    private fun tone(f0: Float, f1: Float, seconds: Float): ShortArray {
        val rate = 22050
        val n = (rate * seconds).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = f0 + (f1 - f0) * t
            phase += 2.0 * Math.PI * f / rate
            val env = (1f - t) * (1f - t)
            out[i] = (Math.sin(phase) * env * 0.6 * 32767.0).toInt().toShort()
        }
        return out
    }

    private fun writeWav(ctx: Context, name: String, pcm: ShortArray): String {
        val file = File(ctx.cacheDir, name)
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())            // PCM
        bb.putShort(1.toShort())            // mono
        bb.putInt(22050)                    // sample rate
        bb.putInt(22050 * 2)                // byte rate
        bb.putShort(2.toShort())            // block align
        bb.putShort(16.toShort())           // bits per sample
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        file.writeBytes(bb.array())
        return file.absolutePath
    }
}

// ---------------------------------------------------------------------------
// Update checker (reads the latest GitHub Release of your repo)
// ---------------------------------------------------------------------------
data class UpdateInfo(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

fun isNewer(remote: String, local: String): Boolean {
    fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split("-")[0].split(".")
            .map { p -> p.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    val r = parts(remote)
    val l = parts(local)
    for (i in 0 until max(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

suspend fun checkForUpdate(current: String): UpdateState = withContext(Dispatchers.IO) {
    if (GITHUB_REPO.startsWith("YOUR_")) {
        return@withContext UpdateState.Error("Set GITHUB_REPO in SettingsAndUpdates.kt first.")
    }
    try {
        val conn = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SnakeArena-UpdateCheck")
        val code = conn.responseCode
        if (code == 404) return@withContext UpdateState.Error("No public releases found yet.")
        if (code != 200) return@withContext UpdateState.Error("GitHub returned error $code.")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(body)
        val tag = json.getString("tag_name")
        val notes = json.optString("body", "")
        val page = json.optString("html_url", "https://github.com/$GITHUB_REPO/releases")
        var apk: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apk = a.getString("browser_download_url")
                    break
                }
            }
        }
        if (isNewer(tag, current)) UpdateState.Available(UpdateInfo(tag, notes, page, apk))
        else UpdateState.UpToDate
    } catch (e: Exception) {
        UpdateState.Error("Couldn't check for updates. Check your internet connection.")
    }
}

fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
} catch (e: Exception) {
    "1.0"
}

fun appName(ctx: Context): String = try {
    ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
} catch (e: Exception) {
    "Snake"
}

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // no browser installed - ignore
    }
}

// ---------------------------------------------------------------------------
// Settings dialog
// ---------------------------------------------------------------------------
@Composable
fun SettingsDialog(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onSave: () -> Unit,
    onRestart: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))

                Text("Game speed: ${"%.1f".format(speed)}x")
                Slider(
                    value = speed,
                    onValueChange = onSpeedChange,
                    onValueChangeFinished = onSave,
                    valueRange = 0.5f..2.5f
                )

                Spacer(Modifier.height(8.dp))
                Text("Volume: ${(volume * 100).toInt()}%")
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = onSave,
                    valueRange = 0f..1f
                )

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) { Text("Restart") }
                    OutlinedButton(onClick = onAbout, modifier = Modifier.weight(1f)) { Text("About") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About dialog: version + update check (like Morphe Manager's About/update flow)
// ---------------------------------------------------------------------------
@Composable
fun AboutDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { appVersion(ctx) }
    val name = remember { appName(ctx) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // check automatically when the About window opens
    LaunchedEffect(Unit) {
        state = UpdateState.Checking
        state = checkForUpdate(version)
    }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text("Version $version", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))

                when (val s = state) {
                    UpdateState.Idle -> {}
                    UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking for updates...")
                    }
                    UpdateState.UpToDate -> Text("You're on the latest version.")
                    is UpdateState.Available -> {
                        Text(
                            "Update available: ${s.info.version}",
                            fontWeight = FontWeight.Bold
                        )
                        if (s.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                s.info.notes.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 140.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { openUrl(ctx, s.info.apkUrl ?: s.info.pageUrl) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Download update") }
                    }
                    is UpdateState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            state = UpdateState.Checking
                            state = checkForUpdate(version)
                        }
                    },
                    enabled = state !is UpdateState.Checking,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Check for updates") }

                TextButton(
                    onClick = { openUrl(ctx, "https://github.com/$GITHUB_REPO") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("View on GitHub") }

                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}