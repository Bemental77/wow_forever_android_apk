package com.wowforever.data

import com.wowforever.db.serializers.DateSerializer
import java.util.Date
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer

@Serializable
data class BranchInfo(
    val name: String,
    @Serializable(with = LongAsStringSerializer::class)
    val buildId: Long,
    val pwdRequired: Boolean,
    @Serializable(with = DateSerializer::class)
    val timeUpdated: Date,
)
