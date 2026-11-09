package com.mrboomdev.kotnya

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Hands a built APK to the system package installer. The user still has to confirm the install
 * (and allow installing unknown apps for this app once).
 */
class ApkInstaller(private val context: Context) {

    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(parameters)

        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            val resultIntent = Intent(context, PackageInstallReceiver::class.java)
                .setAction(PackageInstallReceiver.ACTION_INSTALL_RESULT)
                .putExtra(PackageInstallReceiver.EXTRA_SESSION_ID, sessionId)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)

            session.commit(pendingIntent.intentSender)
        }
    }
}
