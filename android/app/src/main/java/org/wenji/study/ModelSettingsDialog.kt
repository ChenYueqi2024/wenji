package org.wenji.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

@Composable
internal fun ModelSettingsDialog(
    initial: ModelSettingsForm,
    saving: Boolean = false,
    error: String? = null,
    onSave: (ModelSettingsForm) -> Unit,
    onUseDemo: () -> Unit,
    onDismiss: () -> Unit,
) {
    var protocol by remember { mutableStateOf(initial.protocol) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    // Never use rememberSaveable or prefill the saved key. Closing/rotating discards an unsaved key.
    var apiKey by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf(initial.maxOutputTokens.toString()) }
    var choosingProtocol by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("手机模型设置", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("学习资料和记录保存在手机。需要 AI 时，手机直接联网调用你配置的模型服务。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("每次调用前单独确认，只发送本次问题及检索出的资料片段（含来源定位）。配置完成不代表调用已验证。",
                    style = MaterialTheme.typography.bodySmall, color = WenjiColors.Muted)
                Box {
                    OutlinedButton(onClick = { choosingProtocol = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text(modelProtocols[protocol] ?: "选择协议")
                    }
                    DropdownMenu(expanded = choosingProtocol, onDismissRequest = { choosingProtocol = false }) {
                        modelProtocols.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { protocol = value; choosingProtocol = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it }, enabled = !saving,
                    label = { Text("API 基址") }, placeholder = { Text("https://api.example.com/v1") },
                    supportingText = { Text("填写服务方提供的 API 基址；更换地址需重新输入密钥。") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, enabled = !saving,
                    label = { Text("模型名称") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, enabled = !saving,
                    label = { Text("个人 API Key") },
                    placeholder = { Text(if (initial.hasSavedKey) "已保存密钥，留空保留" else "请输入你的个人密钥") },
                    supportingText = { Text("密钥由 Android Keystore 加密保存在本机，不包含在安装包中。") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxTokens, onValueChange = { maxTokens = it.filter(Char::isDigit).take(5) }, enabled = !saving,
                    label = { Text("最大输出 Token") }, supportingText = { Text("1–16384；默认 2048。请求超时为 60 秒。") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                (validationError ?: error)?.takeIf { it.isNotBlank() }?.let { Notice(it, Tone.Red) }
                TextButton(onClick = onUseDemo, enabled = !saving) { Text("清除密钥并使用规则演示") }
                Text("规则演示无需密钥或网络，仅用于固定案例；已有学习资料和记录会保留。",
                    style = MaterialTheme.typography.bodySmall, color = WenjiColors.Muted)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val form = ModelSettingsForm(protocol, baseUrl, model, apiKey, maxTokens.toIntOrNull() ?: 0, initial.hasSavedKey)
                try {
                    validateModelSettings(form)
                    if (apiKey.isBlank() && !initial.hasSavedKey) validateModelKey(apiKey)
                    if (apiKey.isEmpty() && initial.hasSavedKey) validateSavedKeyDestination(baseUrl, initial.baseUrl)
                    validationError = null
                    onSave(form)
                } catch (failure: ModelSettingsException) { validationError = failure.message }
            }) { Text(if (saving) "正在保存…" else "保存设置") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
