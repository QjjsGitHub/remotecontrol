package com.example.data.repository

import com.example.data.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlRepositoryTest {

    private val repository = RemoteControlRepositoryImpl()

    @Test
    fun `test initial states are correct`() = runTest {
        assertEquals(0.3f, repository.floatingScaleMultiplier.value)
        assertEquals(ConnectionState.Disconnected, repository.clientConnectionState.value)
        assertEquals(false, repository.isFloatingWindowRunning.value)
    }

    @Test
    fun `test updating floating window running state`() = runTest {
        repository.setFloatingWindowRunning(true)
        assertEquals(true, repository.isFloatingWindowRunning.value)
        
        repository.setFloatingWindowRunning(false)
        assertEquals(false, repository.isFloatingWindowRunning.value)
    }

    @Test
    fun `test scale multiplier updates`() = runTest {
        repository.updateFloatingScaleMultiplier(0.5f)
        assertEquals(0.5f, repository.floatingScaleMultiplier.value)
        
        repository.updateFloatingScaleMultiplier(0.8f)
        assertEquals(0.8f, repository.floatingScaleMultiplier.value)
    }

    @Test
    fun `test manual ip field updates`() = runTest {
        val testIp = "192.168.1.100"
        repository.setManualIp(testIp)
        assertEquals(testIp, repository.manualIpField.value)
    }
}
