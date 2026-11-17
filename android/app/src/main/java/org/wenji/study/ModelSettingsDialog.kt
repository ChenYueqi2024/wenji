package org.wenji.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable internal fun ModelCapabilityInfo(modelName:String) {
    val capability = remember(modelName) { modelCapabilities(modelName) }
    val uriHandler = LocalUriHandler.current
    var sourceError by remember(modelName) { mutableStateOf(false) }
    var showDetails by rememberSaveable(modelName) { mutableStateOf(false) }
    Surface(color=WenjiColors.Field,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(when(capability?.matchKind) {
                ModelCapabilityMatchKind.VERSION_CANDIDATE -> "官方版本候选参考"
                ModelCapabilityMatchKind.ALIAS_CANDIDATE -> "第三方别名推荐参考"
                else -> "型号能力参考"
            },style=MaterialTheme.typography.titleSmall)
            if(capability==null) {
                Text(if(modelName.isBlank()) "填写模型名后显示能力参考。"
                    else "暂无可靠匹配，参数请以服务商说明为准。",
                    style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Muted)
                Text("问迹当前仅发送文本。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            } else {
                val numbers=remember { java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA) }
                Text("官方型号：${capability.modelId}",style=MaterialTheme.typography.bodyMedium)
                Text("${capability.contextLabel}：${numbers.format(capability.contextTokens)} tokens\n最大输出：${numbers.format(capability.maxOutputTokens)} tokens",style=MaterialTheme.typography.bodyMedium)
                Text("图像输入：${if(capability.imageInput) "支持" else "不支持"} · 问迹仅发送文本",style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Muted)
                Text("官方参数参考，实际部署待核对。",style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Amber)
                TextButton(onClick={showDetails=!showDetails},contentPadding=androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(if(showDetails) "收起匹配依据" else "查看匹配依据") }
                if(showDetails) {
                    if(capability.matchKind==ModelCapabilityMatchKind.ALIAS_CANDIDATE) Text("按名称推荐最接近的已收录官方型号参数，并非当前服务端实测。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Amber)
                    if(capability.notes.isNotBlank()) Text(capability.notes,style=MaterialTheme.typography.bodySmall,color=WenjiColors.Amber)
                    Text("${capability.provider} 官方型号资料 · 核验于 ${capability.verifiedDate}。这是按名称匹配的参考，未探测当前 API；第三方同名映射和账号限额可能不同。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
                    Text("不发送图像、音频或视频，不自动修改已填写的输出 Token。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
                    TextButton(onClick={sourceError=runCatching { uriHandler.openUri(capability.sourceUrl) }.isFailure},contentPadding=androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("查看官方型号资料") }
                    if(sourceError) Text("未能打开浏览器，可稍后查看服务商文档。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Red)
                }
            }
        }
    }
}
