package van.supervisor.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

/**
 * The app's own updates (D-23): notice a newer build of `main` on GitHub and,
 * when the user says so, install it.
 *
 * CI publishes every build of `main` to the rolling `app-latest` release and
 * uploads [VERSION_FILE] last of all, naming that build. Reading the file is
 * the whole check. Installing is a download of the apk it names, proven
 * against it, handed to PackageInstaller.
 *
 * This is the one part of the app that needs the internet, and the process is
 * bound to the one network that has none: MainActivity pins it to the van
 * Wi-Fi (D-15). So nothing here uses the default route. Every request goes
 * out over a network Android has validated as reaching the internet - mobile
 * data in the van, the house Wi-Fi at home - through Network.openConnection().
 *
 * And all of it is optional. No internet, GitHub down, a release half-way
 * through being replaced: the check comes back empty and nothing else in the
 * app notices (CLAUDE.md section 5.5).
 */
object AppUpdate {

    private const val RELEASE =
        "https://github.com/jeroserpa/SmartVan/releases/download/app-latest/"
    const val VERSION_FILE = "van-core-version.txt"

    /** How long "Later" keeps quiet, whatever gets published meanwhile. */
    const val SNOOZE_MS = 12 * 60 * 60 * 1000L

    /** A check is a few hundred bytes, but no reason to repeat one within minutes. */
    const val CHECK_EVERY_MS = 15 * 60 * 1000L

    private const val PREFS = "app_update"
    private const val SNOOZE_UNTIL = "snooze_until"
    private const val PENDING = "pending_build"

    // `apk` is appended to the release URL, so it must be a bare file name:
    // nothing in the version file may point the download anywhere else.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** One published build, as [VERSION_FILE] describes it. */
    class Release(val build: Long, val apk: String, val size: Long, val sha256: String)

    /** [VERSION_FILE] -> [Release], or null if anything in it is missing or odd. */
    fun parse(text: String): Release? {
        val p = Properties()
        runCatching { p.load(StringReader(text)) }.onFailure { return null }
        val build = p.getProperty("build")?.trim()?.toLongOrNull() ?: return null
        val apk = p.getProperty("apk")?.trim() ?: return null
        val size = p.getProperty("size")?.trim()?.toLongOrNull() ?: return null
        val sha = p.getProperty("sha256")?.trim()?.lowercase() ?: return null
        if (build <= 0 || size <= 0) return null
        if (!APK_NAME.matches(apk) || !SHA256.matches(sha)) return null
        return Release(build, apk, size, sha)
    }

    /** Whether [r] is worth offering to someone running [installed]. Pure, for the unit test. */
    fun isOffer(r: Release, installed: Long, snoozeUntil: Long, now: Long): Boolean =
        r.build > installed && now >= snoozeUntil

    /** The build number of the app that is running: CI's run number, see app/build.gradle.kts. */
    fun installedBuild(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }.getOrDefault(0L)

    // --- the "Later" and "back from the permission screen" memory -----------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snoozeUntil(context: Context): Long = prefs(context).getLong(SNOOZE_UNTIL, 0L)

    fun snooze(context: Context) {
        prefs(context).edit().putLong(SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS).apply()
    }

    /**
     * Remembers that the user said yes to [build] and was sent to Android's
     * settings for the install permission. In prefs, not a field: granting it
     * can cost the app its process on some phones.
     */
    fun setPending(context: Context, build: Long) {
        prefs(context).edit().putLong(PENDING, build).apply()
    }

    fun hasPending(context: Context): Boolean = prefs(context).getLong(PENDING, 0L) > 0L

    /** The build the user already said yes to, or 0; clears it either way. */
    fun takePending(context: Context): Long {
        val p = prefs(context).getLong(PENDING, 0L)
        if (p > 0L) prefs(context).edit().remove(PENDING).apply()
        return p
    }

    // --- network ---------------------------------------------------------------

    /**
     * A network that reaches the internet. VALIDATED is the point of it: the
     * van AP never passes, and neither does a captive portal. The default
     * network first, then anything else that qualifies.
     */
    private fun internet(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val candidates = LinkedHashSet<Network>()
        cm.activeNetwork?.let { candidates.add(it) }
        @Suppress("DEPRECATION")
        runCatching { candidates.addAll(cm.allNetworks) }
        return candidates.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private fun get(network: Network, name: String): HttpURLConnection {
        val conn = network.openConnection(URL(RELEASE + name)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.useCaches = false
        // github.com answers with a redirect to its asset CDN. Both are https,
        // so HttpURLConnection follows it, on the same network.
        conn.instanceFollowRedirects = true
        return conn
    }

    // --- check, download, install ----------------------------------------------

    /** The newest published build, or null if there is no answer right now. Blocking. */
    fun latest(context: Context): Release? {
        val net = internet(context) ?: return null
        return runCatching {
            val conn = get(net, VERSION_FILE)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                parse(String(readCapped(conn.inputStream, 4_096), Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Downloads [r] into the cache and proves it is what the release says -
     * exact size, sha256, and an apk of this app at build [Release.build] -
     * before anything may install it. Anything else is deleted and thrown.
     * Blocking; [progress] gets 0..100, [cancelled] is polled per chunk.
     */
    fun download(
        context: Context,
        r: Release,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): File {
        val net = internet(context) ?: throw IOException("no internet connection")
        val dir = File(context.cacheDir, "update")
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("cannot write to the app cache")
        val file = File(dir, r.apk)
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            val conn = get(net, r.apk)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub answered ${conn.responseCode}")
                }
                var got = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            got += n
                            if (got > r.size) throw IOException("larger than the release says")
                            sha.update(buf, 0, n)
                            out.write(buf, 0, n)
                            progress((got * 100 / r.size).toInt())
                        }
                    }
                }
                if (got != r.size) throw IOException("download incomplete ($got of ${r.size} bytes)")
            } finally {
                conn.disconnect()
            }
            if (hex(sha.digest()) != r.sha256) throw IOException("download corrupted (checksum)")
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: throw IOException("the download is not an app")
            if (info.packageName != context.packageName) {
                throw IOException("the download is a different app")
            }
            val build = PackageInfoCompat.getLongVersionCode(info)
            if (build != r.build) throw IOException("the download is build $build, not ${r.build}")
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /**
     * Hands [apk] to PackageInstaller and returns; the outcome arrives at
     * [UpdateReceiver], usually as a request to show Android's own "update
     * this app?" screen. On Android 12+ that screen is skipped when the system
     * allows it, which is once this app installed the build it is replacing:
     * from the second in-app update on. Either way Android closes the app to
     * replace it. Blocking: it copies the apk into the session.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // Mutable, because PackageInstaller fills in the status extras;
                // explicit, because Android 14 refuses a mutable implicit one.
                val status = PendingIntentCompat.getBroadcast(
                    context, id, Intent(context, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT, true,
                ) ?: throw IOException("no status intent")
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        } finally {
            apk.delete()
        }
    }

    /** At most [cap] bytes: a wrong URL must not stream a large file into memory. */
    private fun readCapped(input: InputStream, cap: Int): ByteArray = input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1_024)
        while (out.size() < cap) {
            val n = s.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
