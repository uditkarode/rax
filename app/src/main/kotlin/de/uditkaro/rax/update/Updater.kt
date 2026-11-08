package de.uditkaro.rax.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.uditkaro.rax.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** A preview build on GitHub, numbered like the version code it carries. */
data class Release(val number: Int, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object None : UpdateState

    /** [failed] says what went wrong the last time the user tried it. */
    data class Available(val release: Release, val failed: Failure? = null) : UpdateState

    data class Downloading(val release: Release, val progress: Float) : UpdateState

    /** Downloaded, and Android installs it, after asking the user if it must. */
    data class Installing(val release: Release) : UpdateState
}

enum class Failure { Download, Install }

/**
 * Finds a newer preview on GitHub and installs it. Each push to main publishes a preview, signed
 * with the same key, whose number is its version code. Only release builds update themselves: a
 * debug build has another application ID, so a preview would not replace it.
 */
class Updater(private val app: Application, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var checkedAt: Long? = null
    private var inForeground = false

    /** Android's prompt to install, kept until Rax is on screen, because only then may it open one. */
    private var confirm: Intent? = null

    init {
        if (!BuildConfig.DEBUG) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    inForeground = true
                    confirm?.let { confirm = null; ask(it) }
                    checkIfDue()
                }

                override fun onStop(owner: LifecycleOwner) {
                    inForeground = false
                }
            })
        }
    }

    private fun checkIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt?.let { now - it < CHECK_EVERY_MS } == true) return
        checkedAt = now
        scope.launch(Dispatchers.IO) {
            val newest = try {
                http.newCall(Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json").build()).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    newestPreview(it.body.string())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Couldn't check for an update: $e")
                return@launch
            }
            _state.update { s ->
                when {
                    s is UpdateState.Downloading || s is UpdateState.Installing -> s
                    newest == null || newest.number <= BuildConfig.VERSION_CODE -> UpdateState.None
                    s is UpdateState.Available && s.release == newest -> s
                    else -> UpdateState.Available(newest)
                }
            }
        }
    }

    /** Downloads the update into an install session and hands it to Android. */
    fun install() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _state.value = UpdateState.Downloading(release, 0f)
        scope.launch(Dispatchers.IO) {
            val installer = app.packageManager.packageInstaller
            // A session left over from an earlier try, abandoned or cut short, still holds its space.
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(release.size)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                // Once Rax installed itself, Android lets it update without asking again.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                try {
                    download(release, session)
                    _state.value = UpdateState.Installing(release)
                    session.commit(statusReceiver(id))
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't download preview ${release.number}", e)
                    session.abandon()
                    _state.value = UpdateState.Available(release, Failure.Download)
                }
            }
        }
    }

    private fun download(release: Release, session: PackageInstaller.Session) {
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.size
            session.openWrite("rax.apk", 0, total).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var percent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val now = (done * 100 / total).toInt()
                        if (now != percent) {
                            percent = now
                            _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                        }
                    }
                    session.fsync(out)
                }
            }
        }
    }

    private fun statusReceiver(sessionId: Int) = PendingIntent.getBroadcast(
        app,
        sessionId,
        Intent(app, UpdateReceiver::class.java),
        // Android adds the session's status to the intent.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender

    /** What Android says about the install session. */
    internal fun onStatus(intent: Intent) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (prompt == null) _state.value = UpdateState.Available(release, Failure.Install)
                else if (inForeground) ask(prompt)
                else confirm = prompt
            }
            // Android replaces Rax now, which ends this process.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Available(release)
            else -> {
                Log.w(TAG, "Couldn't install preview ${release.number}: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                _state.value = UpdateState.Available(release, Failure.Install)
            }
        }
    }

    private fun ask(prompt: Intent) {
        try {
            app.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the install prompt", e)
            (_state.value as? UpdateState.Installing)?.let { _state.value = UpdateState.Available(it.release, Failure.Install) }
        }
    }

    private companion object {
        const val TAG = "RaxUpdater"
        const val RELEASES_URL = "https://api.github.com/repos/uditkarode/rax/releases?per_page=10"
        const val CHECK_EVERY_MS = 30 * 60 * 1000L
    }
}

@Serializable
private class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
)

private val json = Json { ignoreUnknownKeys = true }

/** The newest preview with an APK in GitHub's list of releases. */
internal fun newestPreview(releasesJson: String): Release? =
    json.decodeFromString<List<GitHubRelease>>(releasesJson)
        .filter { !it.draft }
        .mapNotNull { r ->
            val number = r.tag.removePrefix("preview-").takeIf { it != r.tag }?.toIntOrNull() ?: return@mapNotNull null
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@mapNotNull null
            Release(number, apk.url, apk.size)
        }
        .maxByOrNull { it.number }
