package com.wowforever.wow

import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/** Merges required CVars into WTF/Config.wtf, keeping all other lines (CRLF). */
object WowConfigWtf {
    // Always forced: narrator / TTS asserts under Wine.
    private val forced = linkedMapOf(
        "EnableVoiceChat" to "0", // its proxy process costs ~95 MB on a memory-tight device
        "accessibilityScreenNarrationEnabled" to "0",
        "showScreenNarrationDialog" to "0",
        "textToSpeech" to "0",
        "remoteTextToSpeech" to "0",
        "speechToText" to "0",
        "winePlatformTTS" to "0",
        // Gamepad UI and cursor from the login screen.
        "InputDeviceInterfaceStyle" to "1",
        "GamePadEnable" to "1",
        "GamePadCursorOnLogin" to "1",
        "GamePadCursorAutoEnable" to "1",
        // Load the WowForeverInput addon whatever the client's interface number.
        "checkAddonVersion" to "0",
    )

    // Written once per container (MARKER), only for missing keys.
    private val firstRunDefaults = linkedMapOf(
        "graphicsQuality" to "1",
        "RAIDgraphicsQuality" to "1",
    )

    private const val MARKER = ".wowforever-wtf-init"

    // Handheld performance profile, applied once per PERF_MARKER version; players can change it in-game.
    private val perfProfile = linkedMapOf(
        "graphicsQuality" to "1",
        "RAIDgraphicsQuality" to "1",
        // 3D at 0.75 scale, upscaled with FSR 1.0.
        "renderScale" to "0.75",
        "ResampleQuality" to "3",
        "vsync" to "0",
        "maxFPS" to "60",
        "maxFPSBk" to "60", // Wine treats the login screen as background
        "MSAAQuality" to "0",
        "ffxAntiAliasingMode" to "0",
        "farclip" to "800",
        "horizonClip" to "1000",
        "RAIDfarclip" to "1000",
        "RAIDhorizonClip" to "1000",
        "reflectionMode" to "0",
        "RAIDreflectionMode" to "0",
        "waterDetail" to "0",
        "RAIDWaterDetail" to "0",
        "sunShafts" to "0",
        "ffxGlow" to "0",
        "volumeFogLevel" to "0",
        "RAIDVolumeFogLevel" to "0",
        // Other GPU effects, shadows, physics
        "graphicsSunshafts" to "0",
        "particulatesEnabled" to "0",
        "graphicsOutlineMode" to "0",
        "outlineMode" to "0",
        "SSAO" to "0",
        "DepthBasedOpacity" to "0",
        "ffxDeath" to "0",
        "ffxNether" to "0",
        "ResampleAlwaysSharpen" to "0",
        "projectedTextures" to "0",
        "rippleDetail" to "0",
        "weatherDensity" to "0",
        "physicsLevel" to "0",
        "textureFilteringMode" to "0",
        "shadowMode" to "0",
        "refraction" to "0",
        "entityShadowFadeScale" to "0",
        "componentTextureLevel" to "0",
        // Full-resolution textures.
        "worldBaseMip" to "0",
        "RAIDworldBaseMip" to "0",
        "terrainMipLevel" to "0",
        "RAIDterrainMipLevel" to "0",
        // GI off; remaining GI dispatches are skipped in DXVK (Wow.SKIP_SHADERS).
        "giQuality" to "0",
        "RAIDgiQuality" to "0",
    ).apply {
        // graphics* / raidGraphics* sliders at their lowest sensible values
        linkedMapOf(
            "ViewDistance" to "1",
            "EnvironmentDetail" to "1",
            "GroundClutter" to "1",
            "LiquidDetail" to "0",
            "ComputeEffects" to "0",
            "ProjectedTextures" to "0",
            "ParticleDensity" to "1",
            "ShadowQuality" to "0",
            "SSAO" to "0",
            "DepthEffects" to "0",
            "SpellDensity" to "0",
            "TextureResolution" to "2",
        ).forEach { (k, v) -> put("graphics$k", v); put("raidGraphics$k", v) }
    }

    private const val PERF_MARKER = ".wowforever-perf-v5"

    fun ensureIfDirExists(c: Container) {
        if (Wow.winToHost(c, Wow.WOW_DIR).isDirectory) ensure(c)
    }

    fun ensure(c: Container) {
        try {
            val f = Wow.winToHost(c, Wow.WOW_DIR + "\\WTF\\Config.wtf")
            f.parentFile?.mkdirs()
            val marker = File(c.rootDir, MARKER)
            val firstRun = !marker.exists()
            val original = if (f.isFile) f.readText(Charsets.UTF_8) else ""
            val perfMarker = File(c.rootDir, PERF_MARKER)
            val applyPerf = !perfMarker.exists()
            val merged = merge(original, firstRun, applyPerf, gxApi = Wow.currentGraphicsApi().uppercase())
            if (merged != original) f.writeText(merged, Charsets.UTF_8)
            if (firstRun) marker.writeText("1")
            if (applyPerf) perfMarker.writeText("1")
        } catch (e: Exception) {
            Timber.tag("WowConfigWtf").e(e, "Failed to update Config.wtf")
        }
    }

    /** [gxApi] is written so Battle.net launches use the same renderer; null leaves it untouched. */
    internal fun merge(text: String, firstRun: Boolean, applyPerf: Boolean = false, gxApi: String? = null): String {
        val lines = text.split(Regex("\r?\n")).toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)

        fun indexOfKey(key: String): Int {
            val re = Regex("^\\s*SET\\s+${Regex.escape(key)}\\s", RegexOption.IGNORE_CASE)
            return lines.indexOfFirst { re.containsMatchIn(it) }
        }
        fun setLine(key: String, value: String) = "SET $key \"$value\""

        fun put(k: String, v: String) {
            val i = indexOfKey(k)
            if (i >= 0) lines[i] = setLine(k, v) else lines += setLine(k, v)
        }
        forced.forEach { (k, v) -> put(k, v) }
        if (gxApi != null) put("gxApi", gxApi)
        if (applyPerf) perfProfile.forEach { (k, v) -> put(k, v) }
        if (firstRun) {
            val hasGraphics = indexOfKey("graphicsQuality") >= 0
            firstRunDefaults.forEach { (k, v) ->
                val isGraphics = k.endsWith("graphicsQuality", ignoreCase = true)
                if (indexOfKey(k) < 0 && !(isGraphics && hasGraphics)) lines += setLine(k, v)
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }
}
