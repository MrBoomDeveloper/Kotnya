package com.mrboomdev.kotnya.compiler

import com.mrboomdev.kotnya.terminal.ShellCommand
import com.mrboomdev.kotnya.terminal.TerminalModule
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Generates release keystores and signs built APKs on the device.
 *
 * `keytool` comes from the JDK in the Termux prefix and `apksigner` from the Android SDK's
 * build-tools. Both are run through the terminal module, so they see the same environment as the
 * builds and the interactive shell.
 */
class AndroidApkSigner(
    private val terminal: TerminalModule,
    private val androidSdk: () -> AndroidSdk?,
) {

    /** The APK produced by the last build, ignoring previously signed outputs. */
    fun findApk(projectDirectory: File): File? = projectDirectory.walkTopDown()
        .onEnter { it.name != ".git" && it.name != SigningConfig.DIRECTORY_NAME }
        .filter { it.isFile && it.extension == "apk" && !it.name.endsWith(SIGNED_SUFFIX) }
        .maxByOrNull(File::lastModified)

    /** Creates a self-signed RSA keystore in `<project>/.kotnya` and persists its configuration. */
    suspend fun generateKeystore(projectDirectory: File, request: KeystoreRequest): SigningConfig {
        require(request.password.length >= MIN_PASSWORD_LENGTH) {
            "The keystore password must be at least $MIN_PASSWORD_LENGTH characters long."
        }
        require(request.validityYears in 1..MAX_VALIDITY_YEARS) {
            "Validity must be between 1 and $MAX_VALIDITY_YEARS years."
        }
        require(request.keySize >= MIN_KEY_SIZE) { "The RSA key size must be at least $MIN_KEY_SIZE bits." }
        require(FILE_NAME.matches(request.fileName)) { "Invalid keystore file name '${request.fileName}'." }
        require(ALIAS.matches(request.alias)) { "Invalid key alias '${request.alias}'." }

        val keytool = File(terminal.bootstrap.prefixDir, "bin/keytool")
        check(keytool.canExecute()) {
            "keytool was not found. Install the Java runtime in the SDK manager first."
        }

        val directory = SigningConfig.directory(projectDirectory)
        directory.mkdirs()

        val keystore = File(directory, request.fileName)
        check(!keystore.exists()) { "${keystore.name} already exists in ${directory.absolutePath}." }

        val commonName = request.commonName
            .replace(UNSAFE_DN_CHARACTERS, " ")
            .trim()
            .ifEmpty { "Kotnya" }

        val result = terminal.shell.run(
            ShellCommand(
                argv = listOf(
                    keytool.absolutePath,
                    "-genkeypair",
                    "-keystore", keystore.absolutePath,
                    "-storetype", "PKCS12",
                    "-alias", request.alias,
                    "-keyalg", "RSA",
                    "-keysize", request.keySize.toString(),
                    "-validity", request.validityDays.toString(),
                    "-dname", "CN=$commonName, OU=Kotnya, O=Kotnya, C=US",
                    "-storepass:env", STORE_PASSWORD_ENV,
                    "-keypass:env", KEY_PASSWORD_ENV,
                ),
                workingDirectory = projectDirectory,
                environment = passwordEnvironment(request.password),
            )
        )

        check(result.isSuccess) {
            "keytool failed with exit code ${result.exitCode}: ${result.output.takeLast(LOG_TAIL)}"
        }

        val config = SigningConfig(
            keystore = keystore,
            alias = request.alias,
            storePassword = request.password,
            keyPassword = request.password,
        )

        SigningConfig.save(projectDirectory, config)
        return config
    }

    /**
     * Signs [apk] with [config] and returns the signed copy next to it.
     *
     * Existing signatures are stripped first, because re-signing a debug-signed APK would otherwise
     * leave stale `META-INF` signature files inside the archive.
     */
    suspend fun sign(projectDirectory: File, apk: File, config: SigningConfig): File {
        check(apk.isFile) { "The APK to sign does not exist: ${apk.absolutePath}" }
        check(config.keystore.isFile) { "Keystore not found: ${config.keystore.absolutePath}" }

        val apksigner = apksignerCommand()
        val tmp = File(SigningConfig.directory(projectDirectory), "tmp").apply { mkdirs() }
        val unsigned = File(tmp, "unsigned-${apk.name}")
        val output = File(apk.parentFile, "${apk.nameWithoutExtension}$SIGNED_SUFFIX")

        try {
            stripSignatures(apk, unsigned)

            val result = terminal.shell.run(
                ShellCommand(
                    argv = apksigner + listOf(
                        "sign",
                        "--ks", config.keystore.absolutePath,
                        "--ks-key-alias", config.alias,
                        "--ks-pass", "env:$STORE_PASSWORD_ENV",
                        "--key-pass", "env:$KEY_PASSWORD_ENV",
                        "--out", output.absolutePath,
                        unsigned.absolutePath,
                    ),
                    workingDirectory = projectDirectory,
                    environment = passwordEnvironment(config.storePassword).toMutableMap().apply {
                        put(KEY_PASSWORD_ENV, config.keyPassword)
                    },
                )
            )

            check(result.isSuccess) {
                "apksigner failed with exit code ${result.exitCode}: ${result.output.takeLast(LOG_TAIL)}"
            }

            val verification = terminal.shell.run(
                ShellCommand(
                    argv = apksigner + listOf("verify", "--print-certs", output.absolutePath),
                    workingDirectory = projectDirectory,
                )
            )

            check(verification.isSuccess) {
                "The signed APK failed verification: ${verification.output.takeLast(LOG_TAIL)}"
            }

            return output
        } finally {
            unsigned.delete()
            tmp.delete()
        }
    }

    /** The APK signer shipped with the newest installed build-tools version. */
    private fun apksignerCommand(): List<String> {
        val sdk = androidSdk() ?: error("Install the Android SDK command-line tools and build-tools first.")
        val buildTools = sdk.buildTools.maxOrNull() ?: error("Install Android build-tools in the SDK dashboard first.")
        val directory = File(sdk.directory, "build-tools/$buildTools")

        val script = File(directory, "apksigner")
        if (script.isFile) {
            return listOf(terminal.bootstrap.shellPath.absolutePath, script.absolutePath)
        }

        val jar = File(directory, "lib/apksigner.jar")
        val java = File(terminal.bootstrap.prefixDir, "bin/java")

        check(jar.isFile && java.canExecute()) {
            "apksigner was not found in $directory. Reinstall Android build-tools."
        }

        return listOf(java.absolutePath, "-jar", jar.absolutePath)
    }

    private fun stripSignatures(input: File, output: File) {
        ZipInputStream(input.inputStream().buffered()).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                while (true) {
                    val entry = zip.nextEntry ?: break

                    if (isSignatureFile(entry.name)) {
                        zip.closeEntry()
                        continue
                    }

                    val copy = ZipEntry(entry.name).apply {
                        method = entry.method
                        time = entry.time

                        // Stored entries need their checksum and size up front.
                        if (entry.method == ZipEntry.STORED) {
                            size = entry.size
                            compressedSize = entry.size
                            crc = entry.crc
                        }
                    }

                    out.putNextEntry(copy)
                    if (!entry.isDirectory) zip.copyTo(out)
                    out.closeEntry()
                    zip.closeEntry()
                }
            }
        }
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false

        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }

    private fun passwordEnvironment(password: String): Map<String, String> = mapOf(
        STORE_PASSWORD_ENV to password,
        KEY_PASSWORD_ENV to password,
    )

    private companion object {
        const val STORE_PASSWORD_ENV = "KOTNYA_STORE_PASSWORD"
        const val KEY_PASSWORD_ENV = "KOTNYA_KEY_PASSWORD"
        const val MIN_PASSWORD_LENGTH = 6
        const val MIN_KEY_SIZE = 2048
        const val MAX_VALIDITY_YEARS = 1000
        const val LOG_TAIL = 800
        const val SIGNED_SUFFIX = "-signed.apk"

        val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val UNSAFE_DN_CHARACTERS = Regex("[,=+<>#;\"\\\\]")
    }
}
