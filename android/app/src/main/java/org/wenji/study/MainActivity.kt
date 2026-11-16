package org.wenji.study

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WenjiTheme { Surface(Modifier.fillMaxSize(),color=WenjiColors.Background) { WenjiApp() } } }
    }
}

private val destinations = listOf("疑问", "资料", "复习")
private val destinationIcons = listOf(Glyph.Question,Glyph.Book,Glyph.Review)
internal fun WenjiModel.canWrite() = connected && !busy && writes.isEmpty()

@Composable
private fun WenjiApp(model: WenjiModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val pageState = rememberSaveableStateHolder()
    LaunchedEffect(model.message) {
        if(model.message.startsWith("疑问已写入")) snack.showSnackbar("已保存到本机，联网后可同步")
        else if(model.message.startsWith("操作成功")) snack.showSnackbar("已更新，请核对资料与状态")
    }
    LaunchedEffect(model.failure?.sequence) {
        model.failure?.let { failure ->
            snack.currentSnackbarData?.dismiss()
            if(snack.showSnackbar(failure.summary,actionLabel="详情",withDismissAction=true,duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) showStatus=true
            model.acknowledgeFailure(failure.sequence)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if(wide) SideNavigation(tab) { tab=it }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                    Row(Modifier.widthIn(max=960.dp).fillMaxWidth().padding(horizontal=if(wide) 28.dp else 20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(!wide) Text("问迹",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text("操作系统",style=MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(5.dp).background(if(model.connected) WenjiColors.Teal else WenjiColors.Amber,RoundedCornerShape(50)))
                                Text(if(model.connected) "服务已连接" else "离线 · 缓存待核验",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Surface(onClick={showStatus=true},shape=RoundedCornerShape(50),color=WenjiColors.AmberSoft) {
                            Text(model.providerInfo.badge + if(model.providerConfigurationFresh) "" else " · 缓存",Modifier.padding(horizontal=10.dp,vertical=6.dp),color=WenjiColors.Amber,style=MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick={showStatus=true},modifier=Modifier.size(36.dp).semantics { contentDescription="连接与模型说明" }) { LineIcon(Glyph.Info,color=if(model.connected) WenjiColors.Teal else WenjiColors.Amber) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(tab) { scroll.scrollTo(0) }
                    Column(Modifier.fillMaxSize().verticalScroll(scroll),horizontalAlignment=Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max=960.dp).fillMaxWidth().padding(horizontal=if(wide) 28.dp else 20.dp).padding(top=14.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            if(model.writes.isNotEmpty()) {
                                Surface(onClick={showStatus=true},color=WenjiColors.AmberSoft,shape=RoundedCornerShape(12.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        LineIcon(Glyph.Sync,Modifier.size(18.dp),WenjiColors.Amber)
                                        Text("有操作待同步，输入已保留",Modifier.weight(1f),color=WenjiColors.Amber,style=MaterialTheme.typography.bodySmall)
                                        Text("处理",color=WenjiColors.Amber,style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            pageState.SaveableStateProvider(tab) {
                                when(tab) { 0 -> Questions(model,wide); 1 -> Sources(model,wide); else -> Reviews(model,wide) }
                            }
                        }
                    }
                    SnackbarHost(snack,Modifier.align(Alignment.BottomCenter).widthIn(max=640.dp).padding(16.dp))
                    if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=WenjiColors.Teal,trackColor=WenjiColors.TealSoft)
                }
                if(!wide) BottomNavigation(tab) { tab=it }
            }
        }
    }
    if(showStatus) StatusDialog(model) { showStatus=false }
    model.explainConsent?.let { consent -> ExplainConsentDialog(model, consent) }
}

@Composable private fun ExplainConsentDialog(model: WenjiModel, consent: ExplainConsent) {
    AlertDialog(onDismissRequest=model::cancelExplain,title={Text("确认本次模型请求",style=MaterialTheme.typography.titleLarge)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("配置不代表服务可用；本次调用将在确认后尝试。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            Text("提供方：${consent.provider.displayLabel}",style=MaterialTheme.typography.bodyMedium)
            Text("模型：${consent.provider.model}",style=MaterialTheme.typography.bodyMedium)
            Text("端点：${consent.provider.endpoint}",style=MaterialTheme.typography.bodyMedium)
            Text("协议：${consent.provider.protocol}",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            HorizontalDivider()
            Text("将发送的问题",style=MaterialTheme.typography.titleSmall)
            Text(consent.question,style=MaterialTheme.typography.bodyMedium)
            Text("本机服务还会发送从本课程资料检索出的相关片段及来源定位，不会上传整份 PDF。此确认仅适用于本次请求；重新检索需要再次确认。",style=MaterialTheme.typography.bodyMedium)
            Text("网络中断后，同步沿用本次授权及请求编号；尚未送达时可能发起首次调用，已受理时只恢复结果。失败或配置改变后需停止旧重试，重新核对并确认。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            if(!model.providerConfigurationFresh) Notice("当前为缓存配置，请取消并同步后重新确认。",Tone.Amber)
        }
    },confirmButton={TextButton(onClick=model::confirmExplain,enabled=model.canWrite()&&model.providerConfigurationFresh) { Text("同意并发送本次请求") }},dismissButton={TextButton(onClick=model::cancelExplain) { Text("取消") }})
}

@Composable private fun SideNavigation(selected: Int, onSelect: (Int)->Unit) {
    NavigationRail(modifier=Modifier.fillMaxHeight().width(104.dp),containerColor=Color.White,header={
        Spacer(Modifier.height(22.dp))
        Surface(color=WenjiColors.Teal,shape=RoundedCornerShape(14.dp),modifier=Modifier.size(44.dp)) {
            Box(contentAlignment=Alignment.Center) { LineIcon(Glyph.Question,Modifier.size(26.dp),Color.White) }
        }
        Spacer(Modifier.height(6.dp)); Text("问迹",style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(38.dp))
    }) {
        destinations.forEachIndexed { i,name ->
            NavigationRailItem(selected=i==selected,onClick={onSelect(i)},icon={LineIcon(destinationIcons[i],color=if(i==selected) WenjiColors.Teal else WenjiColors.Muted)},label={Text(name)},
                colors=NavigationRailItemDefaults.colors(indicatorColor=WenjiColors.TealSoft,selectedTextColor=WenjiColors.Teal))
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f));Text("本地学习",style=MaterialTheme.typography.labelSmall,color=WenjiColors.Muted);Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun BottomNavigation(selected: Int,onSelect:(Int)->Unit) {
    HorizontalDivider(color=WenjiColors.Border)
    NavigationBar(containerColor=Color.White,tonalElevation=0.dp,modifier=Modifier.height(76.dp),windowInsets=WindowInsets(0,0,0,0)) {
        destinations.forEachIndexed { i,name ->
            NavigationBarItem(selected=i==selected,onClick={onSelect(i)},icon={LineIcon(destinationIcons[i],color=if(i==selected) WenjiColors.Teal else WenjiColors.Muted)},label={Text(name)},
                colors=NavigationBarItemDefaults.colors(indicatorColor=WenjiColors.TealSoft,selectedTextColor=WenjiColors.Teal,unselectedTextColor=WenjiColors.Muted))
        }
    }
}

@Composable internal fun PageHeading(title:String,subtitle:String,model:WenjiModel,wide:Boolean) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,Modifier.weight(1f),style=if(wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium)
            TextButton(onClick=model::refresh,enabled=!model.busy,contentPadding=PaddingValues(horizontal=9.dp)) {
                LineIcon(Glyph.Sync,Modifier.size(16.dp),WenjiColors.Teal);Spacer(Modifier.width(6.dp));Text("同步")
            }
        }
        Text(subtitle,color=WenjiColors.Muted,style=MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
    }
}

@Composable private fun StatusDialog(model:WenjiModel,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("连接与模型说明",style=MaterialTheme.typography.titleLarge)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Tag(if(model.connected) "服务已连接" else "当前离线",if(model.connected) Tone.Teal else Tone.Amber)
            Text(model.message,style=MaterialTheme.typography.bodyMedium)
            if(model.fetchedAt!=0L) Text("最近缓存：${SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(model.fetchedAt))}。离线内容可能过期，请同步后再核对依据。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            HorizontalDivider()
            Text(model.providerInfo.displayLabel,style=MaterialTheme.typography.titleSmall)
            if(!model.providerConfigurationFresh) Notice("以下为缓存配置，当前模式可能已变化；同步后再发起新的解释请求。",Tone.Amber)
            Text(model.providerInfo.description,style=MaterialTheme.typography.bodyMedium)
            if(model.providerInfo.mode=="llm") {
                Text("模型：${model.providerInfo.model}\n端点：${model.providerInfo.endpoint}\n协议：${model.providerInfo.protocol}",style=MaterialTheme.typography.bodySmall)
            }
            Text("历史解释按各条记录中的提供方标注，不随当前配置改变。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            Text("看过解释不等于掌握。请核对原文，并在独立作答后自行确认。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            for(write in model.writes) {
                HorizontalDivider()
                Text("待同步：${operationLabel(write.path)}",style=MaterialTheme.typography.titleSmall)
                Text("重试会沿用原请求编号。停止重试不会撤销已经生效的服务操作。",style=MaterialTheme.typography.bodySmall)
                if(write.path.endsWith("/explain")) Text("模型请求仅重试原有的单次授权；配置已变更时需停止旧重试，重新查找依据并确认。",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={model.discardRetry(write.requestId)},enabled=!model.busy) { Text("停止重试并核对状态") }
            }
        }
    },confirmButton={TextButton(onClick=model::refresh,enabled=!model.busy) { Text("重新同步") }},dismissButton={TextButton(onClick=onDismiss) { Text("关闭") }})
}

private fun operationLabel(path:String)=when {
    path=="/sources"->"资料导入";path.endsWith("/revisions")->"资料修订";path.endsWith("/delete")->"资料撤回"
    path.endsWith("/explain")->"查找依据";path.endsWith("/attempts")->"保存作答";path.endsWith("/confirm")->"复习确认";else->"学习记录更新"
}
internal fun status(value:String)=mapOf("recorded" to "待找依据","needs_material" to "待补材料","needs_confirmation" to "待核对解释","needs_review" to "依据待复核","reviewing" to "复习中","ready" to "待核对解释","conflict" to "来源冲突","awaiting_attempt" to "待独立作答","awaiting_confirmation" to "待自评","valid" to "有效","withdrawn" to "依据已撤回","active" to "当前依据","current" to "当前依据","unconfirmed" to "待核对")[value]?:value
internal fun statusTone(value:String)=when(value) { "needs_review","withdrawn","conflict"->Tone.Red;"needs_material","awaiting_confirmation"->Tone.Amber;"reviewing","ready","needs_confirmation","current"->Tone.Teal;else->Tone.Neutral }

@Composable internal fun EmptyState(glyph:Glyph,title:String,description:String) {
    WhiteCard {
        Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Surface(color=WenjiColors.TealSoft,shape=RoundedCornerShape(18.dp),modifier=Modifier.size(60.dp)) { Box(contentAlignment=Alignment.Center) { LineIcon(glyph,Modifier.size(28.dp),WenjiColors.Teal) } }
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(title,style=MaterialTheme.typography.titleMedium);Text(description,color=WenjiColors.Muted,style=MaterialTheme.typography.bodyMedium) }
        }
    }
}
