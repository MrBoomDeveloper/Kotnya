package com.mrboomdev.kotnya.terminal

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.Closeable
import java.io.File

/**
 * A Termux process (usually `bash`) attached to a terminal emulator.
 *
 * Use [TerminalModule.openSession] to create one, and [TermuxTerminal] to render it.
 */
class TermuxSession internal constructor(
    val workingDirectory: File,
    val terminalSession: TerminalSession,
) : Closeable {

    val pid: Int get() = terminalSession.pid

    // A session that has never been rendered has no shell yet and reports pid 0. Termux's own
    // isRunning() treats that as running, so check the pid ourselves.
    val isRunning: Boolean get() = terminalSession.pid > 0 && terminalSession.isRunning
    val exitStatus: Int get() = terminalSession.exitStatus
    val title: String? get() = terminalSession.title

    /** Everything the session has printed, including scrollback. Useful to export terminal output. */
    val transcript: String?
        get() = terminalSession.emulator?.screen?.transcriptText

    /**
     * Starts terminal emulation. [TermuxTerminal] does this on its own; call it for a headless session.
     */
    fun initialize(columns: Int, rows: Int) {
        terminalSession.initializeEmulator(columns, rows)
    }

    fun resize(columns: Int, rows: Int) {
        terminalSession.updateSize(columns, rows)
    }

    fun write(input: String) {
        val bytes = input.toByteArray(Charsets.UTF_8)
        terminalSession.write(bytes, 0, bytes.size)
    }

    /** Writes [command] followed by the Enter key, as if the user typed it. */
    fun writeLine(command: String) = write("$command\r")

    /** Sends Ctrl+C to the foreground process. */
    fun interrupt() = write("\u0003")

    override fun close() {
        // finishIfRunning() calls Os.kill(pid, SIGKILL). For an uninitialized session the pid is 0,
        // and kill(0, ...) targets the caller's whole process group, which kills this app.
        if (terminalSession.pid > 0) terminalSession.finishIfRunning()
    }
}

/**
 * A [TerminalSessionClient] with sensible defaults, so callers only wire up the callbacks they need.
 */
open class SimpleTerminalSessionClient(
    private val titleChangedHandler: (TerminalSession) -> Unit = {},
    private val sessionFinishedHandler: (TerminalSession) -> Unit = {},
    private val bellHandler: (TerminalSession) -> Unit = {},
    private val clipboardTextHandler: (TerminalSession, String) -> Unit = { _, _ -> },
) : TerminalSessionClient {

    override fun onTextChanged(session: TerminalSession) = Unit

    override fun onTitleChanged(session: TerminalSession) = titleChangedHandler(session)

    override fun onSessionFinished(session: TerminalSession) = sessionFinishedHandler(session)

    override fun onClipboardText(session: TerminalSession, text: String) =
        clipboardTextHandler(session, text)

    override fun onBell(session: TerminalSession) = bellHandler(session)

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Termux session failure", e)
    }
}
