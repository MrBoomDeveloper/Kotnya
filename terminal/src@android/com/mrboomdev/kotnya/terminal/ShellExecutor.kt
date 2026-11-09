package com.mrboomdev.kotnya.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream

enum class ShellStream { Stdout, Stderr }

/**
 * A non-interactive process executed with the Termux environment, e.g. `tar -xzf ...` or `pkg install -y`.
 */
data class ShellCommand(
    val argv: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(argv.isNotEmpty()) { "argv must not be empty" }
    }

    val displayName: String get() = argv.joinToString(" ")
}

sealed interface ShellEvent {
    data class Started(val command: ShellCommand) : ShellEvent
    data class Output(val line: String, val stream: ShellStream) : ShellEvent

    /** The process exited. All output lines are emitted before this event. */
    data class Finished(val exitCode: Int) : ShellEvent

    /** The process could not be started, or the flow was cancelled. */
    data class Failed(val message: String, val error: Throwable) : ShellEvent
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val isSuccess: Boolean get() = exitCode == 0

    /** Stdout followed by stderr, for callers that show output as a single blob. */
    val output: String
        get() = when {
            stderr.isEmpty() -> stdout
            stdout.isEmpty() -> stderr
            else -> "$stdout\n$stderr"
        }
}

/**
 * Runs processes inside the Termux user-land, streaming their output line by line.
 *
 * Note that this uses a plain pipe rather than a PTY: it is meant for compilers and package managers.
 * Interactive programs should run in a [TermuxSession] instead.
 */
class ShellExecutor(private val bootstrap: TermuxBootstrap) {

    private companion object {
        /** How long to keep reading output after the process itself has exited. */
        const val STREAM_DRAIN_TIMEOUT_MS = 2_000L
    }

    /** Builds a `bash -c` command with the Termux environment. */
    fun script(
        script: String,
        workingDirectory: File = bootstrap.homeDir,
        environment: Map<String, String> = emptyMap(),
    ): ShellCommand = ShellCommand(
        argv = listOf(bootstrap.shellPath.absolutePath, "-c", script),
        workingDirectory = workingDirectory,
        environment = environment,
    )

    fun stream(command: ShellCommand): Flow<ShellEvent> = channelFlow {
        val process = try {
            start(command)
        } catch (e: IOException) {
            send(ShellEvent.Failed("Could not start ${command.argv.first()}", e))
            return@channelFlow
        }

        send(ShellEvent.Started(command))

        val stdoutJob = launch(Dispatchers.IO) { pump(process.inputStream, ShellStream.Stdout) }
        val stderrJob = launch(Dispatchers.IO) { pump(process.errorStream, ShellStream.Stderr) }

        try {
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }

            // A build daemon or another child process can keep the pipe open after the main
            // process exits. Drain what is there, but never wait forever for an EOF that will not
            // come; otherwise a finished build looks like it is still running.
            withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MS) { stdoutJob.join() }
            withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MS) { stderrJob.join() }
            stdoutJob.cancel()
            stderrJob.cancel()

            send(ShellEvent.Finished(exitCode))
        } finally {
            if (process.isAlive) process.destroy()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun run(command: ShellCommand): ShellResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = -1

        stream(command).collect { event ->
            when (event) {
                is ShellEvent.Started -> Unit
                is ShellEvent.Output ->
                    if (event.stream == ShellStream.Stdout) stdout.appendLine(event.line)
                    else stderr.appendLine(event.line)

                is ShellEvent.Finished -> exitCode = event.exitCode
                is ShellEvent.Failed -> throw event.error
            }
        }

        return ShellResult(
            exitCode = exitCode,
            stdout = stdout.toString().trimEnd('\n'),
            stderr = stderr.toString().trimEnd('\n'),
        )
    }

    private fun start(command: ShellCommand): Process {
        require(command.workingDirectory.isDirectory) {
            "Working directory does not exist: ${command.workingDirectory.absolutePath}"
        }

        return ProcessBuilder(command.argv)
            .directory(command.workingDirectory)
            .apply { environment().putAll(bootstrap.environment(command.environment)) }
            .start()
            .also { process ->
                // Close stdin so a command that unexpectedly prompts cannot wait for input forever.
                runCatching { process.outputStream.close() }
            }
    }

    private suspend fun SendChannel<ShellEvent>.pump(stream: InputStream, name: ShellStream) {
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                send(ShellEvent.Output(line, name))
            }
        }
    }
}
