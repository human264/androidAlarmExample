package com.example.myapplication.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine

object SppServiceConnector {

    suspend fun <T> withService(
        ctx: Context,
        timeoutMs: Long = 2000,
        block: suspend (SppServerService) -> T
    ): T? = suspendCancellableCoroutine { cont ->

        val intent = Intent(ctx, SppServerService::class.java)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(
                name:
                ComponentName, binder: IBinder
            ) {
                val service = (binder as SppServerService.LocalBinder).service
                cont.resume(service, null)
                // ➜ block 호출은 resume 이후, 호출 측 코루틴에서 진행
                ctx.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        if (!ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            cont.resume(null, null)
        }

        cont.invokeOnCancellation { ctx.unbindService(conn) }

        /* 타임아웃 */
        Handler(ctx.mainLooper).postDelayed({
            if (cont.isActive) {
                ctx.unbindService(conn)
                cont.resume(null, null)
            }
        }, timeoutMs)
    }?.let { service -> block(service as SppServerService) }
}