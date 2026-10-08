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
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.SoftReference

class CoreProxyOnlyService : Service(), ServiceControl {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopJob: Job? = null
    private var stopRequested = false
    private var stopCoreCompleted = false
    private var startAccepted = false
    private var terminalFailureMessage: String? = null

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.setNetLoopRuntimeActive(false)
        CoreServiceManager.serviceControl = SoftReference(this)
        CoreServiceManager.registerServiceControlReceiver(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")
        if (stopRequested) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Ignoring start while stop is completing")
            return START_NOT_STICKY
        }
        if (startAccepted) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Ignoring duplicate start for this service instance")
            return START_STICKY
        }
        startAccepted = true
        NotificationManager.showNotification(null)
        try {
            val config = CoreServiceManager.startCoreLoop(null)
            NotificationManager.showNotification(config)
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification()
        } catch (e: Exception) {
            terminalFailureMessage = e.message ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Proxy: core failed to start", e)
            stopService()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        val destroyCoreCompleted = runBlocking {
            CoreServiceManager.stopCoreLoopForServiceDestroy(this@CoreProxyOnlyService)
        }
        CoreServiceManager.unregisterServiceControlReceiver(this)
        val failureMessage = terminalFailureMessage
        if (failureMessage != null) {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, failureMessage)
        } else if (stopRequested && (stopCoreCompleted || destroyCoreCompleted)) {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        } else {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_NOT_RUNNING, "")
        }
        NotificationManager.cancelNotification(this)
        CoreServiceManager.clearServiceControl(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        if (stopJob?.isActive == true) return
        stopRequested = true
        stopJob = serviceScope.launch {
            stopCoreCompleted = CoreServiceManager.stopCoreLoopForServiceStop(this@CoreProxyOnlyService)
            if (!stopCoreCompleted) {
                LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to complete core stop")
            }
            stopSelf()
        }
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
