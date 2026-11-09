package com.mrboomdev.kotnya.compiler

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class AndroidSdkTest {

    @Test
    fun `an sdk without any installed packages is not usable`() {
        val sdk = AndroidSdk(File("/tmp/sdk"), null, emptyList(), emptyList())

        assertFalse(sdk.hasPlatform)
        assertFalse(sdk.hasBuildTools)
        assertFalse(sdk.isInstalled)
    }

    @Test
    fun `a platform without build tools is not usable`() {
        val sdk = AndroidSdk(File("/tmp/sdk"), File("/tmp/sdk/cmdline-tools/latest/bin/sdkmanager"), listOf("android-37"), emptyList())

        assertTrue(sdk.hasPlatform)
        assertFalse(sdk.hasBuildTools)
        assertFalse(sdk.isInstalled)
    }

    @Test
    fun `a platform and build tools make the sdk usable`() {
        val sdk = AndroidSdk(
            directory = File("/tmp/sdk"),
            commandLineTools = File("/tmp/sdk/cmdline-tools/latest/bin/sdkmanager"),
            platforms = listOf("android-37"),
            buildTools = listOf("37.0.0"),
        )

        assertTrue(sdk.isInstalled)
    }
}
