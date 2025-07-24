package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.UUID

class SppServerService : Service() {

    /* ───────── 고정 채널 ───────── */
    private val FIXED_CHANNEL = 28

    /* ───────── 상수 ───────── */
    companion object {
        private const val TAG = "SppServer"

        /* 알림 채널 */
        private const val CHANNEL_STATUS  = "spp_status"
        private const val CHANNEL_MESSAGE = "spp_msg"

        private const val NOTI_FGS = 1
        private var notiSeq = 2

        const val ACTION_MSG    = "com.example.myapplication.ACTION_SPP_MSG"
        const val EXTRA_MSG     = "msg"
        const val ACTION_STATUS = "com.example.myapplication.ACTION_SPP_STATUS"
        const val EXTRA_STATUS  = "status"

        /** 표준 SPP UUID */
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** 사용자 정의 “IMG” 헤더 */
        private val MAGIC_IMG = byteArrayOf(0x49, 0x4D, 0x47)
    }

    /* 숨은 API 캐싱 */
    private val hiddenInsecureOn by lazy {
        runCatching {
            BluetoothAdapter::class.java
                .getMethod("listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private var serverSocket: BluetoothServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /*──────────────── Service 수명주기 ────────────────*/
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
        serverSocket?.close()
        scope.cancel()
        super.onDestroy()
    }

    /*──────────────── 서버 구동 ────────────────*/
    @SuppressLint("MissingPermission")
    private fun launchServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            emitStatus("BLUETOOTH_CONNECT 권한 없음", true)
            stopSelf(); return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled) {
            emitStatus("Bluetooth OFF", true)
            stopSelf(); return
        }

        serverSocket = openServerSocket(adapter) ?: run {
            emitStatus("채널 $FIXED_CHANNEL 소켓 열기 실패", true)
            stopSelf(); return
        }

        emitStatus("채널 $FIXED_CHANNEL(SPP·insecure) 대기 중…")

        scope.launch {
            while (isActive) {
                try {
                    serverSocket?.accept()?.let { handleClient(it) }
                } catch (e: Exception) {
                    emitStatus("accept 예외: ${e.message}", true)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openServerSocket(adapter: BluetoothAdapter): BluetoothServerSocket? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hiddenInsecureOn?.runCatching {
                invoke(adapter, FIXED_CHANNEL) as BluetoothServerSocket
            }?.onSuccess {
                emitStatus("listen ch$FIXED_CHANNEL insecure ✔")
                return it
            }
        }
        return adapter.listenUsingInsecureRfcommWithServiceRecord(
            "SPP‑Server", SPP_UUID
        ).also {
            emitStatus("listen via SDP(UUID=$SPP_UUID) insecure ✔ (fallback)")
        }
    }

    /*──────────────── 클라이언트 처리 ────────────────*/
    private fun handleClient(sock: BluetoothSocket) = scope.launch {
        emitStatus("클라이언트: ${sock.remoteDevice.address}")
        sock.use {
            try {
                val ins = it.inputStream
                val magic = readExact(ins, 3)

                if (magic.contentEquals(MAGIC_IMG)) {
                    /* 이미지 + 캡션 모드 */
                    val len  = ByteBuffer.wrap(readExact(ins, 4)).int
                    if (len <= 0 || len > 1_000_000) {
                        emitStatus("잘못된 이미지 길이=$len", true); return@use
                    }
                    val imgBytes = readExact(ins, len)
                    val bmp      = BitmapFactory.decodeByteArray(imgBytes, 0, len)
                    ins.read()
                    /* ★ UPDATED: 캡션을 LF 기준으로 한 줄만 읽기 */
                    val caption = BufferedReader(
                        InputStreamReader(ins, Charsets.UTF_8)
                    ).readLine() ?: ""

                    if (bmp != null) {
                        emitStatus("이미지($len B)+캡션 수신: \"$caption\"")
                        showImageTextNoti(bmp, caption)
                    } else emitStatus("Bitmap 디코딩 실패", true)
                    return@use
                }

                /* 텍스트 모드 */
                val buf = ByteArray(256)
                System.arraycopy(magic, 0, buf, 0, 3)
                val readN = ins.read(buf, 3, buf.size - 3)
                val msg = String(buf, 0, 3 + maxOf(readN, 0)).trimEnd()

                emitStatus("받은: $msg")
                showTextNoti(msg)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
                sendBroadcast(Intent(ACTION_MSG).putExtra(EXTRA_MSG, msg))
            } catch (e: Exception) {
                emitStatus("클라이언트 오류: ${e.message}", true)
            }
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

    /*─────────── 알림 & 로그 ───────────*/
    private fun emitStatus(text: String, err: Boolean = false) {
        val t = if (err) "[ERR] $text" else text
        if (err) Log.w(TAG, t) else Log.i(TAG, t)
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, t))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_FGS, buildFgsNoti(t))
    }

    private fun buildFgsNoti(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SPP Server")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun showTextNoti(msg: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            notiSeq++,
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("새 SPP 메시지")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        )
    }

    private fun showImageTextNoti(bmp: Bitmap, caption: String) {
        val rv = RemoteViews(packageName, R.layout.notif_image_text).apply {
            setImageViewBitmap(R.id.ivThumb, bmp)
            setTextViewText(R.id.tvCaption, caption)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            notiSeq++,
            NotificationCompat.Builder(this, CHANNEL_MESSAGE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
                .setCustomBigContentView(rv)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        )
    }

    /*──────────────── 알림 채널 ────────────────*/
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "SPP 서버 상태",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE, "SPP 메시지",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 150, 200)
            }
        )
    }
}
