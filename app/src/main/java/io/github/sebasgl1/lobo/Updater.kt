package io.github.sebasgl1.lobo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Aplica un parche LOBODIFF1 (ver tools/delta.py): convierte el APK instalado en el APK nuevo,
 * así solo se descarga lo que cambió.
 */
object Delta {
    private val MAGIC = "LOBODIFF1".toByteArray()

    fun apply(old: ByteArray, patch: ByteArray): ByteArray {
        val inp = DataInputStream(ByteArrayInputStream(patch))
        val magic = ByteArray(MAGIC.size)
        inp.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "no es un parche de Lobo" }
        val newSize = inp.readLong()
        val n = inp.readLong()
        require(newSize in 0..Int.MAX_VALUE.toLong() && n in 0..Int.MAX_VALUE.toLong()) { "parche dañado" }
        fun block(): ByteArray {
            val len = inp.readLong()
            require(len in 0..Int.MAX_VALUE.toLong()) { "parche dañado" }
            val c = ByteArray(len.toInt())
            inp.readFully(c)
            return InflaterInputStream(ByteArrayInputStream(c)).use { it.readBytes() }
        }
        val ctrl = ByteBuffer.wrap(block())
        val diff = block()
        val extra = block()
        val out = ByteArray(newSize.toInt())
        var op = 0L
        var np = 0
        var dp = 0
        var ep = 0
        repeat(n.toInt()) {
            val x = ctrl.long.toInt()
            val y = ctrl.long.toInt()
            val z = ctrl.long
            require(x >= 0 && y >= 0 && np + x + y <= out.size && dp + x <= diff.size && ep + y <= extra.size) { "parche dañado" }
            for (k in 0 until x) {
                val o = op + k
                val ob = if (o >= 0 && o < old.size) old[o.toInt()].toInt() else 0
                out[np + k] = (diff[dp + k] + ob).toByte()
            }
            np += x; op += x; dp += x
            System.arraycopy(extra, ep, out, np, y)
            np += y; ep += y
            op += z
        }
        require(np == out.size) { "parche incompleto" }
        return out
    }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { s ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val r = s.read(buf)
            if (r < 0) break
            md.update(buf, 0, r)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Lo que dice update.json de la última versión publicada en GitHub. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha: String,
    val patches: List<PatchInfo>,
)

data class PatchInfo(val fromSha: String, val url: String, val size: Long)

/**
 * Actualizador: revisa la última versión en GitHub Releases, descarga solo un parche si existe uno para
 * la versión instalada (si no, la app completa), verifica el resultado y abre el instalador de Android.
 * Solo usa internet cuando se toca "Buscar actualización".
 */
class Updater(private val ctx: Context) {
    companion object {
        const val INFO_URL = "https://github.com/sebasgl1/lobo/releases/latest/download/update.json"
    }

    private val pkgInfo get() = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val versionCode: Long get() = PackageInfoCompat.getLongVersionCode(pkgInfo)
    val versionName: String get() = pkgInfo.versionName ?: ""
    private val installedApk get() = File(ctx.applicationInfo.sourceDir)

    /** Devuelve la versión nueva si hay una; null si ya está al día. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val o = JSONObject(String(download(INFO_URL, -1) {}))
        val apk = o.getJSONObject("apk")
        val ps = o.optJSONArray("patches")
        val info = UpdateInfo(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            notes = o.optString("notes", ""),
            apkUrl = apk.getString("url"),
            apkSize = apk.getLong("size"),
            apkSha = apk.getString("sha256"),
            patches = (0 until (ps?.length() ?: 0)).map { i ->
                val p = ps!!.getJSONObject(i)
                PatchInfo(p.getString("fromSha256"), p.getString("url"), p.getLong("size"))
            },
        )
        if (info.versionCode > versionCode) info else null
    }

    /** El parche que sirve para la versión instalada, si lo hay (comparando la huella del APK). */
    suspend fun patchFor(info: UpdateInfo): PatchInfo? = withContext(Dispatchers.IO) {
        val mine = runCatching { sha256(installedApk) }.getOrNull() ?: return@withContext null
        info.patches.firstOrNull { it.fromSha.equals(mine, ignoreCase = true) }
    }

    /** Descarga la actualización (parche o completa) y deja el APK nuevo listo y verificado. */
    suspend fun prepare(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "lobo-${info.versionName}.apk")
        val patch = patchFor(info)
        if (patch != null) {
            val ok = runCatching {
                val p = download(patch.url, patch.size, onProgress)
                val bytes = Delta.apply(installedApk.readBytes(), p)
                if (sha256(bytes) == info.apkSha.lowercase()) {
                    out.writeBytes(bytes)
                    true
                } else false
            }.getOrDefault(false)
            if (ok) return@withContext out
        }
        // Sin parche (o falló): la app completa
        out.outputStream().use { os ->
            stream(info.apkUrl, info.apkSize, onProgress) { input -> input.copyTo(os, 1 shl 16) }
        }
        check(sha256(out) == info.apkSha.lowercase()) { "La descarga llegó dañada. Intenta de nuevo." }
        out
    }

    /** ¿Android ya dejó que Lobo instale actualizaciones? */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Abre el ajuste de Android para permitir que Lobo instale actualizaciones. */
    fun askInstallPermission() {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Abre el instalador de Android con el APK nuevo (Android siempre pide confirmar). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- red ----------

    private fun <T> stream(url: String, expected: Long, onProgress: (Float) -> Unit, body: (InputStream) -> T): T {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Lobo-Android")
        }
        try {
            if (c.responseCode !in 200..299) error("No se pudo descargar (código ${c.responseCode}).")
            val total = if (expected > 0) expected else c.contentLengthLong
            val counting = object : InputStream() {
                val inner = c.inputStream
                var read = 0L
                var last = -1
                override fun read(): Int = inner.read().also { if (it >= 0) tick(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) tick(it) }
                private fun tick(n: Int) {
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt()
                        if (pct != last) { last = pct; onProgress(read.toFloat() / total) }
                    }
                }
                override fun close() = inner.close()
            }
            return counting.use { body(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, expected: Long, onProgress: (Float) -> Unit): ByteArray =
        stream(url, expected, onProgress) { input ->
            val bo = ByteArrayOutputStream()
            input.copyTo(bo, 1 shl 16)
            bo.toByteArray()
        }
}
