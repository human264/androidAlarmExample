package com.example.myapplication.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(msg: MessageEntity)

    @Query("UPDATE message SET read = 1, synced = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("SELECT * FROM message WHERE read = 1 AND synced = 0")
    suspend fun pendingReadSync(): List<MessageEntity>

    @Query("UPDATE message SET synced = 1 WHERE id IN (:ids)")
    suspend fun confirmSynced(ids: List<String>)
}