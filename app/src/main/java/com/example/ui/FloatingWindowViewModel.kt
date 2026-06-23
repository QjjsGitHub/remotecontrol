package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.LogType
import com.example.data.repository.RemoteControlRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FloatingWindowViewModel @Inject constructor(
    private val repository: RemoteControlRepository
) : ViewModel() {

    // 观察数据层状态
    val hasFrameReceived = repository.hasFrameReceived
    val mirroredWidth = repository.mirroredWidth
    val mirroredHeight = repository.mirroredHeight
    val scaleMultiplier = repository.floatingScaleMultiplier
    val connectionState = repository.clientConnectionState
    val videoFrames = repository.videoFrames

    // UI 事件流：通知 Service 关闭
    private val _closeEvent = MutableSharedFlow<Unit>()
    val closeEvent = _closeEvent.asSharedFlow()

    fun updateScale(zoom: Float) {
        val newScale = (scaleMultiplier.value * zoom).coerceIn(0.2f, 0.8f)
        repository.updateFloatingScaleMultiplier(newScale)
    }

    fun sendCommand(command: String) {
        viewModelScope.launch {
            repository.sendCommand(command)
        }
    }

    fun requestClose() {
        viewModelScope.launch {
            _closeEvent.emit(Unit)
        }
    }

    fun addLog(message: String, type: LogType = LogType.WARNING) {
        repository.addClientLog(message, type)
    }
}
