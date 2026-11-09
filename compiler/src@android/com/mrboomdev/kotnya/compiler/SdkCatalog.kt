package com.mrboomdev.kotnya.compiler

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** One downloadable Termux bootstrap archive published by the termux-packages project. */
data class TermuxBootstrapRelease(
    val version: String,
    val abi: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val publishedAt: String? = null,
    val sizeBytes: Long? = null,
)

/** One package of the Android SDK repository, e.g. `platforms;android-36` or `build-tools;36.0.0`. */
data class SdkPackage(
    /** The path passed to `sdkmanager --install`, e.g. `platforms;android-36`. */
    val path: String,
    /** The version used for display and for matching what is already installed, e.g. `android-36`. */
    val version: String,
    val name: String,
    val apiLevel: Int? = null,
    /** A direct Linux download, which only the command-line tools packages provide. */
    val downloadUrl: String? = null,
    val sizeBytes: Long? = null,
    val isStable: Boolean = true,
)

/** The Android SDK packages the dashboard can browse. */
data class SdkPackages(
    val commandLineTools: List<SdkPackage>,
    val platforms: List<SdkPackage>,
    val buildTools: List<SdkPackage>,
)

/**
 * Browsable version lists for everything the app installs.
 *
 * The user never has to paste a URL: bootstraps come from the termux-packages release feed and the
 * Android packages come from Google's repository index, the same file the Android Studio SDK manager
 * reads. Both are fetched on demand and are plain HTTP GETs.
 */
class SdkCatalog(
    private val bootstrapsUrl: String = TERMUX_BOOTSTRAPS_URL,
    private val repositoryUrl: String = ANDROID_REPOSITORY_URL,
) {

    /** All published Termux bootstraps, newest first. */
    suspend fun bootstraps(): List<TermuxBootstrapRelease> = withContext(Dispatchers.IO) {
        parseBootstraps(fetch(bootstrapsUrl))
    }

    /** All stable and preview Android SDK packages, newest first. */
    suspend fun packages(): SdkPackages = withContext(Dispatchers.IO) {
        parseRepository(fetch(repositoryUrl))
    }

    /** The ABI of the bootstrap archive that matches this device, and the version ordering helper. */
    companion object {
        const val TERMUX_BOOTSTRAPS_URL = "https://api.github.com/repos/termux/termux-packages/releases?per_page=30"
        const val ANDROID_REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml"

        val deviceAbi: String
            get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a", "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "i686"
                else -> "aarch64"
            }

        private const val TAG_PREFIX = "bootstrap-"

        private val ABI_BY_ASSET = mapOf(
            "bootstrap-aarch64.zip" to "aarch64",
            "bootstrap-arm.zip" to "arm",
            "bootstrap-i686.zip" to "i686",
            "bootstrap-x86_64.zip" to "x86_64",
        )

        /** Orders `1.0.0` before `1.10.0`, which plain string comparison gets wrong. */
        private val VERSION_ORDER: Comparator<String> = Comparator { left, right ->
            val leftParts = left.split('.', '-')
            val rightParts = right.split('.', '-')

            for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
                val leftPart = leftParts.getOrNull(index)
                val rightPart = rightParts.getOrNull(index)

                if (leftPart == null || rightPart == null) {
                    return@Comparator (leftPart ?: "").compareTo(rightPart ?: "")
                }

                val leftNumber = leftPart.toIntOrNull()
                val rightNumber = rightPart.toIntOrNull()

                if (leftNumber == null || rightNumber == null) {
                    leftPart.compareTo(rightPart).let { if (it != 0) return@Comparator it }
                } else if (leftNumber != rightNumber) {
                    return@Comparator leftNumber - rightNumber
                }
            }

            0
        }
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Kotnya")
            setRequestProperty(
                "Accept",
                if (url.endsWith(".xml")) "application/xml" else "application/vnd.github+json",
            )
        }

        try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) { "Received HTTP $responseCode while fetching $url" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBootstraps(json: String): List<TermuxBootstrapRelease> {
        val releases = JSONArray(json)
        val result = mutableListOf<TermuxBootstrapRelease>()

        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue

            val tag = release.optString("tag_name")
            // The repository also publishes pacman bootstraps and older Android 5 archives; only the
            // apt bootstrap for Android 7+ ships the `pkg` command and libraries this app needs.
            if (!tag.startsWith(TAG_PREFIX) || !tag.contains("apt")) continue

            val assets = release.optJSONArray("assets") ?: continue
            val publishedAt = release.optString("published_at").takeIf(String::isNotEmpty)

            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val abi = ABI_BY_ASSET[asset.optString("name")] ?: continue
                val url = asset.optString("browser_download_url").takeIf(String::isNotEmpty) ?: continue

                result += TermuxBootstrapRelease(
                    version = tag.removePrefix(TAG_PREFIX),
                    abi = abi,
                    downloadUrl = url,
                    sha256 = asset.optString("digest")
                        .removePrefix("sha256:")
                        .takeIf { it.length == 64 },
                    publishedAt = publishedAt,
                    sizeBytes = asset.optLong("size").takeIf { it > 0 },
                )
            }
        }

        return result.sortedWith(compareByDescending<TermuxBootstrapRelease> { it.publishedAt ?: "" })
    }

    private fun parseRepository(xml: String): SdkPackages {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }

        val root = factory.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        val channels = root.children("channel").associate { it.getAttribute("id") to it.text.trim() }

        val commandLineTools = mutableListOf<SdkPackage>()
        val platforms = mutableListOf<SdkPackage>()
        val buildTools = mutableListOf<SdkPackage>()

        root.children("remotePackage").forEach { remotePackage ->
            if (remotePackage.child("obsolete")?.text?.trim() == "true") return@forEach

            val path = remotePackage.getAttribute("path")
            val name = remotePackage.child("display-name")?.text?.trim() ?: path
            val isStable = remotePackage.child("channelRef")
                ?.getAttribute("ref")
                ?.let { channels[it] ?: "stable" }
                ?.equals("stable", ignoreCase = true) ?: true

            when {
                path.startsWith("cmdline-tools;") -> {
                    val version = path.substringAfter(';')
                    val archive = remotePackage.linuxArchive()

                    commandLineTools += SdkPackage(
                        path = path,
                        version = version,
                        name = if (version == "latest") "Command-line tools (latest)"
                        else "Command-line tools $version",
                        downloadUrl = archive?.child("complete")?.child("url")?.text?.trim()
                            ?.takeIf(String::isNotEmpty),
                        sizeBytes = archive?.child("complete")?.child("size")?.text?.trim()?.toLongOrNull(),
                        isStable = isStable,
                    )
                }

                path.startsWith("platforms;") -> platforms += SdkPackage(
                    path = path,
                    version = path.substringAfter(';'),
                    name = name,
                    apiLevel = remotePackage.child("type-details")
                        ?.child("api-level")?.text?.trim()?.toIntOrNull(),
                    isStable = isStable,
                )

                path.startsWith("build-tools;") -> buildTools += SdkPackage(
                    path = path,
                    version = path.substringAfter(';'),
                    name = name,
                    isStable = isStable,
                )
            }
        }

        // The repository can list the same package path more than once (stable and preview revisions),
        // and the dashboard uses the path as its list key, so collapse duplicates here.
        return SdkPackages(
            commandLineTools = commandLineTools
                .distinctBy(SdkPackage::path)
                .sortedWith(newestFirst())
                .sortedByDescending { it.version == "latest" },
            platforms = platforms
                .distinctBy(SdkPackage::path)
                .sortedWith(newestFirst())
                .sortedByDescending { it.apiLevel ?: -1 },
            buildTools = buildTools
                .distinctBy(SdkPackage::path)
                .sortedWith(newestFirst()),
        )
    }

    private fun newestFirst(): Comparator<SdkPackage> =
        compareBy<SdkPackage, String>(VERSION_ORDER.reversed()) { it.version }

    /** The archive of a package that runs on Linux; platforms and build-tools have one for all hosts. */
    private fun Element.linuxArchive(): Element? = child("archives")
        ?.children("archive")
        ?.firstOrNull { archive ->
            val hostOs = archive.child("host-os")?.text?.trim()
            hostOs.isNullOrEmpty() || hostOs == "linux"
        }

    private fun Element.elements(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private fun Element.tag(): String = (localName ?: tagName).substringAfter(':')

    private fun Element.children(tag: String): List<Element> = elements().filter { it.tag() == tag }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

    private val Element.text: String
        get() = textContent ?: ""
}
