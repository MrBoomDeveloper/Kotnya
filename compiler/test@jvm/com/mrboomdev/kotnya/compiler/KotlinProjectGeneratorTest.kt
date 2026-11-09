package com.mrboomdev.kotnya.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class KotlinProjectGeneratorTest {

    private val generator = KotlinProjectGenerator()

    @Test
    fun `generates a console project`() {
        withTempDirectory { root ->
            val directory = File(root, "hello")
            generator.generate(NewProject(directory, "hello", "com.example.hello"))

            assertTrue(File(directory, "project.yaml").isFile)
            assertTrue(File(directory, "app/module.yaml").isFile)
            assertTrue(File(directory, "app/src/Main.kt").isFile)

            assertEquals("- app", moduleList(directory))
            assertEquals("product: jvm/app", File(directory, "app/module.yaml").readText().trim())

            val main = File(directory, "app/src/Main.kt").readText()
            assertContains(main, "package com.example.hello")
            assertContains(main, "fun main()")
            assertContains(main, "Hello from hello!")
        }
    }

    @Test
    fun `generates an android project`() {
        withTempDirectory { root ->
            val directory = File(root, "mobile")
            generator.generate(
                NewProject(
                    directory = directory,
                    name = "mobile",
                    packageName = "com.example.mobile",
                    template = ProjectTemplate.AndroidApp,
                    compileSdk = 37,
                    targetSdk = 28,
                    minSdk = 26,
                )
            )

            val moduleYaml = File(directory, "app/module.yaml").readText()
            assertContains(moduleYaml, "product: android/app")
            assertContains(moduleYaml, "namespace: com.example.mobile")
            assertContains(moduleYaml, "applicationId: com.example.mobile")
            assertContains(moduleYaml, "minSdk: 26")
            assertContains(moduleYaml, "compileSdk: 37")
            assertContains(moduleYaml, "targetSdk: 28")

            val manifest = File(directory, "app/src/AndroidManifest.xml")
            assertTrue(manifest.isFile)
            assertContains(manifest.readText(), "android:name=\".MainActivity\"")
            assertTrue(File(directory, "app/src/MainActivity.kt").readText().contains("class MainActivity"))
        }
    }

    @Test
    fun `rejects an invalid project name`() {
        withTempDirectory { root ->
            assertFailsWith<IllegalArgumentException> {
                generator.generate(NewProject(File(root, "invalid"), "9 bad name", "com.example.hello"))
            }
        }
    }

    @Test
    fun `rejects an invalid package name`() {
        withTempDirectory { root ->
            assertFailsWith<IllegalArgumentException> {
                generator.generate(NewProject(File(root, "demo"), "demo", "com.Example.Hello"))
            }
        }
    }

    @Test
    fun `rejects a non-empty directory`() {
        withTempDirectory { root ->
            val directory = File(root, "taken")
            directory.mkdirs()
            File(directory, "existing.txt").writeText("keep me")

            assertFailsWith<IllegalArgumentException> {
                generator.generate(NewProject(directory, "taken", "com.example.taken"))
            }

            assertFalse(File(directory, "project.yaml").exists())
        }
    }

    private fun moduleList(projectDirectory: File): String =
        File(projectDirectory, "project.yaml").readText()
            .substringAfter("modules:")
            .trim()

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("kotnya-generator-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
