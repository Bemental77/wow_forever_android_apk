package com.wowforever.enums

enum class SyncResult {
    Success,
    UpToDate,
    InProgress,
    PendingOperations,
    Conflict,
    UpdateFail,
    DownloadFail,
    UnknownFail,
}
