package com.wowforever.wow

import com.winlator.container.Container
import org.json.JSONObject
import timber.log.Timber

/** Battle.net helpers: config patch, login check and the cmd wrapper. */
object WowBnet {
    private const val CONFIG = "C:\\users\\xuser\\AppData\\Roaming\\Battle.net\\Battle.net.config"
    private const val LOG_DIR = "C:\\users\\xuser\\AppData\\Local\\Battle.net\\Logs"
    private const val LOGIN_MARKER = "Logged into Battle.net successfully"

    /** Software CEF rendering: with GPU rendering, Battle.net pages/dialogs repaint partially or not at all here. */
    const val CEF_ARGS = "--disable-gpu --disable-gpu-compositing"

    /** Hardware acceleration ON (off stops CEF repainting here); undoes the old Streaming override. */
    fun patchConfig(c: Container) {
        try {
            val f = Wow.winToHost(c, CONFIG)
            val root = f.takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText().removePrefix("\uFEFF")) }.getOrNull() }
                ?: JSONObject()
            val client = root.optJSONObject("Client") ?: JSONObject().also { root.put("Client", it) }
            if (client.optString("HardwareAcceleration") == "true" && !client.has("Streaming")) return
            client.put("HardwareAcceleration", "true")
            client.remove("Streaming")
            f.parentFile?.mkdirs()
            f.writeText(root.toString(4))
            Timber.tag("WowBnet").i("Battle.net.config patched")
        } catch (e: Exception) {
            Timber.tag("WowBnet").w(e, "Battle.net.config patch failed")
        }
    }

    /** True if one of the newest Battle.net logs shows a successful login (token is remembered). */
    fun isLoggedIn(c: Container): Boolean = try {
        Wow.winToHost(c, LOG_DIR).listFiles { f -> f.isFile && f.name.startsWith("battle.net", true) && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.any { f -> f.useLines { lines -> lines.any { it.contains(LOGIN_MARKER) } } }
            ?: false
    } catch (e: Exception) {
        Timber.tag("WowBnet").w(e, "login check failed")
        false
    }

    /**
     * Writes C:\WowForever\bnet.bat. Battle.net must start from cmd in its own folder, or it quits after the login window.
     * [launchWow]: --exec launch; a cold start may only log in, so the request is resent via IPC until WoW runs.
     */
    fun writeBat(c: Container, args: String, launchWow: Boolean = false): String {
        val exec = "--exec=\"launch ${Wow.BNET_EXEC_PRODUCT}\""
        val lines = mutableListOf(
            "@echo off",
            // Caret watcher for the Android auto keyboard; Wine ends it with the session.
            "start \"\" \"${Wow.TEXTFOCUS_EXE}\" ${Wow.TEXTFOCUS_FILE}",
            "cd /d \"${Wow.BNET_DIR}\"",
        )
        if (!launchWow) {
            lines += "\"${Wow.BNET_EXE}\" $args".trimEnd()
        } else {
            lines += listOf(
                "start \"\" \"${Wow.BNET_EXE}\" $exec $args".trimEnd(),
                "set n=0",
                ":wait",
                // timeout + ping: either one alone may return at once under Wine.
                "timeout /t 4 /nobreak >nul 2>&1",
                "ping -n 3 127.0.0.1 >nul 2>&1",
                "tasklist | find /i \"${Wow.WOW_PROCESS}\" >nul && goto close",
                "set /a n+=1",
                "if %n% GEQ 100 exit /b 0",
                // Stop once Battle.net is closed (after a grace period for its first start).
                "if %n% LSS 10 goto send",
                "tasklist | find /i \"battle.net.exe\" >nul || exit /b 0",
                ":send",
                // Fallback: open Battle.net on the beta's tab so one tap on Play works.
                "if %n%==15 start \"\" \"${Wow.BNET_IPC_EXE}\" --game=${Wow.BNET_PRODUCT_UID} " +
                    "--gamepath=\"${Wow.WOW_ROOT}\" --productcode=${Wow.BNET_PRODUCT_UID}",
                // Re-send the launch every ~4 tries; more often makes Battle.net queue duplicates.
                "set /a m=n %% 4",
                "if %m%==0 start \"\" \"${Wow.BNET_IPC_EXE}\" $exec",
                "goto wait",
                // WoW is up: once it has authenticated, close Battle.net (its CEF processes cost
                // ~1 GB and Android's low-memory killer then ends the whole app).
                ":close",
                "timeout /t 20 /nobreak >nul 2>&1",
                "ping -n 21 127.0.0.1 >nul 2>&1",
                "taskkill /f /im \"Battle.net.exe\" >nul 2>&1",
                "exit /b 0",
            )
        }
        val bat = Wow.winToHost(c, Wow.BNET_BAT)
        bat.parentFile?.mkdirs()
        bat.writeText(lines.joinToString("\r\n") + "\r\n")
        return Wow.BNET_BAT
    }

    /**
     * Runs the Battle.net installer, then restarts the Battle.net it opens with [CEF_ARGS]: started by
     * the installer it lacks them and its login page stays blank.
     */
    fun writeSetupBat(c: Container): String {
        val lines = listOf(
            "@echo off",
            "start \"\" \"${Wow.TEXTFOCUS_EXE}\" ${Wow.TEXTFOCUS_FILE}",
            "\"${Wow.SETUP_EXE}\"",
            "set n=0",
            ":wait",
            "tasklist | find /i \"battle.net.exe\" >nul && goto restart",
            "set /a n+=1",
            "if %n% GEQ 60 exit /b 0",
            "timeout /t 2 /nobreak >nul 2>&1",
            "ping -n 2 127.0.0.1 >nul 2>&1",
            "goto wait",
            ":restart",
            // Let the first instance finish its post-install work before replacing it.
            "timeout /t 8 /nobreak >nul 2>&1",
            "ping -n 9 127.0.0.1 >nul 2>&1",
            "taskkill /f /im \"Battle.net.exe\" >nul 2>&1",
            "timeout /t 3 /nobreak >nul 2>&1",
            "cd /d \"${Wow.BNET_DIR}\"",
            "\"${Wow.BNET_EXE}\" $CEF_ARGS",
        )
        val bat = Wow.winToHost(c, Wow.BNET_SETUP_BAT)
        bat.parentFile?.mkdirs()
        bat.writeText(lines.joinToString("\r\n") + "\r\n")
        return Wow.BNET_SETUP_BAT
    }
}
