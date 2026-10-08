package com.wowforever.wow

import android.content.Context
import com.wowforever.PrefManager
import com.wowforever.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** Launch targets for the menu, launcher icon and shortcuts. */
enum class WowTarget {
    /** WowB-ARM64.exe directly (asks for the password). */
    PLAY,

    /** Via Battle.net so the auth token is passed (no password prompt). */
    PLAY_VIA_BNET,

    /** Battle.net client. */
    BNET,

    /** Battle.net client with --disable-gpu. */
    BNET_SAFE,

    /** Battle.net-Setup.exe. */
    SETUP_BNET,

    /** Menu only. */
    MENU,
}

object Wow {
    const val ACTION = "com.wowforever.ACTION"
    const val EXTRA_TARGET = "target"

    /** Fixed container id; numeric suffix is parsed by ContainerUtils. */
    const val APP_ID = "CUSTOM_GAME_2004"
    const val CONTAINER_NAME = "World of Warcraft"

    /** FEXCore manifest id, downloaded by preLaunchApp. */
    const val FEX = "2609-0"

    // Default Turnip driver, bundled in assets/wow.
    const val DRIVER_DEFAULT = "Turnip-a6xx-Banner-26.3.0-0b2fa04"
    const val DRIVER_DEFAULT_ASSET = "wow/$DRIVER_DEFAULT.zip"
    const val DRIVER_RP6 = "Turnip-V32-RP6sched"
    const val DRIVER_ALT = "Turnip-WoW-scheduler-test"

    // Patched ARM64 DXVK 2.4.1 (assets/wow): no premature chunk frees, SKIP_FILE support, device-lost recovery.
    const val DXVK_ASSET = "wow/dxvk-2.4.1-wow-aarch64-r8.wcp"

    // Skipped compute shaders: WoW GI + ocean FFT (Adreno 650 perf). Hashes change with WoW patches.
    const val SKIP_FILE = "C:\\dxvkskip.txt"
    val SKIP_SHADERS = listOf(
        "CS_f27caeb1dfb50263f0ca76f8585ee9ab7258f56a", // GI tracer (indirect)
        "CS_940a0c96886f320234330b4d18cb6fa4d43f51ca", // GI probe atlas update (2048x1024)
        "CS_e2e66a6bb3d4cc162df043f02106d2699a600389", // GI probe atlas update (2048x1024)
        "CS_fc56cc5160b1983e82ea43ce66ff61c000a846d7", // GI froxel volume
        "CS_4ee5bbdbc2f7d4ffe5047662bfbd8f0c7fcd6d1b", // GI screen resolve (full res)
        "CS_54b771f23f302a9f0b66cf4cfa99b5bd0b583d47", // GI screen resolve (half res)
        "CS_0f4c6931b4b9774b27e155018309a5351b0df03d", // ocean: spectrum h(k,t)
        "CS_cb2a0874574230e19684e0f2d4381cfc67072362", // ocean: gradient / Jacobian
        "CS_bce577330b9167fc229f3825113131c6c4d4b8a6", // ocean: pre-FFT setup
        "CS_5a02908ac17b564d31bcaf966930383af3cafeac", // ocean: 256-point FFT (row + column pass)
    )

    // Shader preambles off (GPU hangs); separate Mesa cache so old binaries aren't reused.
    const val IR3_SHADER_DEBUG = "nopreamble"
    const val MESA_SHADER_CACHE_DIR = "/data/data/com.wowforever/files/imagefs/home/xuser/.cache/mesa-wow-np"

    const val WOW_ROOT = "C:\\Program Files (x86)\\World of Warcraft"
    const val WOW_DIR = "$WOW_ROOT\\_classic_beta_"
    const val WOW_EXE = "$WOW_DIR\\WowB-ARM64.exe"

    /** Passed as -d3d11 / -d3d12 and mirrored into Config.wtf gxApi. */
    const val GFX_D3D11 = "d3d11"
    const val GFX_D3D12 = "d3d12"

    // Plain ARM64 (not ARM64EC) vkd3d-proton WCP.
    const val VKD3D_ARM64_WCP = "vkd3d-wow-aarch64.wcp"

    /** Container extra: VKD3D content entry applied over DXVK ("" = none). */
    const val EXTRA_VKD3D = "wowVkd3dProfile"
    const val BNET_DIR = "C:\\Program Files (x86)\\Battle.net"
    const val BNET_EXE = "$BNET_DIR\\Battle.net Launcher.exe"
    const val SETUP_EXE = "C:\\WowForever\\Battle.net-Setup.exe"
    const val BNET_BAT = "C:\\WowForever\\bnet.bat"
    const val BNET_URL =
        "https://www.battle.net/download/getInstallerForGame?os=win&gameProgram=BATTLENET_APP&version=Live"

    // --exec launch code: "WoWF" = WoW: Forever beta (installed as product wow_classic_beta). Not verified on device.
    const val BNET_EXEC_PRODUCT = "WoWF"
    const val BNET_PRODUCT_UID = "wow_classic_beta"

    /** Single-instance client: a second start hands its arguments to the running Battle.net over IPC. */
    const val BNET_IPC_EXE = "$BNET_DIR\\Battle.net.exe"

    // Caret watcher (assets/wow/textfocus.exe) writes '1'/'0' to TEXTFOCUS_FILE; same casing as C:\WowForever on the host.
    const val TEXTFOCUS_ASSET = "wow/textfocus.exe"
    const val TEXTFOCUS_EXE = "C:\\WowForever\\textfocus.exe"
    const val TEXTFOCUS_FILE = "C:\\WowForever\\textfocus"

    // WoW's crash reporter is replaced by a stub that exits at once (original kept as .orig).
    const val BLIZZARD_ERROR_EXE = "$WOW_DIR\\BlizzardError.exe"
    const val BLIZZARD_ERROR_STUB_ASSET = "wow/blizzarderror-stub.exe"

    /** Container extra: "1" ends the session when the WoW process exits (PLAY / PLAY_VIA_BNET). */
    const val EXTRA_EXIT_WITH_WOW = "wowExitWithGame"

    /** Container extra: launch target name; only a direct PLAY shows WoW's own password screen. */
    const val EXTRA_LAUNCH_TARGET = "wowLaunchTarget"
    const val WOW_PROCESS = "wowb-arm64"

    // In-game addon: magenta marker while an edit box has focus, cyan once the in-game UI is loaded.
    const val ADDON_NAME = "WowForeverInput"
    const val ADDON_ASSET_DIR = "wow/$ADDON_NAME"
    const val ADDON_DIR = "$WOW_DIR\\Interface\\AddOns\\$ADDON_NAME"

    /** Maps an absolute C:\ path to the host file inside the container prefix. */
    fun winToHost(c: Container, win: String): File =
        File(c.rootDir, ".wine/drive_c/" + win.removePrefix("C:\\").replace('\\', '/'))

    fun isContainerReady(ctx: Context): Boolean =
        PrefManager.wowSetupDone && ContainerUtils.hasContainer(ctx, APP_ID)

    fun isWowInstalled(c: Container) = winToHost(c, WOW_EXE).isFile
    fun isBnetInstalled(c: Container) = winToHost(c, BNET_EXE).isFile

    fun currentDriver(): String = PrefManager.wowDriver.ifEmpty { DRIVER_DEFAULT }

    fun currentGraphicsApi(): String = if (PrefManager.wowGraphicsApi == GFX_D3D12) GFX_D3D12 else GFX_D3D11

    fun wowArgs(): String = "-" + currentGraphicsApi()
}

/** Filled by MainActivity (shortcuts), consumed by WowHomeScreen. */
object WowLaunchBus {
    val pending = MutableStateFlow<WowTarget?>(null)

    /** Reset in MainActivity.onCreate; prevents auto-relaunch when returning from XServer. */
    @Volatile
    var autoLaunchDone = false
}
