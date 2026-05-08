package com.santoshi.crostakingdashboardyieldoptimizer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SyncStateHolder {
    data class SyncState(
        val isSyncing: Boolean = false,
        val walletAddress: String = "",
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val progress: Float = 0f,
        val baselineClaimed: Double = 0.0,
        val breakdown: Map<String, Double> = emptyMap(),
        val lastSyncDate: String = "",
        val message: String? = null,        // toast-style one-shot message
        val cancelRequested: Boolean = false
    )

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun startSync(address: String) {
        _state.value = _state.value.copy(
            isSyncing = true,
            walletAddress = address,
            currentPage = 0,
            totalPages = 1,
            progress = 0f,
            cancelRequested = false,
            message = null
        )
    }

    fun updateProgress(current: Int, total: Int) {
        _state.value = _state.value.copy(
            currentPage = current,
            totalPages = total,
            progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
        )
    }

    fun updateBreakdown(breakdown: Map<String, Double>) {
        _state.value = _state.value.copy(breakdown = breakdown)
    }

    fun finishSync(baseline: Double, breakdown: Map<String, Double>, date: String, message: String?) {
        _state.value = _state.value.copy(
            isSyncing = false,
            baselineClaimed = baseline,
            breakdown = breakdown,
            lastSyncDate = date,
            message = message,
            cancelRequested = false
        )
    }

    fun failSync(message: String) {
        _state.value = _state.value.copy(
            isSyncing = false,
            message = message,
            cancelRequested = false
        )
    }

    fun requestCancel() {
        _state.value = _state.value.copy(cancelRequested = true)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun postMessage(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }
}