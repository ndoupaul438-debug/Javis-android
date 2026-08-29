package com.javis.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ListeningStatus {
    IDLE, LISTENING_FOR_WAKE, LISTENING_FOR_COMMAND, THINKING, SPEAKING
}

object WakeWordServiceState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _status = MutableStateFlow(ListeningStatus.IDLE)
    val status: StateFlow<ListeningStatus> = _status

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) _status.value = ListeningStatus.IDLE
    }

    fun setStatus(newStatus: ListeningStatus) {
        _status.value = newStatus
    }
}
