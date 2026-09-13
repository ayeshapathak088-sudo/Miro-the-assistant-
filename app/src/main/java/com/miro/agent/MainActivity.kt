package com.miro.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) startVoiceAssistant()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentApp(
                context = this,
                onStartVoice = { startVoiceAssistant() },
                onStopVoice = { stopService(Intent(this, VoiceWakeService::class.java)) }
            )
        }
    }

    private fun startVoiceAssistant() {
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            audioPermissionLauncher.launch(permissions.toTypedArray())
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, VoiceWakeService::class.java))
    }
}

@Composable
private fun AgentApp(context: Context, onStartVoice: () -> Unit, onStopVoice: () -> Unit) {
    val controller = remember { AgentController(context.applicationContext) }
    val securePreferences = remember { SecurePreferences(context.applicationContext) }
    val logs by controller.logs.collectAsStateWithLifecycle()
    val running by controller.running.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(securePreferences.apiKey) }
    var endpoint by remember { mutableStateOf(securePreferences.endpoint) }
    var model by remember { mutableStateOf(securePreferences.model) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var projectionGranted by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) goal = spoken
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        projectionGranted = result.resultCode == Activity.RESULT_OK
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Miro Agent") }) }) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Give the agent a task", style = MaterialTheme.typography.headlineSmall)
                    Text("It will inspect the visible app, choose one action at a time, and report each step.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Natural language task") },
                        trailingIcon = {
                            IconButton(onClick = {
                                speechLauncher.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                })
                            }) { Icon(Icons.Default.Mic, "Speak task") }
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = goal.isNotBlank() && !running, onClick = { controller.start(goal) }) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Run agent")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = running, onClick = controller::stop) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }
                }
                item {
                    PermissionCard(
                        accessibilityEnabled = accessibilityEnabled,
                        overlayEnabled = overlayEnabled,
                        projectionGranted = projectionGranted,
                        onAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOverlay = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
                        onProjection = { projectionLauncher.launch((context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()) },
                        onRefresh = {
                            accessibilityEnabled = isAccessibilityEnabled(context)
                            overlayEnabled = Settings.canDrawOverlays(context)
                        },
                        onStartOverlay = {
                            if (overlayEnabled) context.startForegroundService(Intent(context, AgentOverlayService::class.java))
                        }
                    )
                }
                item {
                    Text("Background voice", style = MaterialTheme.typography.titleLarge)
                    Text("Keep Miro listening after this screen is closed. Say wake up, hey, or hello followed by a task.", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        Button(onClick = onStartVoice) { Text("Start listening") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onStopVoice) { Text("Stop listening") }
                    }
                }
                item {
                    Text("Provider", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key (encrypted on device)") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI-compatible endpoint") })
                    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") })
                    Button(onClick = { securePreferences.apiKey = apiKey; securePreferences.endpoint = endpoint; securePreferences.model = model }) { Text("Save provider settings") }
                }
                item { Text("Execution log", style = MaterialTheme.typography.titleLarge) }
                if (logs.isEmpty()) item { Text("No runs yet.", style = MaterialTheme.typography.bodyMedium) }
                items(logs.reversed()) { log ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(log.kind.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(log.message)
                        if (log.screenshotBase64 != null) Text("Screenshot attached", style = MaterialTheme.typography.labelSmall)
                    } }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    projectionGranted: Boolean,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onProjection: () -> Unit,
    onRefresh: () -> Unit,
    onStartOverlay: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Permissions", style = MaterialTheme.typography.titleLarge)
        PermissionRow("Accessibility control", accessibilityEnabled, onAccessibility)
        PermissionRow("Screen capture consent", projectionGranted, onProjection)
        PermissionRow("Display over other apps", overlayEnabled, onOverlay)
        Row {
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = overlayEnabled, onClick = onStartOverlay) { Text("Show floating trigger") }
        }
    } }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (granted) "OK  $label" else "Required  $label")
        if (!granted) OutlinedButton(onClick = action) { Text("Open") }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name
    }
}
