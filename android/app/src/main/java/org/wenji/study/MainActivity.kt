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
        if(model.message.startsWith("疑问已写入")) snack.showSnackbar("已保存到手机，不需要网络或电脑")
        else if(model.message.startsWith("通用答案与复习卡已保存")) snack.showSnackbar(model.message)
        else if(model.message.startsWith("资料已保存到手机")) snack.showSnackbar(model.message)
        else if(model.message.startsWith("已切换当前模型") || model.message.startsWith("已切回离线") ||
            model.message.startsWith("供应商已") || model.message.startsWith("模型已")) snack.showSnackbar(model.message)
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
        val compactHeader = maxWidth < 480.dp
        Row(Modifier.fillMaxSize()) {
            if(wide) SideNavigation(tab) { tab=it }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                    Column(Modifier.widthIn(max=960.dp).fillMaxWidth().padding(horizontal=if(wide) 28.dp else 20.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(compactHeader) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("问迹",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Surface(onClick=model::openSettings,enabled=!model.busy,shape=RoundedCornerShape(50),color=WenjiColors.TealSoft) {
                                    Text(if(model.providerInfo.mode=="llm") "切换模型" else "配置模型",Modifier.padding(horizontal=12.dp,vertical=13.dp),color=WenjiColors.Teal,style=MaterialTheme.typography.labelLarge)
                                }
                                IconButton(onClick={showStatus=true},modifier=Modifier.size(48.dp).semantics { contentDescription="本机状态与操作记录" }) { LineIcon(Glyph.Info,color=if(model.connected) WenjiColors.Teal else WenjiColors.Amber) }
                            }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(5.dp).background(if(model.connected) WenjiColors.Teal else WenjiColors.Amber,RoundedCornerShape(50)))
                                Text("操作系统 · ${if(model.connected) "手机独立运行" else "本机记录待恢复"}",Modifier.weight(1f),color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                            }
                        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(!wide) Text("问迹",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text("操作系统",style=MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Box(Modifier.size(5.dp).background(if(model.connected) WenjiColors.Teal else WenjiColors.Amber,RoundedCornerShape(50)))
                                    Text(if(model.connected) "手机独立运行" else "本机记录待恢复",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            Surface(onClick=model::openSettings,enabled=!model.busy,shape=RoundedCornerShape(50),color=WenjiColors.TealSoft) {
                                Text(if(model.providerInfo.mode=="llm") "切换模型" else "配置模型",Modifier.padding(horizontal=12.dp,vertical=10.dp),color=WenjiColors.Teal,style=MaterialTheme.typography.labelLarge)
                            }
                            IconButton(onClick={showStatus=true},modifier=Modifier.size(48.dp).semantics { contentDescription="本机状态与操作记录" }) { LineIcon(Glyph.Info,color=if(model.connected) WenjiColors.Teal else WenjiColors.Amber) }
                        }
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
                                        Text("有操作待处理，输入已保留",Modifier.weight(1f),color=WenjiColors.Amber,style=MaterialTheme.typography.bodySmall)
                                        Text("处理",color=WenjiColors.Amber,style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            pageState.SaveableStateProvider(tab) {
                                when(tab) { 0 -> Questions(model,wide,onSources={tab=1}); 1 -> Sources(model,wide); else -> Reviews(model,wide) }
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
    if(showStatus) StatusDialog(model,onSettings={showStatus=false;model.openSettings()}) { showStatus=false }
    if(model.showProviderManager) {
        val catalogue=model.providerCatalog
        if(catalogue!=null) ProviderManagerDialog(catalog=catalogue,busy=model.busy,error=model.settingsError,
            onSaveProvider=model::saveProvider,onSaveModel=model::saveProviderModel,
            onSelectModel=model::selectProviderModel,onDeleteProvider=model::deleteProvider,
            onDeleteModel=model::deleteProviderModel,onUseDemo=model::useDemo,onDismiss=model::closeSettings)
        else AlertDialog(onDismissRequest=model::closeSettings,title={Text("模型供应商",style=MaterialTheme.typography.titleLarge)},
            text={Text(model.settingsError ?: "正在读取本机加密配置…",style=MaterialTheme.typography.bodyMedium)},
            confirmButton={TextButton(onClick=model::openSettings,enabled=!model.busy) { Text("重新读取") }},
            dismissButton={TextButton(onClick=model::closeSettings,enabled=!model.busy) { Text("关闭") }})
    }
    model.explainConsent?.let { consent -> ExplainConsentDialog(model, consent) }
    if(shouldShowDemoIntro(model.providerInfo,model.providerConfigurationFresh,model.demoIntroAcknowledged)
        && !model.busy && !showStatus && !model.showProviderManager && model.explainConsent==null) {
        DemoIntroDialog(onConfigure={model.acknowledgeDemoIntro();model.openSettings()},onContinue=model::acknowledgeDemoIntro)
    }
}

@Composable private fun DemoIntroDialog(onConfigure:()->Unit,onContinue:()->Unit) {
    AlertDialog(onDismissRequest=onContinue,title={Text("先选一种开始方式",style=MaterialTheme.typography.titleLarge)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("先离线体验",style=MaterialTheme.typography.titleMedium,color=WenjiColors.Ink)
            Text("记录疑问、导入资料，试用内置的死锁案例。无需联网。",style=MaterialTheme.typography.bodyMedium)
            Notice("离线演示只会解答固定案例，不能回答你自己的其他问题。",Tone.Amber)
            Text("让模型回答自己的问题",style=MaterialTheme.typography.titleMedium,color=WenjiColors.Ink)
            Text("添加供应商的地址和 API Key，再添加并选用模型。没有资料也能先生成答案和复习卡。",style=MaterialTheme.typography.bodyMedium)
            Text("无资料的答案会标注“待补课程依据”；课后导入资料再核对。每次联网发送前都由你确认。",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={Button(onClick=onConfigure) { Text("添加模型供应商") }},dismissButton={TextButton(onClick=onContinue) { Text("先离线体验") }})
}

@Composable private fun ExplainConsentDialog(model: WenjiModel, consent: ExplainConsent) {
    val general = consent.kind == ModelRequestKind.GENERAL_REFERENCE
    AlertDialog(onDismissRequest=model::cancelExplain,title={Text(if(general) "生成答案与复习卡" else "生成有引用的解答",style=MaterialTheme.typography.titleLarge)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("本次使用",style=MaterialTheme.typography.titleMedium)
            Text("${consent.provider.displayLabel}\n${consent.provider.model}",style=MaterialTheme.typography.bodyMedium)
            Text("发送地址：${consent.provider.endpoint}",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("你的问题",style=MaterialTheme.typography.titleMedium)
            Text(consent.question,style=MaterialTheme.typography.bodyMedium)
            Text("将发送什么",style=MaterialTheme.typography.titleMedium)
            Text(if(general) "仅发送这道问题，不发送课程资料。" else "发送问题和检索到的课程原文片段，不上传整份 PDF。",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
            if(general) Notice("答案和复习卡尚无课程依据，课后补资料再核对。",Tone.Amber)
            else Notice("引用能定位不等于结论正确，生成后仍需核对原文。",Tone.Teal)
            Text("确认后才尝试联网。切换模型后需要重新确认；刷新或重启不会自动重发。",style=MaterialTheme.typography.bodySmall)
            if(!model.providerConfigurationFresh) Notice("当前为缓存配置，请取消并刷新后重新确认。",Tone.Amber)
        }
    },confirmButton={Button(onClick=model::confirmExplain,enabled=model.canWrite()&&model.providerConfigurationFresh) { Text(if(general) "仅发送问题，开始生成" else "确认发送并生成") }},dismissButton={TextButton(onClick=model::cancelExplain) { Text("取消") }})
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
                LineIcon(Glyph.Sync,Modifier.size(16.dp),WenjiColors.Teal);Spacer(Modifier.width(6.dp));Text("刷新")
            }
        }
        Text(subtitle,color=WenjiColors.Muted,style=MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
    }
}

@Composable private fun StatusDialog(model:WenjiModel,onSettings:()->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("本机与模型",style=MaterialTheme.typography.titleLarge)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Tag(if(model.connected) "手机独立运行" else "本机记录待恢复",if(model.connected) Tone.Teal else Tone.Amber)
            Text("资料导入、检索、版本管理和复习都在手机完成。只有确认调用外部模型时才需要联网。",style=MaterialTheme.typography.bodyMedium)
            Button(onClick=onSettings,enabled=!model.busy,modifier=Modifier.fillMaxWidth()) { Text("管理供应商与模型") }
            Text(model.message,style=MaterialTheme.typography.bodyMedium)
            if(model.fetchedAt!=0L) Text("本机记录更新时间：${SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(model.fetchedAt))}。操作失败时请刷新核对状态。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            if(model.hasDesktopRecords) Text("独立版使用新的手机学习空间。旧电脑版资料仍保留在电脑，原手机缓存也未删除；本版不会自动迁移或混入旧请求。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            HorizontalDivider()
            Text(model.providerInfo.displayLabel,style=MaterialTheme.typography.titleSmall)
            if(!model.providerConfigurationFresh) Notice("以下为缓存配置，当前模式可能已变化；刷新后再发起新的解释请求。",Tone.Amber)
            Text(model.providerInfo.description,style=MaterialTheme.typography.bodyMedium)
            if(model.providerInfo.mode=="llm") {
                Text("模型：${model.providerInfo.model}\n端点：${model.providerInfo.endpoint}\n协议：${model.providerInfo.protocol}",style=MaterialTheme.typography.bodySmall)
            }
            Text("历史解释按各条记录中的提供方标注，不随当前配置改变。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            Text("看过解释不等于掌握。请核对原文，并在独立作答后自行确认。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            for(write in model.writes) {
                HorizontalDivider()
                Text("待处理：${operationLabel(write.path)}",style=MaterialTheme.typography.titleSmall)
                Text("重试会沿用原请求编号。停止重试不会撤销已经生效的本机操作。",style=MaterialTheme.typography.bodySmall)
                if(!mayAutoRetry(write.path)) Text("刷新不会自动重发模型请求。请停止旧重试并核对是否已有结果；需要新生成时重新发起并确认。",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={model.discardRetry(write.requestId)},enabled=!model.busy) { Text("停止重试并核对状态") }
            }
        }
    },confirmButton={TextButton(onClick=model::refresh,enabled=!model.busy) { Text("刷新记录") }},dismissButton={TextButton(onClick=onDismiss) { Text("关闭") }})
}

private fun operationLabel(path:String)=when {
    path=="/sources"->"资料导入";path.endsWith("/revisions")->"资料修订";path.endsWith("/delete")->"资料撤回"
    path.endsWith("/explain")->"查找依据";path.endsWith("/reference")->"通用答案与复习卡";path.endsWith("/attempts")->"保存作答";path.endsWith("/confirm")->"复习确认";else->"学习记录更新"
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
