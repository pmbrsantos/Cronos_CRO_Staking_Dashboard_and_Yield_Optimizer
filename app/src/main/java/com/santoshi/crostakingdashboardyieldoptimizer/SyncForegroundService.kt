package com.santoshi.crostakingdashboardyieldoptimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.santoshi.SYNC_START"
        const val ACTION_STOP = "com.santoshi.SYNC_STOP"
        const val EXTRA_ADDRESS = "wallet_address"

        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIF_ID = 4242

        fun start(context: Context, address: String) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ADDRESS, address)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private val engine = CryptoEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SyncStateHolder.requestCancel()
                // Don't stop self yet — let the coroutine finalize and persist progress
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground(address)
                if (syncJob?.isActive != true) {
                    syncJob = scope.launch { runSync(address) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(address: String) {
        val notif = buildNotification(address, 0, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private suspend fun runSync(address: String) {
        val prefs = applicationContext.getSharedPreferences("NodePrefs", Context.MODE_PRIVATE)
        SyncStateHolder.startSync(address)

        try {
            val savedHash = prefs.getString("last_hash_$address", null)
            val savedTotal = prefs.getString("baseline_$address", "0.0")?.toDoubleOrNull() ?: 0.0
            val savedManual = prefs.getString("manual_claims_$address", "0.0")?.toDoubleOrNull() ?: 0.0
            val savedRestake = prefs.getString("auto_restake_$address", "0.0")?.toDoubleOrNull() ?: 0.0
            val savedAutoClaims = prefs.getString("auto_claims_$address", "0.0")?.toDoubleOrNull() ?: 0.0
            val savedCommissions = prefs.getString("validator_commissions_$address", "0.0")?.toDoubleOrNull() ?: 0.0

            val savedBreakdown = mapOf(
                "Manual Claims" to savedManual,
                "Auto-Restake" to savedRestake,
                "Staking Auto-Claims" to savedAutoClaims,
                "Validator Commissions" to savedCommissions
            )
            SyncStateHolder.updateBreakdown(savedBreakdown)

            val scannedTotal = engine.syncAllTimeRewards(
                address = address,
                savedHash = savedHash,
                savedTotal = savedTotal,
                savedBreakdown = savedBreakdown,
                isCancelled = { SyncStateHolder.state.value.cancelRequested },
                onSaveState = { newTotal, newHash, newBreakdown ->
                    prefs.edit()
                        .putString("baseline_$address", newTotal.toString())
                        .putString("last_hash_$address", newHash)
                        .putString("manual_claims_$address", (newBreakdown["Manual Claims"] ?: 0.0).toString())
                        .putString("auto_restake_$address", (newBreakdown["Auto-Restake"] ?: 0.0).toString())
                        .putString("auto_claims_$address", (newBreakdown["Staking Auto-Claims"] ?: 0.0).toString())
                        .putString("validator_commissions_$address", (newBreakdown["Validator Commissions"] ?: 0.0).toString())
                        .apply()
                },
                onProgress = { current, total ->
                    SyncStateHolder.updateProgress(current, total)
                    updateNotification(address, current, total)
                },
                onBreakdown = { breakdownMap ->
                    SyncStateHolder.updateBreakdown(breakdownMap)
                }
            )

            val currentDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            prefs.edit().putString("sync_date_$address", currentDate).apply()

            val finalBreakdown = mapOf(
                "Manual Claims" to (prefs.getString("manual_claims_$address", "0.0")?.toDoubleOrNull() ?: 0.0),
                "Auto-Restake" to (prefs.getString("auto_restake_$address", "0.0")?.toDoubleOrNull() ?: 0.0),
                "Staking Auto-Claims" to (prefs.getString("auto_claims_$address", "0.0")?.toDoubleOrNull() ?: 0.0),
                "Validator Commissions" to (prefs.getString("validator_commissions_$address", "0.0")?.toDoubleOrNull() ?: 0.0)
            )

            val msg = when {
                SyncStateHolder.state.value.cancelRequested -> "Sync stopped. Progress saved."
                scannedTotal == savedTotal -> "History up to date!"
                else -> "Sync complete!"
            }
            SyncStateHolder.finishSync(scannedTotal, finalBreakdown, currentDate, msg)

        } catch (e: Exception) {
            e.printStackTrace()
            SyncStateHolder.failSync("Sync error: ${e.message}")
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(address: String, current: Int, total: Int): Notification {
        val shortAddr = if (address.length > 12) "${address.take(8)}…${address.takeLast(4)}" else address

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SyncForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Syncing $shortAddr")
            .setContentText(if (total > 0) "Page $current of $total" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), current, total <= 0)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(address: String, current: Int, total: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(address, current, total))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reward Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background blockchain sync progress"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}