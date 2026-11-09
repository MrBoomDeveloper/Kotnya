package com.mrboomdev.kotnya.compiler

import com.mrboomdev.kotnya.terminal.ShellCommand
import com.mrboomdev.kotnya.terminal.TerminalModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Progress of installing Termux packages from the Termux repository. */
sealed interface PackageEvent {
    data class Downloading(val packageName: String, val bytes: Long, val total: Long?) : PackageEvent
    data class Extracting(val packageName: String) : PackageEvent
    data class Installed(val packageName: String) : PackageEvent
    data class Failed(val packageName: String, val message: String, val cause: Throwable? = null) : PackageEvent
}

/** One package of the Termux apt repository. */
data class TermuxPackage(
    val name: String,
    val version: String,
    val filename: String,
    val sha256: String?,
    val sizeBytes: Long?,
    val dependencies: List<String>,
)

/**
 * Installs Termux packages without apt.
 *
 * The bootstrap and every Termux binary are built for the `com.termux` package name, so its `pkg`
 * and `apt` scripts look for `/data/data/com.termux/files/usr`, which this app cannot provide.
 * Instead the `.deb` archives are downloaded from the Termux repository and unpacked straight into
 * the app's own prefix, together with the dependencies the package declares.
 */
class TermuxDebInstaller(
    private val terminal: TerminalModule,
    private val repository: String = TERMUX_REPOSITORY,
) {

    suspend fun install(packageNames: List<String>): Flow<PackageEvent> = channelFlow {
        val prefix = terminal.bootstrap.prefixDir
        val tmp = terminal.bootstrap.tmpDir
        val staging = File(tmp, "deb-staging")
        val markerDirectory = File(prefix, "var/lib/kotnya/packages")

        try {
            val index = fetchIndex()
            val queue = resolveDependencies(index, packageNames)

            if (!packageNames.all(index::containsKey)) {
                val missing = packageNames.filterNot(index::containsKey)
                send(PackageEvent.Failed(missing.first(), "Package not found in the Termux repository"))
                return@channelFlow
            }

            for (name in queue) {
                val packageFile = index[name] ?: continue
                val marker = File(markerDirectory, name)
                if (marker.isFile) continue

                installPackage(packageFile, prefix, tmp, staging) { trySend(it) }
                marker.parentFile?.mkdirs()
                marker.writeText(packageFile.version)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(
                PackageEvent.Failed(
                    packageName = packageNames.lastOrNull() ?: "package",
                    message = e.message ?: "Could not install the package",
                    cause = e,
                )
            )
        } finally {
            staging.deleteRecursively()
            File(tmp, "data.tar.xz").delete()
            File(tmp, "data.tar").delete()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun installPackage(
        pkg: TermuxPackage,
        prefix: File,
        tmp: File,
        staging: File,
        emit: (PackageEvent) -> Unit,
    ) {
        val deb = File(tmp, "${pkg.name}.deb")
        emit(PackageEvent.Downloading(pkg.name, 0, pkg.sizeBytes))

        downloadFile("$repository/${pkg.filename}", deb, pkg.sha256) { downloaded, total ->
            emit(PackageEvent.Downloading(pkg.name, downloaded, total))
        }

        emit(PackageEvent.Extracting(pkg.name))

        val dataArchive = File(tmp, "data.tar.xz")
        dataArchive.delete()
        extractDebMember(deb, "data.tar.xz", dataArchive)

        val dataTar = File(tmp, "data.tar")
        dataTar.delete()
        run(prefix, "unxz", "-f", dataArchive.absolutePath)

        staging.deleteRecursively()
        staging.mkdirs()
        run(prefix, "tar", "-xf", dataTar.absolutePath, "-C", staging.absolutePath, "--no-same-owner")

        // deb payloads are laid out as `./data/data/com.termux/files/usr/...`.
        val root = File(staging, "data/data/com.termux/files/usr")
        check(root.isDirectory) { "The ${pkg.name} archive has an unexpected layout" }

        // `cp -a` keeps the symlinks and executable bits the JDK relies on.
        run(prefix, "cp", "-a", root.absolutePath + "/.", prefix.absolutePath)

        emit(PackageEvent.Installed(pkg.name))

        deb.delete()
        dataTar.delete()
        staging.deleteRecursively()
    }

    private suspend fun run(prefix: File, tool: String, vararg arguments: String) {
        val result = terminal.shell.run(
            ShellCommand(
                argv = listOf(File(prefix, "bin/$tool").absolutePath) + arguments,
                workingDirectory = terminal.bootstrap.tmpDir,
            )
        )

        check(result.isSuccess) {
            "$tool failed with exit code ${result.exitCode}: ${result.output.take(400)}"
        }
    }

    private fun fetchIndex(): Map<String, TermuxPackage> {
        val url = "$repository/dists/stable/main/binary-${SdkCatalog.deviceAbi}/Packages"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Kotnya")
        }

        val text = try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) { "Received HTTP $responseCode while fetching $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        return text.split("\n\n")
            .mapNotNull(::parseStanza)
            .associateBy(TermuxPackage::name)
    }

    private fun parseStanza(stanza: String): TermuxPackage? {
        val fields = mutableMapOf<String, String>()

        stanza.lines().forEach { line ->
            if (line.startsWith(' ') || line.startsWith('\t')) return@forEach

            val separator = line.indexOf(':')
            if (separator > 0) fields[line.take(separator)] = line.substring(separator + 1).trim()
        }

        val name = fields["Package"] ?: return null
        val filename = fields["Filename"] ?: return null

        return TermuxPackage(
            name = name,
            version = fields["Version"].orEmpty(),
            filename = filename,
            sha256 = fields["SHA256"],
            sizeBytes = fields["Size"]?.toLongOrNull(),
            dependencies = fields["Depends"]
                ?.split(',')
                ?.mapNotNull { dependency ->
                    dependency.substringBefore('|')
                        .trim()
                        .substringBefore(' ')
                        .substringBefore('(')
                        .trim()
                        .ifEmpty { null }
                }
                .orEmpty(),
        )
    }

    /** Dependency-first order, so a package is unpacked after everything it needs. */
    private fun resolveDependencies(
        index: Map<String, TermuxPackage>,
        requested: List<String>,
    ): List<String> {
        val ordered = mutableListOf<String>()

        fun visit(name: String) {
            if (name in ordered) return

            val pkg = index[name] ?: return
            ordered += name
            pkg.dependencies.forEach(::visit)
        }

        requested.forEach(::visit)

        // `ordered` is parent-first; reverse it into a dependency-first install order.
        return ordered.asReversed()
    }

    /** Extracts one member of the deb's `ar` archive, e.g. `data.tar.xz`. */
    private fun extractDebMember(deb: File, member: String, target: File) {
        deb.inputStream().buffered().use { input ->
            val globalHeader = ByteArray(8)
            check(readFully(input, globalHeader) && String(globalHeader) == "!<arch>\n") {
                "${deb.name} is not a deb archive"
            }

            val header = ByteArray(60)

            while (readFully(input, header)) {
                val name = String(header, 0, 16).trim().trimEnd('/')
                val size = String(header, 48, 10).trim().toLong()

                if (name == member) {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        copyExactly(input, output, size)
                    }
                    return
                }

                skipExactly(input, size + (size % 2))
            }
        }

        error("${deb.name} does not contain $member")
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0

        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }

        return true
    }

    private fun copyExactly(input: InputStream, output: OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(read >= 0) { "Unexpected end of the deb archive" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes

        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                check(input.read() >= 0) { "Unexpected end of the deb archive" }
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private companion object {
        const val TERMUX_REPOSITORY = "https://packages.termux.dev/apt/termux-main"
    }
}
