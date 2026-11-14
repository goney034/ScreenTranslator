package app.screenbubble

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxWidth()) { SettingsScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    val client = remember { LlmClient() }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(prefs.provider) }
    var apiKey by remember { mutableStateOf(prefs.apiKey(provider)) }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl(provider)) }
    var model by remember { mutableStateOf(prefs.model(provider)) }
    var direction by remember { mutableStateOf(prefs.direction) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateMapOf<String, ProbeResult>() }
    val running = remember { mutableStateMapOf<String, Boolean>() }
    var shown by remember { mutableStateOf(30) }

    fun cfg() = ProviderConfig(provider, apiKey.trim(), baseUrl.trim().trimEnd('/'), model.trim())

    fun selectProvider(p: Provider) {
        provider = p
        apiKey = prefs.apiKey(p)
        baseUrl = prefs.baseUrl(p)
        model = prefs.model(p)
        models = emptyList()
        results.clear()
        listError = null
        prefs.provider = p
    }

    suspend fun probeOne(name: String) {
        running[name] = true
        try {
            results[name] = client.probe(cfg(), name)
        } catch (e: CancellationException) {
            throw e
        } finally {
            running.remove(name)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_CODE, result.resultCode)
                .putExtra(OverlayService.EXTRA_DATA, result.data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
    fun requestProjection() {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestProjection() // the service still works if notifications are denied
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyMedium)

        Text(stringResource(R.string.section_ai), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Provider.values().forEach { p ->
                FilterChip(selected = provider == p, onClick = { selectProvider(p) }, label = { Text(p.label) })
            }
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; prefs.setApiKey(provider, it) },
            label = { Text(stringResource(R.string.api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; prefs.setBaseUrl(provider, it) },
            label = { Text(stringResource(R.string.base_url)) },
            supportingText = { Text(stringResource(R.string.base_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; prefs.setModel(provider, it) },
            label = { Text(stringResource(R.string.model)) },
            supportingText = { Text(stringResource(R.string.model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.section_models), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !loading && apiKey.isNotBlank(),
                onClick = {
                    loading = true
                    listError = null
                    results.clear()
                    shown = 30
                    scope.launch {
                        try {
                            models = client.listModels(cfg())
                            if (models.isEmpty()) listError = context.getString(R.string.no_models)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: LlmException) {
                            listError = if (e.code > 0) "HTTP ${e.code}: ${e.message}" else e.message
                        } catch (e: Exception) {
                            listError = e.javaClass.simpleName
                        } finally {
                            loading = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.fetch_models)) }
            OutlinedButton(
                enabled = model.isNotBlank() && apiKey.isNotBlank() && running.isEmpty(),
                onClick = { scope.launch { probeOne(model.trim()) } },
            ) { Text(stringResource(R.string.test_current)) }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        listError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        results[model.trim()]?.let { ResultLine(it) }

        if (models.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; shown = 30 },
                label = { Text(stringResource(R.string.filter_models)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val filtered = models.filter { it.contains(query.trim(), ignoreCase = true) }
            OutlinedButton(
                enabled = running.isEmpty() && filtered.isNotEmpty(),
                onClick = {
                    val targets = filtered.take(shown)
                    scope.launch {
                        val gate = Semaphore(3)
                        coroutineScope {
                            targets.map { name -> async { gate.withPermit { probeOne(name) } } }.awaitAll()
                        }
                    }
                },
            ) { Text(stringResource(R.string.test_all)) }
            if (results.isNotEmpty()) {
                val active = results.values.count { it.ok }
                Text(
                    stringResource(R.string.summary, active, results.size - active),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            filtered.take(shown).forEach { name ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (running[name] == true) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        TextButton(enabled = running[name] != true, onClick = { scope.launch { probeOne(name) } }) {
                            Text(stringResource(R.string.test))
                        }
                        TextButton(onClick = { model = name; prefs.setModel(provider, name) }) {
                            Text(stringResource(R.string.use))
                        }
                    }
                    results[name]?.let { ResultLine(it) }
                }
            }
            if (filtered.size > shown) {
                TextButton(onClick = { shown += 30 }) { Text(stringResource(R.string.show_more, filtered.size - shown)) }
            }
        }

        Text(stringResource(R.string.section_direction), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Direction.AUTO to R.string.direction_auto,
                Direction.FA_TO_EN to R.string.direction_fa_en,
                Direction.EN_TO_FA to R.string.direction_en_fa,
            ).forEach { (d, label) ->
                FilterChip(
                    selected = direction == d,
                    onClick = { direction = d; prefs.direction = d },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Text(stringResource(R.string.section_start), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.start_hint), style = MaterialTheme.typography.bodySmall)
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                prefs.provider = provider
                if (!Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                } else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requestProjection()
                }
            },
        ) { Text(stringResource(R.string.start_bubble)) }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)) },
        ) { Text(stringResource(R.string.stop_bubble)) }
        Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultLine(result: ProbeResult) {
    Text(
        if (result.ok) stringResource(R.string.result_ok, result.millis.toInt())
        else stringResource(R.string.result_fail, result.detail),
        style = MaterialTheme.typography.bodySmall,
        color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}
