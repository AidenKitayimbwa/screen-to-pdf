package com.aiden.screentopdf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiden.screentopdf.R
import com.aiden.screentopdf.ai.ApiKeyStore

/** Lets the user save or remove their Claude API key and pick the model. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    // The key that is saved on the phone right now, or null.
    var savedKey by remember { mutableStateOf(readSavedKey()) }
    // What the user has typed. Starts as the saved key so they can see it is there.
    var keyText by remember { mutableStateOf(savedKey.orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(readModel()) }

    val isSaved = savedKey != null && keyText == savedKey

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_key_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_key_label)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (showKey) R.string.settings_key_hide else R.string.settings_key_show,
                        ),
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSaved) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = stringResource(
                    if (isSaved) R.string.settings_key_saved else R.string.settings_key_not_saved,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSaved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = {
                    val key = keyText.trim()
                    if (key.isNotEmpty()) {
                        ApiKeyStore.setKey(key)
                        savedKey = key
                        keyText = key
                    }
                },
                enabled = keyText.isNotBlank() && !isSaved,
            ) {
                Text(stringResource(R.string.settings_key_save))
            }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(
                onClick = {
                    ApiKeyStore.setKey(null)
                    savedKey = null
                    keyText = ""
                },
                enabled = savedKey != null,
            ) {
                Text(stringResource(R.string.settings_key_remove))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_key_where),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.settings_model_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        ModelOption(
            label = stringResource(R.string.settings_model_sonnet),
            selected = model == ApiKeyStore.MODEL_SONNET,
            onSelect = {
                model = ApiKeyStore.MODEL_SONNET
                ApiKeyStore.setModel(ApiKeyStore.MODEL_SONNET)
            },
        )
        ModelOption(
            label = stringResource(R.string.settings_model_haiku),
            selected = model == ApiKeyStore.MODEL_HAIKU,
            onSelect = {
                model = ApiKeyStore.MODEL_HAIKU
                ApiKeyStore.setModel(ApiKeyStore.MODEL_HAIKU)
            },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun readSavedKey(): String? =
    try {
        ApiKeyStore.getKey()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

private fun readModel(): String =
    try {
        ApiKeyStore.getModel()
    } catch (e: Exception) {
        ApiKeyStore.MODEL_SONNET
    }
