package com.wowforever.utils

import android.content.Context
import com.wowforever.service.SteamService
import com.wowforever.service.amazon.AmazonService
import com.wowforever.service.epic.EpicService
import com.wowforever.service.gog.GOGService

object PlatformAuthUtils {
    fun isSignedInToAnyPlatform(context: Context): Boolean =
        SteamService.isLoggedIn ||
        GOGService.hasStoredCredentials(context) ||
        EpicService.hasStoredCredentials(context) ||
        AmazonService.hasStoredCredentials(context)
}
