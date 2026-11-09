package com.mrboomdev.kotnya.compiler

import com.mrboomdev.kotnya.terminal.ShellEvent
import com.mrboomdev.kotnya.terminal.TerminalModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.zip.ZipInputStream

sealed interface SdkEvent {
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long?) : SdkEvent
    data class Extracting(val archive: File) : SdkEvent
    data class Installed(val sdk: AndroidSdk) : SdkEvent
    data class Failed(val message: String, val cause: Throwable? = null) : SdkEvent
}

/**
 * Manages an Android SDK on-device: the command-line tools are downloaded once and then used to
 * install platforms and build-tools into [storageDirectory].
 *
 * The SDK lives outside the Termux prefix, so reinstalling the bootstrap does not wipe it.
 */
class AndroidSdkManager(
    private val terminal: TerminalModule,
    val storageDirectory: File = File(terminal.bootstrap.filesDir, "android-sdk"),
) {

    fun status(): AndroidSdk = AndroidSdk(
        directory = storageDirectory,
        commandLineTools = findSdkManager(),
        platforms = subdirectories("platforms"),
        buildTools = subdirectories("build-tools"),
    )

    /** Downloads the Android command-line tools zip and unpacks it into `cmdline-tools/latest`. */
    fun installCommandLineTools(url: String, sha256: String? = null): Flow<SdkEvent> = channelFlow {
        val archive = File(terminal.bootstrap.tmpDir, "commandlinetools.zip")
        val extractDirectory = File(terminal.bootstrap.tmpDir, "commandlinetools")

        try {
            downloadFile(url, archive, sha256) { downloaded, total ->
                trySend(SdkEvent.Downloading(downloaded, total))
            }

            send(SdkEvent.Extracting(archive))

            extractDirectory.deleteRecursively()
            extractDirectory.mkdirs()
            extractZip(archive, extractDirectory)

            // The official archive wraps everything in a `cmdline-tools` directory.
            val root = File(extractDirectory, "cmdline-tools").takeIf(File::isDirectory) ?: extractDirectory
            val target = File(storageDirectory, "cmdline-tools/latest")

            target.deleteRecursively()
            target.parentFile?.mkdirs()
            check(root.copyRecursively(target, overwrite = true)) {
                "Could not copy the command-line tools into ${target.absolutePath}"
            }

            File(target, "bin").listFiles()?.forEach { it.setExecutable(true, false) }

            check(findSdkManager() != null) {
                "The archive does not contain cmdline-tools/bin/sdkmanager"
            }

            send(SdkEvent.Installed(status()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(SdkEvent.Failed("Could not install the Android command-line tools", e))
        } finally {
            archive.delete()
            extractDirectory.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Installs one SDK package, e.g. `platforms;android-37` or `build-tools;37.0.0`.
     * License prompts are accepted through `yes`, which the Termux bootstrap provides.
     */
    fun installPackage(packageId: String): Flow<ShellEvent> {
        val sdkManager = findSdkManager() ?: return flowOf(
            ShellEvent.Failed(
                "Install the Android command-line tools first.",
                IllegalStateException("sdkmanager was not found in ${storageDirectory.absolutePath}"),
            )
        )

        val script = buildString {
            append("yes | ")
            append(shellQuote(sdkManager.absolutePath))
            append(" --sdk_root=")
            append(shellQuote(storageDirectory.absolutePath))
            append(" --install ")
            append(shellQuote(packageId))
        }

        return terminal.shell.stream(
            terminal.shell.script(
                script = script,
                environment = mapOf(
                    "ANDROID_HOME" to storageDirectory.absolutePath,
                    "ANDROID_SDK_ROOT" to storageDirectory.absolutePath,
                ),
            )
        )
    }

    private fun findSdkManager(): File? = listOf(
        File(storageDirectory, "cmdline-tools/latest/bin/sdkmanager"),
        File(storageDirectory, "cmdline-tools/bin/sdkmanager"),
    ).firstOrNull(File::isFile)

    private fun subdirectories(name: String): List<String> = File(storageDirectory, name)
        .listFiles()
        ?.filter(File::isDirectory)
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    private fun extractZip(archive: File, target: File) {
        val root = target.canonicalFile

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = entry.name.replace('\\', '/').trimStart('/')

                if (relative.isNotEmpty()) {
                    val output = File(root, relative).canonicalFile
                    check(output == root || output.path.startsWith(root.path + File.separator)) {
                        "Zip entry escapes the target directory: ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { zip.copyTo(it) }
                    }
                }

                zip.closeEntry()
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
