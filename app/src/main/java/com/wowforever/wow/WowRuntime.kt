package com.wowforever.wow

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Runtime files downloaded at first run, mirrored (paths patched to com.wowforever with
 * tools/patch-assets.js) on the WoW Forever GitHub release. Nothing is fetched from GameNative.
 */
object WowRuntime {
    const val BASE_URL = "https://github.com/Bemental77/wow_forever_android_apk/releases/download/runtime-v1/"

    /** Bundled component manifest (replaces the remote GameNative manifest.json). */
    const val MANIFEST_ASSET = "wow-runtime.json"

    /** [aliases]: file names the stock code asks for that map to this release asset. */
    data class Asset(val name: String, val sha256: String, val aliases: List<String> = emptyList()) {
        val url: String get() = BASE_URL + name
    }

    val PROTON = Asset("proton-11.0-90624-arm64ec.wcp", "4f44dd00b9acbd71d96fee7e783d0b94189934d4602e3239465f9f65cd90fa1c")
    val TURNIP_ALT = Asset("turnip-wow-scheduler-test.zip", "02384f692735515f73aad8a544bc640abe3f35fe18e8df2e667be712c442e54c")
    val TURNIP_RP6 = Asset("Turnip-V32-RP6sched-A8xx.zip", "05434d3c102563a4577f514410cc713494bcf900f52c2b935391adf87a76b77a")

    val ASSETS = listOf(
        PROTON,
        TURNIP_ALT,
        TURNIP_RP6,
        Asset("imagefs_bionic.txz", "368db62bfc58b72c97e5169bda9aa64d4246f07964c447e27c79a065e7e9c48b"),
        Asset("experimental-drm-20260116.tzst", "0d40e7a0f39054d89bfb7e7a641cf715fd2662abae387ddaddc42d38ffbaf191"),
        Asset("FEXCore-2609.wcp", "64f5538b841f533a7c9f07446c9db0650a754059966080c91340e3fd281a29d7"),
        // container_files (modern flavor; legacy bundles them)
        Asset("extras.tzst", "d47d2c0e12f4f59beb3e4a80ed9e7373a15fc7cc796d406fd6f18d0f58249184"),
        Asset("container_pattern_common_20260821.tzst", "c62311ac7a10a149f33cd1b2fcdf9f79c8a6b35d8b46066b33108c003b7e85c6"),
        Asset(
            "container_pattern_wowforever.tzst",
            "f19a9e7280df753c0ea197ca8aaee46aa31de9ee2d7e59de3a5870132d0001fd",
            aliases = listOf("container_pattern_gamenative.tzst"),
        ),
        Asset("proton-9.0-x86_64_container_pattern.tzst", "fa25987a3ba4f1b951bd1161f9a65a9450f0b72b87583fd3d9971d92c63a9608"),
        Asset("proton-9.0-arm64ec_container_pattern.tzst", "9826ac61405f641ca8326335208c3e1ed5cec107f4b0354f813825ec398b841b"),
        // wincomponents enabled in the WoW container (modern flavor)
        Asset("direct3d.tzst", "149d55436e050bfa3fe8caf650cb97ddb2bc9fa99cd96869bd2bc4977b708fb5"),
        Asset("directsound.tzst", "72f0e880eef1387a55602ac02b07ef26778d23c2f70d931bd2d13e92f8dc99d4"),
        Asset("vcrun2010.tzst", "9def7b4b5c31c839d155f05e372e0e9ce4968b57f815c1abf93b7735e7d0ceb7"),
        Asset("wmdecoder.tzst", "49a9f29fa099d4568b6c43a0f710e6f5e0dba3c1eb401325bbda9c82c8937b9b"),
        // "Wrapper" graphics driver files (modern flavor)
        Asset("wrapper.tzst", "2005169d30ab7558b0c404ff8a3d781cddfde2f012531e097ccc95c423a706a5"),
        Asset("extra_libs.tzst", "5e42e95bc79f098f22822cdee25a21099e585536db01927216ea30a6eb7ca27f"),
        Asset("zink_dlls.tzst", "efe27f0de6a55bfb6c2e9eab79b6baaae64b35af81ce49c097de6b720f258cbb"),
    )

    /** Release asset for a stock file name such as "imagefs_bionic.txz" or "container_files/x.tzst". */
    fun find(fileName: String): Asset? {
        val base = fileName.substringAfterLast('/')
        return ASSETS.firstOrNull { it.name == base || base in it.aliases }
    }

    /** GameNative-operated hosts/repos the WoW build never contacts. */
    fun isBlocked(url: String): Boolean {
        val u = url.lowercase()
        return "gamenative.app" in u || "gamenative.com" in u || "utkarshdalal" in u ||
            "pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev" in u || "posthog.com" in u
    }

    /** Release asset served at [url], or null for any other URL. */
    fun forUrl(url: String): Asset? =
        if (url.startsWith(BASE_URL)) ASSETS.firstOrNull { it.url == url } else null

    /** Deletes [f] and throws if its SHA-256 does not match [a]. */
    fun verify(f: File, a: Asset) {
        val actual = sha256(f)
        if (actual != a.sha256) {
            f.delete()
            throw IOException("Checksum mismatch for ${a.name}\nexpected ${a.sha256}\ngot $actual")
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
