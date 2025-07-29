package com.example.myapplication.activitiy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.myapplication.SppServerService

class MainActivity : ComponentActivity() {

    /* ───────── 런타임 권한 런처 ───────── */
    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                startForegroundSvcAndLaunchCenter()
            } else {
                Toast.makeText(this,
                    "필수 권한이 없어 앱을 종료합니다.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    /* ───────── lifecycle ───────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 별도 레이아웃 없이 권한만 확인
        checkAndRequestPermissions()
    }
    /* ───── 런타임 권한 확인 & 요청 ───── */
    private fun checkAndRequestPermissions() {
        // Android 12(S)+ : BLUETOOTH_CONNECT / BLUETOOTH_SCAN
        // Android 13(T)+ : POST_NOTIFICATIONS
        val perms = buildList<String> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        val allGranted = perms.isEmpty() || perms.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startForegroundSvcAndLaunchCenter()
        } else {
            // 하나라도 미허용이면 런타임 퍼미션 요청
            permsLauncher.launch(perms)
        }
    }

    /* ───── SPP Foreground‑Service 기동 ───── */
    @SuppressLint("ForegroundServiceType")   // Manifest 에 connectedDevice 타입 선언済
    private fun startSppService() {
        val svc = Intent(this, SppServerService::class.java)
        ContextCompat.startForegroundService(this, svc)
    }

    /* 서비스 기동 후 메시지 센터로 전환 */
    private fun startForegroundSvcAndLaunchCenter() {
        startSppService()                                           // ① 서비스 실행
        startActivity(Intent(this, MessageCenterActivity::class.java)) // ② 센터 화면
        finish()                                                    // ③ MainActivity 종료
    }

}
