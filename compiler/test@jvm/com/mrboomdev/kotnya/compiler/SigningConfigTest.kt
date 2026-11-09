package com.mrboomdev.kotnya.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class SigningConfigTest {

    @Test
    fun `saves and loads a relative keystore path`() {
        withProject { project ->
            val keystore = File(SigningConfig.directory(project), "release.keystore").apply {
                parentFile?.mkdirs()
                writeText("not a real keystore")
            }

            val config = SigningConfig(
                keystore = keystore,
                alias = "release",
                storePassword = "secret1",
                keyPassword = "secret2",
            )

            val file = SigningConfig.save(project, config)
            assertTrue(file.isFile)

            val loaded = SigningConfig.load(project)
            assertEquals("release", loaded?.alias)
            assertEquals("secret1", loaded?.storePassword)
            assertEquals("secret2", loaded?.keyPassword)
            assertEquals(keystore.absolutePath, loaded?.keystore?.absolutePath)
        }
    }

    @Test
    fun `returns null when the project has no signing configuration`() {
        withProject { project ->
            assertNull(SigningConfig.load(project))
        }
    }

    private fun withProject(block: (File) -> Unit) {
        val project = Files.createTempDirectory("kotnya-signing-test").toFile()
        try {
            block(project)
        } finally {
            project.deleteRecursively()
        }
    }
}
