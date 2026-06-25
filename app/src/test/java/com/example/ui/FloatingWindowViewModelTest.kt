package com.example.ui

import app.cash.turbine.test
import com.example.data.repository.RemoteControlRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingWindowViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: RemoteControlRepository
    private lateinit var viewModel: FloatingWindowViewModel

    private val scaleFlow = MutableStateFlow(0.5f)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        
        // 模拟 Repository 中的 Flow
        every { repository.floatingScaleMultiplier } returns scaleFlow
        every { repository.mirroredWidth } returns MutableStateFlow(1080)
        every { repository.mirroredHeight } returns MutableStateFlow(1920)
        
        viewModel = FloatingWindowViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test sendCommand forwards formatted string to repository`() = runTest {
        val tapCommand = "TAP:0.5,0.5"
        viewModel.sendCommand(tapCommand)
        
        coVerify { repository.sendCommand(tapCommand) }
    }

    @Test
    fun `test updateScale logic with zoom factor`() = runTest {
        // 初始 scale 是 0.5
        // 模拟手势缩放，zoom = 1.2
        viewModel.updateScale(1.2f)
        
        // 0.5 * 1.2 = 0.6
        io.mockk.verify { repository.updateFloatingScaleMultiplier(0.6f) }
    }

    @Test
    fun `test updateScale limits`() = runTest {
        // 测试上限 0.8f
        scaleFlow.value = 0.7f
        viewModel.updateScale(2.0f) // 1.4f -> should be 0.8f
        io.mockk.verify { repository.updateFloatingScaleMultiplier(0.8f) }

        // 测试下限 0.2f
        scaleFlow.value = 0.3f
        viewModel.updateScale(0.1f) // 0.03f -> should be 0.2f
        io.mockk.verify { repository.updateFloatingScaleMultiplier(0.2f) }
    }

    @Test
    fun `test requestClose emits close event`() = runTest {
        viewModel.closeEvent.test {
            viewModel.requestClose()
            awaitItem() // 验证是否收到了 Unit 事件
        }
    }
}
