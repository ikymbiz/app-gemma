package com.gemmabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the embedded Ktor API server alive while the user is
 * elsewhere (a browser, another app, the home screen).
 *
 * Engine selection (chosen in settings):
 *  - `engine = proxy`: forward to a separate OpenAI-compatible HTTP server (default:
 *    http://127.0.0.1:8081). Useful for testing with a llama-server running in Termux.
 *  - `engine = mediapipe`: on-device inference via Google's MediaPipe LLM Inference API
 *    using a user-supplied `.task` file.
 */
class GemmaService : Service() {

    private var apiServer: ApiServer? = null
    private var proxy: ProxyEngine? = null
    private var native: MediaPipeEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()

        val prefs = settingsPrefs(this)
        val port = prefs.getInt(PREF_PORT, DEFAULT_PORT)
        val engineKind = prefs.getString(PREF_ENGINE, ENGINE_PROXY) ?: ENGINE_PROXY

        val engine: LlamaEngine = when (engineKind) {
            ENGINE_MEDIAPIPE -> {
                val modelPath = prefs.getString(PREF_MODEL_PATH, "") ?: ""
                require(modelPath.isNotEmpty()) {
                    "MediaPipe engine selected but no model path is configured."
                }
                MediaPipeEngine(applicationContext, modelPath).also { native = it }
            }
            else -> {
                val upstream = prefs.getString(PREF_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM
                ProxyEngine(upstream).also { proxy = it }
            }
        }

        apiServer = ApiServer(engine, KeyManager(this), port).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        apiServer?.stop()
        proxy?.close()
        native?.close()
        super.onDestroy()
    }

    private fun startInForeground() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gemma Bridge",
                NotificationManager.IMPORTANCE_LOW,
            )
            mgr.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Gemma Bridge running")
                    .setContentText("Local API on 127.0.0.1")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentIntent(openIntent)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Gemma Bridge running")
                    .setContentText("Local API on 127.0.0.1")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentIntent(openIntent)
                    .setOngoing(true)
                    .build()
            }

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "gemma_bridge"
        private const val NOTIFICATION_ID = 1

        const val PREFS_NAME = "settings"
        const val PREF_PORT = "port"
        const val PREF_ENGINE = "engine"
        const val PREF_UPSTREAM = "upstream_url"
        const val PREF_MODEL_PATH = "model_path"

        const val ENGINE_PROXY = "proxy"
        const val ENGINE_MEDIAPIPE = "mediapipe"

        const val DEFAULT_UPSTREAM = "http://127.0.0.1:8081"
        const val DEFAULT_PORT = ApiServer.DEFAULT_PORT

        fun settingsPrefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun start(ctx: Context) {
            val intent = Intent(ctx, GemmaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GemmaService::class.java))
        }
    }
}
