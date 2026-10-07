package app.gamenative.wow

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import app.gamenative.data.TouchGestureConfig
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * First-run setup. Every step is idempotent so re-running resumes / repairs.
 * Runs blocking; call from Dispatchers.IO. Everything lives in internal app storage.
 */
class WowSetup(
    private val ctx: Context,
    private val progress: (message: String, fraction: Float) -> Unit,
) {
    private data class Asset(val name: String, val sha256: String)

    private val releaseBase = "https://github.com/SteamFlowYT/wow-forever-gamenative/releases/download/v1.0.0/"
    private val proton = Asset("proton-11.0-90624-arm64ec.wcp", "137807f4145b1b440075f83b2b1b3501d19c4eb618784b1a28418652cae3db03")
    private val drivers = listOf(
        Asset("turnip-wow-scheduler-test.zip", "02384f692735515f73aad8a544bc640abe3f35fe18e8df2e667be712c442e54c") to Wow.DRIVER_ALT,
        Asset("Turnip-V32-RP6sched-A8xx.zip", "05434d3c102563a4577f514410cc713494bcf900f52c2b935391adf87a76b77a") to Wow.DRIVER_RP6,
    )

    private val dlDir = File(ctx.filesDir, "wow-dl").apply { mkdirs() }
    private val http by lazy {
        Net.http.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    fun run() {
        // 1. download + verify release assets
        val all = listOf(proton) + drivers.map { it.first }
        all.forEachIndexed { i, a ->
            val f = File(dlDir, a.name)
            val label = "(${i + 1}/${all.size}) ${a.name}"
            if (f.isFile && sha256(f) == a.sha256) return@forEachIndexed
            download(releaseBase + a.name, f) { p -> progress("Downloading $label", p) }
            progress("Verifying $label", -1f)
            val actual = sha256(f)
            if (actual != a.sha256) {
                f.delete()
                error("Checksum mismatch for ${a.name}\nexpected ${a.sha256}\ngot $actual")
            }
        }

        // 2. Wine (Proton) + DXVK into ContentsManager
        val cm = ContentsManager(ctx).apply { syncContents() }
        progress("Installing Proton (this can take a minute)", -1f)
        val wineId = importWcp(cm, File(dlDir, proton.name))
        progress("Installing DXVK and the default graphics driver", -1f)
        val dxvkId = ensureRenderStack()
        Timber.tag("WowSetup").i("wineId=$wineId dxvkId=$dxvkId")

        // 3. alternative Turnip drivers into AdrenotoolsManager
        progress("Installing graphics drivers", -1f)
        val atm = AdrenotoolsManager(ctx)
        drivers.forEach { (a, expectedId) ->
            if (atm.enumarateInstalledDrivers().contains(expectedId)) return@forEach
            val id = atm.installDriver(Uri.fromFile(File(dlDir, a.name)))
            if (id.isEmpty() && !atm.enumarateInstalledDrivers().contains(expectedId)) {
                error("Driver import failed: ${a.name}")
            }
            if (id.isNotEmpty() && id != expectedId) {
                Timber.tag("WowSetup").w("Driver ${a.name} installed as '$id', expected '$expectedId'")
            }
        }
        if (PrefManager.wowDriver.isEmpty()) PrefManager.wowDriver = Wow.DRIVER_DEFAULT

        // 4. container
        progress("Creating container", -1f)
        val c = createOrUpdateContainer(wineId, dxvkId)

        // 5. Battle.net installer from Blizzard (no fixed hash: validate host + MZ header + size)
        val setup = Wow.winToHost(c, Wow.SETUP_EXE)
        setup.parentFile?.mkdirs()
        if (!isValidSetup(setup)) {
            downloadBnet(setup)
        }

        PrefManager.useExternalStorage = false
        PrefManager.hideAiFeatures = true
        PrefManager.tipped = true
        PrefManager.wowSetupDone = true
        // PrefManager writes are async; wait briefly so the caller's re-check sees it.
        repeat(40) { if (PrefManager.wowSetupDone) return; Thread.sleep(50) }
        // 6. caller launches SETUP_BNET; preLaunchApp then downloads imagefs + FEXCore 2609.
    }

    // ── bundled DXVK + default driver ────────────────────────────────────

    /**
     * Installs the DXVK WCP ([Wow.DXVK_ASSET]) and the default Turnip ([Wow.DRIVER_DEFAULT]) shipped in the APK
     * and returns the DXVK container identifier. Idempotent; also run by WowLauncher so containers set up by
     * older versions move to them.
     */
    fun ensureRenderStack(): String {
        val cm = ContentsManager(ctx).apply { syncContents() }
        val dxvkId = importWcp(cm, bundled(Wow.DXVK_ASSET))
        val atm = AdrenotoolsManager(ctx)
        if (!atm.enumarateInstalledDrivers().contains(Wow.DRIVER_DEFAULT)) {
            val id = atm.installDriver(Uri.fromFile(bundled(Wow.DRIVER_DEFAULT_ASSET)))
            if (!atm.enumarateInstalledDrivers().contains(Wow.DRIVER_DEFAULT)) {
                error("Driver import failed: ${Wow.DRIVER_DEFAULT_ASSET} (installed as '$id')")
            }
        }
        return dxvkId
    }

    /** Copies an APK asset into wow-dl (again after each APK install/update) and returns the file. */
    private fun bundled(asset: String): File {
        val dst = File(dlDir, asset.substringAfterLast('/'))
        val stamp = File(dlDir, dst.name + ".apk")
        val apkTime = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (!dst.isFile || !stamp.isFile || stamp.readText() != apkTime) {
            val tmp = File(dlDir, dst.name + ".tmp")
            ctx.assets.open(asset).use { input -> FileOutputStream(tmp).use { input.copyTo(it, 256 * 1024) } }
            dst.delete()
            if (!tmp.renameTo(dst)) error("Could not move ${tmp.name}")
            File(dlDir, dst.name + ".id").delete() // importWcp's cached id may name an older package
            stamp.writeText(apkTime)
        }
        return dst
    }

    // ── D3D12 ────────────────────────────────────────────────────────────

    /**
     * Installs the ARM64 vkd3d-proton WCP ([Wow.VKD3D_ARM64_WCP]) as VKD3D content and returns its content
     * entry name (for [Wow.EXTRA_VKD3D]). Throws with a user-facing message if it is unavailable or not ARM64.
     * No fixed checksum (no published build yet); the PE machine check below keeps x64 / ARM64EC builds out.
     */
    fun ensureVkd3dArm64(): String {
        val name = Wow.VKD3D_ARM64_WCP
        val local = File(dlDir, name)
        if (!local.isFile) {
            val sideloaded = ctx.getExternalFilesDir(null)?.let { File(it, name) }
            if (sideloaded?.isFile == true) {
                sideloaded.copyTo(local, overwrite = true)
            } else {
                try {
                    download(releaseBase + name, local) { p -> progress("Downloading $name", p) }
                } catch (e: Exception) {
                    Timber.tag("WowSetup").w(e, "No $name")
                    error(
                        "D3D12 needs an ARM64 (aarch64) vkd3d-proton build, which was not found.\n" +
                            "Put $name (WCP with system32/d3d12.dll + d3d12core.dll) into " +
                            "${ctx.getExternalFilesDir(null)?.absolutePath ?: "the app's external files dir"} " +
                            "or switch back to D3D11.",
                    )
                }
            }
        }
        val cm = ContentsManager(ctx).apply { syncContents() }
        val id = importWcp(cm, local)
        val p = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)
            ?.firstOrNull { it.remoteUrl == null && "${it.verName}-${it.verCode}" == id }
            ?: error("$name is not a VKD3D package")
        val dll = File(ContentsManager.getInstallDir(ctx, p), "system32/d3d12.dll")
        if (peMachine(dll) != 0xAA64) {
            error("$name: system32/d3d12.dll is not a plain ARM64 DLL (x64 and ARM64EC builds cannot load in WoW).")
        }
        return ContentsManager.getEntryName(p)
    }

    /** IMAGE_FILE_HEADER.Machine, or -1. ARM64EC images report AMD64 (0x8664) here, only plain ARM64 is 0xAA64. */
    private fun peMachine(f: File): Int = try {
        java.io.RandomAccessFile(f, "r").use { r ->
            r.seek(0x3C)
            val pe = Integer.reverseBytes(r.readInt()).toLong()
            r.seek(pe + 4)
            r.read() or (r.read() shl 8)
        }
    } catch (e: Exception) {
        -1
    }

    // ── content import ───────────────────────────────────────────────────

    /** Returns the container identifier "<verName>-<verCode>" (e.g. proton-11.0-90624-arm64ec-1). */
    private fun importWcp(cm: ContentsManager, f: File): String {
        val idFile = File(dlDir, f.name + ".id")
        if (idFile.isFile) {
            val id = idFile.readText().trim()
            // still installed?
            val installed = ContentProfile.ContentType.values().any { t ->
                cm.getProfiles(t)?.any { p -> p.remoteUrl == null && "${p.verName}-${p.verCode}" == id } == true
            }
            if (installed) return id
        }

        var extracted: ContentProfile? = null
        var err: String? = null
        cm.extraContentFile(
            Uri.fromFile(f),
            object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    err = "$reason ${e?.message.orEmpty()}"
                }
                override fun onSucceed(profile: ContentProfile) {
                    extracted = profile
                }
            },
        )
        val p = extracted ?: error("Could not read ${f.name}: $err")

        var finishErr: ContentsManager.InstallFailedReason? = null
        cm.finishInstallContent(
            p,
            object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    finishErr = reason
                }
                override fun onSucceed(profile: ContentProfile) {}
            },
        )
        when (finishErr) {
            null -> Unit
            ContentsManager.InstallFailedReason.ERROR_EXIST -> {
                Timber.tag("WowSetup").i("${p.verName} already installed")
                ContentsManager.cleanTmpDir(ctx)
            }
            else -> error("Install of ${f.name} failed: $finishErr")
        }
        cm.syncContents()
        val id = "${p.verName}-${p.verCode}"
        idFile.writeText(id)
        return id
    }

    // ── container ────────────────────────────────────────────────────────

    private fun createOrUpdateContainer(wineId: String, dxvkId: String): Container {
        val mgr = ContainerManager(ctx)
        val existing = mgr.getContainerById(Wow.APP_ID)
        val c = existing ?: run {
            // Create exactly like stock GameNative: from container_pattern_gamenative (no wineVersion
            // here), with Proton overlaid on first launch. Creating straight from the Proton prefix
            // skipped the pattern's ".update-timestamp=disable", so Wine re-ran wineboot --update and
            // Battle.net's embedded Chromium then crashed (empty grey login window).
            val data = JSONObject().put("name", Wow.CONTAINER_NAME)
            mgr.createContainerFuture(Wow.APP_ID, data).get()
                ?: error("Container creation failed")
        }
        applyWowConfig(ctx, c, wineId, dxvkId, initialExe = if (existing == null) Wow.SETUP_EXE else null)
        return c
    }

    // ── downloads ────────────────────────────────────────────────────────

    private fun download(url: String, dst: File, onProgress: (Float) -> Unit) {
        val part = File(dst.parentFile, dst.name + ".part")
        val have = if (part.isFile) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            val append = resp.code == 206 && have > 0
            val body = resp.body
            val total = body.contentLength().let { if (it > 0) it + (if (append) have else 0L) else -1L }
            var done = if (append) have else 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        }
        dst.delete()
        if (!part.renameTo(dst)) error("Could not move ${part.name}")
    }

    private fun downloadBnet(dst: File) {
        progress("Downloading Battle.net installer", -1f)
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        val req = Request.Builder().url(Wow.BNET_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Battle.net installer: HTTP ${resp.code}")
            val host = resp.request.url.host.lowercase()
            if (!(host == "battle.net" || host.endsWith(".battle.net") || host.endsWith(".blizzard.com"))) {
                error("Battle.net installer redirected to unexpected host: $host")
            }
            val body = resp.body
            body.byteStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it, 256 * 1024) } }
        }
        if (!isValidSetup(tmp)) {
            val size = tmp.length()
            tmp.delete()
            error("Battle.net installer looks invalid (size $size bytes)")
        }
        dst.delete()
        if (!tmp.renameTo(dst)) error("Could not move Battle.net installer")
    }

    private fun isValidSetup(f: File): Boolean {
        if (!f.isFile) return false
        val len = f.length()
        if (len < 1_000_000L || len > 100_000_000L) return false
        val head = ByteArray(2)
        f.inputStream().use { if (it.read(head) != 2) return false }
        return head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()
    }

    private fun sha256(f: File): String {
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

    companion object {
        /**
         * Wrapper BCn emulation. "auto" sets WRAPPER_EMULATE_BCN=3, a pointless per-texture transcode on Adreno,
         * which samples BCn natively. Also migrated onto existing containers by WowLauncher.
         */
        const val BCN_EMULATION = "none"

        /**
         * Swapchain present mode (Wrapper WSI env + graphicsDriverConfig). FIFO (vsync): at a locked 60 fps every
         * frame shows for exactly one refresh; mailbox let uneven frames through, which read as shaking while moving.
         * Also migrated onto existing containers by WowLauncher.
         */
        const val PRESENT_MODE = "fifo"

        /**
         * Wrapper "syncFrame=1" -> MESA_VK_WSI_DEBUG=forcesync: the WSI waits for the frame's rendering before
         * PresentPixmap. The X server skips the present's wait fence and samples the AHardwareBuffer zero-copy,
         * and kgsl has no implicit sync, so without this the compositor sometimes showed a swapchain image
         * before WoW's GPU had rewritten it: the frame from 2-3 presents earlier, seen as the view snapping
         * back while moving. Also migrated onto existing containers by WowLauncher.
         */
        const val SYNC_FRAME = "1"

        /** Wine desktop / WoW window size. 960x540 (left on early containers) made the UI and text blurry on the
         * 1080p screen; the 3D cost is kept down by renderScale 0.75 (WowConfigWtf). Also migrated by WowLauncher. */
        const val SCREEN_SIZE = "1280x720"

        /**
         * DXVK options for WoW, passed as DXVK_CONFIG (';'-separated, parsed by DXVK after any dxvk.conf).
         * The container env is merged after DXVKHelper's own DXVK_CONFIG in XServerScreen.setupXEnvironment,
         * so this value wins. DXVK_CONFIG_FILE is a Unix path that DXVK opens through Wine's Windows file
         * APIs, so a dxvk.conf file there is not a reliable channel; the env var is.
         *
         * dxgi.maxFrameLatency = 2: WoW in-world is GPU-bound (~10 fps on Adreno 650), and the DXGI default
         * of 3 queued frames adds ~300 ms of input lag at that rate. Two frames keep one queued so the GPU
         * never idles (the CPU side reaches 60 fps on the login screen), cutting ~100 ms of lag for free.
         * Also migrated onto existing containers by WowLauncher.
         */
        const val DXVK_CONFIG = "dxgi.maxFrameLatency = 2"

        /**
         * Turnip debug flags. "sysmem" (bypass GMEM tiling) was copied from the reference launcher's
         * Adreno 8xx profile; its Adreno 6xx/7xx profile, and the RP5 container the current numbers were
         * measured on, use plain "noconform", so new containers match that. sysmem is an A/B candidate
         * (WoW's cost barely drops with render scale, i.e. it is not fill-bound, which is where sysmem
         * can win by skipping the binning pass), not a default.
         */
        const val TU_DEBUG = "noconform"

        /**
         * Writes the WoW container configuration. Uses ContainerUtils.applyToContainer once (it also
         * writes the Wine registry), then clears the side effects we don't want (needsUnpacking,
         * config_changed). Later exe switches go straight to Container fields (see WowLauncher).
         */
        fun applyWowConfig(ctx: Context, c: Container, wineId: String, dxvkId: String, initialExe: String?) {
            val env = EnvVars(Container.DEFAULT_ENV_VARS).apply {
                put("WINEESYNC", "0")
                put("TU_DEBUG", TU_DEBUG)
                put("MESA_VK_WSI_PRESENT_MODE", PRESENT_MODE)
                put("DXVK_CONFIG", DXVK_CONFIG)
                put("IR3_SHADER_DEBUG", Wow.IR3_SHADER_DEBUG)
                put("MESA_SHADER_CACHE_DIR", Wow.MESA_SHADER_CACHE_DIR)
            }
            val gdc = KeyValueSet(Container.DEFAULT_GRAPHICSDRIVERCONFIG).apply {
                put("version", Wow.currentDriver())
                put("adrenotoolsTurnip", "1")
                put("bcnEmulation", BCN_EMULATION)
                put("presentMode", PRESENT_MODE)
                put("syncFrame", SYNC_FRAME)
            }
            val dxc = KeyValueSet(Container.DEFAULT_DXWRAPPERCONFIG).apply { put("version", dxvkId) }
            // D: would otherwise be re-added by WineUtils pointing at shared Downloads; keep it internal.
            val internalD = File(ctx.filesDir, "wow-d").apply { mkdirs() }.absolutePath
            val internalE = File(ctx.dataDir, "storage").apply { mkdirs() }.absolutePath

            val data = ContainerUtils.toContainerData(c).copy(
                name = Wow.CONTAINER_NAME,
                containerVariant = Container.BIONIC,
                wineVersion = wineId,
                emulator = "FEXCore",
                fexcoreVersion = Wow.FEX,
                graphicsDriver = "Wrapper",
                graphicsDriverConfig = gdc.toString(),
                displayRenderer = "vulkan",
                // DRI3 on: the Wrapper's X11 WSI hands its AHardwareBuffer-backed swapchain images to the X
                // server (DRI3 PixmapFromBuffers, modifier 1255) and the renderer samples them zero-copy. Off,
                // XServerScreen sets MESA_VK_WSI_DEBUG=sw: a CPU readback + SHM upload of every frame.
                useDRI3 = true,
                dxwrapper = "dxvk",
                dxwrapperConfig = dxc.toString(),
                screenSize = SCREEN_SIZE,
                envVars = env.toString(),
                drives = "D:${internalD}E:$internalE",
                executablePath = initialExe ?: c.executablePath,
                execArgs = if (initialExe != null) "" else c.execArgs,
                // Gamepad: the Retroid's pad reaches Wine as evshim's virtual SDL "Xbox 360 Controller", which
                // winebus exposes to Wine's builtin xinput/dinput. A bare Container defaults sdlControllerAPI to
                // false; stock GameNative containers get true, so match them.
                sdlControllerAPI = true,
                enableXInput = true,
                enableDInput = true,
                useSteamInput = false,
                sharpnessEffect = "None", // no vkBasalt layer
                touchscreenMode = true,
                gestureConfig = WowInput.gestureJson(),
                launchRealSteam = false,
                launchBionicSteam = false,
                localSavesOnly = true,
                unpackFiles = false,
                suspendPolicy = Container.SUSPEND_POLICY_MANUAL,
            )
            ContainerUtils.applyToContainer(ctx, c, data)
            c.setNeedsUnpacking(false)
            c.putExtra("config_changed", "false")
            c.saveData()
        }
    }
}

/** Direct-touch gestures tuned for WoW (touchscreenMode = true: tap where you touch). */
object WowInput {
    fun gestureJson(): String = TouchGestureConfig(
        tapAction = TouchGestureConfig.ACTION_LEFT_CLICK,
        dragAction = TouchGestureConfig.PAN_LEFT_CLICK_DRAG, // drag UI / Battle.net scroll
        longPressEnabled = true,
        longPressAction = TouchGestureConfig.ACTION_RIGHT_CLICK, // loot / interact
        twoFingerDragAction = TouchGestureConfig.PAN_RIGHT_CLICK_DRAG, // camera / turn
        pinchAction = TouchGestureConfig.ZOOM_SCROLL_WHEEL, // camera zoom / list scroll
        twoFingerTapAction = TouchGestureConfig.ACTION_RIGHT_CLICK,
        threeFingerTapAction = TouchGestureConfig.ACTION_SHOW_KEYBOARD, // on-screen keyboard
        threeFingerHoldAction = TouchGestureConfig.ACTION_KEY_ESC, // game menu
        showCursorInTouchscreenMode = false,
    ).toJson()
}
