package org.wenji.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun Questions(model:WenjiModel,wide:Boolean,onSources:()->Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    PageHeading("我的疑问","课堂先记下，课后再用资料核对。",model,wide)
    QuestionModeGuide(model,onSources)
    WhiteCard {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            LineIcon(Glyph.Plus,Modifier.size(18.dp),WenjiColors.Teal);Spacer(Modifier.width(8.dp))
            Text("记一个疑问",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Tag("离线可用")
        }
        InputField(draft,{draft=it.take(2000)},"例如：资源分配图有环，是不是一定死锁？",minLines=2,maxLines=5)
        AdaptiveActions(horizontalArrangement=Arrangement.SpaceBetween) { actionModifier ->
            TextButton(onClick={draft="资源分配图有环，是不是一定死锁？"},contentPadding=PaddingValues(horizontal=0.dp),modifier=actionModifier) { Text("填入演示问题",style=MaterialTheme.typography.bodySmall) }
            PrimaryAction("保存疑问",{model.saveQuestion(draft){draft=""}},!model.busy&&draft.isNotBlank(),Glyph.Plus,modifier=actionModifier)
        }
        if(draft.length>1600) Text("${draft.length} / 2000",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
    }
    val questions=model.state.objects("questions")
    SectionTitle("疑问记录","${questions.size+model.pending.size} 条")
    for(q in model.pending) key(q.requestId) {
        WhiteCard {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { Tag("已保存到本机",Tone.Teal);Text("等待处理，请刷新",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
            Text(q.text,style=MaterialTheme.typography.titleMedium)
            AdaptiveActions { actionModifier ->
                TextButton(onClick={draft=q.text},enabled=!model.busy,contentPadding=PaddingValues(0.dp),modifier=actionModifier) { Text("复制并修改") }
                TextButton(onClick={model.removePendingQuestion(q.requestId)},enabled=!model.busy,modifier=actionModifier) { Text("移除记录",color=WenjiColors.Muted) }
            }
        }
    }
    if(questions.isEmpty()&&model.pending.isEmpty()) EmptyState(Glyph.Question,"从一个小疑问开始","记下问题后，选择先问模型，或导入资料找依据。")
    for(q in questions.reversed()) key(q.optString("id")) {
        val answer=q.optJSONObject("answer")
        val reference=q.optJSONObject("reference")
        val answerStatus=answer?.optString("status")
        val evidencePreferred=answerStatus=="ready"
        var expanded by rememberSaveable(answer?.optString("id")) { mutableStateOf(evidencePreferred) }
        var showOldGeneral by rememberSaveable(reference?.optString("id"),answer?.optString("id"),answerStatus) { mutableStateOf(!evidencePreferred) }
        val state=if(answerStatus in setOf("needs_review","conflict","needs_material")) answerStatus.orEmpty() else q.optString("state")
        val recovery=demoRecoveryAction(answer?.optString("provider").orEmpty(),answerStatus.orEmpty(),model.providerInfo,model.providerConfigurationFresh)
        val generalAnswered=reference!=null && reference.optString("status")=="unverified" && !evidencePreferred && answerStatus !in setOf("conflict","needs_review")
        WhiteCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Tag(if(generalAnswered) "已解答 · 待补依据" else if(recovery!=DemoRecoveryAction.NONE) "演示未解答" else status(state),if(generalAnswered) Tone.Amber else statusTone(state));Spacer(Modifier.weight(1f));Text("操作系统",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
            Text(q.optString("text"),style=MaterialTheme.typography.titleMedium)
            if(state=="needs_review") Notice("来源已变更，旧解释暂不能作为当前依据。",Tone.Red)
            if(evidencePreferred) Tag("优先显示 · 课程资料版",Tone.Teal)
            if(reference!=null && answerStatus in setOf("conflict","needs_review")) Notice("课程资料版仍需核对；下方通用答案也尚无课程依据。请分别查看，不将两版视为已验证一致。",Tone.Amber)
            if(answer==null) {
                if(reference==null) {
                    Text("还没有资料？先让模型解答并生成复习卡，课后再补依据。",style=MaterialTheme.typography.bodyMedium)
                    GeneralRequestAction(false,model,{model.requestReference(q.getString("id"))})
                    OutlinedButton(onClick={model.requestExplain(q.getString("id")){expanded=true}},enabled=model.canWrite()) { Text("查找课程依据") }
                }
            } else {
                Text("本条处理方式 · ${answerProviderLabel(answer.optString("provider"))}",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                if(answer.optString("provider").startsWith("llm:")) Text(answerCallLabel(answer.opt("remote_called") as? Boolean),color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                if(!expanded && answer.objects("claims").isNotEmpty()) Text(answer.objects("claims").first().optString("text"),maxLines=2,overflow=TextOverflow.Ellipsis,color=WenjiColors.Muted,style=MaterialTheme.typography.bodyMedium)
                AdaptiveActions(horizontalArrangement=Arrangement.SpaceBetween) { actionModifier ->
                    TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp),modifier=actionModifier) { Text(if(expanded) "收起解释与引用" else "查看解释与引用");Spacer(Modifier.width(6.dp));LineIcon(if(expanded) Glyph.Down else Glyph.Right,Modifier.size(16.dp),WenjiColors.Teal) }
                    TextButton(onClick={model.requestExplain(q.getString("id")){expanded=true}},enabled=model.canWrite(),modifier=actionModifier) { Text(if(state=="needs_review") "重新找依据" else "重新检索",style=MaterialTheme.typography.bodySmall) }
                }
                if(expanded) {
                    HorizontalDivider()
                    for((index,claim) in answer.objects("claims").withIndex()) {
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("${index+1}".padStart(2,'0'),color=WenjiColors.Teal,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=4.dp))
                            Text(claim.optString("text"),style=MaterialTheme.typography.bodyLarge)
                        }
                        for(evidence in claim.objects("evidence")) Evidence(evidence)
                    }
                    if(recovery!=DemoRecoveryAction.NONE) Text("上次演示的结果",style=MaterialTheme.typography.labelMedium,color=WenjiColors.Muted)
                    for(missing in answer.strings("missing_information")) Notice(missing,Tone.Amber)
                    for(conflict in answer.objects("conflicts")) Notice("来源冲突：${conflict.optString("text")}",Tone.Red)
                    if(answerStatus=="needs_material") {
                        Text("请导入与本题相关的 PDF 或文本，再查找课程依据。内置死锁教材仅适用于死锁案例。",style=MaterialTheme.typography.bodyMedium)
                        when(recovery) {
                            DemoRecoveryAction.CONFIGURE_MODEL -> PrimaryAction("配置模型 API",model::openSettings,!model.busy,Glyph.Plus)
                            DemoRecoveryAction.CONFIRM_CURRENT_MODEL -> {
                                Text("当前已配置模型；上面的记录仍是旧演示结果。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
                                PrimaryAction("用当前模型找依据",{model.requestExplain(q.getString("id")){expanded=true}},model.canWrite(),Glyph.Search)
                            }
                            DemoRecoveryAction.REFRESH_CONFIG -> TextButton(onClick=model::refresh,enabled=!model.busy) { Text("刷新模型状态") }
                            DemoRecoveryAction.NONE -> Unit
                        }
                        TextButton(onClick=onSources,contentPadding=PaddingValues(0.dp)) { LineIcon(Glyph.Book,Modifier.size(16.dp),WenjiColors.Teal);Spacer(Modifier.width(6.dp));Text("导入相关资料") }
                    }
                    Text("引用可定位，不代表解释一定正确。请对照原文核对。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
            if(answerStatus=="needs_material" || reference!=null) {
                HorizontalDivider()
                if(evidencePreferred && reference!=null) TextButton(onClick={showOldGeneral=!showOldGeneral},contentPadding=PaddingValues(0.dp)) { Text(if(showOldGeneral) "收起旧通用答案" else "查看旧通用答案（尚无课程依据）") }
                if(showOldGeneral || !evidencePreferred) GeneralReference(reference,model,onAsk={model.requestReference(q.getString("id"))})
                if(reference!=null) {
                    Text(if(evidencePreferred) "旧通用答案、复习卡和作答历史已保留。" else "课后补充资料，再生成带引用的答案。导入只保存在手机，不会自动联网。",style=MaterialTheme.typography.bodyMedium)
                    if(model.state.objects("sources").any { !it.optBoolean("deleted") }) {
                        when(explainAction(model.providerInfo,model.providerConfigurationFresh)) {
                            ExplainAction.CONFIRM_REMOTE -> PrimaryAction("生成课程资料版答案",{model.requestExplain(q.getString("id")){expanded=true}},model.canWrite(),Glyph.Book)
                            ExplainAction.DEMO -> PrimaryAction("选择模型以核对资料",model::openSettings,!model.busy,Glyph.Plus)
                            ExplainAction.BLOCKED -> TextButton(onClick=model::refresh,enabled=!model.busy) { Text("刷新模型状态") }
                        }
                        Text("资料需涵盖本题；若未找到依据，仍会提示补充。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
                    }
                    TextButton(onClick=onSources,contentPadding=PaddingValues(0.dp)) { Text("导入课后资料") }
                }
            }
        }
    }
}

@Composable private fun QuestionModeGuide(model:WenjiModel,onSources:()->Unit) {
    val action=explainAction(model.providerInfo,model.providerConfigurationFresh)
    Surface(color=if(action==ExplainAction.CONFIRM_REMOTE) WenjiColors.TealSoft else WenjiColors.AmberSoft,shape=RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(when(action) { ExplainAction.DEMO->"还未选择模型";ExplainAction.CONFIRM_REMOTE->"模型学习已准备";ExplainAction.BLOCKED->"请先刷新模型状态" },style=MaterialTheme.typography.titleMedium)
            Text(when(action) {
                ExplainAction.DEMO->"可以先记录疑问；要回答其他课程问题，请添加并选择模型。"
                ExplainAction.CONFIRM_REMOTE->"先获取答案与复习卡，课后补资料核对。课程资料版会优先显示。"
                ExplainAction.BLOCKED->"模型配置尚未核验，暂不能生成答案。疑问仍可保存到手机。"
            },style=MaterialTheme.typography.bodyMedium)
            if(model.state.objects("questions").isEmpty()&&model.pending.isEmpty()) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    for(step in listOf("1  记疑问","2  先学习","3  补依据")) Text(step,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=WenjiColors.Teal)
                }
            }
            Button(onClick=model::openSettings,enabled=!model.busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp)) {
                LineIcon(Glyph.Plus,Modifier.size(18.dp),Color.White);Spacer(Modifier.width(8.dp))
                Text(if(action==ExplainAction.DEMO) "添加或选择模型" else "供应商与模型")
            }
            Text(if(action==ExplainAction.DEMO) "未配置时仅可体验内置死锁案例。保存疑问不调用模型。" else "每次联网前，都会确认要发送的问题与资料。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                if(action==ExplainAction.BLOCKED) TextButton(onClick=model::refresh,enabled=!model.busy,contentPadding=PaddingValues(0.dp)) { Text("刷新状态") }
                TextButton(onClick=onSources,contentPadding=PaddingValues(0.dp)) { Text("导入相关资料") }
            }
        }
    }
}

@Composable private fun GeneralReference(reference:JSONObject?,model:WenjiModel,onAsk:()->Unit) {
    var fullText by rememberSaveable(reference?.optString("id")) { mutableStateOf(false) }
    Text("通用答案与复习卡",style=MaterialTheme.typography.titleSmall)
    Notice("尚无课程依据，答案可能有误。请在课后用资料核对。",Tone.Amber)
    if(reference!=null) {
        if(reference.optString("status")=="needs_review") Notice("问题已变化，以下是旧参考，请重新核对。",Tone.Red)
        if(reference.optString("status")=="superseded") Text("已有课程资料版；此通用答案及作答作为历史保留。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
        Text(reference.optString("text"),style=MaterialTheme.typography.bodyLarge,maxLines=if(fullText) Int.MAX_VALUE else 6,overflow=TextOverflow.Ellipsis)
        TextButton(onClick={fullText=!fullText},contentPadding=PaddingValues(0.dp)) { Text(if(fullText) "收起参考正文" else "展开完整参考") }
        Text("本条参考 · ${answerProviderLabel(reference.optString("provider"))}",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
        Text(if(reference.optString("status")=="unverified") "到“复习”页先独立作答与自评。自评不表示已掌握课程。" else "对应卡片和作答历史可在“复习”页查看。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
    } else Text("只发送这个问题，生成答案和通用复习卡。",style=MaterialTheme.typography.bodyMedium)
    GeneralRequestAction(reference!=null,model,onAsk)
}

@Composable private fun GeneralRequestAction(hasReference:Boolean,model:WenjiModel,onAsk:()->Unit) {
    val action=explainAction(model.providerInfo,model.providerConfigurationFresh)
    when(action) {
        ExplainAction.CONFIRM_REMOTE -> PrimaryAction(if(hasReference) "重新生成答案与卡片" else "生成答案与复习卡",onAsk,model.canWrite(),Glyph.Review)
        ExplainAction.DEMO -> TextButton(onClick=model::openSettings,enabled=!model.busy,contentPadding=PaddingValues(0.dp)) { Text("选择模型，开始解答") }
        ExplainAction.BLOCKED -> TextButton(onClick=model::refresh,enabled=!model.busy,contentPadding=PaddingValues(0.dp)) { Text("刷新模型状态后询问") }
    }
}

@Composable private fun Evidence(e:JSONObject) {
    var open by rememberSaveable(e.optString("id")) { mutableStateOf(false) }
    Surface(color=WenjiColors.Field,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,WenjiColors.Border)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable{open=!open}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                LineIcon(Glyph.File,Modifier.size(18.dp),WenjiColors.Teal)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(e.optString("source_title"),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                    Text("v${e.optInt("revision")}  ·  第 ${e.optInt("page")} 页",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                }
                Text(if(open) "收起" else "原文",color=WenjiColors.Teal,style=MaterialTheme.typography.labelMedium)
                LineIcon(if(open) Glyph.Down else Glyph.Right,Modifier.size(16.dp))
            }
            if(open) {
                HorizontalDivider(Modifier.padding(horizontal=12.dp))
                Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(3.dp).height(26.dp).background(WenjiColors.Teal,RoundedCornerShape(2.dp)))
                    Text(e.optString("quote").ifBlank{"原文已撤回或不可用"},style=MaterialTheme.typography.bodyMedium)
                }
                Text("文本位置 ${e.optInt("start")}–${e.optInt("end")}  ·  ${status(e.optString("status"))}",Modifier.padding(start=14.dp,end=14.dp,bottom=14.dp),color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable internal fun Reviews(model:WenjiModel,wide:Boolean) {
    PageHeading("复习与自测","先写自己的答案，再查看参考和自评。",model,wide)
    val cards=model.state.objects("cards")
    if(cards.isEmpty()) EmptyState(Glyph.Review,"让理解留下自己的答案","生成通用答案或课程引用解答后，就可以在这里练习。")
    else SectionTitle("自测记录","${cards.size} 张卡片")
    for(card in cards.reversed().sortedBy { reviewDisplayPriority(it.optString("basis"),it.optString("status")) }) key(card.optString("id")) {
        val attempts=model.state.objects("attempts").filter{it.optString("card_id")==card.optString("id")}
        val latest=attempts.lastOrNull()
        var answer by rememberSaveable { mutableStateOf("") }
        var newAttempt by rememberSaveable { mutableStateOf(false) }
        var history by rememberSaveable { mutableStateOf(false) }
        val stale=card.optString("status")=="needs_review"
        val general=card.optString("basis")=="general_reference"
        val superseded=card.optString("superseded_by_answer_id").let { it.isNotBlank() && it!="null" }
        WhiteCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Tag(status(card.optString("status")),statusTone(card.optString("status")));Spacer(Modifier.weight(1f));LineIcon(Glyph.Review,Modifier.size(20.dp),WenjiColors.Muted)
            }
            if(general) {
                Tag("通用卡 · 尚无课程依据",Tone.Amber)
                Text("先独立作答，再对照模型答案自评；自评不等于掌握课程。",style=MaterialTheme.typography.bodyMedium)
            } else Tag("课程资料版",Tone.Teal)
            Text(card.optString("prompt"),style=MaterialTheme.typography.titleMedium)
            if(stale) Notice(if(general && superseded) "已有课程资料版，本卡和作答历史保留为只读。请使用资料版继续复习。"
                else if(general) "问题或参考状态已变化，本卡暂停作答与确认。请回到疑问核对。"
                else "来源已失效，本卡暂停作答与确认。请回到疑问重新查找依据。",Tone.Red)
            else if(latest==null || newAttempt) {
                InputField(answer,{answer=it.take(10_000)},"用自己的话写下关键条件和推理过程…",minLines=4,maxLines=8)
                if(!newAttempt) Text(if(general) "提交后查看模型参考答案" else "提交后查看参考关键点",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                AdaptiveActions(horizontalArrangement=Arrangement.End) { actionModifier ->
                    if(newAttempt) TextButton(onClick={newAttempt=false},modifier=actionModifier) { Text("取消") }
                    PrimaryAction("提交回答",{model.mutate("/cards/${card.getString("id")}/attempts",JSONObject().put("answer",answer)){answer="";newAttempt=false}},model.canWrite()&&answer.isNotBlank(),Glyph.Arrow,modifier=actionModifier)
                }
            }
            if(latest!=null) {
                AttemptFeedback(latest,card,model,stale,true)
                if(!stale && !newAttempt) TextButton(onClick={newAttempt=true},enabled=!model.busy,contentPadding=PaddingValues(0.dp)) { LineIcon(Glyph.Plus,Modifier.size(16.dp),WenjiColors.Teal);Spacer(Modifier.width(6.dp));Text("再答一次") }
            }
            if(attempts.size>1) {
                TextButton(onClick={history=!history},contentPadding=PaddingValues(0.dp)) { Text(if(history) "收起历史作答" else "查看 ${attempts.size-1} 次历史作答",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
                if(history) for(attempt in attempts.dropLast(1).reversed()) AttemptFeedback(attempt,card,model,stale,false)
            }
            card.optString("due_at").takeIf{it.isNotBlank()&&it!="null"}?.let { due ->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) { LineIcon(Glyph.Clock,Modifier.size(15.dp));Text("下次复习建议 · ${formatDue(due)}",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun formatDue(value:String):String = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm")) }.getOrDefault(value)

@Composable private fun AttemptFeedback(attempt:JSONObject,card:JSONObject,model:WenjiModel,stale:Boolean,latest:Boolean) {
    val general=card.optString("basis")=="general_reference"
    Surface(color=if(stale) WenjiColors.Field else WenjiColors.TealSoft,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                LineIcon(Glyph.Check,Modifier.size(18.dp),WenjiColors.Teal)
                Text(if(latest) "本次作答" else "历史作答",style=MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f));if(attempt.optBoolean("confirmed")) Text("已自评",color=WenjiColors.Teal,style=MaterialTheme.typography.labelMedium)
            }
            Text(attempt.optString("answer"),style=MaterialTheme.typography.bodyMedium)
            attempt.optJSONObject("feedback")?.let { feedback ->
                if(feedback.strings("reference_points").isNotEmpty()) {
                    HorizontalDivider(color=Color(0xFFD3E5DE))
                    Text(if(general) "对照模型参考（尚无课程依据）" else "对照参考关键点",style=MaterialTheme.typography.titleSmall,color=WenjiColors.Teal)
                    for(point in feedback.strings("reference_points")) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Text("·",color=WenjiColors.Teal,fontWeight=FontWeight.Bold);Text(point,style=MaterialTheme.typography.bodyMedium) }
                }
                Text(feedback.optString("message"),color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            }
            val warning=attempt.optString("source_warning")
            if(warning.isNotBlank()&&warning!="null") Notice(warning,if(general&&!stale) Tone.Amber else Tone.Red)
            // Only the newest attempt may be confirmed; source invalidation takes precedence.
            if(latest&&!stale&&!attempt.optBoolean("confirmed")) {
                AdaptiveActions { actionModifier ->
                    OutlinedButton(onClick={model.mutate("/cards/${card.getString("id")}/confirm",JSONObject().put("attempt_id",attempt.getString("id")).put("self_rating","again"))},enabled=model.canWrite(),modifier=actionModifier,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Color(0xFFBBD4C9))) { Text("还需练习") }
                    PrimaryAction(if(general) "已自评" else "已核对",{model.mutate("/cards/${card.getString("id")}/confirm",JSONObject().put("attempt_id",attempt.getString("id")).put("self_rating","understood"))},model.canWrite(),Glyph.Check,modifier=actionModifier)
                }
            }
        }
    }
}
