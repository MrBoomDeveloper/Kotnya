package com.mrboomdev.kotnya.compiler

import com.mrboomdev.kotnya.terminal.ShellCommand
import com.mrboomdev.kotnya.terminal.ShellEvent
import com.mrboomdev.kotnya.terminal.ShellStream
import com.mrboomdev.kotnya.terminal.TerminalModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class CompileRequest(
    val projectDirectory: File,
    val toolchain: KotlinToolchain,
    /** Kotlin Toolchain tasks, e.g. `build`, `test`, `run`. */
    val tasks: List<String> = listOf("build"),
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)

sealed interface CompilerEvent {
    data class Started(val command: String) : CompilerEvent
    data class Output(val line: String, val stream: ShellStream) : CompilerEvent
    data class Finished(val exitCode: Int, val durationMillis: Long) : CompilerEvent {
        val isSuccess: Boolean get() = exitCode == 0
    }
    data class Failed(val message: String, val cause: Throwable? = null) : CompilerEvent
}

/**
 * Runs Kotlin Toolchain tasks for a project directory and streams their output as it arrives.
 *
 * The build runs inside the same Termux prefix as the terminal, so anything the terminal module
 * installed (JDK, git, packages) is visible to the toolchain.
 */
class KotlinCompiler(private val terminal: TerminalModule) {

    fun compile(request: CompileRequest): Flow<CompilerEvent> = channelFlow {
        if (!request.toolchain.isInstalled) {
            send(
                CompilerEvent.Failed(
                    "Kotlin Toolchain ${request.toolchain.version} is not installed " +
                        "(missing ${request.toolchain.launcher.absolutePath})"
                )
            )
            return@channelFlow
        }

        if (!request.projectDirectory.isDirectory) {
            send(CompilerEvent.Failed("Project directory does not exist: ${request.projectDirectory.absolutePath}"))
            return@channelFlow
        }

        val javaHome = findJavaHome(terminal.bootstrap.prefixDir)
        if (javaHome == null) {
            send(
                CompilerEvent.Failed(
                    "No Java 25 runtime was found. Install the Java runtime in the SDK manager first."
                )
            )
            return@channelFlow
        }

        val argv = buildList {
            add(terminal.bootstrap.shellPath.absolutePath)
            add(request.toolchain.launcher.absolutePath)
            addAll(request.tasks)
            addAll(request.arguments)
        }

        val command = ShellCommand(
            argv = argv,
            workingDirectory = request.projectDirectory,
            environment = request.environment + mapOf(
                "KOTLIN_CLI_NO_WELCOME_BANNER" to "1",
                // Without this the launcher downloads its own JRE, which uses a hard-link lock that
                // Android refuses on app-private storage and then loops forever. Java 25 is already
                // installed for the toolchain, so point the launcher at it instead.
                "KOTLIN_CLI_JAVA_HOME" to javaHome.absolutePath,
                "JAVA_HOME" to javaHome.absolutePath,
            ),
        )

        send(CompilerEvent.Started(command.displayName))
        val startedAt = System.currentTimeMillis()

        terminal.shell.stream(command).collect { event ->
            when (event) {
                is ShellEvent.Started -> Unit
                is ShellEvent.Output -> send(CompilerEvent.Output(event.line, event.stream))
                is ShellEvent.Finished ->
                    send(CompilerEvent.Finished(event.exitCode, System.currentTimeMillis() - startedAt))

                is ShellEvent.Failed -> send(CompilerEvent.Failed(event.message, event.error))
            }
        }
    }.flowOn(Dispatchers.IO)
}
