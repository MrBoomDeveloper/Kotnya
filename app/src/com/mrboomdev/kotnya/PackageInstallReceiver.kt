package com.mrboomdev.kotnya

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Carries install results from the broadcast receiver to the editor's output panel. */
object InstallStatusBus {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun post(message: String) {
        _messages.tryEmit(message)
    }
}

class PackageInstallReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallStatusBus.post("Package installed successfully")

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { confirmation ->
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }
                InstallStatusBus.post("Confirm the installation on the device")
            }

            else -> InstallStatusBus.post("Install failed: ${message ?: "status $status"}")
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.mrboomdev.kotnya.INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
