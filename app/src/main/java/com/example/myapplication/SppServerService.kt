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
import java.nio.charset.StandardCharsets
import java.util.*

/** SPP 서버(Foreground Service) */
class SppServerService : Service() {

    /* ───────── 상수 ───────── */
    companion object {
        private const val TAG             = "SppServer"
        private const val CHANNEL_STATUS  = "spp_status"
        private const val CHANNEL_MESSAGE = "spp_msg"

        private const val NOTI_FGS   = 1          // 서비스 상태
        private const val NOTI_STATE = 100        // 연결/해제 상태
        private var       notiSeq    = 2          // 일반 메시지

        /** 표준 SPP UUID */
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** 사용자 정의 헤더 */
        private val MAGIC_TXT = byteArrayOf(0x54, 0x58, 0x54) // "TXT"
        private val MAGIC_IMG = byteArrayOf(0x49, 0x4D, 0x47) // "IMG"

        /* 내부 브로드캐스트(옵션) */
        const val ACTION_MSG    = "com.example.myapplication.ACTION_SPP_MSG"
        const val EXTRA_MSG     = "msg"
        const val ACTION_STATUS = "com.example.myapplication.ACTION_SPP_STATUS"
        const val EXTRA_STATUS  = "status"
    }

    /* 숨은 API 캐싱(S 미만) */
    private val hiddenInsecureOn by lazy {
        runCatching {
            BluetoothAdapter::class.java.getMethod(
                "listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private var secureSocket  : BluetoothServerSocket? = null
    private var insecureSocket: BluetoothServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /*──────────────── Service 수명 ────────────────*/
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTI_FGS, buildFgsNoti("SPP 서버 초기화…"))
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
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) {
            emitStatus("BLUETOOTH_CONNECT 권한 없음", true); stopSelf(); return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled) { emitStatus("Bluetooth OFF", true); stopSelf(); return }

        openSockets(adapter)
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

    @SuppressLint("MissingPermission")
    private fun openSockets(adapter: BluetoothAdapter) {
        insecureSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
            "SPP‑Server‑INSECURE", SPP_UUID).also { emitStatus("listen insecure(UUID) ✔") }

        secureSocket = adapter.listenUsingRfcommWithServiceRecord(
            "SPP‑Server‑SECURE", SPP_UUID).also { emitStatus("listen secure(UUID) ✔") }
    }

    /*──────────────── 클라이언트 처리 ────────────────*/
    private fun handleClient(sock: BluetoothSocket) = scope.launch {
        showStateNoti("🔵 SPP 연결됨",
            "PC ${sock.remoteDevice.address} 과 연결되었습니다")
        toast("SPP 연결 완료")
        emitStatus("클라이언트 접속: ${sock.remoteDevice.address}")

        try {
            sock.use {
                val ins = it.inputStream
                while (true) {
                    val magic = readExactOrNull(ins, 3) ?: break        // EOF → while‑exit
                    when {
                        /* ─── IMG 프로토콜 (이전과 동일) ─── */
                        magic.contentEquals(MAGIC_IMG) -> {
                            try {
                                val dis = DataInputStream(ins)

                                val imgLen   = dis.readInt()
                                if (imgLen !in 1..1_000_000) {
                                    emitStatus("이미지 len=$imgLen 오류", true); break
                                }
                                val imgData  = readExact(ins, imgLen)

                                val titleLen = dis.readInt()
                                val title    = String(readExact(ins, titleLen), StandardCharsets.UTF_8)

                                val bodyLen  = dis.readInt()
                                val body     = String(readExact(ins, bodyLen), StandardCharsets.UTF_8)

                                /* 비트맵 디코딩 및 알림 */
                                BitmapFactory.decodeByteArray(imgData, 0, imgLen)?.let { bmp ->
                                    showImageTextNoti(bmp, title, body)
                                    // 필요하다면 내부 브로드캐스트
                                    sendBroadcast(Intent(ACTION_MSG).putExtra(EXTRA_MSG, "🖼️ $title: $body"))
                                } ?: emitStatus("Bitmap 디코딩 실패", true)

                            } catch (e: Exception) {
                                emitStatus("이미지 처리 예외: ${e.message}", true)
                            }
                        }


                        /* ─── TXT 프로토콜 ─── */
                        magic.contentEquals(MAGIC_TXT) -> {
                            val version = ins.read()
                            if (version == 1) {
                                /* 새 v1 포맷: [TXT][01][tLen][title…][bLen][body…] */
                                val tLen = DataInputStream(ins).readInt()
                                val titleBytes = readExact(ins, tLen)
                                val bLen = DataInputStream(ins).readInt()
                                val bodyBytes  = readExact(ins, bLen)
                                val title = String(titleBytes, StandardCharsets.UTF_8)
                                val body  = String(bodyBytes , StandardCharsets.UTF_8)
                                showTextNoti(title, body)
                            } else {
                                /* 구버전(헤더 뒤 LF 기준 두 줄) 대비 호환 */
                                val title = readLineUtf8(ins)
                                val body  = readLineUtf8(ins)
                                showTextNoti(title, body)
                            }
                        }

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

    /*─────────── I/O 헬퍼 ───────────*/
    private fun readExact(ins: InputStream, size: Int): ByteArray =
        ByteArray(size).also { b ->
            var off = 0
            while (off < size) {
                val r = ins.read(b, off, size - off)
                if (r < 0) throw EOFException()
                off += r
            }
        }

    private fun readExactOrNull(ins: InputStream, size: Int): ByteArray? =
        try { readExact(ins, size) } catch (_: EOFException) { null }

    private fun readLineUtf8(ins: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b == -1 || b == '\n'.code) break      // EOF 또는 LF → 종료
            buf.write(b)
        }
        return buf.toString(Charsets.UTF_8.name())
    }
    /*─────────── 알림 & 로그 ───────────*/
    private fun emitStatus(text: String, err: Boolean = false) {
        val t = if (err) "[ERR] $text" else text
        if (err) Log.w(TAG, t) else Log.i(TAG, t)
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, t))
        nm().notify(NOTI_FGS, buildFgsNoti(t))
    }

    private fun buildFgsNoti(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SPP Server")
            .setContentText(text)
            .setOngoing(true)
            .build()

    /*────────── 메시지 알림 ──────────*/
    private fun showTextNoti(title: String, body: String) =
        nm().notify(
            notiSeq++,
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
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
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
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
            NotificationCompat.Builder(this, CHANNEL_STATUS)
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
            NotificationChannel(
                CHANNEL_STATUS, "SPP 서버 상태",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) })

        nm().createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE, "SPP 메시지",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 150, 200)
            })
    }

    /*────────── 확장 & 헬퍼 ──────────*/
    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun toast(msg: String) =
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    private fun BluetoothServerSocket.socketType() =
        if (this === secureSocket) "secure" else "insecure"
}
