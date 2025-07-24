package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            val s = Intent(c, SppServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                c.startForegroundService(s)
            else c.startService(s)
        }
    }
}
