package com.mrboomdev.kotnya.terminal

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Entry point of the terminal module.
 *
 * Typical use:
 * ```
 * val terminal = TerminalModule(context)
 * terminal.install(BootstrapSource.Asset("bootstrap-aarch64.zip"), "0.118.0")
 * val session = terminal.openSession()
 * ```
 */
class TerminalModule(context: Context) {

    private val appContext = context.applicationContext

    val bootstrap = TermuxBootstrap(appContext)
    val shell = ShellExecutor(bootstrap)

    /** Downloads, verifies and extracts the Termux user-land. */
    suspend fun install(
        source: BootstrapSource,
        version: String = "unknown",
        onProgress: (BootstrapProgress) -> Unit = {},
        onDownloadProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) = bootstrap.install(source, version, onProgress, onDownloadProgress)

    /**
     * Starts an interactive shell (or [arguments] instead of a shell) attached to a PTY.
     *
     * @param arguments arguments passed to the shell, e.g. `listOf("-c", "ls")`.
     */
    fun openSession(
        arguments: List<String> = emptyList(),
        workingDirectory: File = bootstrap.homeDir,
        client: TerminalSessionClient = SimpleTerminalSessionClient(),
        transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    ): TermuxSession {
        check(bootstrap.isInstalled) {
            "The Termux bootstrap is not installed. Call install() before opening a session."
        }

        workingDirectory.mkdirs()

        val environment = bootstrap.environment()
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()

        val session = TerminalSession(
            bootstrap.shellPath.absolutePath,
            workingDirectory.absolutePath,
            arguments.toTypedArray(),
            environment,
            transcriptRows,
            client,
        )

        Log.d(TAG, "Opened Termux session ${session.mHandle} in ${workingDirectory.absolutePath}")
        return TermuxSession(workingDirectory, session)
    }

    /** Runs a non-interactive `bash -c` script and buffers its output. */
    suspend fun exec(
        script: String,
        workingDirectory: File = bootstrap.homeDir,
        environment: Map<String, String> = emptyMap(),
    ): ShellResult = shell.run(shell.script(script, workingDirectory, environment))

    private companion object {
        const val TAG = "TerminalModule"
        const val DEFAULT_TRANSCRIPT_ROWS = 2_000
    }
}
