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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.UUID

class SppServerService : Service() {

    /* ───────── 고정 채널 ───────── */
    private val FIXED_CHANNEL = 28               // ★ 여기서만 값 변경하면 양쪽 통일

    /* ───────── 브로드캐스트 상수 ───────── */
    companion object {
        private const val TAG = "SppServer"

        /* 알림 채널 & ID 분리 */
        private const val CHANNEL_STATUS = "spp_status"    // Foreground Service 상태
        private const val CHANNEL_MESSAGE = "spp_msg"      // 수신 메시지용

        private const val NOTI_FGS = 1                      // 고정(FGS) ID
        private var notiSeq = 2                             // 메시지 알림에 사용

        const val ACTION_MSG = "com.example.myapplication.ACTION_SPP_MSG"
        const val EXTRA_MSG = "msg"
        const val ACTION_STATUS = "com.example.myapplication.ACTION_SPP_STATUS"
        const val EXTRA_STATUS = "status"

        /** 표준 SPP UUID */
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    /* hidden‑API(Method) 캐싱 – listenUsingInsecureRfcommOn(int) */
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
        // 권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            emitStatus("BLUETOOTH_CONNECT 권한 없음 – 종료", true)
            stopSelf(); return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled) {
            emitStatus("Bluetooth OFF – 종료", true)
            stopSelf(); return
        }

        /* 내부 Bluetooth 서비스 초기화. 이 한 줄로
           getBluetoothService() called with no BluetoothManagerCallback 경고 제거 */
        BluetoothAdapter.getDefaultAdapter()

        // 고정 채널 28 insecure 서버소켓
        serverSocket = openServerSocket(adapter)
        if (serverSocket == null) {
            emitStatus("채널 $FIXED_CHANNEL 소켓 열기 실패 – 종료", true)
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
        // ① S(12) 미만에서만 숨은 API 시도
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hiddenInsecureOn?.runCatching {
                invoke(adapter, FIXED_CHANNEL) as BluetoothServerSocket
            }?.onSuccess {
                emitStatus("listen ch$FIXED_CHANNEL insecure ✔")
                return it
            }
        }

        // ② 실패하거나 S+이면 공개 API로
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
            val buf = ByteArray(256)
            val n = it.inputStream.read(buf)
            if (n <= 0) return@use
            val msg = String(buf, 0, n).trimEnd()

            emitStatus("받은: $msg")            // 상태 알림 업데이트
            showMessageNoti(msg)                // 신규 알림 + 진동/팝업

            // 토스트 – 메인 스레드
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            }

            sendBroadcast(Intent(ACTION_MSG).putExtra(EXTRA_MSG, msg))
        }
    }

    /*──────────────── 로그∙알림 헬퍼 ────────────────*/
    private fun emitStatus(text: String, warn: Boolean = false) {
        if (warn) Log.w(TAG, text) else Log.i(TAG, text)
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, text))

        // FGS 알림 내용 갱신
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_FGS, buildFgsNoti(text))
    }

    /* Foreground‑Service 알림 */
    private fun buildFgsNoti(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SPP Server")
            .setContentText(text)
            .setOngoing(true)
            .build()

    /* 수신 메시지용 알림 (Heads‑up + 진동) */
    private fun showMessageNoti(msg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // FGS 상태 채널 (LOW)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "SPP 서버 상태",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        // 메시지 채널 (HIGH)
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