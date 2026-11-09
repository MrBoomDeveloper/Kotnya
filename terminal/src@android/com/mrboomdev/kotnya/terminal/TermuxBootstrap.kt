package com.mrboomdev.kotnya.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Where the Termux user-land (the "bootstrap") comes from. */
sealed interface BootstrapSource {
    /** A zip bundled in the application's assets, e.g. `bootstrap-aarch64.zip`. */
    data class Asset(val name: String) : BootstrapSource

    /** A zip downloaded from [url], optionally verified against a hex [sha256] digest. */
    data class Url(val url: String, val sha256: String? = null) : BootstrapSource

    /** A zip that was already downloaded, for example by an installer screen. */
    data class LocalFile(val file: File) : BootstrapSource
}

data class BootstrapProgress(val extractedEntries: Int, val currentEntry: String?)

/**
 * Owns the Termux file system layout: `$PREFIX`, `$HOME`, `$TMPDIR` and the login shell that terminal
 * sessions and compiler runs are executed in.
 *
 * The bootstrap has to live in the application's private data directory: that is the only place
 * Termux packages may be installed without root.
 */
class TermuxBootstrap(private val context: Context) {

    val filesDir: File = context.filesDir

    /** `$PREFIX`, the Termux root that contains `bin`, `lib`, `etc` and so on. */
    val prefixDir: File = File(filesDir, "usr")

    /** `$HOME`. */
    val homeDir: File = File(filesDir, "home")

    /** `$TMPDIR`. */
    val tmpDir: File = File(filesDir, "tmp")

    val shellPath: File = File(prefixDir, "bin/bash")

    private val versionFile: File = File(prefixDir, ".bootstrap-version")

    val isInstalled: Boolean get() = versionFile.isFile && shellPath.isFile

    val installedVersion: String?
        get() = versionFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotEmpty)

    /** Environment every Termux process needs in order to find its binaries and libraries. */
    fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val externalStorage = context.getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath

        return buildMap {
            put("HOME", homeDir.absolutePath)
            put("PREFIX", prefixDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("PATH", "${prefixDir.absolutePath}/bin:/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", "${prefixDir.absolutePath}/lib")
            put("SHELL", shellPath.absolutePath)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "en_US.UTF-8")
            put("LC_ALL", "en_US.UTF-8")
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")
            put("EXTERNAL_STORAGE", externalStorage)
            putAll(extra)
        }
    }

    fun ensureDirectories() {
        listOf(prefixDir, homeDir, tmpDir).forEach(File::mkdirs)
        // Termux binaries are executed by the app's own UID, but the directories still have to be
        // traversable and the prefix has to be readable for the dynamic linker.
        prefixDir.setReadable(true, false)
        prefixDir.setExecutable(true, false)
    }

    /**
     * Extracts [source] into [prefixDir] and marks the bootstrap as installed.
     *
     * [onProgress] and [onDownloadProgress] are invoked on [Dispatchers.IO].
     */
    suspend fun install(
        source: BootstrapSource,
        version: String = "unknown",
        onProgress: (BootstrapProgress) -> Unit = {},
        onDownloadProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        check(!isInstalled) {
            "A Termux bootstrap is already installed in ${prefixDir.absolutePath}. Uninstall it first."
        }

        ensureDirectories()
        // A previous, interrupted installation must not be mixed with the new one.
        prefixDir.deleteRecursively()
        prefixDir.mkdirs()

        Log.i(TAG, "Installing Termux bootstrap $version from $source")
        val archive = (source as? BootstrapSource.Url)?.let { download(it, onDownloadProgress) }

        try {
            val input = when (source) {
                is BootstrapSource.Asset -> context.assets.open(source.name)
                is BootstrapSource.LocalFile -> source.file.inputStream()
                is BootstrapSource.Url -> requireNotNull(archive).inputStream()
            }

            input.use { extract(it, onProgress) }
        } finally {
            archive?.delete()
        }

        createSymlinks()
        versionFile.writeText(version)
        Log.i(TAG, "Termux bootstrap $version installed into ${prefixDir.absolutePath}")
    }

    private fun download(
        source: BootstrapSource.Url,
        onProgress: (Long, Long?) -> Unit,
    ): File {
        val target = File(filesDir, "bootstrap-${System.currentTimeMillis()}.zip")
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }

        try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) { "Received HTTP $responseCode while downloading ${source.url}" }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            val digest = source.sha256?.let { MessageDigest.getInstance("SHA-256") }
            var downloaded = 0L
            var reported = 0L

            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break

                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        downloaded += read

                        if (downloaded - reported >= 512 * 1024) {
                            reported = downloaded
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
            }

            onProgress(downloaded, totalBytes)

            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (!actual.equals(source.sha256, ignoreCase = true)) {
                    target.delete()
                    error("Checksum mismatch for ${source.url}: expected ${source.sha256} but got $actual")
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        return target
    }

    private fun extract(stream: InputStream, onProgress: (BootstrapProgress) -> Unit): Int {
        var extracted = 0

        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = resolveEntry(entry.name)

                if (target != null) {
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }

                        if (needsExecutableBit(entry.name)) {
                            target.setExecutable(true, false)
                        }

                        extracted++
                        onProgress(BootstrapProgress(extracted, entry.name))
                    }
                }

                zip.closeEntry()
            }
        }

        return extracted
    }

    /**
     * Maps a zip entry to a file inside [prefixDir], refusing entries that try to escape it.
     */
    private fun resolveEntry(name: String): File? {
        val relative = name.replace('\\', '/').trimStart('/')
        if (relative.isEmpty()) return null

        val root = prefixDir.canonicalFile
        val target = File(root, relative).canonicalFile
        val isInsidePrefix = target == root || target.path.startsWith(root.path + File.separator)

        if (!isInsidePrefix) {
            Log.w(TAG, "Skipping zip entry outside of the prefix: $name")
            return null
        }

        return target
    }

    /** Java's zip API drops Unix modes, so restore the executable bit for binaries and libraries. */
    private fun needsExecutableBit(entryName: String): Boolean =
        entryName.startsWith("bin/") ||
            entryName.startsWith("lib/") ||
            entryName.startsWith("libexec/") ||
            entryName.endsWith(".so") ||
            entryName.contains(".so.")

    /**
     * The Termux bootstrap zip describes its symlinks in `SYMLINKS.txt`, one `target←link` per line.
     */
    private fun createSymlinks() {
        val listing = File(prefixDir, "SYMLINKS.txt")
        if (!listing.isFile) return

        listing.forEachLine { line ->
            val separator = line.indexOf('←')
            if (separator <= 0) return@forEachLine

            val target = line.substring(0, separator)
            val link = resolveEntry(line.substring(separator + 1)) ?: return@forEachLine

            link.parentFile?.mkdirs()
            link.delete()

            try {
                Os.symlink(target, link.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create symlink ${link.absolutePath} -> $target", e)
            }
        }
    }

    private companion object {
        const val TAG = "TermuxBootstrap"
    }
}
