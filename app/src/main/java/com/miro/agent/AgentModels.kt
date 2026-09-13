package com.miro.agent

import kotlinx.serialization.Serializable

@Serializable
data class UiElement(
    val id: String,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: RectData,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean
)

@Serializable
data class RectData(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class ScreenState(
    val packageName: String?,
    val elements: List<UiElement>,
    val screenshotBase64: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
)

@Serializable
data class AgentAction(
    val action: String,
    val elementId: String? = null,
    val text: String? = null,
    val startX: Float? = null,
    val startY: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long? = null,
    val taskComplete: Boolean = false,
    val message: String? = null
)

data class AgentLog(
    val timestamp: Long = System.currentTimeMillis(),
    val kind: LogKind,
    val message: String,
    val screenshotBase64: String? = null
)

enum class LogKind { INFO, ACTION, ERROR, SUCCESS }
