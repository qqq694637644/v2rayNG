package com.v2ray.ang.netloop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NetLoopPluginManager(
    context: Context,
    private val onUnexpectedDisconnect: (() -> Unit)? = null,
) {
    companion object {
        const val PACKAGE_NAME = "com.libzt.netloop"
        const val SERVICE_NAME = "com.libzt.netloop.NetLoopControlService"
        const val CONTROL_API_VERSION = 1

        private const val COMMAND_START = 1
        private const val COMMAND_STOP = 2
        private const val COMMAND_GET_STATUS = 3
        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val START_TIMEOUT_MS = 125_000L
        private const val STATUS_POLL_MS = 250L

        fun isInstalled(context: Context): Boolean {
            return try {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        suspend fun queryStatus(context: Context): Status {
            val manager = NetLoopPluginManager(context)
            return try {
                manager.getStatus()
            } finally {
                manager.detach()
            }
        }
    }

    enum class State {
        STOPPED,
        STARTING,
        READY,
        ERROR,
    }

    data class Status(
        val apiVersion: Int,
        val state: State,
        val nodeId: String?,
        val primaryOverlayAddress: String?,
        val lastError: String?,
    )

    private val appContext = context.applicationContext
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val bindingLock = Any()

    @Volatile
    private var remote: Messenger? = null

    @Volatile
    private var bound = false

    @Volatile
    private var expectedRunning = false

    @Volatile
    private var suppressDisconnect = false

    private var bindDeferred: CompletableDeferred<Messenger>? = null

    private val replyMessenger = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                pending.remove(msg.arg1)?.complete(msg.data)
            }
        }
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                failBinding("NetLoop control service returned a null binder.")
                return
            }

            val messenger = Messenger(service)
            synchronized(bindingLock) {
                if (!bound) return
                remote = messenger
                bindDeferred?.complete(messenger)
                bindDeferred = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleDisconnected("NetLoop control service disconnected.")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleDisconnected("NetLoop control service binding died.")
        }

        override fun onNullBinding(name: ComponentName?) {
            failBinding("NetLoop control service rejected the binding.")
        }
    }

    suspend fun startAndWaitReady(config: NetLoopSettings.Config): Status {
        require(isInstalled(appContext)) {
            "NetLoop plugin is not installed."
        }

        val initial = getStatus()
        requireApiVersion(initial)

        var status = start(config)
        if (status.state == State.READY) {
            expectedRunning = true
            return status
        }

        if (status.state == State.ERROR) {
            throw IllegalStateException(status.lastError ?: "NetLoop startup failed.")
        }

        status = withTimeout(START_TIMEOUT_MS) {
            var current = status
            while (current.state == State.STARTING) {
                delay(STATUS_POLL_MS)
                current = getStatus()
            }
            current
        }

        if (status.state != State.READY) {
            throw IllegalStateException(status.lastError ?: "NetLoop startup failed.")
        }

        expectedRunning = true
        return status
    }

    suspend fun rebindAndWaitReady(config: NetLoopSettings.Config): Status {
        detach()
        return startAndWaitReady(config)
    }

    suspend fun getStatus(): Status {
        val data = request(COMMAND_GET_STATUS, Bundle())
        requireOk(data)
        return parseStatus(data).also(::requireApiVersion)
    }

    fun stop() {
        expectedRunning = false
        suppressDisconnect = true
        try {
            remote?.send(Message.obtain(null, COMMAND_STOP))
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetLoop STOP failed: ${e.message}")
        } finally {
            detachInternal()
            suppressDisconnect = false
        }
    }

    fun detach() {
        expectedRunning = false
        suppressDisconnect = true
        try {
            detachInternal()
        } finally {
            suppressDisconnect = false
        }
    }

    private suspend fun start(config: NetLoopSettings.Config): Status {
        val data = Bundle().apply {
            putInt("api_version", CONTROL_API_VERSION)
            putString("network_id", config.networkId)
            putString("default_exit", config.defaultExit)
            putStringArrayList("peers", ArrayList(config.peers))
        }
        val response = request(COMMAND_START, data)
        requireOk(response)
        return parseStatus(response).also(::requireApiVersion)
    }

    private suspend fun request(command: Int, data: Bundle): Bundle {
        val target = connect()
        val requestId = requestIds.getAndIncrement().let {
            if (it > 0) it else 1
        }
        val deferred = CompletableDeferred<Bundle>()
        pending[requestId] = deferred

        val message = Message.obtain(null, command).apply {
            arg1 = requestId
            this.data = data
            replyTo = replyMessenger
        }

        try {
            target.send(message)
            return withTimeout(REQUEST_TIMEOUT_MS) {
                deferred.await()
            }
        } finally {
            pending.remove(requestId)
            message.recycle()
        }
    }

    private suspend fun connect(): Messenger {
        remote?.let { return it }

        val deferred = synchronized(bindingLock) {
            remote?.let { return it }
            bindDeferred ?: CompletableDeferred<Messenger>().also {
                bindDeferred = it
            }
        }

        if (!bound) {
            withContext(Dispatchers.Main.immediate) {
                if (!bound) {
                    val intent = Intent().apply {
                        component = ComponentName(PACKAGE_NAME, SERVICE_NAME)
                    }
                    val accepted = try {
                        appContext.bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    } catch (e: Exception) {
                        synchronized(bindingLock) {
                            bindDeferred = null
                        }
                        throw e
                    }
                    if (!accepted) {
                        synchronized(bindingLock) {
                            bindDeferred = null
                        }
                        throw IllegalStateException("Unable to bind NetLoop control service.")
                    }
                    bound = true
                }
            }
        }

        return withTimeout(REQUEST_TIMEOUT_MS) {
            deferred.await()
        }
    }

    private fun handleDisconnected(reason: String) {
        val shouldNotify: Boolean
        synchronized(bindingLock) {
            remote = null
            bindDeferred = CompletableDeferred()
            failPending(reason)
            shouldNotify = expectedRunning && !suppressDisconnect
        }
        if (shouldNotify) {
            onUnexpectedDisconnect?.invoke()
        }
    }

    private fun failBinding(reason: String) {
        val shouldUnbind = synchronized(bindingLock) {
            val wasBound = bound
            bound = false
            remote = null
            bindDeferred?.completeExceptionally(IllegalStateException(reason))
            bindDeferred = null
            failPending(reason)
            wasBound
        }
        if (shouldUnbind) {
            try {
                appContext.unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun failPending(reason: String) {
        pending.values.forEach {
            it.completeExceptionally(IllegalStateException(reason))
        }
        pending.clear()
    }

    private fun detachInternal() {
        synchronized(bindingLock) {
            if (bound) {
                try {
                    appContext.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                }
            }
            bound = false
            remote = null
            bindDeferred = null
            failPending("NetLoop control service binding closed.")
        }
    }

    private fun requireOk(data: Bundle) {
        if (data.getBoolean("ok", false)) return
        throw IllegalStateException(
            data.getString("error")
                ?: data.getString("last_error")
                ?: "NetLoop control request failed."
        )
    }

    private fun parseStatus(data: Bundle): Status {
        val state = try {
            State.valueOf(data.getString("state") ?: "ERROR")
        } catch (_: IllegalArgumentException) {
            State.ERROR
        }
        return Status(
            apiVersion = data.getInt("api_version", -1),
            state = state,
            nodeId = data.getString("node_id")?.takeIf { it.isNotBlank() },
            primaryOverlayAddress = data.getString("primary_overlay_address")
                ?.takeIf { it.isNotBlank() },
            lastError = data.getString("last_error")?.takeIf { it.isNotBlank() },
        )
    }

    private fun requireApiVersion(status: Status) {
        require(status.apiVersion == CONTROL_API_VERSION) {
            "NetLoop control API version mismatch."
        }
    }
}
