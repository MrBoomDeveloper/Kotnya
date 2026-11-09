package com.mrboomdev.kotnya.compiler

/** Share of active Android devices that run one platform version. */
data class AndroidVersionShare(val apiLevel: Int, val name: String, val percent: Double)

/**
 * Snapshot of Google's distribution dashboard. Google does not expose the dashboard as an API, so
 * the table is bundled and refreshed by hand; the SDK dashboard shows [capturedOn] next to it.
 *
 * Percentages only cover devices that have Google Play installed.
 */
object AndroidDistribution {
    const val capturedOn = "28 days ending November 24, 2025"
    const val sourceUrl = "https://developer.android.com/about/dashboards"

    val versions: List<AndroidVersionShare> = listOf(
        AndroidVersionShare(21, "Android 5.0 (Lollipop)", 0.1),
        AndroidVersionShare(22, "Android 5.1 (Lollipop)", 0.3),
        AndroidVersionShare(23, "Android 6.0 (Marshmallow)", 0.4),
        AndroidVersionShare(24, "Android 7.0 (Nougat)", 0.4),
        AndroidVersionShare(25, "Android 7.1 (Nougat)", 0.4),
        AndroidVersionShare(26, "Android 8.0 (Oreo)", 0.8),
        AndroidVersionShare(27, "Android 8.1 (Oreo)", 2.3),
        AndroidVersionShare(28, "Android 9 (Pie)", 4.5),
        AndroidVersionShare(29, "Android 10", 7.8),
        AndroidVersionShare(30, "Android 11", 13.7),
        AndroidVersionShare(31, "Android 12", 11.4),
        AndroidVersionShare(33, "Android 13", 13.9),
        AndroidVersionShare(34, "Android 14", 17.2),
        AndroidVersionShare(35, "Android 15", 19.3),
        AndroidVersionShare(36, "Android 16", 7.4),
    )

    private val byApiLevel = versions.associateBy(AndroidVersionShare::apiLevel)

    /** The release name for [apiLevel], or null when the dashboard does not report it. */
    fun nameOf(apiLevel: Int): String? = byApiLevel[apiLevel]?.name

    /** The device share for [apiLevel] in percent, or null when it is not reported. */
    fun shareOf(apiLevel: Int): Double? = byApiLevel[apiLevel]?.percent
}
