package com.example.myapplication.activitiy

import android.annotation.SuppressLint
import android.content.*
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.adapter.MsgAdapter
import com.example.myapplication.adapter.TreeAdapter
import com.example.myapplication.database.AppDatabase
import com.example.myapplication.dto.UiCategory
import com.example.myapplication.dto.UiMessage
import com.example.myapplication.dto.UiSubCategory
import com.example.myapplication.service.SppServerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import kotlin.collections.mutableListOf
import kotlin.math.max

class MessageCenterActivity : AppCompatActivity() {

    private val COLOR_IDLE = 0xFFB0B0B0.toInt()
    private val COLOR_ACTIVE = 0xFF2979FF.toInt()
    private var dragStartX = 0f
    private var dragStartPercent = 0f
    private var splitterPercent = 0.30f

    private val cats = mutableListOf<UiCategory>()
    private val subs = mutableListOf<UiSubCategory>()
    private val msgStore = mutableListOf<UiMessage>()

    private var selectedCat: String? = null
    private var selectedSub: String? = null

    private lateinit var treeAdapter: TreeAdapter
    private lateinit var msgAdapter: MsgAdapter
    private lateinit var sppBinder: SppServerService.LocalBinder
    private var bound = false
    private val dao by lazy { AppDatabase.getInstance(this).messageDao() }
    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(c: ComponentName, b: IBinder) {
            sppBinder = b as SppServerService.LocalBinder
            bound = true
            Log.i("MsgCenter", "SPP Service bound")
        }

        override fun onServiceDisconnected(c: ComponentName) {
            bound = false
        }
    }

    override fun onStart() {
        super.onStart()
        /* 서비스가 이미 돌고 있으니 bind 만 */
        Intent(this, SppServerService::class.java).also {
            bindService(it, svcConn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (bound) unbindService(svcConn)
        bound = false
        super.onStop()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_center)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        treeAdapter = TreeAdapter(cats, subs) { catId, subId ->
            filterMessages(catId, subId)
        }

        findViewById<RecyclerView>(R.id.rvTree).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = treeAdapter
        }
        msgAdapter = MsgAdapter(
            data = emptyList(),
            onRead = {                         // UI만 갱신
                recalcUnread()
                treeAdapter.notifyDataSetChanged()
            },
            dao = dao,
            ioScope = lifecycleScope,
            /* ⑤  읽음‑동기화 람다 (Service 통해) */
            syncRead = {
                if (bound) {
                    sppBinder.syncReadStatus()
                } else {
                    Log.w("MsgCenter", "Service not bound – skip sync")
                }
            })

        findViewById<RecyclerView>(R.id.rvMsgs).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = msgAdapter
        }

        initSplitter()
        loadCategoryDataFromDb()
    }

    private fun loadCategoryDataFromDb() {
        lifecycleScope.launch {
            val catIds = dao.distinctCategories()
            val subMap = mutableMapOf<String, List<String>>()

            catIds.forEach { catId ->
                subMap[catId] = dao.distinctSubCategories(catId)
            }

            // 메시지 먼저 로드
            val allMsgs = dao.getAllMessages()

            // 아이콘 경로 매핑 생성
            val catIconMap = allMsgs
                .filter { !it.iconPath.isNullOrBlank() && File(it.iconPath).exists() }
                .groupBy { it.catId }
                .mapValues { (_, msgs) -> msgs.last().iconPath ?: "" }

            val subIconMap = allMsgs
                .filter { it.subId.isNotBlank() && !it.iconPath.isNullOrBlank() && File(it.iconPath).exists() }
                .groupBy { it.subId }
                .mapValues { (_, msgs) -> msgs.last().iconPath ?: "" }

            // 트리 구성
            val loadedCats = catIds.map { catId ->
                UiCategory(
                    id = catId,
                    name = catId.cap(),
                    iconPath = catIconMap[catId] ?: ""
                )
            }

            val loadedSubs = subMap.flatMap { (catId, subIds) ->
                subIds.mapNotNull { subId ->
                    if (subId.isBlank()) return@mapNotNull null
                    val rawSub = subId.removePrefix("${catId}_")
                    UiSubCategory(
                        id = subId,
                        catId = catId,
                        name = rawSub.cap(),
                        iconPath = subIconMap[subId] ?: ""
                    )
                }
            }

            cats.clear(); cats += loadedCats
            subs.clear(); subs += loadedSubs

            // 메시지 저장소 초기화
            msgStore.clear(); msgStore += allMsgs.map { m ->
            val path = m.iconPath?.takeIf { p -> p.isNotBlank() && File(p).exists() } ?: ""
            UiMessage(
                id       = m.id,
                catId    = m.catId,
                subId    = m.subId,
                title    = m.title,
                body     = m.body,
                ts       = m.ts,
                iconPath = path,
                read     = m.read,
                synced   = m.synced      // ★ 추가
            )
        }

            recalcUnread()
            treeAdapter.notifyDataSetChanged()
            filterMessages(selectedCat, selectedSub)
        }
    }


    private fun initSplitter() {
        val divider = findViewById<View>(R.id.divider)
        val guide = findViewById<Guideline>(R.id.guide)
        val rootLayout = findViewById<ConstraintLayout>(R.id.rootLayout)

        splitterPercent =
            (guide.layoutParams as ConstraintLayout.LayoutParams).guidePercent

        divider.setBackgroundColor(COLOR_IDLE)
        divider.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    divider.setBackgroundColor(COLOR_ACTIVE)
                    dragStartX = ev.rawX
                    dragStartPercent =
                        (guide.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val w = max(rootLayout.width.toFloat(), 1f)
                    val percent = (dragStartPercent + (ev.rawX - dragStartX) / w)
                        .coerceIn(0.15f, 0.85f)
                    (guide.layoutParams as ConstraintLayout.LayoutParams).apply {
                        guidePercent = percent
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

        val initP = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        ) 0.40f else 0.30f
        (guide.layoutParams as ConstraintLayout.LayoutParams).apply {
            guidePercent = initP
            guide.layoutParams = this
        }
        splitterPercent = initP
    }

    private val sppReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent) {
            when (i.action) {
                SppServerService.ACTION_MSG  -> addIncoming(i)

                SppServerService.ACTION_SYNC -> {             // ★ READ/UNREAD‑sync
                    val unread = i.getStringArrayListExtra("unreadIds")
                    val read   = i.getStringArrayListExtra("ids")

                    // ① msgStore 내부 플래그만 수정 — UI 즉시 반응
                    var changed = false
                    unread?.forEach { id ->
                        msgStore.find { it.id == id }?.let { m ->
                            if (m.read) { m.read = false; changed = true }
                        }
                    }
                    read?.forEach { id ->
                        msgStore.find { it.id == id }?.let { m ->
                            if (!m.read) { m.read = true ; changed = true }
                        }
                    }

                    if (changed) {
                        recalcUnread()
                        treeAdapter.notifyDataSetChanged()
                        msgAdapter.notifyDataSetChanged()
                    }

                    // ② 여전히 DB 와의 최종 정합성은 유지하고 싶으면
                    //    (예: 앱이 백그라운드였다가 복귀한 경우)
                    //    지연 호출로 전체 리로드
                    lifecycleScope.launch {
                        delay(300)          // UI 깜빡임 줄이려고 살짝 지연
                        loadCategoryDataFromDb()
                    }
                }
            }
        }
    }



    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sppReceiver,
            IntentFilter().apply {
                addAction(SppServerService.ACTION_MSG)
                addAction(SppServerService.ACTION_SYNC)   // ★
            }
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sppReceiver)
        super.onPause()
    }

    private fun filterMessages(catId: String?, subId: String?) {
        selectedCat = catId
        selectedSub = subId
        msgAdapter.submit(
            msgStore.filter { m ->
                (catId == null || m.catId == catId) &&
                        (subId == null || m.subId == subId)
            }.sortedByDescending { it.ts }
        )
    }

    private fun addIncoming(i: Intent) {
        /* ---------- 반드시 패킷 id 사용 ---------- */
        val id   = i.getStringExtra(SppServerService.EXTRA_ID)
            ?: UUID.randomUUID().toString()          // fallback

        val cat = i.getStringExtra(SppServerService.EXTRA_CAT) ?: "misc"
        val sub = i.getStringExtra(SppServerService.EXTRA_SUB) ?: ""
        val title = i.getStringExtra(SppServerService.EXTRA_TITLE) ?: "(no‑title)"
        val body = i.getStringExtra(SppServerService.EXTRA_BODY) ?: ""


        val iconMsg = i.getStringExtra("icon_msg")?.takeUnless { it.isBlank() }
        val iconSub = i.getStringExtra("icon_sub")?.takeUnless { it.isBlank() } ?: iconMsg
        val iconCat = i.getStringExtra("icon_cat")?.takeUnless { it.isBlank() } ?: iconMsg
        val bestIcon = iconMsg ?: iconSub ?: iconCat ?: ""

        val subId = if (sub.isBlank()) "" else "${cat}_${sub}"

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

        Log.d("addIncoming", "cat=$cat, sub=$sub, subId=$subId")
        msgStore += UiMessage(
            id       = id,                // ← PC 와 동일한 id 저장
            catId = cat,
            subId = subId,
            title = title,
            body = body,
            ts = System.currentTimeMillis(),
            iconPath = bestIcon
        )

        recalcUnread()
        treeAdapter.notifyDataSetChanged()
        filterMessages(selectedCat, selectedSub)
        findViewById<RecyclerView>(R.id.rvMsgs).scrollToPosition(0)
    }

    private fun recalcUnread() {
        val subMap = msgStore.groupingBy { it.subId.ifBlank { it.catId } }
            .fold(0) { acc, m -> acc + if (!m.read) 1 else 0 }

        subs.forEach { s -> s.unreadCount = subMap[s.id] ?: 0 }

        cats.forEach { c ->
            val direct = subMap[c.id] ?: 0
            val fromSubs = subs.filter { it.catId == c.id }.sumOf { it.unreadCount }
            c.unreadCount = direct + fromSubs
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val guide = findViewById<Guideline>(R.id.guide)
        (guide.layoutParams as ConstraintLayout.LayoutParams).apply {
            guidePercent = splitterPercent
            guide.layoutParams = this
        }
    }

    private fun String.cap() =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
