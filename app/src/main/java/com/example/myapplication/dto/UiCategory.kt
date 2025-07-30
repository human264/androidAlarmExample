package com.example.myapplication.dto


/* ───────── 카테고리 ───────── */
data class UiCategory(
    val id: String,
    val name: String,
    var iconPath: String = "",   // 썸네일 PNG 절대경로
    var unreadCount: Int = 0     // 👈 안 읽은 메시지 수를 실시간 반영
)


data class UiSubCategory(
    val id: String,
    val catId: String,
    val name: String,
    var iconPath: String = "",
    var unreadCount: Int = 0       // 👈 추가
)

/* ───────── 메시지(알림) ───────── */
data class UiMessage(
    val id: String,
    val catId: String,
    val subId: String,
    val title: String,
    val body: String,
    val ts: Long,
    var iconPath: String? = null,
    var read: Boolean = false,        // 👈 읽음 여부(클릭 시 true 로 변경)
    var synced: Boolean = false        // 👈 읽음 여부(클릭 시 true 로 변경)
)