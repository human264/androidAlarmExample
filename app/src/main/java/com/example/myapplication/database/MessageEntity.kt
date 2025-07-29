package com.example.myapplication.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message")
data class MessageEntity(
    @PrimaryKey val id: String,
    val catId: String,
    val subId: String,
    val title: String,
    val body: String,
    val ts: Long,
    val iconPath: String?,
    var read: Boolean = false,
    var synced: Boolean = false
)