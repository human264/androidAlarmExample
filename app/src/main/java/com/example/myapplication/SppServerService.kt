// SppServerService.kt  ── FINAL
package com.example.myapplication
import android.util.Base64
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.util.Preconditions.checkArgument
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.myapplication.database.AppDatabase
import com.example.myapplication.database.MessageEntity
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*

/* ─────────────────────────────────────────── */
/*  SPP 서버 + 메시지 수신/저장 + UI 브로드캐스트  */
/* ─────────────────────────────────────────── */
class SppServerService : Service() {

    /* ---------- 상수 ---------- */
    companion object {
        private const val TAG            = "SppServer"
        private const val CHANNEL_STATUS = "spp_status"
        private const val CHANNEL_MESSAGE= "spp_msg"
        private const val NOTI_FGS       = 1
        private const val NOTI_STATE     = 100
        private var   notiSeq            = 2

        val  SPP_UUID     : UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val MAGIC_TXT    = byteArrayOf(0x54, 0x58, 0x54)   // "TXT"
        private val MAGIC_IMG    = byteArrayOf(0x49, 0x4D, 0x47)   // "IMG"

        /* Activity 갱신용 브로드캐스트 필드 */
        const val ACTION_MSG     = "com.example.myapplication.ACTION_SPP_MSG"
        const val EXTRA_CAT      = "cat"
        const val EXTRA_SUB      = "sub"
        const val EXTRA_TITLE    = "title"
        const val EXTRA_BODY     = "body"
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
    override fun onBind(intent: Intent?) = LocalBinder()

    /* ---------- Bluetooth 상태 ---------- */
    @Volatile private var activeSocket: BluetoothSocket? = null
    private var secureSocket:   BluetoothServerSocket? = null
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
        scope.cancel()
        super.onDestroy()
    }

    /* ───────────── 서버 기동 ───────────── */
    @SuppressLint("MissingPermission")
    private fun launchServer() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }

        if (!adapter.isEnabled) { stopSelf(); return }

        secureSocket   = adapter.listenUsingRfcommWithServiceRecord("SPP_SECURE", SPP_UUID)
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
    private fun handleClient(sock: BluetoothSocket) = scope.launch {
        activeSocket = sock
        showStateNoti("🔵 SPP 연결", sock.remoteDevice.address)

        try {
            sock.use { s ->
                val ins = s.inputStream
                while (true) {
                    val magic = readExactOrNull(ins, 3) ?: break
                    val ver   = ins.read()
                    when {
                        magic.contentEquals(MAGIC_IMG) -> when (ver) {
                            3 -> handleImgV3(ins)
                            2 -> handleImgV2(ins)
                            else -> handleImgLegacy(ins, ver)
                        }
                        magic.contentEquals(MAGIC_TXT) -> handleTxt(ins, ver)
                        else -> emitStatus("알 수 없는 헤더", true)
                    }
                }
            }
        } catch (e: Exception) {
            emitStatus("클라이언트 예외: ${e.message}", true)
        } finally {
            activeSocket = null
            showStateNoti("⚪ SPP 해제", sock.remoteDevice.address)
        }
    }

    /* ─────────── IMG v3 ─────────── */
    private fun handleImgV3(ins: InputStream) {
        val dis = DataInputStream(ins)
        val imgCat = readExact(ins, dis.readInt())
        val imgSub = readExact(ins, dis.readInt())
        val imgMsg = readExact(ins, dis.readInt())

        val cat   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val sub   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val title = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body  = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        val pCat = saveIfAbsent(cat, imgCat)
        val pSub = saveIfAbsent("${cat}_${sub}", imgSub)
        val pMsg = saveIfAbsent("${cat}_${sub}_m", imgMsg)

        processImagePacket(
            imgMsg, cat, sub, title, body,
            fallbackIcon = pMsg ?: pSub ?: pCat ?: ""
        )
    }

    /* ─────────── IMG v2 ─────────── */
    private fun handleImgV2(ins: InputStream) {
        val dis     = DataInputStream(ins)
        val imgData = readExact(ins, dis.readInt())
        val cat     = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val sub     = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val title   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body    = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        val pSub = saveIfAbsent("${cat}_${sub}", imgData)

        processImagePacket(imgData, cat, sub, title, body, fallbackIcon = pSub ?: "")
    }

    /* ─────────── IMG legacy ─────────── */
    private fun handleImgLegacy(ins: InputStream, ver: Int) {
        val dis     = DataInputStream(ins)
        val imgData = readExact(ins, dis.readInt())
        val title   = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body    = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        processImagePacket(imgData, "misc", "", title, body, fallbackIcon = "")
    }

    /* ───────── 공통 이미지 처리 ───────── */
    private fun processImagePacket(
        imgData: ByteArray,
        cat: String,
        sub: String,
        title: String,
        body: String,
        fallbackIcon: String
    ) {
        val bmp = decodeImage(imgData)

        if (bmp == null) {
            emitStatus("Bitmap decode 실패 → 텍스트 알림으로 대체")
            showTextNoti("[$cat/$sub] $title", body)
            persistMessage(cat, sub, title, body, fallbackIcon)
            broadcastMsg(cat, sub, title, body, fallbackIcon, null, null)
            return
        }

        showImageTextNoti(bmp, "[$cat/$sub] $title", body)
        persistMessage(cat, sub, title, body, fallbackIcon)
        broadcastMsg(cat, sub, title, body, fallbackIcon, null, null)
    }

    /* ─────────── TXT ─────────── */
    private fun handleTxt(ins: InputStream, ver: Int) {
        val dis  = DataInputStream(ins)
        val cat  = if (ver >= 2) String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8) else "text"
        val sub  = if (ver >= 2) String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8) else ""
        val title= String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)
        val body = String(readExact(ins, dis.readInt()), StandardCharsets.UTF_8)

        showTextNoti("[$cat/$sub] $title", body)
        persistMessage(cat, sub, title, body, iconPath = "")
        broadcastMsg(cat, sub, title, body, null, null, null)
    }

    /* ───────── 이미지 디코더 ───────── */
    private fun decodeImage(src: ByteArray): Bitmap? {
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
        nm().notify(
            notiSeq++,
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true).build()
        )

    private fun showImageTextNoti(bmpOrig: Bitmap?, title: String, body: String) {
        if (bmpOrig == null) {           // 안전‑가드
            showTextNoti(title, body)
            return
        }
        val bmp = if (bmpOrig.width > 128 || bmpOrig.height > 128)
            Bitmap.createScaledBitmap(bmpOrig, 128, 128, true) else bmpOrig

        val catSub = title.substringBefore(']').trim('[', ' ')
        val time   = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis())

        val rv = RemoteViews(packageName, R.layout.notif_popup).apply {
            setImageViewBitmap(R.id.ivThumb , bmp)
            setTextViewText (R.id.tvCatSub , catSub)
            setTextViewText (R.id.tvTime   , time)
            setTextViewText (R.id.tvTitle  , title.substringAfter("] ").trim())
            setTextViewText (R.id.tvDetail , "")
            setTextViewText (R.id.tvBody   , body)
        }

        val noti = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)              // 접근성
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setCustomHeadsUpContentView(rv)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        nm().notify(notiSeq++, noti)
    }

    private fun showStateNoti(t: String, b: String) =
        nm().notify(
            NOTI_STATE,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(t).setContentText(b)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        )

    /* ───────── DB 저장 ───────── */
    private fun persistMessage(
        cat: String, sub: String, title: String, body: String, iconPath: String
    ) = scope.launch {
        dao.upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                catId = cat,
                subId = if (sub.isBlank()) "" else "${cat}_${sub}",
                title = title, body = body,
                ts = System.currentTimeMillis(),
                iconPath = iconPath,
                read = false, synced = false
            )
        )
    }

    /* ───────── 읽음 동기화 ───────── */
    suspend fun syncReadStatus() {
        val os = activeSocket?.outputStream ?: return
        val pending = dao.pendingReadSync(); if (pending.isEmpty()) return

        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.write(MAGIC_TXT); dos.writeByte(4); dos.writeByte(0x01)
            dos.writeShort(pending.size)
            pending.forEach {
                val id = it.id.toByteArray(StandardCharsets.UTF_8)
                dos.writeShort(id.size); dos.write(id)
            }
        }
        os.write(baos.toByteArray()); os.flush()
        dao.confirmSynced(pending.map { it.id })
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
        if (!file.exists()) { file.parentFile?.mkdirs(); file.writeBytes(data) }
        return file.absolutePath
    }

    /* ---------- 브로드캐스트 ---------- */
    private fun broadcastMsg(
        cat: String, sub: String, title: String, body: String,
        iconMsg: String?, iconSub: String?, iconCat: String?
    ) {
        val i = Intent(ACTION_MSG).apply {
            putExtra(EXTRA_CAT, cat)
            putExtra(EXTRA_SUB, sub)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_ICON_MSG, iconMsg ?: "")
            putExtra(EXTRA_ICON_SUB, iconSub ?: "")
            putExtra(EXTRA_ICON_CAT, iconCat ?: "")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    /* ---------- 채널 & 상태 ---------- */
    private fun buildFgsNoti(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
