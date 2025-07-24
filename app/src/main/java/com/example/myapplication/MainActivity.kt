package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var txt: TextView
    private lateinit var scroll: ScrollView

    /* 메시지 & 상태 모두 수신 */
    private val sppReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                SppServerService.ACTION_MSG ->
                    appendLine("📩 " + i.getStringExtra(SppServerService.EXTRA_MSG))
                SppServerService.ACTION_STATUS ->
                    appendLine("ℹ️  " + i.getStringExtra(SppServerService.EXTRA_STATUS))
            }
        }
    }

    private fun appendLine(s: String?) {
        if (s == null) return
        txt.append("\n$s")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /* 런타임 퍼미션 런처 */
    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.values.all { ok -> ok }) startSppService()
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        txt = TextView(this).apply {
            text = "▶ SPP 수신 대기 중…"
            textSize = 18f
            gravity = Gravity.START
            setPadding(40, 60, 40, 40)
        }
        scroll = ScrollView(this).also { it.addView(txt) }
        setContentView(scroll)

        checkAndRequestPermissions()
    }

    /* 권한 체크 & 요청 */
    /* 권한 체크 & 요청 */
    private fun checkAndRequestPermissions() {
        val perms = buildList {
            // S+ 전용 BLE 권한은 Android 12(S) 이상에서만 추가
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            // 알림 권한은 Android 13(Tiramisu)+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        if (perms.isEmpty() ||                     // API 28 ~ 30 은 요청할 권한 자체가 없음
            perms.all {
                ContextCompat.checkSelfPermission(this, it) ==
                        PackageManager.PERMISSION_GRANTED
            }
        ) {
            startSppService()
        } else {
            permsLauncher.launch(perms)
        }
    }

    /* Foreground Service 기동 */
    private fun startSppService() =
        ContextCompat.startForegroundService(
            this, Intent(this, SppServerService::class.java)
        )

    /* 리시버 등록/해제 */
    @SuppressLint("InlinedApi")
    override fun onResume() {
        super.onResume()
        registerReceiver(
            sppReceiver,
            IntentFilter().apply {
                addAction(SppServerService.ACTION_MSG)
                addAction(SppServerService.ACTION_STATUS)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        unregisterReceiver(sppReceiver)
        super.onPause()
    }
}
