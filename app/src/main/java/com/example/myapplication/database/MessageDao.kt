package com.example.myapplication.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
@Dao
interface MessageDao {

    /* ───────── 삽입 / 기본 동기화 ───────── */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(msg: MessageEntity)

    /* ───────── 읽음 · 안읽음 토글 ───────── */
    @Query("UPDATE message SET read = 1, synced = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE message SET read = 1, synced = 0 WHERE id IN (:ids)")
    suspend fun markReadList(ids: List<String>)

    @Query("UPDATE message SET read = 0, synced = 0 WHERE id IN (:ids)")
    suspend fun markUnreadList(ids: List<String>)

    /* ───────── ‘읽음‑sync’ 큐 / 확인 ─────── */
    @Query("SELECT * FROM message WHERE read = 1 AND synced = 0")
    suspend fun pendingReadSync(): List<MessageEntity>

    @Query("UPDATE message SET synced = 1 WHERE id IN (:ids)")
    suspend fun confirmSynced(ids: List<String>)

    /** Full‑Sync 완료 시 모든 행을 ‘동기화됨’ 으로 표시 */
    @Query("UPDATE message SET synced = 1")
    suspend fun confirmSyncedAll()                    // ★ NEW

    /* ───────── 카테고리 정보 조회 ───────── */
    @Query("SELECT DISTINCT catId FROM message")
    suspend fun distinctCategories(): List<String>

    @Query("SELECT DISTINCT subId FROM message WHERE catId = :catId")
    suspend fun distinctSubCategories(catId: String): List<String>

    /* ───────── 유지보수 / 진단 ─────────── */
    @Query("SELECT * FROM message")
    suspend fun getAllMessages(): List<MessageEntity>

    /** RESET 패킷 수신 시 로컬 DB 를 비우기 */
    @Query("DELETE FROM message")
    suspend fun deleteAll()                           // ★ NEW (이미 호출 중)
}
