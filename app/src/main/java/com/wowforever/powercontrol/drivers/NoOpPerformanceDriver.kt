package com.wowforever.powercontrol.drivers

import com.wowforever.powercontrol.PowerProfile
import com.wowforever.powercontrol.profiles.CpuGovernor
import com.wowforever.powercontrol.profiles.PerformancePreset
import timber.log.Timber

class NoOpPerformanceDriver : PerformanceDriver() {

    companion object {
        private const val TAG = "NoOpPerformanceDriver"
    }

    init {
        Timber.tag(TAG).w("No performance driver available on this device")
    }

    override fun isDriverSupported(): Boolean = false

    override fun getDisplayUnit(): DisplayUnit = DisplayUnit.INTEGER
}
