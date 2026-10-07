package com.wowforever.data

import com.wowforever.db.serializers.OsEnumSetSerializer
import com.wowforever.enums.OS
import com.wowforever.enums.OSArch
import java.util.EnumSet
import kotlinx.serialization.Serializable

@Serializable
data class LaunchInfo(
    val executable: String,
    val workingDir: String,
    val description: String,
    val type: String,
    @Serializable(with = OsEnumSetSerializer::class)
    val configOS: EnumSet<OS>,
    val configArch: OSArch,
)
