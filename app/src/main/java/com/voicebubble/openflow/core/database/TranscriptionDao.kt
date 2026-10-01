package com.voicebubble.openflow.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {
    @Insert
    suspend fun insert(record: TranscriptionRecord)

    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionRecord>>
    
    @Query("DELETE FROM transcriptions")
    suspend fun clearAll()
}
