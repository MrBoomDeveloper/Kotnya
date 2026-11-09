package com.mrboomdev.kotnya.compiler

import android.content.Context
import com.mrboomdev.kotnya.terminal.TerminalModule

/**
 * Entry point of the compiler module.
 *
 * It owns the [TerminalModule] used for toolchain installation and compilation, so the compiler
 * shares the terminal's Termux prefix, `$HOME` and environment. An app usually creates one
 * [CompilerModule] and takes the terminal sessions it shows from [CompilerModule.terminal].
 */
class CompilerModule(context: Context) {
    val terminal = TerminalModule(context)
    val toolchains = KotlinToolchainManager(terminal)
    val androidSdk = AndroidSdkManager(terminal)
    val catalog = SdkCatalog()
    val compiler = KotlinCompiler(terminal)
    val projects = KotlinProjectGenerator()
}
