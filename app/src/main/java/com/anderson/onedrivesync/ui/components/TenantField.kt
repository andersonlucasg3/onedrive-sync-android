package com.anderson.onedrivesync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.core.normalizeTenant

/**
 * OAuth authority tenant field, shared by onboarding and settings. Blank never survives a save:
 * the caller stores the normalized value and the field shows it back.
 */
@Composable
fun TenantField(tenant: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(tenant) }
    val focusManager = LocalFocusManager.current

    fun save() {
        if (text != tenant) {
            onSave(text)
            text = normalizeTenant(text)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus -> if (!focus.isFocused) save() },
            label = { Text(stringResource(R.string.auth_tenant_label)) },
            supportingText = { Text(stringResource(R.string.auth_tenant_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                save()
                focusManager.clearFocus()
            }),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { save() }, enabled = text != tenant) {
                Text(stringResource(R.string.auth_tenant_save))
            }
        }
    }
}
