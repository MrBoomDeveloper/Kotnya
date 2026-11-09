package com.mrboomdev.kotnya.compiler

import java.io.File

/** The Kotlin Toolchain version pinned by this project's `kotlin` wrapper script. */
const val DEFAULT_TOOLCHAIN_VERSION = "0.13.0"

/** Maven root that hosts Kotlin Toolchain distributions. */
const val DEFAULT_TOOLCHAIN_DOWNLOAD_ROOT = "https://packages.jetbrains.team/maven/p/amper/amper"

/**
 * An extracted Kotlin Toolchain distribution. [directory] contains `bin/launcher.sh` and `lib/`,
 * and is run with a Java runtime available in the Termux `$PATH`.
 */
data class KotlinToolchain(
    val version: String,
    val directory: File,
) {
    val launcher: File get() = File(directory, "bin/launcher.sh")

    val isInstalled: Boolean get() = launcher.isFile

    val downloadUrl: String
        get() = "$DEFAULT_TOOLCHAIN_DOWNLOAD_ROOT/org/jetbrains/kotlin/kotlin-cli/$version/kotlin-cli-$version-dist.tgz"
}
