package com.silf.app.ui.chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    @Before
    fun setup() { Dispatchers.setMain(testDispatcher) }
    @After
    fun tearDown() { Dispatchers.resetMain() }
    @Test
    fun `initial state is correct`() = runTest {
        val viewModel = ChatViewModel()
        val state = viewModel.uiState.value
        assertEquals("", state.currentInput)
        assertEquals(1, state.messages.size)
        assertEquals("Hola, soy Silf", state.messages[0].text)
        assertEquals(false, state.messages[0].isFromUser)
    }
    @Test
    fun `OnMessageChange updates currentInput`() = runTest {
        val viewModel = ChatViewModel()
        viewModel.onEvent(ChatEvent.OnMessageChange("Hello"))
        val state = viewModel.uiState.value
        assertEquals("Hello", state.currentInput)
    }
    @Test
    fun `OnSendMessage with non-blank text adds message and clears input`() = runTest {
        val viewModel = ChatViewModel()
        viewModel.onEvent(ChatEvent.OnMessageChange("Hello"))
        viewModel.onEvent(ChatEvent.OnSendMessage)
        val state = viewModel.uiState.value
        assertEquals("", state.currentInput)
        assertEquals(2, state.messages.size)
        assertEquals("Hello", state.messages[1].text)
        assertEquals(true, state.messages[1].isFromUser)
    }
    @Test
    fun `OnSendMessage with blank text does not add message`() = runTest {
        val viewModel = ChatViewModel()
        viewModel.onEvent(ChatEvent.OnMessageChange("   "))
        viewModel.onEvent(ChatEvent.OnSendMessage)
        val state = viewModel.uiState.value
        assertEquals("   ", state.currentInput)
        assertEquals(1, state.messages.size)
    }
}
