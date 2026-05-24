package com.gemmabridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val prefs = remember { GemmaService.settingsPrefs(context) }
    val keys = remember { KeyManager(context) }

    var engineKind by remember {
        mutableStateOf(prefs.getString(GemmaService.PREF_ENGINE, GemmaService.ENGINE_PROXY)!!)
    }
    var upstream by remember {
        mutableStateOf(prefs.getString(GemmaService.PREF_UPSTREAM, GemmaService.DEFAULT_UPSTREAM)!!)
    }
    var modelPath by remember {
        mutableStateOf(prefs.getString(GemmaService.PREF_MODEL_PATH, "")!!)
    }
    var portText by remember {
        mutableStateOf(prefs.getInt(GemmaService.PREF_PORT, GemmaService.DEFAULT_PORT).toString())
    }
    var newKeyName by remember { mutableStateOf("default") }
    var lastIssuedKey by remember { mutableStateOf<String?>(null) }
    var keyList by remember { mutableStateOf(keys.list()) }
    var serviceOn by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { keyList = keys.list() }

    // SAF picker for .task model files. MediaPipe needs a real file path, so we copy
    // the chosen file into app-private storage on first selection.
    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val dest = File(context.filesDir, "model.task")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            modelPath = dest.absolutePath
            prefs.edit().putString(GemmaService.PREF_MODEL_PATH, modelPath).apply()
            statusMessage = "Model copied to ${dest.name} (${dest.length() / (1024 * 1024)} MB)"
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Gemma Bridge") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Engine", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = engineKind == GemmaService.ENGINE_PROXY,
                    onClick = { engineKind = GemmaService.ENGINE_PROXY },
                    label = { Text("Proxy") },
                )
                FilterChip(
                    selected = engineKind == GemmaService.ENGINE_MEDIAPIPE,
                    onClick = { engineKind = GemmaService.ENGINE_MEDIAPIPE },
                    label = { Text("MediaPipe (on-device)") },
                )
            }

            if (engineKind == GemmaService.ENGINE_PROXY) {
                OutlinedTextField(
                    value = upstream,
                    onValueChange = { upstream = it },
                    label = { Text("Upstream URL (llama-server)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    if (modelPath.isEmpty()) "No model selected" else "Model: $modelPath",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                    Text("Pick .task model file")
                }
                Text(
                    "Recommended: gemma-3n-E2B-it-int4.task " +
                        "from https://huggingface.co/litert-community on Hugging Face",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Listen port") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    prefs.edit()
                        .putString(GemmaService.PREF_ENGINE, engineKind)
                        .putString(GemmaService.PREF_UPSTREAM, upstream)
                        .putString(GemmaService.PREF_MODEL_PATH, modelPath)
                        .putInt(GemmaService.PREF_PORT, portText.toIntOrNull() ?: GemmaService.DEFAULT_PORT)
                        .apply()
                    GemmaService.start(context)
                    serviceOn = true
                    statusMessage = "Running on http://127.0.0.1:$portText"
                }) { Text(if (serviceOn) "Restart" else "Start") }
                Button(onClick = {
                    GemmaService.stop(context)
                    serviceOn = false
                    statusMessage = "Stopped"
                }) { Text("Stop") }
            }
            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }

            Divider()

            Text("API keys", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = newKeyName,
                onValueChange = { newKeyName = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                lastIssuedKey = keys.create(newKeyName)
                keyList = keys.list()
            }) { Text("Generate new key") }

            lastIssuedKey?.let { k ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("New key (copy now, it won't be shown again):")
                        Spacer(Modifier.height(4.dp))
                        Text(k, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Text("Existing keys", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                items(keyList) { rec ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            rec.key.take(18) + "..." + rec.key.takeLast(4),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("name=${rec.name}", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            keys.revoke(rec.key)
                            keyList = keys.list()
                        }) { Text("Revoke") }
                    }
                }
            }
        }
    }
}
