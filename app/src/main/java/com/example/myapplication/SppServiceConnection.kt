package com.example.myapplication

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
 object SppServiceConnection {

    private var boundService: SppServerService? = null

    suspend fun bind(ctx: Context, timeoutMs: Long): SppServerService? =
        suspendCancellableCoroutine { cont ->
            val intent = Intent(ctx, SppServerService::class.java)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(cn: ComponentName?, binder: IBinder?) {
                    boundService = (binder as? SppServerService.LocalBinder)
                        ?.let { it@SppServerService.LocalBinder::class.java
                            .getDeclaredMethod("this$0")
                            .invoke(binder) as SppServerService }
                    cont.resume(boundService, null)
                }
                override fun onServiceDisconnected(cn: ComponentName?) {
                    boundService = null
                }
            }
            if (!ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE))
                cont.resume(null, null)

            /* 타임아웃 처리 */
            cont.invokeOnCancellation { ctx.unbindService(conn) }
            GlobalScope.launch {
                delay(timeoutMs)
                if (cont.isActive) {
                    ctx.unbindService(conn)
                    cont.resume(null, null)
                }
            }
        }
}
