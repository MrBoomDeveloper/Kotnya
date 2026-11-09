package com.mrboomdev.kotnya.compiler

import java.io.File

/** The details needed to generate a release keystore with `keytool`. */
data class KeystoreRequest(
    val fileName: String = "release.keystore",
    val alias: String = "release",
    val password: String,
    val validityYears: Int = 30,
    val keySize: Int = 2048,
    val commonName: String,
) {
    /** `keytool` takes validity in days; the entered number of years is converted at 365 days each. */
    val validityDays: Long get() = validityYears * 365L
}

/**
 * The release signing configuration of one project.
 *
 * The keystore and `signing.properties` are stored in the project's `.kotnya` directory. The
 * passwords are kept there in plain text so the IDE can sign later builds without asking again;
 * the directory is hidden from the file browser and should never be committed.
 */
data class SigningConfig(
    val keystore: File,
    val alias: String,
    val storePassword: String,
    val keyPassword: String,
) {
    companion object {
        const val DIRECTORY_NAME = ".kotnya"
        const val FILE_NAME = "signing.properties"

        fun directory(projectDirectory: File): File = File(projectDirectory, DIRECTORY_NAME)

        fun propertiesFile(projectDirectory: File): File = File(directory(projectDirectory), FILE_NAME)

        fun load(projectDirectory: File): SigningConfig? {
            val file = propertiesFile(projectDirectory)
            if (!file.isFile) return null

            val values = readProperties(file)
            val storeFile = values["storeFile"] ?: return null
            val alias = values["keyAlias"] ?: return null

            val keystore = File(storeFile).let { candidate ->
                if (candidate.isAbsolute) candidate else File(directory(projectDirectory), storeFile)
            }

            return SigningConfig(
                keystore = keystore,
                alias = alias,
                storePassword = values["storePassword"].orEmpty(),
                keyPassword = values["keyPassword"].orEmpty(),
            )
        }

        /** Writes [config]; a keystore inside the project is stored as a path relative to `.kotnya`. */
        fun save(projectDirectory: File, config: SigningConfig): File {
            val file = propertiesFile(projectDirectory)
            file.parentFile?.mkdirs()

            val directory = directory(projectDirectory).absoluteFile
            val storeFile = runCatching {
                config.keystore.absoluteFile.relativeTo(directory).path
            }.getOrElse { config.keystore.absolutePath }

            file.writeText(
                buildString {
                    appendLine("storeFile=$storeFile")
                    appendLine("storePassword=${config.storePassword}")
                    appendLine("keyAlias=${config.alias}")
                    appendLine("keyPassword=${config.keyPassword}")
                }
            )

            return file
        }

        private fun readProperties(file: File): Map<String, String> = buildMap {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachLine

                val separator = trimmed.indexOf('=')
                if (separator > 0) {
                    put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim())
                }
            }
        }
    }
}
