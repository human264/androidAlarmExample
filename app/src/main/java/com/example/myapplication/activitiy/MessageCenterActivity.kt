package com.example.myapplication.activitiy

import android.annotation.SuppressLint
import android.content.*
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.MsgAdapter
import com.example.myapplication.R
import com.example.myapplication.SppServerService
import com.example.myapplication.TreeAdapter
import com.example.myapplication.UiCategory
import com.example.myapplication.UiMessage
import com.example.myapplication.UiSubCategory
import com.example.myapplication.database.AppDatabase
import java.util.*
import kotlin.collections.plusAssign
import kotlin.math.max

class MessageCenterActivity : AppCompatActivity() {

    /* ───── 컬러 & 분할선 상태 ───── */
    private val COLOR_IDLE   = 0xFFB0B0B0.toInt()
    private val COLOR_ACTIVE = 0xFF2979FF.toInt()
    private var dragStartX       = 0f
    private var dragStartPercent = 0f
    private var splitterPercent  = 0.30f   // 0‒1 (기본값)

    /* ───── 데이터 모델 ───── */
    private val cats = mutableListOf(
        UiCategory("system", "System"),
        UiCategory("order", "Order")
    )
    private val subs = mutableListOf(
        UiSubCategory("sys-info", "system", "Info"),
        UiSubCategory("order-new", "order", "New"),
        UiSubCategory("order-cancel", "order", "Cancel")
    )
    private val msgStore = mutableListOf<UiMessage>()

    private var selectedCat: String? = null
    private var selectedSub: String? = null

    /* ───── 어댑터 ───── */
    private lateinit var treeAdapter: TreeAdapter
    private lateinit var msgAdapter : MsgAdapter

    /* ───── DB (Room) ───── */
    private val dao by lazy { AppDatabase.getInstance(this).messageDao() }

    /* ────────────────────────────────────────── */
    @SuppressLint("ClickableViewAccessibility")   // 분할선에 onTouch 설정
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_center)

        /* ─── 트리 RV ─── */
        treeAdapter = TreeAdapter(cats, subs) { catId, subId ->
            filterMessages(catId, subId)
        }
        findViewById<RecyclerView>(R.id.rvTree).apply {
            layoutManager = LinearLayoutManager(context)
            adapter       = treeAdapter
        }

        /* ─── 메시지 RV ─── */
        msgAdapter = MsgAdapter(
            data = emptyList(),
            dao = dao,
            ioScope = lifecycleScope,          // 외부에서 IO 디스패처로 전환
            onRead = {
                recalcUnread()
                treeAdapter.notifyDataSetChanged()
            }
        )
        findViewById<RecyclerView>(R.id.rvMsgs).apply {
            layoutManager = LinearLayoutManager(context)
            adapter       = msgAdapter
        }

        /* ─── 분할선 핸들 ─── */
        initSplitter()
    }

    /* ───── 분할선 초기화 ───── */
    private fun initSplitter() {
        val divider    = findViewById<View>(R.id.divider)
        val guide      = findViewById<Guideline>(R.id.guide)
        val rootLayout = findViewById<ConstraintLayout>(R.id.rootLayout)

        // XML 초기값 → 변수 보관
        splitterPercent =
            (guide.layoutParams as ConstraintLayout.LayoutParams).guidePercent

        divider.setBackgroundColor(COLOR_IDLE)
        divider.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    divider.setBackgroundColor(COLOR_ACTIVE)
                    dragStartX       = ev.rawX
                    dragStartPercent =
                        (guide.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = max(rootLayout.width.toFloat(), 1f)
                    val percent = (dragStartPercent + (ev.rawX - dragStartX) / w)
                        .coerceIn(0.15f, 0.85f)
                    (guide.layoutParams as ConstraintLayout.LayoutParams).apply {
                        guidePercent      = percent
                        guide.layoutParams = this
                    }
                    splitterPercent = percent
                    rootLayout.requestLayout()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    divider.setBackgroundColor(COLOR_IDLE)
                    true
                }
                else -> false
            }
        }

        /* 첫 진입 시 방향별 기본값 */
        val initP = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT) 0.40f else 0.30f
        (guide.layoutParams as ConstraintLayout.LayoutParams).apply {
            guidePercent      = initP
            guide.layoutParams = this
        }
        splitterPercent = initP
    }

    /* ───── 로컬 브로드캐스트 ───── */
    private val sppReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent) = addIncoming(i)
    }
    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sppReceiver, IntentFilter(SppServerService.Companion.ACTION_MSG))
    }
    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sppReceiver)
        super.onPause()
    }

    /* ───── 메시지 필터링 ───── */
    private fun filterMessages(catId: String?, subId: String?) {
        selectedCat = catId; selectedSub = subId
        msgAdapter.submit(
            msgStore.filter { m ->
                (catId == null || m.catId == catId) &&
                        (subId == null || m.subId == subId)
            }.sortedByDescending { it.ts }
        )
    }

    /* ───── SPP → UI ───── */
    private fun addIncoming(i: Intent) {
        val cat   = i.getStringExtra(SppServerService.Companion.EXTRA_CAT ) ?: "misc"
        val sub   = i.getStringExtra(SppServerService.Companion.EXTRA_SUB ) ?: ""
        val title = i.getStringExtra(SppServerService.Companion.EXTRA_TITLE) ?: "(no‑title)"
        val body  = i.getStringExtra(SppServerService.Companion.EXTRA_BODY ) ?: ""

        val iconMsg = i.getStringExtra("icon_msg")?.takeUnless { it.isBlank() }
        val iconSub = i.getStringExtra("icon_sub")?.takeUnless { it.isBlank() } ?: iconMsg
        val iconCat = i.getStringExtra("icon_cat")?.takeUnless { it.isBlank() } ?: iconMsg
        val bestIcon= iconMsg ?: iconSub ?: iconCat ?: ""

        val subId = if (sub.isBlank()) "" else "${cat}_${sub}"

        /* ― 카테고리 & 서브카테고리 동기화 ― */
        val catObj = cats.find { it.id == cat } ?: UiCategory(
            id = cat, name = cat.cap(), iconPath = iconCat ?: ""
        ).also { cats += it }
        if (iconCat != null) catObj.iconPath = iconCat

        if (sub.isNotBlank()) {
            val subObj = subs.find { it.id == subId } ?: UiSubCategory(
                id = subId, catId = cat, name = sub.cap(), iconPath = iconSub ?: ""
            ).also { subs += it }
            if (iconSub != null) subObj.iconPath = iconSub
        }

        /* ― 메시지 수집 ― */
        msgStore += UiMessage(
            id      = UUID.randomUUID().toString(),
            catId   = cat,
            subId   = subId,
            title   = title,
            body    = body,
            ts      = System.currentTimeMillis(),
            iconPath= bestIcon
        )

        /* ― UI 갱신 ― */
        recalcUnread()
        treeAdapter.notifyDataSetChanged()
        filterMessages(selectedCat, selectedSub)
        findViewById<RecyclerView>(R.id.rvMsgs).scrollToPosition(0)
    }

    /* ───── 미읽음 집계 ───── */
    private fun recalcUnread() {
        val subMap = msgStore.groupingBy { it.subId.ifBlank { it.catId } }
            .fold(0) { acc, m -> acc + if (!m.read) 1 else 0 }

        subs.forEach { s -> s.unreadCount = subMap[s.id] ?: 0 }

        cats.forEach { c ->
            val direct   = subMap[c.id] ?: 0
            val fromSubs = subs.filter { it.catId == c.id }.sumOf { it.unreadCount }
            c.unreadCount = direct + fromSubs
        }
    }

    /* ───── 방향 전환 시 분할비율 유지 ───── */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val guide = findViewById<Guideline>(R.id.guide)
        (guide.layoutParams as ConstraintLayout.LayoutParams).apply {
            guidePercent      = splitterPercent
            guide.layoutParams = this
        }
    }

    /* ― 문자열 보조 ― */
    private fun String.cap() =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
