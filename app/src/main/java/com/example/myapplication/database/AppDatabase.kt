package com.example.myapplication.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase


@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    /* DAO 노출 */
    abstract fun messageDao(): MessageDao

    companion object {
        /* 싱글톤 인스턴스 */
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * 전역에서 안전하게 호출하는 헬퍼
         * ex) `val dao = AppDatabase.getInstance(ctx).messageDao()`
         */
        fun getInstance(ctx: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,              // Leak 방지
                    AppDatabase::class.java,
                    "msg.db"
                )
                    .fallbackToDestructiveMigration()    // 필요 시
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
