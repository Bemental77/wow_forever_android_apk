package com.wowforever.powercontrol.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.wowforever.ui.util.SnackbarManager
import java.io.File
import timber.log.Timber

/**
 * Detects when Android has parked the fast CPU cores (Qualcomm core_ctl "Isolated"), which it does
 * at very low battery. The game then runs on the little cores only (60 fps drops to ~20), so the
 * player is told once per session instead of seeing an unexplained slowdown.
 */
internal object CoreParkingWatch {
    private const val TAG = "PowerMetrics"
    private const val CPU_DIR = "/sys/devices/system/cpu"

    private var warned = false
    private var strikes = 0

    fun reset() {
        warned = false
        strikes = 0
    }

    fun check(context: Context) {
        if (warned) return
        val parked = starvedClusters()
        // core_ctl also parks idle cores on its own; only a shortfall seen twice in a row counts.
        strikes = if (parked.isEmpty()) 0 else strikes + 1
        if (strikes < 2) return
        warned = true
        val battery = batteryPercent(context)
        Timber.tag(TAG).w("CPU clusters held below the cores they need: %s (battery %s%%)", parked, battery ?: "?")
        val reason = if (battery != null && battery <= 15) "the battery is at $battery%" else "the device is limiting power"
        SnackbarManager.show(
            "Android has turned off the fast CPU cores because $reason. " +
                "WoW will run slowly until the device is charged.",
        )
    }

    /**
     * First CPU of each core_ctl cluster whose "Active CPUs" is below its "Need CPUs", i.e. cores the
     * load asks for are being held off. Empty if none or the state isn't readable.
     */
    private fun starvedClusters(): List<Int> {
        val state = File(CPU_DIR).listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.firstNotNullOfOrNull { cpu -> runCatching { File(cpu, "core_ctl/global_state").readText() }.getOrNull() }
            ?: return emptyList()
        val starved = mutableListOf<Int>()
        var cpu = -1
        var active = -1
        for (line in state.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("CPU:") -> { cpu = t.substringAfter(':').trim().toIntOrNull() ?: -1; active = -1 }
                t.startsWith("Active CPUs:") -> active = t.substringAfter(':').trim().toIntOrNull() ?: -1
                t.startsWith("Need CPUs:") -> {
                    val need = t.substringAfter(':').trim().toIntOrNull() ?: -1
                    if (cpu >= 0 && active >= 0 && need > active) starved += cpu
                }
            }
        }
        return starved.distinct()
    }

    private fun batteryPercent(context: Context): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }
}
