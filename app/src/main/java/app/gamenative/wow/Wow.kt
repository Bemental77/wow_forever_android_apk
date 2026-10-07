package app.gamenative.wow

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** Launch targets reachable from the menu, the launcher icon and the static shortcuts. */
enum class WowTarget {
    /** WowB-ARM64.exe -d3d11 / -d3d12 directly (always asks for the password). */
    PLAY,

    /** Battle.net, which then launches WoW itself so the auth token is passed (experimental). */
    PLAY_VIA_BNET,

    /** Battle.net client for install / update / login. */
    BNET,

    /** Battle.net client with --disable-gpu (for when the Chromium UI does not repaint). */
    BNET_SAFE,

    /** The downloaded Battle.net-Setup.exe. */
    SETUP_BNET,

    /** Just show the WoW Forever menu. */
    MENU,
}

object Wow {
    const val ACTION = "app.gamenative.wow.ACTION"
    const val EXTRA_TARGET = "target"

    /** Fixed container id; the numeric suffix is parsed by ContainerUtils.extractGameIdFromContainerId. */
    const val APP_ID = "CUSTOM_GAME_2004"
    const val CONTAINER_NAME = "World of Warcraft"

    /** FEXCore manifest id (manifest.json "fexcore" entry "2609-0"); downloaded by preLaunchApp. */
    const val FEX = "2609-0"

    /**
     * Default driver, bundled in the APK (assets/wow). With giQuality 0 it was the Turnip build that stayed up
     * in the open world on the RP5 (Adreno 650); RP6sched hung on the same path. Turnip-WoW-scheduler-test
     * aborted on world load (tu_knl_kgsl.cc:781 wait_timestamp_safe "errno == ETIMEDOUT").
     */
    const val DRIVER_DEFAULT = "Turnip-a6xx-Banner-26.3.0-0b2fa04"
    const val DRIVER_DEFAULT_ASSET = "wow/$DRIVER_DEFAULT.zip"
    const val DRIVER_RP6 = "Turnip-V32-RP6sched"
    const val DRIVER_ALT = "Turnip-WoW-scheduler-test"

    /**
     * Patched ARM64 DXVK 2.4.1, bundled in the APK (assets/wow):
     *  - never vkFreeMemory's a memory chunk while the device lives: through the Wrapper, chunks were freed while
     *    the GPU still read them (kgsl "GPU PAGE FAULT ... premature free" -> GPU hang -> device lost);
     *  - skips the compute dispatches named in C:\dxvkskip.txt ([SKIP_FILE]) and zeroes their UAVs once, so
     *    readers see defined data instead of recycled memory;
     *  - after VK_ERROR_DEVICE_LOST nothing blocks any more, so WoW's own device recovery (GxRestart) can
     *    recreate the device instead of deadlocking.
     */
    const val DXVK_ASSET = "wow/dxvk-2.4.1-wow-aarch64-r6.wcp"

    /**
     * Compute shaders (DXVK names, WoW Classic beta build 70245) skipped on Adreno 650. Raven Hill went from
     * 8 fps (~150 ms GPU) to a locked 60 (~12 ms GPU):
     *  - global illumination that still runs at giQuality 0 (~45 ms);
     *  - the FFTTile ocean wave simulation (Tessendorf spectrum + 256-point FFTs on a 256x256x7 array, ~15 ms),
     *    which runs whenever an ocean liquid tile is loaded, with no CVar to turn it off; ocean surfaces are flat.
     * A WoW patch changes the hashes, which only turns the skips off. The file always exists so DXVK's built-in
     * list is never used.
     */
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

    /**
     * ir3 (Turnip's shader compiler) without shader preambles. Two kgsl hang snapshots showed the GPU stuck in a
     * plain direct draw with VS and FS EARLYPREAMBLE=1 (shader cores busy, no memory traffic, no wait packet)
     * every 20 s - 5 min. "noearlypreamble" alone still hung occasionally, so preambles are off entirely. The
     * Mesa shader cache moves to a new directory so no binaries built with preambles are reused.
     */
    const val IR3_SHADER_DEBUG = "nopreamble"
    const val MESA_SHADER_CACHE_DIR = "/data/data/app.gamenative/files/imagefs/home/xuser/.cache/mesa-wow-np"

    const val WOW_DIR = "C:\\Program Files (x86)\\World of Warcraft\\_classic_beta_"
    const val WOW_EXE = "$WOW_DIR\\WowB-ARM64.exe"

    /** Renderer, passed as -d3d11 / -d3d12 and mirrored into Config.wtf "gxApi" (D3D11 / D3D12). */
    const val GFX_D3D11 = "d3d11"
    const val GFX_D3D12 = "d3d12"

    /**
     * Plain Windows ARM64 (aarch64, not ARM64EC) vkd3d-proton WCP with system32/d3d12.dll + d3d12core.dll.
     * Every vkd3d build GameNative / the WCP Hub ships is x86_64 or ARM64EC, which WowB-ARM64.exe cannot load.
     * Looked up in wow-dl, then the app's external files dir (adb push), then the release.
     */
    const val VKD3D_ARM64_WCP = "vkd3d-wow-aarch64.wcp"

    /** Container extra naming the VKD3D content entry XServerScreen lays over DXVK's prefix ("" = none). */
    const val EXTRA_VKD3D = "wowVkd3dProfile"
    const val BNET_DIR = "C:\\Program Files (x86)\\Battle.net"
    const val BNET_EXE = "$BNET_DIR\\Battle.net Launcher.exe"
    const val SETUP_EXE = "C:\\WowForever\\Battle.net-Setup.exe"
    const val BNET_BAT = "C:\\WowForever\\bnet.bat"
    const val BNET_URL =
        "https://www.battle.net/download/getInstallerForGame?os=win&gameProgram=BATTLENET_APP&version=Live"

    /**
     * Battle.net "--exec" product code for PLAY_VIA_BNET.
     *
     * UNCERTAIN: Battle.net's documented-by-usage shortcut form is `Battle.net.exe --exec="launch <code>"`
     * with short codes such as "WoW" (retail) and "WoWC" (WoW Classic, per the Lutris installer). No public
     * code for the Classic *beta* is known. The installed build's .build.info lists Product "wow_classic_beta",
     * which is the TACT/agent product uid; we pass that (as requested) and hope Battle.net accepts uids here.
     * If Battle.net only opens without launching, try "WoWC" or the beta's short code on device and change this.
     * Also uncertain: whether "Battle.net Launcher.exe" forwards --exec to Battle.net.exe (it normally does
     * forward its command line); if not, switch BNET_EXEC_EXE to BNET_DIR + "\\Battle.net.exe".
     */
    const val BNET_EXEC_PRODUCT = "WOW_classic_beta"
    const val BNET_EXEC_EXE = BNET_EXE

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

    /** Per activity instance (reset in MainActivity.onCreate): don't auto-relaunch when popping back from XServer. */
    @Volatile
    var autoLaunchDone = false
}
