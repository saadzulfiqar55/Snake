package com.saad.snake

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.material3.LinearProgressIndicator
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
// Settings dialog (used from the home screen and from inside the game)
// ---------------------------------------------------------------------------
@Composable
fun SettingsDialog(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onSave: () -> Unit,
    inGame: Boolean,
    onRestart: () -> Unit,
    onHome: () -> Unit,
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
                if (inGame) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) { Text("Restart") }
                        OutlinedButton(onClick = onHome, modifier = Modifier.weight(1f)) { Text("Main menu") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(if (inGame) "Resume" else "Close")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// In-app APK download + install
// ---------------------------------------------------------------------------
sealed class DownloadState {
    object None : DownloadState()
    object NeedPermission : DownloadState()
    data class Downloading(val done: Long, val total: Long) : DownloadState()
    data class Ready(val file: File) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/** Downloads the APK into the app's private cache, reporting progress. Cancel by cancelling the coroutine. */
suspend fun downloadApk(ctx: Context, url: String, onProgress: (Long, Long) -> Unit): File =
    withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }   // remove older downloads
        val out = File(dir, "update.apk")
        val tmp = File(dir, "update.apk.part")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true      // GitHub redirects to its file servers
            conn.setRequestProperty("User-Agent", "SnakeArena-Updater")
            val code = conn.responseCode
            if (code != 200) throw IOException("Server returned $code")
            val total = conn.contentLengthLong        // -1 if the server doesn't say
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 100) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                    if (total > 0 && done != total) throw IOException("Download incomplete")
                }
            }
            if (!tmp.renameTo(out)) throw IOException("Couldn't save the file")
            out
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

/**
 * Returns an error message if the file is not a valid APK for THIS app
 * (same package name AND same signing key), otherwise null.
 */
@Suppress("DEPRECATION")
fun validateApk(ctx: Context, file: File): String? {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES
    val pm = ctx.packageManager
    val archive = try {
        pm.getPackageArchiveInfo(file.absolutePath, flags)
    } catch (e: Exception) {
        null
    }
    if (archive == null) return "The downloaded file isn't a valid APK."
    if (archive.packageName != ctx.packageName) return "The downloaded APK belongs to a different app."

    val installed = try {
        pm.getPackageInfo(ctx.packageName, flags)
    } catch (e: Exception) {
        null
    }
    val newKeys = signerKeys(archive)
    val oldKeys = signerKeys(installed)
    if (newKeys.isNotEmpty() && oldKeys.isNotEmpty() && newKeys.intersect(oldKeys).isEmpty()) {
        return "This update is signed with a different key than the installed app, so Android would " +
                "refuse it. Uninstall the game once, install the release APK from GitHub, and future " +
                "updates will work."
    }
    return null
}

@Suppress("DEPRECATION")
private fun signerKeys(info: PackageInfo?): Set<String> {
    if (info == null) return emptySet()
    val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return sigs?.map { it.toCharsString() }?.toSet() ?: emptySet()
}

fun canInstallApks(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

fun openInstallPermissionSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        // settings screen unavailable - ignore
    }
}

/** Opens Android's installer for the downloaded APK. Returns false if the install permission is missing. */
fun tryInstall(ctx: Context, file: File): Boolean {
    if (!canInstallApks(ctx)) {
        openInstallPermissionSettings(ctx)
        return false
    }
    return try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1048576f)

// ---------------------------------------------------------------------------
// About dialog: version, update check, in-app download + install
// ---------------------------------------------------------------------------
@Composable
fun AboutDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { appVersion(ctx) }
    val name = remember { appName(ctx) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dl by remember { mutableStateOf<DownloadState>(DownloadState.None) }
    var job by remember { mutableStateOf<Job?>(null) }

    // check automatically when the About window opens
    LaunchedEffect(Unit) {
        state = UpdateState.Checking
        state = checkForUpdate(version)
    }

    fun startDownload(url: String) {
        // Ask for the "install unknown apps" permission BEFORE downloading
        if (!canInstallApks(ctx)) {
            dl = DownloadState.NeedPermission
            openInstallPermissionSettings(ctx)
            return
        }
        job?.cancel()
        dl = DownloadState.Downloading(0L, -1L)
        job = scope.launch {
            try {
                val file = downloadApk(ctx, url) { done, total ->
                    dl = DownloadState.Downloading(done, total)
                }
                val err = validateApk(ctx, file)
                if (err != null) {
                    file.delete()
                    dl = DownloadState.Failed(err)
                } else {
                    dl = DownloadState.Ready(file)
                    tryInstall(ctx, file)
                }
            } catch (e: CancellationException) {
                dl = DownloadState.None
                throw e
            } catch (e: Exception) {
                dl = DownloadState.Failed("Download failed: ${e.message ?: "unknown error"}")
            }
        }
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
                        val apk = s.info.apkUrl
                        Text("Update available: ${s.info.version}", fontWeight = FontWeight.Bold)
                        if (s.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                s.info.notes.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        when (val d = dl) {
                            DownloadState.None, DownloadState.NeedPermission, is DownloadState.Failed -> {
                                if (d == DownloadState.NeedPermission) {
                                    Text(
                                        "Android needs your permission to install updates from this app. " +
                                                "Turn on Allow from this source, then come back and tap Download again.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (d is DownloadState.Failed) {
                                    Text(d.message, color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (apk != null) {
                                    Button(
                                        onClick = { startDownload(apk) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (d is DownloadState.Failed) "Retry download" else "Download and install") }
                                } else {
                                    Text("This release has no APK file attached.")
                                    OutlinedButton(
                                        onClick = { openUrl(ctx, s.info.pageUrl) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Open release page") }
                                }
                            }
                            is DownloadState.Downloading -> {
                                if (d.total > 0) {
                                    val frac = (d.done.toFloat() / d.total).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    Text("Downloading... ${(frac * 100).toInt()}%  (${mb(d.done)} / ${mb(d.total)} MB)")
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    Text("Downloading... ${mb(d.done)} MB")
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { job?.cancel(); dl = DownloadState.None },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Cancel") }
                            }
                            is DownloadState.Ready -> {
                                Text("Download complete. Tap Install if the installer didn't open.")
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { tryInstall(ctx, d.file) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Install update") }
                            }
                        }
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
                    enabled = state !is UpdateState.Checking && dl !is DownloadState.Downloading,
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