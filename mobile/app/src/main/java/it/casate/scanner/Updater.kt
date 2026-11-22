package it.casate.scanner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer app published by the CI (version.json next to the APK in the release "scanner-latest"). */
data class AppUpdate(val versionCode: Long, val versionName: String, val sha256: String, val size: Long, val notes: String) {
    companion object {
        fun parse(o: JSONObject): AppUpdate? {
            val code = o.optLong("versionCode", 0)
            if (code <= 0) return null
            return AppUpdate(code, o.optString("versionName", "1.0.$code"), o.optString("sha256").lowercase(),
                o.optLong("size", 0), o.optString("notes"))
        }

        /** Newer than what is installed? */
        fun isNewer(u: AppUpdate?, installed: Long) = u != null && u.versionCode > installed
    }
}

/**
 * The app updates itself like the desktop program: asks the platform for the latest version, downloads the
 * APK, checks its sha256 and installs it with the system installer (same signature → data and pairing stay).
 * The first time Android asks once to allow "install unknown apps" for this app; after that, on Android 12+,
 * updates install without questions.
 */
object Updater {
    const val ACTION = "it.casate.scanner.INSTALL_RESULT"
    const val GITHUB = "https://github.com/TEA-CoderR/Prenotazione_Scarico/releases/download/scanner-latest/"

    @Suppress("DEPRECATION")
    fun installed(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun get(url: String, timeout: Int = 15_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        return c
    }

    /** Latest version: from the platform (<site>/app/version), else straight from GitHub. null = none / offline. */
    fun latest(baseUrl: String): AppUpdate? {
        for (u in listOf("$baseUrl/app/version", GITHUB + "version.json")) {
            val r = runCatching {
                val c = get(u)
                try {
                    if (c.responseCode != 200) null
                    else AppUpdate.parse(JSONObject(c.inputStream.bufferedReader().use { it.readText() }))
                } finally { c.disconnect() }
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /** Downloads the APK into the app's cache and checks it; progress 0–100. */
    fun download(ctx: Context, baseUrl: String, u: AppUpdate, progress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update-${u.versionCode}.apk")
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("update-") && it != out }?.forEach { it.delete() }
        if (out.exists() && u.sha256.isNotEmpty() && sha256(out) == u.sha256) return out
        var last: Exception? = null
        for (url in listOf("$baseUrl/app", GITHUB + "casate-scanner.apk")) {
            try {
                val c = get(url)
                try {
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: u.size
                    val md = MessageDigest.getInstance("SHA-256")
                    c.inputStream.use { inp ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                val k = inp.read(buf)
                                if (k < 0) break
                                o.write(buf, 0, k)
                                md.update(buf, 0, k)
                                done += k
                                val pc = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                                if (pc != shown) { shown = pc; progress(pc) }
                            }
                        }
                    }
                    val got = md.digest().joinToString("") { "%02x".format(it) }
                    if (u.sha256.isNotEmpty() && got != u.sha256) {
                        out.delete()
                        throw IllegalStateException("File scaricato non valido (controllo non superato)")
                    }
                    return out
                } finally { c.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Download non riuscito")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val k = i.read(b); if (k < 0) break; md.update(b, 0, k) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Android must allow this app to install apps (asked once, in the system settings). */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands the APK to the system installer; the result arrives in [InstallReceiver]. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("casate-scanner.apk", 0, apk.length()).use { o ->
                apk.inputStream().use { it.copyTo(o) }
                s.fsync(o)
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** What the system installer says: ask the user when needed, show a clear message when it fails. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(ctx, "App aggiornata", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = when (st) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Aggiornamento annullato"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Aggiornamento non installato: firma diversa. Disinstalla l'app e installala da <sito>/app (poi ricollega lo scanner)."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Spazio insufficiente per l'aggiornamento"
                    else -> "Aggiornamento non riuscito: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore")
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
