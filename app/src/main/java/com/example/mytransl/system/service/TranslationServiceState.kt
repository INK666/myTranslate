package com.example.mytransl.system.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TranslationServiceState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun setRunning(running: Boolean) {
        _running.value = running
    }
}

