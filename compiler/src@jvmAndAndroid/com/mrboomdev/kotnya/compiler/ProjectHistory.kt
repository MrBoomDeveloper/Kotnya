package com.mrboomdev.kotnya.compiler

import java.io.File

/** A project found in the workspace, with the time it was last opened from the IDE. */
data class ProjectEntry(
    val name: String,
    val directory: File,
    val lastOpenedAt: Long?,
)

/**
 * Keeps the workspace's project list and its metadata (`last opened`) in sync.
 *
 * The metadata lives outside the project folders, in `<workspace>/.kotnya/projects.properties`, so
 * renaming or deleting a project never touches anything but its own directory.
 */
class ProjectHistory(val workspace: File) {

    val metadataFile: File = File(workspace, "$METADATA_DIRECTORY/projects.properties")

    /** Projects sorted by most recently opened first, with never-opened projects last. */
    fun entries(): List<ProjectEntry> {
        workspace.mkdirs()
        val lastOpened = readLastOpened()

        return workspace.listFiles()
            ?.filter { it.isDirectory && File(it, "project.yaml").isFile }
            ?.map { ProjectEntry(name = it.name, directory = it, lastOpenedAt = lastOpened[it.name]) }
            ?.sortedWith(
                compareByDescending<ProjectEntry> { it.lastOpenedAt ?: 0L }
                    .thenBy { it.name.lowercase() }
            )
            .orEmpty()
    }

    fun markOpened(directory: File, at: Long = System.currentTimeMillis()) {
        val values = readLastOpened()
        values[directory.name] = at
        writeLastOpened(values)
    }

    fun rename(oldDirectory: File, newDirectory: File) {
        val values = readLastOpened()
        val openedAt = values.remove(oldDirectory.name) ?: return

        values[newDirectory.name] = openedAt
        writeLastOpened(values)
    }

    fun forget(directory: File) {
        val values = readLastOpened()
        if (values.remove(directory.name) == null) return

        writeLastOpened(values)
    }

    private fun readLastOpened(): MutableMap<String, Long> {
        if (!metadataFile.isFile) return mutableMapOf()

        return buildMap {
            metadataFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachLine

                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@forEachLine

                val timestamp = trimmed.substring(separator + 1).trim().toLongOrNull()
                if (timestamp != null) {
                    put(trimmed.substring(0, separator).trim(), timestamp)
                }
            }
        }.toMutableMap()
    }

    private fun writeLastOpened(values: Map<String, Long>) {
        metadataFile.parentFile?.mkdirs()

        val body = values.entries
            .sortedBy { it.key }
            .joinToString(separator = "\n") { (name, timestamp) -> "$name=$timestamp" }

        metadataFile.writeText(body + if (body.isEmpty()) "" else "\n")
    }

    private companion object {
        const val METADATA_DIRECTORY = ".kotnya"
    }
}
