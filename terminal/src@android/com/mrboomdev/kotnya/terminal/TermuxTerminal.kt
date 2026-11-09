package com.mrboomdev.kotnya.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Renders [session] with Termux's own renderer, so colors, cursor, scrollback, text selection and
 * soft-keyboard input behave exactly like in the Termux application.
 */
@Composable
fun TermuxTerminal(
    session: TermuxSession,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 12.sp,
    viewClient: TerminalViewClient = remember { DefaultTerminalViewClient() },
) {
    val textSizePx = with(LocalDensity.current) { textSize.toPx().toInt() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context, null).apply {
                setTerminalViewClient(viewClient)
                setTextSize(textSizePx)
                attachSession(session.terminalSession)
            }
        },
        update = { view ->
            if (view.currentSession !== session.terminalSession) {
                view.attachSession(session.terminalSession)
            }

            view.setTextSize(textSizePx)
        },
    )
}

/**
 * Base [TerminalViewClient] that leaves keyboard handling to the renderer. Override it to add an
 * extra key row, pinch-to-zoom or a hardware keyboard mapping.
 */
open class DefaultTerminalViewClient : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(event: MotionEvent) = Unit

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() = Unit

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
        Log.e(tag, "Terminal view failure", e)
    }
}
