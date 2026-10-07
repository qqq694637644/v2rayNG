package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread. On teardown the rules are removed before the core stops.
 */
class CoreRootService : Service(), ServiceControl {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var setupJob: Job? = null
    private var stopJob: Job? = null
    private var routingStopped = false
    private var stopRequested = false
    private var stopCoreCompleted = false
    private var startAccepted = false

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.setNetLoopRuntimeActive(false)
        CoreServiceManager.serviceControl = SoftReference(this)
        CoreServiceManager.registerServiceControlReceiver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")
        if (stopRequested) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: Ignoring start while stop is completing")
            return START_NOT_STICKY
        }
        if (startAccepted) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: Ignoring duplicate start for this service instance")
            return START_STICKY
        }
        startAccepted = true

        // Start the in-process core first (this also posts the foreground notification),
        // then install the root routing off the main thread.
        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
            stopService()
            return START_NOT_STICKY
        }

        setupJob = serviceScope.launch(Dispatchers.IO) {
            if (!RootProxyManager.start(this@CoreRootService)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                stopService()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        val destroyCoreCompleted = runBlocking {
            stopRootRouting()
            CoreServiceManager.stopCoreLoopForServiceDestroy(this@CoreRootService)
        }
        CoreServiceManager.unregisterServiceControlReceiver(this)
        if (stopRequested && (stopCoreCompleted || destroyCoreCompleted)) {
            com.v2ray.ang.util.MessageUtil.sendMsg2UI(
                this,
                AppConfig.MSG_STATE_STOP_SUCCESS,
                "",
            )
        }
        NotificationManager.cancelNotification(this)
        CoreServiceManager.clearServiceControl(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        if (stopJob?.isActive == true) return
        stopRequested = true
        stopJob = serviceScope.launch {
            stopRootRouting()
            stopCoreCompleted = CoreServiceManager.stopCoreLoopForServiceStop(this@CoreRootService)
            if (!stopCoreCompleted) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: Failed to complete core stop")
            }
            stopSelf()
        }
    }

    private suspend fun stopRootRouting() {
        if (routingStopped) return
        setupJob?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            RootProxyManager.stop(this@CoreRootService)
        }
        routingStopped = true
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
