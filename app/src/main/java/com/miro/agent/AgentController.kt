package com.miro.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentController(
    context: Context,
    private val onMessage: (String, LogKind) -> Unit = { _, _ -> }
) {
    private val client = VlmClient(SecurePreferences(context))
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _logs = MutableStateFlow<List<AgentLog>>(emptyList())
    val logs: StateFlow<List<AgentLog>> = _logs.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var job: Job? = null

    fun start(goal: String) {
        job?.cancel()
        job = scope.launch {
            _running.value = true
            append(LogKind.INFO, "Starting: $goal")
            try {
                runLoop(goal)
            } catch (error: Exception) {
                append(LogKind.ERROR, error.message ?: "Agent stopped unexpectedly")
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        _running.value = false
        append(LogKind.INFO, "Stopped by user")
    }

    private suspend fun runLoop(goal: String) {
        repeat(MAX_STEPS) { step ->
            val service = AgentAccessibilityService.current ?: error("Enable Miro Agent in Accessibility settings first.")
            val layout = service.inspectScreen()
            val state = layout.copy(screenshotBase64 = service.captureScreenshot())
            append(LogKind.INFO, "Step ${step + 1}: inspected ${state.elements.size} visible elements", state.screenshotBase64)
            val action = client.nextAction(goal, _logs.value, state)
            append(LogKind.ACTION, action.message ?: action.action)
            execute(service, state, action)
            if (action.taskComplete || action.action == "finish") {
                append(LogKind.SUCCESS, action.message ?: "Task complete")
                return
            }
            delay(1_700)
        }
        error("Stopped after $MAX_STEPS steps to prevent an uncontrolled loop.")
    }

    private suspend fun execute(service: AgentAccessibilityService, state: ScreenState, action: AgentAction) {
        when (action.action.lowercase()) {
            "tap" -> state.elements.firstOrNull { it.id == action.elementId }?.let { check(service.click(it)) { "Tap failed for ${it.id}" } }
                ?: error("Tap target not found")
            "type" -> state.elements.firstOrNull { it.id == action.elementId }?.let { check(service.setText(it, action.text.orEmpty())) { "Text entry failed" } }
                ?: error("Type target not found")
            "swipe" -> check(service.swipe(action.startX ?: error("startX missing"), action.startY ?: error("startY missing"), action.endX ?: error("endX missing"), action.endY ?: error("endY missing"), action.durationMs ?: 500)) { "Swipe was cancelled" }
            "back" -> check(service.globalBack()) { "Back action failed" }
            "home" -> check(service.globalHome()) { "Home action failed" }
            "wait", "finish" -> Unit
            else -> error("Unsupported action: ${action.action}")
        }
    }

    private fun append(kind: LogKind, message: String, screenshot: String? = null) {
        _logs.value = (_logs.value + AgentLog(kind = kind, message = message, screenshotBase64 = screenshot)).takeLast(100)
        onMessage(message, kind)
    }

    companion object { private const val MAX_STEPS = 30 }
}
