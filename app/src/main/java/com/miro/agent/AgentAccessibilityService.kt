package com.miro.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class AgentAccessibilityService : AccessibilityService() {
    private val nodeIds = AtomicInteger(0)

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun inspectScreen(): ScreenState {
        val elements = buildList {
            windows.forEach { window ->
                window.root?.let { root -> collectNodes(root, this) }
            }
        }
        return ScreenState(
            packageName = rootInActiveWindow?.packageName?.toString(),
            elements = elements
        )
    }

    private fun collectNodes(node: AccessibilityNodeInfo, output: MutableList<UiElement>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (bounds.width() > 0 && bounds.height() > 0 && (text != null || description != null || node.isClickable || node.isEditable)) {
            output += UiElement(
                id = "element-${nodeIds.incrementAndGet()}",
                className = node.className?.toString().orEmpty(),
                text = text,
                contentDescription = description,
                bounds = RectData(bounds.left, bounds.top, bounds.right, bounds.bottom),
                clickable = node.isClickable,
                editable = node.isEditable,
                enabled = node.isEnabled
            )
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let { collectNodes(it, output) }
    }

    fun click(element: UiElement): Boolean {
        return findNodeAt(element.bounds)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun setText(element: UiElement, value: String): Boolean {
        val node = findNodeAt(element.bounds) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 5_000)))
                .build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { continuation.resume(false) }
            }, null)
        }

    suspend fun captureScreenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    continuation.resume(bitmap?.toBase64())
                    bitmap?.recycle()
                }
                override fun onFailure(errorCode: Int) { continuation.resume(null) }
            })
        }
    }

    private fun findNodeAt(bounds: RectData): AccessibilityNodeInfo? {
        val centerX = (bounds.left + bounds.right) / 2
        val centerY = (bounds.top + bounds.bottom) / 2
        return windows.asSequence().mapNotNull { it.root }.mapNotNull { findNodeAt(it, centerX, centerY) }.firstOrNull()
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            findNodeAt(child, x, y)?.let { return it }
        }
        return node.takeIf { bounds.contains(x, y) && (it.isClickable || it.isEditable) }
    }

    private fun Bitmap.toBase64(): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG,  seventyPercent, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    companion object {
        private const val seventyPercent = 70
        @Volatile var current: AgentAccessibilityService? = null
            private set
    }
}
