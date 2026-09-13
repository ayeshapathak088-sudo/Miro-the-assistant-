package com.miro.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

class VlmClient(private val preferences: SecurePreferences) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun testConnection(): String {
        val action = nextAction(
            goal = "Confirm that you are reachable by returning a finish action.",
            history = emptyList(),
            state = ScreenState(packageName = null, elements = emptyList())
        )
        check(action.action.equals("finish", ignoreCase = true) || action.taskComplete) {
            "The model responded, but did not return a valid finish action."
        }
        return action.message ?: "AI connection is working."
    }

    suspend fun nextAction(goal: String, history: List<AgentLog>, state: ScreenState): AgentAction {
        val apiKey = preferences.apiKey
        require(apiKey.isNotBlank()) { "Add an API key before running an agent task." }
        val endpoint = preferences.endpoint.ifBlank { "https://api.openai.com/v1/chat/completions" }
        val model = preferences.model.ifBlank { "gpt-4o-mini" }
        val prompt = buildPrompt(goal, history, state)
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            })
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        check(connection.responseCode in 200..299) { "VLM request failed (${connection.responseCode}): $response" }
        val completion = json.decodeFromString<ChatCompletion>(response)
        val content = completion.choices.firstOrNull()?.message?.content ?: error("VLM returned no action")
        return json.decodeFromString(stripCodeFence(content))
    }

    private fun buildPrompt(goal: String, history: List<AgentLog>, state: ScreenState): String {
        val visibleHistory = history.takeLast(8).joinToString("\n") { "${it.kind}: ${it.message}" }
        return """Goal: $goal

Recent history:
$visibleHistory

Current screen JSON:
${json.encodeToString(ScreenState.serializer(), state)}

Choose exactly one next action as JSON. Use elementId from the current screen for tap/type. For completion, set taskComplete=true and action="finish"."""
    }

    private fun stripCodeFence(value: String): String = value.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    @Serializable private data class ChatCompletion(val choices: List<Choice> = emptyList())
    @Serializable private data class Choice(val message: Message)
    @Serializable private data class Message(val content: String)

    companion object {
        private const val SYSTEM_PROMPT = """You are a cautious Android UI control agent. Return only valid JSON matching this schema: {action: tap|type|swipe|back|home|wait|finish, elementId?: string, text?: string, startX?: number, startY?: number, endX?: number, endY?: number, durationMs?: number, taskComplete?: boolean, message?: string}. Prefer semantic element ids. Never claim completion without evidence. Handle unexpected dialogs by choosing a visible action."""
    }
}
