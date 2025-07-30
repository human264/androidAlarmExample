package com.example.myapplication.service

import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Binder
import android.os.Build
import android.text.format.DateFormat
import android.util.Base64
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.myapplication.database.AppDatabase
import com.example.myapplication.database.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/* ─────────────────────────────────────────── */
/*  SPP 서버 + 메시지 수신/저장 + UI 브로드캐스트  */
/* ─────────────────────────────────────────── */
class SppServerService : Service() {



    companion object {
        @Volatile private var bulkSync = false
        private const val MAX_ACTIVE_NOTI = 24
        private const val TAG = "SppServer"
        private const val CHANNEL_STATUS = "spp_status"
        private const val CHANNEL_MESSAGE = "spp_msg"
        private const val NOTI_FGS = 1
        private const val NOTI_STATE = 100
        private var notiSeq = 2
        const val EXTRA_ID = "id"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val MAGIC_TXT = byteArrayOf(0x54, 0x58, 0x54)   // "TXT"
        private val MAGIC_IMG = byteArrayOf(0x49, 0x4D, 0x47)   // "IMG"

        // ★ 읽음 동기화 관련
        private const val VER_TXT_V4: Byte = 0x04
        private const val OP_READ_FROM_PHONE: Byte = 0x01
        private const val OP_READ_TO_PHONE: Byte = 0x02        // PC  ➜  Phone
        private const val OP_UNREAD_TO_PHONE: Byte = 0x03        // PC  ➜  Phone
        private const val VER_TXT_V5: Byte = 0x05
        private const val OP_DEVICE_RESET : Byte = 0x10   // (기존 선언 그대로)
        private const val OP_SYNC_END     : Byte = 0x11   // (기존 선언 그대로)
        /* Activity 갱신용 브로드캐스트 필드 */
        const val ACTION_MSG = "com.example.myapplication.ACTION_SPP_MSG"
        const val ACTION_SYNC = "com.example.myapplication.ACTION_SYNC"     // ★
        const val EXTRA_CAT = "cat"
        const val EXTRA_SUB = "sub"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_ICON_MSG = "icon_msg"
        const val EXTRA_ICON_SUB = "icon_sub"
        const val EXTRA_ICON_CAT = "icon_cat"
    }

    /* ---------- DB ---------- */
    private val dao by lazy { AppDatabase.getInstance(applicationContext).messageDao() }

    /* ---------- Binder ---------- */
    inner class LocalBinder : Binder() {
        val service: SppServerService get() = this@SppServerService
        suspend fun syncReadStatus() = service.syncReadStatus()
    }
    @Volatile private var outStream: DataOutputStream? = null
    private fun getOut(sock: BluetoothSocket): DataOutputStream {
        return outStream ?: DataOutputStream(sock.outputStream).also { outStream = it }
    }
    override fun onBind(intent: Intent?) = LocalBinder()

    /* ---------- Bluetooth 상태 ---------- */
    /* ---------- Bluetooth 상태 & 코루틴 ---------- */
    @Volatile
    private var activeSocket: BluetoothSocket? = null
    private var secureSocket: BluetoothServerSocket? = null
    private var insecureSocket: BluetoothServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /* ---------- 서비스 수명 ---------- */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTI_FGS, buildFgsNoti("SPP 서버 초기화…"))
        launchServer()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY


    override fun onDestroy() {
        secureSocket?.close(); insecureSocket?.close()
        try { outStream?.close() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    /* ───────────── 서버 기동 ───────────── */
    @SuppressLint("MissingPermission")
    private fun launchServer() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        if (!adapter.isEnabled) { stopSelf(); return }

        secureSocket   = adapter.listenUsingRfcommWithServiceRecord("SPP_SECURE",   SPP_UUID)
        insecureSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("SPP_INSECURE", SPP_UUID)

        listOfNotNull(secureSocket, insecureSocket).forEach { ss ->
            scope.launch {
                while (isActive) {
                    try { ss.accept()?.let { handleClient(it) } }
                    catch (e: IOException) { emitStatus("accept 오류: ${e.message}", true) }
                }
            }
        }
    }

    /* ───────── 클라이언트 루프 ───────── */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun handleClient(sock: BluetoothSocket) = scope.launch {
        activeSocket = sock
        showStateNoti("🔵 SPP 연결", sock.remoteDevice.address)

        launch { syncReadStatus() }          // 읽음‑동기화 초기 전송

        val ins = DataInputStream(sock.inputStream)
        try {
            while (isActive && sock.isConnected) {
                val hdr = ByteArray(3); ins.readFully(hdr)
                val ver = ins.read(); if (ver < 0) break

                when {
                    hdr.contentEquals(MAGIC_IMG) -> when (ver) {
                        5 -> handleImgV5(ins)
                        3 -> handleImgV3(ins)
                        2 -> handleImgV2(ins)
                        else -> handleImgLegacy(ins, ver)
                    }
                    hdr.contentEquals(MAGIC_TXT) -> handleTxt(ins, ver)
                    else -> emitStatus("알 수 없는 헤더", true)
                }
            }
        } catch (e: IOException) {
            emitStatus("I/O 예외: ${e.message}", true)
        } finally {
            try { sock.close() } catch (_: IOException) {}
            outStream = null                         // ★ 스트림 정리
            if (activeSocket === sock) activeSocket = null
            showStateNoti("⚪ SPP 해제", sock.remoteDevice.address)
        }
    }


    /* ─────────── IMG v3 ─────────── */
    private suspend fun handleImgV3(ins: InputStream) {
        val dis = DataInputStream(ins)
        val imgCat = readExact(ins, dis.readInt())
        val imgSub = readExact(ins, dis.readInt())
        val imgMsg = readExact(ins, dis.readInt())

        val cat = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val sub = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        val pCat = saveIfAbsent(cat, imgCat)
        val pSub = saveIfAbsent("${cat}_${sub}", imgSub)
        val pMsg = saveIfAbsent("${cat}_${sub}_m", imgMsg)

        processImagePacket(
            imgMsg, cat, sub, title, body,
            fallbackIcon = pMsg ?: pSub ?: pCat ?: ""
        )
    }

    /* ─────────── IMG v2 ─────────── */
    private suspend fun handleImgV2(ins: InputStream) {
        val dis = DataInputStream(ins)
        val imgData = readExact(ins, dis.readInt())
        val cat = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val sub = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        val pSub = saveIfAbsent("${cat}_${sub}", imgData)

        processImagePacket(imgData, cat, sub, title, body, fallbackIcon = pSub ?: "")
    }

    /* ─────────── IMG legacy ─────────── */
    private suspend fun handleImgLegacy(ins: InputStream, ver: Int) {
        val dis = DataInputStream(ins)
        val imgData = readExact(ins, dis.readInt())
        val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        processImagePacket(imgData, "misc", "", title, body, fallbackIcon = "")
    }
    /* ───────── 공통 이미지 처리 ───────── */
    private suspend fun processImagePacket(
        imgData: ByteArray,
        cat: String,
        sub: String,
        title: String,
        body: String,
        fallbackIcon: String            // best 아이콘을 iconCat 로 전달
    ) {
        val bmp   = decodeImage(imgData)
        val newId = UUID.randomUUID().toString()      // 고유 ID

        if (bmp == null) {
            emitStatus("Bitmap decode 실패 → 텍스트 알림으로 대체")
            showTextNoti("[$cat/$sub] $title", body)
            persistMessage(newId, cat, sub, title, body, fallbackIcon)
            broadcastMsg(
                newId, cat, sub, title, body,
                null,            // iconMsg
                null,            // iconSub
                fallbackIcon     // iconCat
            )
            return
        }

        showImageTextNoti(bmp, "[$cat/$sub] $title", body)
        persistMessage(newId, cat, sub, title, body, fallbackIcon)
        broadcastMsg(
            newId, cat, sub, title, body,
            null, null, fallbackIcon
        )
    }

    /* ─────────── TXT ─────────── */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun handleTxt(ins: InputStream, ver: Int) {
        val dis = DataInputStream(ins)

        /* ① READ / RESET / SYNC 제어‑패킷 (v4) */
        if (ver == VER_TXT_V4.toInt()) {
            val op = dis.readByte()

            when (op) {
                OP_READ_TO_PHONE   -> { handleReadSyncFromPc(dis);   return }
                OP_UNREAD_TO_PHONE -> { handleUnreadSyncFromPc(dis); return }
                OP_READ_FROM_PHONE -> {                 // echo 무시
                    val cnt = dis.readUnsignedShort()
                    repeat(cnt) { ins.skipNBytes(dis.readUnsignedShort().toLong()) }
                    Log.i(TAG, "READ‑sync echo 무시 ($cnt)")
                    return
                }

                /* ──── ★ 추가된 두 가지 ──── */
                OP_DEVICE_RESET    -> { handleDeviceReset(); return }
                OP_SYNC_END        -> { handleSyncEnd();     return }
            }
        }

        /* ② TXT‑v5 : 고유 id 포함 */
        if (ver == VER_TXT_V5.toInt()) {
            val idLen = dis.readUnsignedShort()
            val id    = String(readExact(ins, idLen), StandardCharsets.UTF_8)

            val cat   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
            val sub   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
            val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
            val body  = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

            showTextNoti("[$cat/$sub] $title", body)
            scope.launch { persistMessage(id, cat, sub, title, body, "") }
            broadcastMsg(id, cat, sub, title, body, null, null, null)
            return
        }

        /* ③ legacy TXT (v1~v3) */
        val cat   = if (ver >= 2)
            String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8) else "text"
        val sub   = if (ver >= 2)
            String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8) else ""
        val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body  = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        val newId = UUID.randomUUID().toString()

        showTextNoti("[$cat/$sub] $title", body)
        persistMessage(newId, cat, sub, title, body, "")
        broadcastMsg(newId, cat, sub, title, body, null, null, null)
    }

    private suspend fun handleDeviceReset() {
        bulkSync = true // ★ NEW
        Log.i(TAG, "DEVICE_RESET ← PC  ▶ 모든 메시지/아이콘 삭제")
        /* 1) DB 삭제 */
        dao.deleteAll()                                              // ← MessageDao 에 deleteAll() 필요
        /* 2) 캐시 아이콘 지우기 */
        kotlin.runCatching {
            File(filesDir, "icons").deleteRecursively()
        }
        /* 3) UI 알림 */
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_SYNC).putExtra("reset", true)
        )
    }

    private suspend fun handleSyncEnd() {                             // ★ NEW
        Log.i(TAG, "SYNC_END ← PC  ▶ Full-Sync 완료")

        /* 아직 synced=false 인 레코드 모두 true 로 마킹 */
        dao.confirmSyncedAll()                                       // ← DAO 메서드 추가
        bulkSync = false
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_SYNC).putExtra("done", true)
        )
    }

    private inline fun notifyUnlessBulk(id: Int,
                                        build: () -> Notification) {
        if (bulkSync) return          // 동기화 중엔 출력 안 함
        safeNotify(id, build())
    }


    private fun safeNotify(id: Int, noti: Notification) {
        val nm = nm()
        if (Build.VERSION.SDK_INT >= 23) {
            val actives = nm.activeNotifications
                .filter { it.id != NOTI_STATE }    // 상태표시 알림은 제외
            if (actives.size >= MAX_ACTIVE_NOTI) {
                actives.minByOrNull { it.notification.`when` }?.let {
                    nm.cancel(it.tag, it.id)
                }
            }
        }
        nm.notify(id, noti)
    }



    /* ─────────── IMG v5 ─────────── */
    private fun handleImgV5(ins: InputStream) {
        val dis   = DataInputStream(ins)

        val idLen = dis.readUnsignedShort()
        val id    = String(readExact(ins, idLen), StandardCharsets.UTF_8)

        val imgCat = readExact(ins, dis.readInt())
        val imgSub = readExact(ins, dis.readInt())
        val imgMsg = readExact(ins, dis.readInt())

        val cat   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val sub   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body  = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        // 아이콘 저장 & 우선순위 선정
        val pCat = saveIfAbsent(cat, imgCat)
        val pSub = saveIfAbsent("${cat}_${sub}", imgSub)
        val pMsg = saveIfAbsent("${cat}_${sub}_m", imgMsg)
        val best = pMsg ?: pSub ?: pCat ?: ""

        val bmp = when {
            imgMsg.isNotEmpty() -> decodeImage(imgMsg)
            imgSub.isNotEmpty() -> decodeImage(imgSub)
            else                -> decodeImage(imgCat)
        }

        if (bmp == null) showTextNoti("[$cat/$sub] $title", body)
        else             showImageTextNoti(bmp, "[$cat/$sub] $title", body)

        scope.launch { persistMessage(id, cat, sub, title, body, best) }
        broadcastMsg(id, cat, sub, title, body,
            null, null, best)
    }



    private fun handleReadSyncFromPc(dis: DataInputStream) {
        val cnt = dis.readUnsignedShort()
        val ids = mutableListOf<String>()
        repeat(cnt) {
            val len = dis.readUnsignedShort()
            ids += String(readExact(dis, len), StandardCharsets.UTF_8)
        }
        Log.i(TAG, "READ‑sync ← PC : ${ids.size}건")

        /* handleReadSyncFromPc() 마지막에 ↓ 추가 */
        scope.launch {
            val s = activeSocket ?: return@launch
            val dos = DataOutputStream(s.outputStream)    // 재사용 가능

            synchronized(dos) {
                dos.write(byteArrayOf(0x54, 0x58, 0x54))  // "TXT"
                dos.writeByte(VER_TXT_V4.toInt())         // 0x04
                dos.writeByte(OP_READ_FROM_PHONE.toInt()) // echo opcode
                dos.writeShort(ids.size)                  // count
                ids.forEach { id ->
                    val b = id.toByteArray(StandardCharsets.UTF_8)
                    dos.writeShort(b.size)
                    dos.write(b)
                }
                dos.flush()
            }
        }

        // UI 갱신용 broadcast (선택)
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_SYNC).putStringArrayListExtra("ids", ArrayList(ids))
        )
    }

    /* ───────── 이미지 디코더 ───────── */
    private fun decodeImage(src: ByteArray): Bitmap? {
        if (src.isEmpty()) return null
        var data = src

        /* ① Base64 판단 & 복호화 */
        fun looksLikeBase64(buf: ByteArray): Boolean {
            for (i in 0 until minOf(buf.size, 32)) {
                val b = buf[i]
                if (b < 0x20 || b > 0x7E) return false
            }
            return true
        }
        if (looksLikeBase64(data)) {
            try {
                val text = data.toString(Charsets.US_ASCII).trim()
                data = try {
                    Base64.decode(text, Base64.DEFAULT)        // ← import android.util.Base64
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Base64 decode 실패: ${e.message}")
                    return null
                }
                Log.i(TAG, "Base64 → ${data.size} bytes 복호화 완료")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Base64 decode 실패: ${e.message}")
                return null
            }
        }

        /* ② 레벨별 디코딩 */
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
                ImageDecoder.decodeBitmap(source) { d, _, _ ->
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                }
            } else {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap decode 실패: ${e.message}")
            null
        }
    }

    /* ───────── 알림 ───────── */
    private fun showTextNoti(title: String, body: String) =
        notifyUnlessBulk(notiSeq++) {
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
                .setSmallIcon(R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true).build()
        }

    private fun showImageTextNoti(bmpOrig: Bitmap?, title: String, body: String) {
        if (bmpOrig == null) {           // 안전‑가드
            showTextNoti(title, body)
            return
        }
        val bmp = if (bmpOrig.width > 128 || bmpOrig.height > 128)
            Bitmap.createScaledBitmap(bmpOrig, 128, 128, true) else bmpOrig

        val catSub = title.substringBefore(']').trim('[', ' ')
        val time = DateFormat.format("HH:mm", System.currentTimeMillis())

        val rv = RemoteViews(packageName, com.example.myapplication.R.layout.notif_popup).apply {
            setImageViewBitmap(com.example.myapplication.R.id.ivThumb, bmp)
            setTextViewText(com.example.myapplication.R.id.tvCatSub, catSub)
            setTextViewText(com.example.myapplication.R.id.tvTime, time)
            setTextViewText(
                com.example.myapplication.R.id.tvTitle,
                title.substringAfter("] ").trim()
            )
            setTextViewText(com.example.myapplication.R.id.tvDetail, "")
            setTextViewText(com.example.myapplication.R.id.tvBody, body)
        }

        val noti = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.stat_notify_chat)
            .setContentTitle(title)              // 접근성
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setCustomHeadsUpContentView(rv)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

//        nm().notify(notiSeq++, noti)
        notifyUnlessBulk(notiSeq++, { noti })
    }

    private fun showStateNoti(t: String, b: String) =
        nm().notify(
            NOTI_STATE,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(t).setContentText(b)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        )

    /* ───────── DB 저장 ───────── */
    private suspend fun persistMessage(
        id: String,
        cat: String, sub: String,
        title: String, body: String,
        iconPath: String
    ) = dao.upsert(
        MessageEntity(
            id = id,                          // ★ 그대로 사용
            catId = cat,
            subId = if (sub.isBlank()) "" else "${cat}_${sub}",
            title = title,
            body = body,
            ts = System.currentTimeMillis(),
            iconPath = iconPath,
            read = false,
            synced = false
        )
    )

    /*  읽음 동기화  */
    /*  읽음 동기화  */
    suspend fun syncReadStatus() {
        val pending = dao.pendingReadSync()
        if (pending.isEmpty()) return

        var waited = 0
        while (waited < 5000) {               // 최대 5 초 대기
            val s = activeSocket
            if (s != null && s.isConnected) {
                sendReadSync(s, pending)
                return
            }
            delay(250)
            waited += 250
        }
        Log.w(TAG, "syncReadStatus: socket null – postpone (${pending.size})")
    }
    /* ★ 추가: 안읽음‑sync 처리 루틴 */
    private fun handleUnreadSyncFromPc(dis: DataInputStream) {
        val cnt = dis.readUnsignedShort()
        val ids = mutableListOf<String>()
        repeat(cnt) {
            val len = dis.readUnsignedShort()
            ids += String(readExact(dis, len), StandardCharsets.UTF_8)
        }
        Log.i(TAG, "UNREAD‑sync ← PC : ${ids.size}건")

        scope.launch {
            dao.markUnreadList(ids)       // ★ DAO에 read=false 로 바꾸는 메서드 추가
        }

        /* 필요시 UI 갱신 브로드캐스트 */
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_SYNC).apply {
                putStringArrayListExtra("unreadIds", ArrayList(ids))
            }
        )
    }

    private suspend fun sendReadSync(sock: BluetoothSocket,
                                     list: List<MessageEntity>) {
        val dos = getOut(sock)                // ★ 닫지 않고 재사용
        synchronized(dos) {
            dos.write(MAGIC_TXT)
            dos.writeByte(VER_TXT_V4.toInt())
            dos.writeByte(OP_READ_FROM_PHONE.toInt())
            dos.writeShort(list.size)
            list.forEach {
                val b = it.id.toByteArray(StandardCharsets.UTF_8)
                dos.writeShort(b.size); dos.write(b)
            }
            dos.flush()
        }
        dao.confirmSynced(list.map { it.id })
        Log.i(TAG, "READ‑sync ▶ PC (${list.size})")
    }



    /* ───────── 헬퍼 ───────── */
    private fun readExact(ins: InputStream, size: Int) = ByteArray(size).also { buf ->
        var off = 0
        while (off < size) {
            val n = ins.read(buf, off, size - off)
            if (n < 0) throw EOFException(); off += n
        }
    }

    private fun readExactOrNull(ins: InputStream, size: Int): ByteArray? =
        runCatching { readExact(ins, size) }.getOrNull()

    private fun saveIfAbsent(key: String, data: ByteArray): String? {
        if (data.isEmpty()) return null
        val file = File(filesDir, "icons/${key.lowercase(Locale.ROOT)}.png")
        if (!file.exists()) {
            file.parentFile?.mkdirs(); file.writeBytes(data)
        }
        return file.absolutePath
    }

    /* ---------- 브로드캐스트 ---------- */
    private fun broadcastMsg(
        id: String, cat: String, sub: String, title: String, body: String,
        iconMsg: String?, iconSub: String?, iconCat: String?
    ) {
        Intent(ACTION_MSG).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_CAT, cat)
            putExtra(EXTRA_SUB, sub)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_ICON_MSG, iconMsg ?: "")
            putExtra(EXTRA_ICON_SUB, iconSub ?: "")
            putExtra(EXTRA_ICON_CAT, iconCat ?: "")
        }.also {
            LocalBroadcastManager.getInstance(this).sendBroadcast(it)
        }
    }


    /* ---------- 채널 & 상태 ---------- */
    private fun buildFgsNoti(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SPP Server").setContentText(text)
            .setOngoing(true).build()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        nm().createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "SPP 서버 상태",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm().createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE, "SPP 메시지",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
        )
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun emitStatus(text: String, err: Boolean = false) {
        val tag = if (err) "[ERR] $text" else text
        if (err) Log.w(TAG, tag) else Log.i(TAG, tag)
        nm().notify(NOTI_FGS, buildFgsNoti(tag))
    }
}