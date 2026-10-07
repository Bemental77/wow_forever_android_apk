package app.gamenative.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconSwitcher {

    fun applyLauncherIcon(context: Context, useAltIcon: Boolean) {
        // WoW Forever: the alternate launcher alias was removed from the manifest; toggling a
        // non-existent component would throw, and disabling the default alias would hide the app.
        if (app.gamenative.BuildConfig.WOW_SINGLE_APP) return
        val packageManager = context.packageManager

        val defaultAlias = ComponentName(
            context,
            "app.gamenative.MainActivityAliasDefault",
        )
        val altAlias = ComponentName(
            context,
            "app.gamenative.MainActivityAliasAlt",
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


