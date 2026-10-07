package app.gamenative.wow

import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Merges required CVars into WTF/Config.wtf.
 *
 * Existing `SET <key> ...` lines are replaced in place (key match is case-insensitive), missing keys are
 * appended, and every other line (portal, locale, user settings) is preserved. Written with CRLF.
 * Never filter the file line-by-line with external tools: that previously dropped portal/locale.
 */
object WowConfigWtf {
    /**
     * Always forced: the narrator / TTS stack asserts under Wine
     * (ASSERTSAFE(m_platformInterface != nullptr) VoiceSpeakManager.cpp:188/213).
     * Names extracted from WowB-ARM64.exe build 70235.
     */
    private val forced = linkedMapOf(
        "accessibilityScreenNarrationEnabled" to "0",
        "showScreenNarrationDialog" to "0",
        "textToSpeech" to "0",
        "remoteTextToSpeech" to "0",
        "speechToText" to "0",
        "winePlatformTTS" to "0",
        // Handheld: gamepad UI (InputDeviceInterfaceStyle 1) with the gamepad cursor up from the login screen.
        "InputDeviceInterfaceStyle" to "1",
        "GamePadEnable" to "1",
        "GamePadCursorOnLogin" to "1",
        "GamePadCursorAutoEnable" to "1",
    )

    /**
     * First-run defaults, written once per container (marker file in the container root) and only for
     * keys the file does not already contain. Graphics presets are skipped entirely if a graphicsQuality
     * line already exists. WoW drops CVars equal to their default, so "missing later" does not mean
     * "never set"; hence the one-shot marker instead of re-adding on every launch.
     */
    private val firstRunDefaults = linkedMapOf(
        "graphicsQuality" to "1",
        "RAIDgraphicsQuality" to "1",
    )

    private const val MARKER = ".wowforever-wtf-init"

    /**
     * Handheld performance profile, applied once per profile version (overwriting existing values) so the
     * player can still change settings in-game afterwards. On the RP5 (Adreno 650) WoW was GPU-bound at
     * 99% busy on preset 2 with vsync and native render scale, and RAM was nearly exhausted.
     */
    private val perfProfile = linkedMapOf(
        "graphicsQuality" to "1",
        "RAIDgraphicsQuality" to "1",
        // v5: the window is 1280x720 (UI and text at that size); the 3D scene renders at 0.75 (960x540, the
        // resolution the 60 fps budget was measured at) and is upscaled with FidelityFX FSR 1.0.
        "renderScale" to "0.75",
        "ResampleQuality" to "3",
        "vsync" to "0",
        "maxFPS" to "60",
        "maxFPSBk" to "60", // under Wine the login screen counts as background; a low cap pinned it at 8 fps
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
        // v2: the remaining GPU-side effects and texture / shadow / physics costs
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
        // v5: full-resolution textures (memory, not GPU time, on the RP5; low textures looked blurry).
        "worldBaseMip" to "0",
        "RAIDworldBaseMip" to "0",
        "terrainMipLevel" to "0",
        "RAIDterrainMipLevel" to "0",
        // v4: WoW's global illumination was ~110 ms of a ~150 ms open-world frame on Adreno 650 (three ~1 MB
        // compute shaders); giQuality 0 alone took Raven Hill from 8 to ~15 fps. Its remaining dispatches are
        // skipped in DXVK (Wow.SKIP_SHADERS).
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

    /**
     * [gxApi] ("D3D11" / "D3D12", the only values WowB-ARM64.exe accepts) is always written so launches that
     * don't pass our -d3d11 / -d3d12 argument (Battle.net) use the same renderer; null leaves it untouched.
     */
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
