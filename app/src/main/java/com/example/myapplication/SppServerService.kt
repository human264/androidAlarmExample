package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.util.*

/** SPP(Foreground) 서버 – TXT·IMG 프로토콜 대응 */
class SppServerService : Service() {

    /* ───────── 상수 ───────── */
    companion object {
        private const val TAG             = "SppServer"
        private const val CH_STATUS       = "spp_status"
        private const val CH_MESSAGE      = "spp_msg"
        const val EXTRA_MSG = "msg"
        private const val NOTI_FGS   = 1     // 서비스
        private const val NOTI_STATE = 100   // 연결/해제
        private var   notiSeq = 2            // 일반 메시지

        /** 표준 SPP UUID */
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** 3‑바이트 헤더 */
        private val H_TXT = byteArrayOf(0x54, 0x58, 0x54)   // "TXT"
        private val H_IMG = byteArrayOf(0x49, 0x4D, 0x47)   // "IMG"

        /* ─── 내부 브로드캐스트 ─── */
        const val ACTION_STATUS  = "com.example.myapplication.ACTION_SPP_STATUS"
        const val ACTION_MSG     = "com.example.myapplication.ACTION_SPP_MSG"   // ★ 복구
        const val EXTRA_TITLE    = "title"   // 메시지 제목
        const val EXTRA_BODY     = "body"    // 메시지 본문
        const val EXTRA_STATUS   = "status"
    }


    /* 숨은 listenUsingInsecureRfcommOn(int) –(Android S 미만) */
    private val hiddenInsecureOn by lazy {
        runCatching {
            BluetoothAdapter::class.java.getMethod(
                "listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private var secureSocket  : BluetoothServerSocket? = null
    private var insecureSocket: BluetoothServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /*──────────────── Service lifecycle ────────────────*/
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTI_FGS, fgsNoti("SPP 서버 초기화…"))
        launchServer()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?) = null
    override fun onDestroy() {
        secureSocket?.close(); insecureSocket?.close(); scope.cancel()
        super.onDestroy()
    }

    /*──────────────── 서버 구동 ────────────────*/
    @SuppressLint("MissingPermission")
    private fun launchServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            emitStatus("BLUETOOTH_CONNECT 권한 없음", true); stopSelf(); return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled) { emitStatus("Bluetooth OFF", true); stopSelf(); return }

        /* insecure / secure 두 소켓 모두 연다 */
        insecureSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
            "SPP‑Insecure", SPP_UUID).also { emitStatus("listen insecure(UUID) ✔") }

        secureSocket = adapter.listenUsingRfcommWithServiceRecord(
            "SPP‑Secure", SPP_UUID).also { emitStatus("listen secure(UUID) ✔") }

        emitStatus("SPP(UUID) secure+insecure 대기 중…")

        listOfNotNull(secureSocket, insecureSocket).forEach { ss ->
            scope.launch {
                while (isActive) {
                    try { ss.accept()?.let { handleClient(it) } }
                    catch (e: Exception) {
                        emitStatus("accept 예외(${ss.socketType()}): ${e.message}", true)
                    }
                }
            }
        }
    }

    /*──────────────── 클라이언트 처리 ────────────────*/
    private fun handleClient(sock: BluetoothSocket) = scope.launch {
        val peer = sock.remoteDevice.address
        showStateNoti("🔵 SPP 연결됨", "PC $peer 과 연결되었습니다")
        toast("SPP 연결 완료")
        emitStatus("클라이언트 접속: $peer")

        try {
            sock.use {
                val ins = it.inputStream
                while (true) {
                    val hdr = readExactOrNull(ins, 3) ?: break   // EOF → 종료
                    when {
                        hdr.contentEquals(H_IMG) -> readImagePacket(ins)
                        hdr.contentEquals(H_TXT) -> readTextPacket(ins)
                        else -> emitStatus("알 수 없는 헤더", true)
                    }
                }
            }
        } catch (e: Exception) {
            emitStatus("클라이언트 오류: ${e.message}", true)
        } finally {
            showStateNoti("⚪ SPP 해제", "PC 연결이 종료되었습니다")
            toast("SPP 연결 종료")
        }
    }
    /*────────── Packet 파서 ──────────*/
    private fun readTextPacket(ins: InputStream) {
        val title = readLineUtf8(ins).ifEmpty { "(제목 없음)" }
        val body  = readLineUtf8(ins)
        showTextNoti(title, body)
        /* ▶ Broadcast */
        sendBroadcast(
            Intent(ACTION_MSG)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY,  body)
                .putExtra(EXTRA_MSG,   body)      // ★ 추가
        )
    }


    private fun readImagePacket(ins: InputStream) {
        val len = ByteBuffer.wrap(readExact(ins, 4)).int
        if (len !in 1..1_000_000) { emitStatus("이미지 len=$len 오류", true); return }
        val imageBytes = readExact(ins, len)
        ins.read()                     // '\n'
        val title = readLineUtf8(ins).ifEmpty { "(제목 없음)" }
        val body  = readLineUtf8(ins)

        BitmapFactory.decodeByteArray(imageBytes, 0, len)?.let { bmp ->
            showImageTextNoti(bmp, title, body)
            /* ▶ Broadcast (썸네일은 보내지 않고, 제목/본문만) */
            sendBroadcast(
                Intent(ACTION_MSG).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body)
            )
        } ?: emitStatus("Bitmap 디코딩 실패", true)
    }


    /*─────────── I/O 헬퍼 ───────────*/
    private fun readExact(ins: InputStream, size: Int): ByteArray =
        ByteArray(size).also { buf ->
            var off = 0
            while (off < size) {
                val r = ins.read(buf, off, size - off)
                if (r < 0) throw EOFException()
                off += r
            }
        }

    private fun readExactOrNull(ins: InputStream, size: Int): ByteArray? =
        try { readExact(ins, size) } catch (_: EOFException) { null }

    private fun readLineUtf8(ins: InputStream): String =
        BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).readLine() ?: ""

    /*─────────── 알림 & 로그 ───────────*/
    private fun emitStatus(msg: String, err: Boolean = false) {
        val text = if (err) "[ERR] $msg" else msg
        if (err) Log.w(TAG, text) else Log.i(TAG, text)
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, text))
        nm().notify(NOTI_FGS, fgsNoti(text))
    }

    private fun fgsNoti(text: String) =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SPP Server")
            .setContentText(text)
            .setOngoing(true)
            .build()

    /*────────── 메시지 알림 ──────────*/
    private fun showTextNoti(title: String, body: String) =
        nm().notify(
            notiSeq++,
            NotificationCompat.Builder(this, CH_MESSAGE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        )

    private fun showImageTextNoti(bmp: Bitmap, title: String, body: String) {
        val rv = RemoteViews(packageName, R.layout.notif_image_text).apply {
            setImageViewBitmap(R.id.ivThumb, bmp)
            setTextViewText(R.id.tvCaption, body)
        }
        nm().notify(
            notiSeq++,
            NotificationCompat.Builder(this, CH_MESSAGE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
                .setCustomBigContentView(rv)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        )
    }

    /*────────── 연결/해제 상태 알림 ──────────*/
    private fun showStateNoti(title: String, body: String) =
        nm().notify(
            NOTI_STATE,
            NotificationCompat.Builder(this, CH_STATUS)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        )

    /*──────────────── 알림 채널 ────────────────*/
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        nm().createNotificationChannel(
            NotificationChannel(CH_STATUS, "SPP 서버 상태",
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })

        nm().createNotificationChannel(
            NotificationChannel(CH_MESSAGE, "SPP 메시지",
                NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 150, 200)
            })
    }

    /*────────── 헬퍼 ──────────*/
    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun toast(msg: String) =
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }

    private fun BluetoothServerSocket.socketType() =
        if (this === secureSocket) "secure" else "insecure"
}
