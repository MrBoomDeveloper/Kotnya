package com.mrboomdev.kotnya.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class ProjectHistoryTest {

    @Test
    fun `lists projects by last opened time`() {
        withWorkspace { workspace ->
            createProject(workspace, "alpha")
            createProject(workspace, "beta")
            createProject(workspace, "gamma")
            createProject(workspace, "not-a-project", marker = false)

            val history = ProjectHistory(workspace)
            history.markOpened(File(workspace, "alpha"), at = 1_000)
            history.markOpened(File(workspace, "beta"), at = 2_000)

            val entries = history.entries()

            assertEquals(listOf("beta", "alpha", "gamma"), entries.map { it.name })
            assertEquals(2_000L, entries.first().lastOpenedAt)
            assertNull(entries.last().lastOpenedAt)
        }
    }

    @Test
    fun `keeps the timestamp when a project is renamed`() {
        withWorkspace { workspace ->
            val oldDirectory = createProject(workspace, "old")
            val newDirectory = File(workspace, "new")

            val history = ProjectHistory(workspace)
            history.markOpened(oldDirectory, at = 42)
            assertTrue(oldDirectory.renameTo(newDirectory))

            history.rename(oldDirectory, newDirectory)

            val entry = history.entries().single()
            assertEquals("new", entry.name)
            assertEquals(42L, entry.lastOpenedAt)
        }
    }

    @Test
    fun `forgets a deleted project`() {
        withWorkspace { workspace ->
            val directory = createProject(workspace, "gone")

            val history = ProjectHistory(workspace)
            history.markOpened(directory, at = 7)
            history.forget(directory)

            assertNull(history.entries().single().lastOpenedAt)
        }
    }

    private fun createProject(workspace: File, name: String, marker: Boolean = true): File {
        val directory = File(workspace, name).apply { mkdirs() }
        if (marker) File(directory, "project.yaml").writeText("modules:\n  - app\n")
        return directory
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val workspace = Files.createTempDirectory("kotnya-history-test").toFile()
        try {
            block(workspace)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
