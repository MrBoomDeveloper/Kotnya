package com.mrboomdev.kotnya.ui

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** The small set of languages Kotnya can syntax-highlight without pulling in a grammar engine. */
internal enum class SourceKind {
    Kotlin,
    Java,
    Groovy,
    Xml,
    Json,
    Yaml,
    Properties,
    Shell,
    Markdown,
    Plain;

    companion object {
        fun fromPath(path: String): SourceKind = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> Kotlin
            "java" -> Java
            "gradle", "groovy" -> Groovy
            "xml", "html" -> Xml
            "json" -> Json
            "yaml", "yml" -> Yaml
            "properties", "cfg", "ini" -> Properties
            "sh", "bat" -> Shell
            "md", "markdown" -> Markdown
            else -> Plain
        }
    }
}

/** One colored range of a line. The end is exclusive. */
private data class Token(val start: Int, val end: Int, val style: Long)

/**
 * A context-free tokenizer for the languages the IDE opens.
 *
 * This is intentionally small: it runs in sora-editor's background analyzer thread, tracks the
 * handful of multi-line constructs a code file needs (block comments, XML comments and Kotlin
 * triple-quoted strings), and leaves the rest of the text with the normal text color.
 */
internal class CodeSyntaxAnalyzer(
    private val source: SourceKind,
) : SimpleAnalyzeManager<Unit>() {

    private class ScanState {
        var blockComment = false
        var tripleString = false
        var xmlComment = false
    }

    override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
        val builder = MappedSpans.Builder()
        val state = ScanState()
        var line = 0
        var start = 0
        val length = text.length

        while (true) {
            if (delegate.isCancelled) return Styles()

            var end = text.indexOf('\n', start)
            if (end < 0) end = length

            var lineText = text.substring(start, end)
            if (lineText.endsWith('\r')) lineText = lineText.dropLast(1)

            builder.add(line, Span.obtain(0, NORMAL))

            for (token in tokensFor(lineText, state)) {
                if (token.start < lineText.length) {
                    builder.add(line, Span.obtain(token.start, token.style))
                }
            }

            line++
            if (end >= length) break
            start = end + 1
        }

        builder.determine((line - 1).coerceAtLeast(0))
        return Styles(builder.build())
    }

    private fun tokensFor(line: String, state: ScanState): List<Token> = when (source) {
        SourceKind.Kotlin, SourceKind.Java, SourceKind.Groovy -> cLikeTokens(line, state)
        SourceKind.Xml -> xmlTokens(line, state)
        SourceKind.Json -> jsonTokens(line)
        SourceKind.Yaml, SourceKind.Properties, SourceKind.Shell -> hashCommentTokens(line)
        SourceKind.Markdown -> markdownTokens(line)
        SourceKind.Plain -> emptyList()
    }

    private fun cLikeTokens(line: String, state: ScanState): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        if (state.blockComment) {
            val close = line.indexOf("*/")
            if (close < 0) {
                if (line.isNotEmpty()) tokens += Token(0, line.length, COMMENT)
                return tokens
            }

            tokens += Token(0, close + 2, COMMENT)
            i = close + 2
            state.blockComment = false
        }

        if (state.tripleString) {
            val close = line.indexOf("\"\"\"", i)
            if (close < 0) {
                if (i < line.length) tokens += Token(i, line.length, STRING)
                return tokens
            }

            tokens += Token(i, close + 3, STRING)
            i = close + 3
            state.tripleString = false
        }

        while (i < line.length) {
            val c = line[i]

            when {
                c == '/' && line.startsWith("//", i) -> {
                    tokens += Token(i, line.length, COMMENT)
                    return tokens
                }

                c == '/' && line.startsWith("/*", i) -> {
                    val close = line.indexOf("*/", i + 2)
                    if (close < 0) {
                        tokens += Token(i, line.length, COMMENT)
                        state.blockComment = true
                        return tokens
                    }

                    tokens += Token(i, close + 2, COMMENT)
                    i = close + 2
                }

                c == '"' && line.startsWith("\"\"\"", i) -> {
                    val close = line.indexOf("\"\"\"", i + 3)
                    if (close < 0) {
                        tokens += Token(i, line.length, STRING)
                        state.tripleString = true
                        return tokens
                    }

                    tokens += Token(i, close + 3, STRING)
                    i = close + 3
                }

                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != c) {
                        if (line[j] == '\\') j++
                        j++
                    }

                    val end = (j + 1).coerceAtMost(line.length)
                    tokens += Token(i, end, STRING)
                    i = end
                }

                c == '@' -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++

                    tokens += Token(i, j, ANNOTATION)
                    i = j
                }

                c.isDigit() -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++

                    tokens += Token(i, j, LITERAL)
                    i = j
                }

                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++

                    val word = line.substring(i, j)
                    val style = when {
                        word == "true" || word == "false" || word == "null" -> LITERAL
                        word in keywords -> KEYWORD
                        line.getOrNull(j) == '(' -> FUNCTION
                        word.first().isUpperCase() -> TYPE
                        else -> null
                    }

                    if (style != null) tokens += Token(i, j, style)
                    i = j
                }

                else -> i++
            }
        }

        return tokens
    }

    private fun jsonTokens(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        while (i < line.length) {
            val c = line[i]

            when {
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != c) {
                        if (line[j] == '\\') j++
                        j++
                    }

                    val end = (j + 1).coerceAtMost(line.length)
                    val isKey = line.getOrNull(end)?.let { next ->
                        var k = end
                        while (k < line.length && line[k].isWhitespace()) k++
                        line.getOrNull(k) == ':'
                    } == true

                    tokens += Token(i, end, if (isKey) ATTRIBUTE else STRING)
                    i = end
                }

                c.isDigit() || (c == '-' && line.getOrNull(i + 1)?.isDigit() == true) -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isDigit() || line[j] in ".eE+-")) j++
                    tokens += Token(i, j, LITERAL)
                    i = j
                }

                c.isLetter() -> {
                    var j = i + 1
                    while (j < line.length && line[j].isLetter()) j++

                    if (line.substring(i, j) in JSON_LITERALS) tokens += Token(i, j, LITERAL)
                    i = j
                }

                else -> i++
            }
        }

        return tokens
    }

    private fun hashCommentTokens(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var firstWord = true

        while (i < line.length) {
            val c = line[i]

            when {
                c == '#' -> {
                    tokens += Token(i, line.length, COMMENT)
                    return tokens
                }

                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != c) {
                        if (line[j] == '\\') j++
                        j++
                    }

                    tokens += Token(i, (j + 1).coerceAtMost(line.length), STRING)
                    i = j + 1
                    firstWord = false
                }

                c.isDigit() -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isDigit() || line[j] == '.')) j++
                    tokens += Token(i, j, LITERAL)
                    i = j
                    firstWord = false
                }

                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_-.")) j++
                    val word = line.substring(i, j)

                    if (firstWord) {
                        var k = j
                        while (k < line.length && line[k].isWhitespace()) k++
                        if (line.getOrNull(k) == ':' || line.getOrNull(k) == '=') {
                            tokens += Token(i, j, ATTRIBUTE)
                        }
                    } else if (word in SHELL_KEYWORDS) {
                        tokens += Token(i, j, KEYWORD)
                    }

                    firstWord = false
                    i = j
                }

                else -> i++
            }
        }

        return tokens
    }

    private fun xmlTokens(line: String, state: ScanState): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        if (state.xmlComment) {
            val close = line.indexOf("-->")
            if (close < 0) {
                if (line.isNotEmpty()) tokens += Token(0, line.length, COMMENT)
                return tokens
            }

            tokens += Token(0, close + 3, COMMENT)
            i = close + 3
            state.xmlComment = false
        }

        while (i < line.length) {
            val comment = line.indexOf("<!--", i)
            val tag = line.indexOf('<', i)

            when {
                comment >= 0 && (tag < 0 || comment < tag) -> {
                    val close = line.indexOf("-->", comment + 4)
                    if (close < 0) {
                        tokens += Token(comment, line.length, COMMENT)
                        state.xmlComment = true
                        return tokens
                    }

                    tokens += Token(comment, close + 3, COMMENT)
                    i = close + 3
                }

                tag >= 0 -> {
                    val close = tagEnd(line, tag)
                    if (close < 0) {
                        tokens += Token(tag, line.length, TAG)
                        return tokens
                    }

                    tokens += Token(tag, close + 1, TAG)
                    i = close + 1
                }

                else -> break
            }
        }

        return tokens
    }

    private fun tagEnd(line: String, start: Int): Int {
        var quote = '\u0000'

        for (i in start + 1 until line.length) {
            val c = line[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
        }

        return -1
    }

    private fun markdownTokens(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val trimmed = line.trimStart()

        if (trimmed.startsWith("#")) {
            tokens += Token(line.length - trimmed.length, line.length, KEYWORD)
        }

        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '`' -> {
                    val close = line.indexOf('`', i + 1)
                    val end = if (close < 0) line.length else close + 1
                    tokens += Token(i, end, STRING)
                    i = end
                }

                c == '#' -> {
                    val marker = line.length - line.trimStart().length
                    if (i == marker) i = line.length
                    else {
                        tokens += Token(i, line.length, COMMENT)
                        return tokens
                    }
                }

                else -> i++
            }
        }

        return tokens
    }

    private companion object {
        val NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        val KEYWORD = TextStyle.makeStyle(EditorColorScheme.KEYWORD)
        val COMMENT = TextStyle.makeStyle(EditorColorScheme.COMMENT)
        val STRING = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        val LITERAL = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        val ANNOTATION = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
        val FUNCTION = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
        val TYPE = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME)
        val TAG = TextStyle.makeStyle(EditorColorScheme.HTML_TAG)
        val ATTRIBUTE = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)

        val JSON_LITERALS = setOf("true", "false", "null")

        val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "in", "select", "until", "export", "local", "readonly", "return",
        )

        val keywords = setOf(
            // Shared control flow.
            "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break",
            "continue", "try", "catch", "finally", "throw", "new", "this", "super", "instanceof",
            // Java.
            "abstract", "assert", "boolean", "byte", "char", "class", "const", "double", "enum",
            "extends", "final", "float", "goto", "implements", "import", "int", "interface", "long",
            "native", "package", "private", "protected", "public", "short", "static", "strictfp",
            "synchronized", "throws", "transient", "void", "volatile", "var", "record", "sealed",
            "permits", "non-sealed", "yield",
            // Kotlin.
            "as", "by", "companion", "constructor", "crossinline", "data", "delegate", "dynamic",
            "expect", "actual", "external", "field", "fun", "get", "infix", "init", "inline", "inner",
            "internal", "is", "it", "lateinit", "noinline", "object", "open", "operator", "out",
            "override", "param", "property", "receiver", "reified", "set", "setparam", "suspend",
            "tailrec", "typealias", "val", "value", "vararg", "when", "where", "annotation",
            // Gradle / Groovy.
            "apply", "buildscript", "dependencies", "plugins", "repositories", "ext", "task", "def",
        )
    }
}

/** A fresh sora language per editor, as required by the editor's lifecycle. */
internal class CodeSyntaxLanguage(private val source: SourceKind) : EmptyLanguage() {
    private val analyzer = CodeSyntaxAnalyzer(source)

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun destroy() {
        analyzer.destroy()
    }
}
