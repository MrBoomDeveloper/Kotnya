package com.mrboomdev.kotnya.compiler

import android.system.Os
import com.mrboomdev.kotnya.terminal.ShellCommand
import com.mrboomdev.kotnya.terminal.TerminalModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import java.io.File

sealed interface ToolchainEvent {
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long?) : ToolchainEvent
    data class Extracting(val archive: File) : ToolchainEvent
    data class Installed(val toolchain: KotlinToolchain) : ToolchainEvent
    data class Failed(val message: String, val cause: Throwable? = null) : ToolchainEvent
}

/**
 * Installs and removes Kotlin Toolchain distributions inside the Termux prefix.
 *
 * Installation downloads `kotlin-cli-<version>-dist.tgz` (the same artifact the `kotlin` wrapper
 * script provisions on a desktop), verifies its checksum when one is supplied, and unpacks it with
 * the Termux `tar`, which is the only archiver available on-device.
 */
class KotlinToolchainManager(
    private val terminal: TerminalModule,
    val storageDirectory: File = File(terminal.bootstrap.filesDir, "toolchains"),
) {

    private val packages = TermuxDebInstaller(terminal)

    fun installed(): List<KotlinToolchain> = storageDirectory.listFiles()
        ?.filter(File::isDirectory)
        ?.map { KotlinToolchain(version = it.name, directory = it) }
        ?.filter(KotlinToolchain::isInstalled)
        ?.sortedBy(KotlinToolchain::version)
        .orEmpty()

    fun find(version: String): KotlinToolchain = KotlinToolchain(version, directoryFor(version))

    fun isInstalled(version: String): Boolean = find(version).isInstalled

    fun install(
        version: String = DEFAULT_TOOLCHAIN_VERSION,
        sha256: String? = null,
        downloadRoot: String = DEFAULT_TOOLCHAIN_DOWNLOAD_ROOT,
    ): Flow<ToolchainEvent> = channelFlow {
        val toolchain = find(version)
        if (toolchain.isInstalled) {
            send(ToolchainEvent.Installed(toolchain))
            return@channelFlow
        }

        storageDirectory.mkdirs()
        terminal.bootstrap.ensureDirectories()

        val archive = File(terminal.bootstrap.tmpDir, "kotlin-cli-$version.tgz")
        val url = "$downloadRoot/org/jetbrains/kotlin/kotlin-cli/$version/kotlin-cli-$version-dist.tgz"

        try {
            downloadFile(url, archive, sha256) { downloaded, total ->
                trySend(ToolchainEvent.Downloading(downloaded, total))
            }

            send(ToolchainEvent.Extracting(archive))
            toolchain.directory.mkdirs()

            val result = terminal.shell.run(
                ShellCommand(
                    argv = listOf(tarPath(), "-xzf", archive.absolutePath, "-C", toolchain.directory.absolutePath),
                    workingDirectory = terminal.bootstrap.tmpDir,
                )
            )

            check(result.isSuccess) {
                "tar failed with exit code ${result.exitCode}: ${result.stderr}"
            }
            check(toolchain.isInstalled) {
                "The archive does not look like a Kotlin Toolchain distribution: ${toolchain.launcher} is missing"
            }

            send(ToolchainEvent.Installed(toolchain))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ToolchainEvent.Failed("Could not install Kotlin Toolchain $version", e))
        } finally {
            archive.delete()
        }
    }.flowOn(Dispatchers.IO)

    fun uninstall(version: String): Boolean = directoryFor(version).deleteRecursively()

    /** Whether a Java runtime is available in the Termux prefix, which the toolchain needs to run. */
    suspend fun hasJavaRuntime(): Boolean {
        val java = javaBinary()
        if (!java.canExecute()) return false

        return terminal.shell.run(
            ShellCommand(listOf(java.absolutePath, "-version"), terminal.bootstrap.homeDir)
        ).isSuccess
    }

    /**
     * Installs a JDK from the Termux repository.
     *
     * `pkg install` cannot be used here: the Termux bootstrap is built for the `com.termux` package
     * name and its apt/dpkg binaries look for `/data/data/com.termux/files/usr`, which does not exist
     * inside this app. The .deb archives are unpacked directly instead.
     */
    fun installJavaRuntime(packageName: String = "openjdk-21"): Flow<PackageEvent> = channelFlow {
        require(TERMUX_PACKAGE_NAME.matches(packageName)) {
            "Invalid Termux package name: $packageName"
        }

        var failed = false

        packages.install(listOf(packageName)).collect { event ->
            send(event)
            if (event is PackageEvent.Failed) failed = true
        }

        if (failed) return@channelFlow

        val javaHome = javaHome()
        if (javaHome == null) {
            send(PackageEvent.Failed(packageName, "The $packageName package does not contain bin/java"))
            return@channelFlow
        }

        createJavaLinks(javaHome)

        val java = javaBinary()
        val result = terminal.shell.run(
            ShellCommand(listOf(java.absolutePath, "-version"), terminal.bootstrap.homeDir)
        )

        if (result.isSuccess) {
            send(PackageEvent.Installed(packageName))
        } else {
            send(PackageEvent.Failed(packageName, "java -version failed: ${result.output.take(500)}"))
        }
    }.flowOn(Dispatchers.IO)

    /** The JDK directory, e.g. `$PREFIX/lib/jvm/java-21-openjdk`. */
    private fun javaHome(): File? = File(terminal.bootstrap.prefixDir, "lib/jvm")
        .listFiles()
        ?.filter(File::isDirectory)
        ?.firstOrNull { File(it, "bin/java").isFile }

    /** Termux exposes the JDK tools through `$PREFIX/bin`, so mirror that with relative symlinks. */
    private fun createJavaLinks(javaHome: File) {
        val prefix = terminal.bootstrap.prefixDir
        val binDirectory = File(prefix, "bin")
        binDirectory.mkdirs()

        val javaHomeRelative = ".." + javaHome.absolutePath.removePrefix(prefix.absolutePath)

        JAVA_TOOLS.forEach { tool ->
            val target = File(javaHome, "bin/$tool")
            if (!target.isFile) return@forEach

            val link = File(binDirectory, tool)
            link.delete()

            try {
                Os.symlink("$javaHomeRelative/bin/$tool", link.absolutePath)
            } catch (e: Exception) {
                target.copyTo(link, overwrite = true)
                link.setExecutable(true, false)
            }
        }
    }

    private fun javaBinary(): File = File(terminal.bootstrap.prefixDir, "bin/java")

    private fun tarPath(): String = File(terminal.bootstrap.prefixDir, "bin/tar").absolutePath

    private fun directoryFor(version: String): File = File(storageDirectory, version)

    private companion object {
        val TERMUX_PACKAGE_NAME = Regex("[A-Za-z0-9@._+-]+")
        val JAVA_TOOLS = listOf("java", "javac", "jar", "keytool", "jarsigner")
    }
}
