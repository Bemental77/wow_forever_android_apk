package com.wowforever.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wowforever.data.LibraryPlayHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryPlayHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LibraryPlayHistory)

    @Query("SELECT * FROM library_play_history")
    fun getAll(): Flow<List<LibraryPlayHistory>>
}
