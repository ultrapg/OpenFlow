package com.voicebubble.openflow.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcriptions",
    indices = [Index(value = ["timestamp"])]
)
data class TranscriptionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "transcribed_text")
    val transcribedText: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "audio_samples_count")
    val audioSamplesCount: Int,
    @ColumnInfo(name = "model_identifier")
    val modelIdentifier: String,
    @ColumnInfo(name = "injected_via_accessibility")
    val wasInjectedSuccessfully: Boolean
)
