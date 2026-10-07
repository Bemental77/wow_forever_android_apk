package com.wowforever.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconSwitcher {

    fun applyLauncherIcon(context: Context, useAltIcon: Boolean) {
        // WoW Forever: alternate launcher alias was removed from the manifest.
        if (com.wowforever.BuildConfig.WOW_SINGLE_APP) return
        val packageManager = context.packageManager

        val defaultAlias = ComponentName(
            context,
            "com.wowforever.MainActivityAliasDefault",
        )
        val altAlias = ComponentName(
            context,
            "com.wowforever.MainActivityAliasAlt",
        )

        val defaultState = if (useAltIcon)
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        val altState = if (useAltIcon)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        packageManager.setComponentEnabledSetting(
            defaultAlias,
            defaultState,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            altAlias,
            altState,
            PackageManager.DONT_KILL_APP,
        )
    }
}


