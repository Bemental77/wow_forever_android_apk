package com.wowforever.wow

import android.content.Context
import com.wowforever.MainActivity
import com.wowforever.PrefManager
import com.wowforever.ui.component.dialog.state.MessageDialogState
import com.wowforever.ui.enums.DialogType
import com.wowforever.ui.model.MainViewModel
import com.wowforever.ui.preLaunchApp
import com.wowforever.utils.ContainerUtils
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Points the WoW container at the target exe and launches it via preLaunchApp. [ctx] must be an Activity. */
class WowLauncher(
    private val ctx: Context,
    private val vm: MainViewModel,
    private val msg: (MessageDialogState) -> Unit,
) {
    fun launch(t: WowTarget, finishOnExit: Boolean = false) {
        if (t == WowTarget.MENU) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                doLaunch(t, finishOnExit)
            } catch (e: Exception) {
                Timber.tag("WowLauncher").e(e, "Launch $t failed")
                showError("Could not start", e.message ?: e.toString())
            }
        }
    }

    private suspend fun doLaunch(t: WowTarget, finishOnExit: Boolean) {
        val c = ContainerUtils.getContainer(ctx, Wow.APP_ID)
        // WRITECOPY emulation is only for Battle.net; WoW runs without it.
        val env = EnvVars(c.envVars).apply { remove("WINE_SIMULATE_WRITECOPY"); remove("WINEDLLOVERRIDES") }
        // D3D12 needs ARM64 vkd3d-proton; only direct PLAY is blocked without it.
        val vkd3d = if (Wow.currentGraphicsApi() != Wow.GFX_D3D12) {
            ""
        } else {
            try {
                WowSetup(ctx) { m, _ -> Timber.tag("WowLauncher").i(m) }.ensureVkd3dArm64()
            } catch (e: Exception) {
                if (t == WowTarget.PLAY) {
                    showError("D3D12 is not available", e.message ?: e.toString())
                    return
                }
                Timber.tag("WowLauncher").w(e, "No ARM64 vkd3d-proton")
                ""
            }
        }
        c.putExtra(Wow.EXTRA_VKD3D, vkd3d)
        // Migrations for older WoW containers.
        c.isSdlControllerAPI = true
        c.graphicsDriverConfig = KeyValueSet(c.graphicsDriverConfig).apply { put("bcnEmulation", WowSetup.BCN_EMULATION) }.toString()
        c.putExtra("sharpnessEffect", "None")
        // Keep DRI3 on; add WoW's DXVK options unless DXVK_CONFIG is already set.
        c.isUseDRI3 = true
        if (!env.has("DXVK_CONFIG")) env.put("DXVK_CONFIG", WowSetup.DXVK_CONFIG)
        // Bundled DXVK + default Turnip; RP6sched (old default) moves to the new default.
        val dxvkId = WowSetup(ctx) { m, _ -> Timber.tag("WowLauncher").i(m) }.ensureRenderStack()
        c.dxWrapperConfig = KeyValueSet(c.dxWrapperConfig).apply { put("version", dxvkId) }.toString()
        val driver = Wow.currentDriver().let { if (it == Wow.DRIVER_RP6) Wow.DRIVER_DEFAULT else it }
        PrefManager.wowDriver = driver
        c.graphicsDriverConfig = KeyValueSet(c.graphicsDriverConfig).apply {
            put("version", driver)
            put("presentMode", WowSetup.PRESENT_MODE)
            put("syncFrame", WowSetup.SYNC_FRAME)
        }.toString()
        env.put("MESA_VK_WSI_PRESENT_MODE", WowSetup.PRESENT_MODE)
        c.screenSize = WowSetup.SCREEN_SIZE
        writeSkipList(c)
        env.put("IR3_SHADER_DEBUG", Wow.IR3_SHADER_DEBUG)
        env.put("MESA_SHADER_CACHE_DIR", Wow.MESA_SHADER_CACHE_DIR)
        installTextFocusHelper(c)
        if (Wow.winToHost(c, Wow.WOW_DIR).isDirectory) installAddon(c)
        when (t) {
            WowTarget.PLAY -> {
                if (!Wow.isWowInstalled(c)) {
                    showError("World of Warcraft is not installed", "Open Battle.net and install WoW Classic (beta) to the default C: location first.")
                    return
                }
                WowConfigWtf.ensure(c)
                c.executablePath = Wow.WOW_EXE
                c.execArgs = Wow.wowArgs()
            }
            WowTarget.PLAY_VIA_BNET -> {
                if (!Wow.isBnetInstalled(c)) {
                    showError("Battle.net is not installed", "Install Battle.net from the menu first.")
                    return
                }
                // Battle.net may start WoW, so Config.wtf must be ready.
                WowConfigWtf.ensureIfDirExists(c)
                env.put("WINE_SIMULATE_WRITECOPY", "1")
                c.executablePath = writeBnetBat(c, "--exec=\"launch ${Wow.BNET_EXEC_PRODUCT}\"")
                c.execArgs = ""
            }
            WowTarget.BNET, WowTarget.BNET_SAFE -> {
                if (!Wow.isBnetInstalled(c)) {
                    showError("Battle.net is not installed", "Install Battle.net from the menu first.")
                    return
                }
                WowConfigWtf.ensureIfDirExists(c)
                env.put("WINE_SIMULATE_WRITECOPY", "1")
                // --in-process-gpu crashes Battle.net.
                c.executablePath = writeBnetBat(c, if (t == WowTarget.BNET_SAFE) "--disable-gpu" else "")
                c.execArgs = ""
            }
            WowTarget.SETUP_BNET -> {
                if (!Wow.winToHost(c, Wow.SETUP_EXE).isFile) {
                    showError("Battle.net installer missing", "Re-run setup to download it again.")
                    return
                }
                c.executablePath = Wow.SETUP_EXE
                c.execArgs = ""
            }
            WowTarget.MENU -> return
        }
        // Set fields directly; ContainerUtils.applyToContainer would flag needsUnpacking on exe change.
        c.envVars = env.toString()
        c.setNeedsUnpacking(false)
        c.saveData()
        Timber.tag("WowLauncher").i("Launching $t: ${c.executablePath} ${c.execArgs}")

        withContext(Dispatchers.Main) {
            MainActivity.wasLaunchedViaExternalIntent = finishOnExit
            vm.setLaunchedAppId(Wow.APP_ID)
            vm.setBootToContainer(false)
            vm.setOffline(false)
        }
        preLaunchApp(
            context = ctx,
            appId = Wow.APP_ID,
            setLoadingDialogVisible = vm::setLoadingDialogVisible,
            setLoadingProgress = vm::setLoadingDialogProgress,
            setLoadingMessage = vm::setLoadingDialogMessage,
            setMessageDialogState = msg,
            onSuccess = vm::launchApp,
            isOffline = false,
        )
    }

    /** Battle.net must start from cmd in its own folder, or it quits after opening the login window. */
    private fun writeBnetBat(c: com.winlator.container.Container, args: String): String {
        val bat = Wow.winToHost(c, Wow.BNET_BAT)
        bat.parentFile?.mkdirs()
        val lines = listOf(
            "@echo off",
            // Caret watcher for the Android auto keyboard; Wine ends it with the session.
            "start \"\" \"${Wow.TEXTFOCUS_EXE}\" ${Wow.TEXTFOCUS_FILE}",
            "cd /d \"${Wow.BNET_DIR}\"",
            "\"${Wow.BNET_EXE}\" $args".trimEnd(),
        )
        bat.writeText(lines.joinToString("\r\n") + "\r\n")
        return Wow.BNET_BAT
    }

    /** Copies textfocus.exe into C:\WowForever and resets its state file so a stale '1' can't open the keyboard. */
    private fun installTextFocusHelper(c: com.winlator.container.Container) {
        try {
            copyAssetIfChanged(Wow.TEXTFOCUS_ASSET, Wow.winToHost(c, Wow.TEXTFOCUS_EXE))
            Wow.winToHost(c, Wow.TEXTFOCUS_FILE).writeText("0")
        } catch (e: Exception) {
            Timber.tag("WowLauncher").w(e, "textfocus helper install failed")
        }
    }

    /** Installs/updates the WowForeverInput addon into the WoW AddOns folder. */
    private fun installAddon(c: com.winlator.container.Container) {
        try {
            val dir = Wow.winToHost(c, Wow.ADDON_DIR)
            ctx.assets.list(Wow.ADDON_ASSET_DIR)?.forEach { name ->
                copyAssetIfChanged("${Wow.ADDON_ASSET_DIR}/$name", java.io.File(dir, name))
            }
        } catch (e: Exception) {
            Timber.tag("WowLauncher").w(e, "addon install failed")
        }
    }

    private fun copyAssetIfChanged(asset: String, dst: java.io.File) {
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        if (dst.isFile && dst.length() == bytes.size.toLong() && dst.readBytes().contentEquals(bytes)) return
        dst.parentFile?.mkdirs()
        dst.writeBytes(bytes)
    }

    /** Writes Wow.SKIP_SHADERS to C:\dxvkskip.txt for the bundled DXVK. */
    private fun writeSkipList(c: com.winlator.container.Container) {
        val f = Wow.winToHost(c, Wow.SKIP_FILE)
        val text = Wow.SKIP_SHADERS.joinToString("\r\n", postfix = "\r\n")
        if (!f.isFile || f.readText() != text) f.writeText(text)
    }

    /** Flips between the two imported Turnip drivers and returns the new one. */
    fun toggleDriver(): String {
        val next = if (Wow.currentDriver() == Wow.DRIVER_DEFAULT) Wow.DRIVER_ALT else Wow.DRIVER_DEFAULT
        PrefManager.wowDriver = next
        try {
            val c = ContainerUtils.getContainer(ctx, Wow.APP_ID)
            val gdc = KeyValueSet(c.graphicsDriverConfig).apply { put("version", next) }
            c.graphicsDriverConfig = gdc.toString()
            c.saveData()
        } catch (e: Exception) {
            Timber.tag("WowLauncher").e(e, "Driver switch failed")
        }
        return next
    }

    /** Toggles D3D11 / D3D12 and returns the new one; applied on next launch. */
    fun toggleGraphicsApi(): String {
        val next = if (Wow.currentGraphicsApi() == Wow.GFX_D3D12) Wow.GFX_D3D11 else Wow.GFX_D3D12
        PrefManager.wowGraphicsApi = next
        return next
    }

    private suspend fun showError(title: String, message: String) = withContext(Dispatchers.Main) {
        msg(
            MessageDialogState(
                visible = true,
                type = DialogType.SYNC_FAIL,
                title = title,
                message = message,
                dismissBtnText = "OK",
            ),
        )
    }
}
