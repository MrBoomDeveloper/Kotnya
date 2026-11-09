package com.mrboomdev.kotnya.compiler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads [url] into [target], reporting progress and verifying [expectedSha256] when supplied.
 * On any failure the partial file is deleted.
 */
internal suspend fun downloadFile(
    url: String,
    target: File,
    expectedSha256: String? = null,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> },
) = withContext(Dispatchers.IO) {
    target.parentFile?.mkdirs()

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 60_000
        instanceFollowRedirects = true
    }

    try {
        val responseCode = connection.responseCode
        check(responseCode in 200..299) { "Received HTTP $responseCode while downloading $url" }

        val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        var downloaded = 0L
        var reported = 0L

        connection.inputStream.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    output.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    downloaded += read

                    if (downloaded - reported >= 512 * 1024) {
                        reported = downloaded
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        }

        onProgress(downloaded, totalBytes)

        if (digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                target.delete()
                error("Checksum mismatch for $url: expected $expectedSha256 but got $actual")
            }
        }
    } catch (e: Throwable) {
        target.delete()
        throw e
    } finally {
        connection.disconnect()
    }
}
