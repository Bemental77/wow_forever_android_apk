package com.wowforever.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wowforever.data.SteamFileHashCache

@Dao
interface SteamFileHashCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SteamFileHashCache)

    @Query("SELECT * FROM steam_file_hash_cache WHERE appId = :appId AND absPath = :absPath")
    suspend fun getByAppIdAndPath(appId: Int, absPath: String): SteamFileHashCache?

    @Query("DELETE FROM steam_file_hash_cache WHERE appId = :appId")
    suspend fun deleteByAppId(appId: Int)
}
