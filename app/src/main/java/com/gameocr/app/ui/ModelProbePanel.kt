package com.gameocr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.translate.ApiDetection
import com.gameocr.app.translate.ApiKind
import com.gameocr.app.translate.ModelProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val PAGE_SIZE = 30
private const val PARALLEL_PROBES = 3

/**
 * Detects which API style (OpenAI / Anthropic) the entered endpoint speaks, lists every model it
 * exposes and lets the user test any of them — or any hand-typed model name — with a real request.
 * Nothing is limited to a preset list. State is ephemeral and keyed to the tested inputs.
 */
@Composable
internal fun ModelProbePanel(
    inputKey: Any,
    onDetect: suspend () -> ApiDetection,
    onProbe: suspend (ApiKind, String) -> ModelProbeResult,
    onUse: (ApiKind, String) -> Unit,
) {
    key(inputKey) {
        val scope = rememberCoroutineScope()
        var detecting by remember { mutableStateOf(false) }
        var detection by remember { mutableStateOf<ApiDetection?>(null) }
        var query by remember { mutableStateOf("") }
        var shown by remember { mutableStateOf(PAGE_SIZE) }
        val results = remember { mutableStateMapOf<String, ModelProbeResult>() }
        val running = remember { mutableStateMapOf<String, Boolean>() }

        suspend fun probeOne(kind: ApiKind, model: String) {
            running[model] = true
            try {
                results[model] = onProbe(kind, model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                results[model] = ModelProbeResult(false, 0L, error.javaClass.simpleName)
            } finally {
                running.remove(model)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = !detecting,
                    onClick = {
                        detecting = true
                        detection = null
                        results.clear()
                        shown = PAGE_SIZE
                        scope.launch {
                            try {
                                detection = onDetect()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                detection = ApiDetection(null, emptyList(), error.javaClass.simpleName)
                            } finally {
                                detecting = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (detecting) R.string.model_probe_detecting else R.string.model_probe_detect))
                }
                if (detecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            val found = detection
            if (found != null && found.kind == null) {
                Text(
                    stringResource(R.string.model_probe_not_detected_format, found.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (found != null && found.kind != null) {
                val kind: ApiKind = found.kind
                val kindName = stringResource(
                    if (kind == ApiKind.ANTHROPIC) R.string.model_probe_kind_anthropic
                    else R.string.model_probe_kind_openai,
                )
                Text(
                    stringResource(R.string.model_probe_detected_format, kindName, found.models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; shown = PAGE_SIZE },
                    label = { Text(stringResource(R.string.model_probe_filter_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                val needle = query.trim()
                val filtered = found.models.filter { it.contains(needle, ignoreCase = true) }
                // Any model name works, even one the endpoint does not list.
                val custom = needle.takeIf { it.isNotEmpty() && it !in found.models }
                val rows = listOfNotNull(custom) + filtered
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = rows.isNotEmpty() && running.isEmpty(),
                        onClick = {
                            val targets = rows.take(shown)
                            scope.launch {
                                val gate = Semaphore(PARALLEL_PROBES)
                                coroutineScope {
                                    targets.map { model ->
                                        async { gate.withPermit { probeOne(kind, model) } }
                                    }.awaitAll()
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.model_probe_test_all)) }
                }
                val active = results.values.count { it.active }
                val failed = results.size - active
                if (results.isNotEmpty()) {
                    Text(
                        stringResource(R.string.model_probe_summary_format, active, failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.take(shown).forEach { model ->
                    val result = results[model]
                    val busy = running[model] == true
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                model,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            TextButton(enabled = !busy, onClick = { scope.launch { probeOne(kind, model) } }) {
                                Text(stringResource(R.string.model_probe_test))
                            }
                            TextButton(onClick = { onUse(kind, model) }) {
                                Text(stringResource(R.string.model_probe_use))
                            }
                        }
                        if (result != null) {
                            Text(
                                if (result.active) {
                                    stringResource(R.string.model_probe_active_format, result.latencyMs.toInt())
                                } else {
                                    stringResource(R.string.model_probe_failed_format, result.detail)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (rows.size > shown) {
                    TextButton(onClick = { shown += PAGE_SIZE }) {
                        Text(stringResource(R.string.model_probe_show_more, rows.size - shown))
                    }
                }
            }
        }
    }
}

/** Settings wiring: probes the endpoint typed in the active engine's fields (saved or not). */
@Composable
internal fun ModelProbeSection(
    viewModel: SettingsViewModel,
    baseUrl: String,
    apiKey: String,
    timeoutSeconds: Int,
    onUse: (ApiKind, String) -> Unit,
) {
    ModelProbePanel(
        inputKey = Triple(baseUrl, apiKey, timeoutSeconds),
        onDetect = { viewModel.detectApi(baseUrl, apiKey, timeoutSeconds) },
        onProbe = { kind, model -> viewModel.probeModel(kind, baseUrl, apiKey, model, timeoutSeconds) },
        onUse = onUse,
    )
}
