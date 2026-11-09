package com.mrboomdev.kotnya.ui

import java.io.File

/** One node of the project file tree. Directories carry their children, files carry none. */
data class FileNode(val file: File, val children: List<FileNode> = emptyList()) {
    val isDirectory: Boolean get() = file.isDirectory
}

/** A single line of a file that matched a content search. */
data class ContentMatch(val file: File, val lineNumber: Int, val line: String)

private val SKIPPED_DIRECTORIES = setOf("build", ".git", ".idea", ".gradle", ".kotnya")

val EDITABLE_EXTENSIONS = setOf(
    "kt", "kts", "java", "xml", "yaml", "yml", "toml", "md", "properties", "txt", "pro", "json",
    "gradle", "sh", "bat", "cfg", "ini", "csv", "html", "css", "js", "ts",
)

const val MAX_EDITABLE_FILE_SIZE = 512L * 1024L

fun buildFileTree(root: File): List<FileNode> = childrenOf(root)

private fun childrenOf(directory: File): List<FileNode> = directory.listFiles()
    ?.filter { file -> !(file.isDirectory && file.name in SKIPPED_DIRECTORIES) }
    ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    ?.map { file -> FileNode(file, if (file.isDirectory) childrenOf(file) else emptyList()) }
    .orEmpty()

fun isEditableFile(file: File): Boolean = file.isFile &&
    file.length() <= MAX_EDITABLE_FILE_SIZE &&
    (file.extension.lowercase() in EDITABLE_EXTENSIONS || file.name == "kotlin")

fun walkProjectFiles(root: File): Sequence<File> = root.walkTopDown()
    .onEnter { file -> file == root || file.name !in SKIPPED_DIRECTORIES }
    .filter(File::isFile)

/** Matches the query against file names and their project-relative paths. */
fun searchFileNames(root: File, query: String, limit: Int = 200): List<File> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()

    return walkProjectFiles(root)
        .filter { file ->
            file.name.contains(needle, ignoreCase = true) ||
                file.relativeTo(root).path.contains(needle, ignoreCase = true)
        }
        .take(limit)
        .toList()
}

/** Searches the text files of a project line by line. */
fun searchFileContents(root: File, query: String, limit: Int = 200): List<ContentMatch> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()

    val matches = mutableListOf<ContentMatch>()

    for (file in walkProjectFiles(root)) {
        if (!isEditableFile(file)) continue

        val lines = runCatching { file.readLines() }.getOrNull() ?: continue

        for ((index, line) in lines.withIndex()) {
            if (!line.contains(needle, ignoreCase = true)) continue

            matches += ContentMatch(file, index + 1, line.trim())
            if (matches.size >= limit) return matches
        }
    }

    return matches
}
