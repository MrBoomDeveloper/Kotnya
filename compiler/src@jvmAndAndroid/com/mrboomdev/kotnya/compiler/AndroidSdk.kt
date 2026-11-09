package com.mrboomdev.kotnya.compiler

import java.io.File

/** The Android SDK installation that lives inside the Termux data directory. */
data class AndroidSdk(
    val directory: File,
    val commandLineTools: File?,
    val platforms: List<String>,
    val buildTools: List<String>,
) {
    val hasPlatform: Boolean get() = platforms.isNotEmpty()
    val hasBuildTools: Boolean get() = buildTools.isNotEmpty()

    /** An SDK is only usable for builds when at least one platform and one build-tools version exist. */
    val isInstalled: Boolean get() = hasPlatform && hasBuildTools
}
