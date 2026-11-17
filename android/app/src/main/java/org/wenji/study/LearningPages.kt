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

@Composable internal fun Questions(model:WenjiModel,wide:Boolean) {
    var draft by rememberSaveable { mutableStateOf("") }
    PageHeading("我的疑问","记下没听懂的一点，让理解有迹可循。",model,wide)
    WhiteCard {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            LineIcon(Glyph.Plus,Modifier.size(18.dp),WenjiColors.Teal);Spacer(Modifier.width(8.dp))
            Text("记一个疑问",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Tag("离线可用")
        }
        InputField(draft,{draft=it.take(2000)},"例如：资源分配图有环，是不是一定死锁？",minLines=2,maxLines=5)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={draft="资源分配图有环，是不是一定死锁？"},contentPadding=PaddingValues(horizontal=0.dp)) { Text("填入演示问题",style=MaterialTheme.typography.bodySmall) }
            PrimaryAction("保存疑问",{model.saveQuestion(draft){draft=""}},!model.busy&&draft.isNotBlank(),Glyph.Plus)
        }
        if(draft.length>1600) Text("${draft.length} / 2000",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
    }
    val questions=model.state.objects("questions")
    SectionTitle("疑问记录","${questions.size+model.pending.size} 条")
    for(q in model.pending) key(q.requestId) {
        WhiteCard {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { Tag("已保存到本机",Tone.Teal);Text("等待处理，请刷新",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
            Text(q.text,style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick={draft=q.text},enabled=!model.busy,contentPadding=PaddingValues(0.dp)) { Text("复制并修改") }
                TextButton(onClick={model.removePendingQuestion(q.requestId)},enabled=!model.busy) { Text("移除记录",color=WenjiColors.Muted) }
            }
        }
    }
    if(questions.isEmpty()&&model.pending.isEmpty()) EmptyState(Glyph.Question,"从一个小疑问开始","先记下来。课后再找资料、核对依据。")
    for(q in questions.reversed()) key(q.optString("id")) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val answer=q.optJSONObject("answer")
        val answerStatus=answer?.optString("status")
        val state=if(answerStatus in setOf("needs_review","conflict","needs_material")) answerStatus.orEmpty() else q.optString("state")
        WhiteCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Tag(status(state),statusTone(state));Spacer(Modifier.weight(1f));Text("操作系统",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
            Text(q.optString("text"),style=MaterialTheme.typography.titleMedium)
            if(state=="needs_review") Notice("来源已变更，旧解释暂不能作为当前依据。",Tone.Red)
            if(answer==null) {
                Text("从本课程资料中寻找可以核对的原文。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodyMedium)
                PrimaryAction("查找依据",{model.requestExplain(q.getString("id")){expanded=true}},model.canWrite(),Glyph.Search)
            } else {
                Text("本条解释 · ${answerProviderLabel(answer.optString("provider"))}",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                if(!expanded && answer.objects("claims").isNotEmpty()) Text(answer.objects("claims").first().optString("text"),maxLines=2,overflow=TextOverflow.Ellipsis,color=WenjiColors.Muted,style=MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) { Text(if(expanded) "收起解释与引用" else "查看解释与引用");Spacer(Modifier.width(6.dp));LineIcon(if(expanded) Glyph.Down else Glyph.Right,Modifier.size(16.dp),WenjiColors.Teal) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={model.requestExplain(q.getString("id")){expanded=true}},enabled=model.canWrite()) { Text(if(state=="needs_review") "重新找依据" else "重新检索",style=MaterialTheme.typography.bodySmall) }
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
                    for(missing in answer.strings("missing_information")) Notice(missing,Tone.Amber)
                    for(conflict in answer.objects("conflicts")) Notice("来源冲突：${conflict.optString("text")}",Tone.Red)
                    Text("引用可定位，不代表解释一定正确。请对照原文核对。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
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
    PageHeading("复习与自测","先用自己的话回答，再对照原文核对。",model,wide)
    val cards=model.state.objects("cards")
    if(cards.isEmpty()) EmptyState(Glyph.Review,"让理解留下自己的答案","找到可支持的依据后，自测任务会出现在这里。")
    else SectionTitle("自测记录","${cards.size} 张卡片")
    for(card in cards.reversed()) key(card.optString("id")) {
        val attempts=model.state.objects("attempts").filter{it.optString("card_id")==card.optString("id")}
        val latest=attempts.lastOrNull()
        var answer by rememberSaveable { mutableStateOf("") }
        var newAttempt by rememberSaveable { mutableStateOf(false) }
        var history by rememberSaveable { mutableStateOf(false) }
        val stale=card.optString("status")=="needs_review"
        WhiteCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Tag(status(card.optString("status")),statusTone(card.optString("status")));Spacer(Modifier.weight(1f));LineIcon(Glyph.Review,Modifier.size(20.dp),WenjiColors.Muted)
            }
            Text(card.optString("prompt"),style=MaterialTheme.typography.titleMedium)
            if(stale) Notice("来源已失效，本卡暂停作答与确认。请回到疑问重新查找依据。",Tone.Red)
            else if(latest==null || newAttempt) {
                InputField(answer,{answer=it.take(10_000)},"用自己的话写下关键条件和推理过程…",minLines=4,maxLines=8)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(newAttempt) TextButton(onClick={newAttempt=false}) { Text("取消") } else Text("提交后显示参考关键点",Modifier.weight(1f),color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                    if(newAttempt) Spacer(Modifier.weight(1f))
                    PrimaryAction("提交回答",{model.mutate("/cards/${card.getString("id")}/attempts",JSONObject().put("answer",answer)){answer="";newAttempt=false}},model.canWrite()&&answer.isNotBlank(),Glyph.Arrow)
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
                    Text("对照参考关键点",style=MaterialTheme.typography.titleSmall,color=WenjiColors.Teal)
                    for(point in feedback.strings("reference_points")) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Text("·",color=WenjiColors.Teal,fontWeight=FontWeight.Bold);Text(point,style=MaterialTheme.typography.bodyMedium) }
                }
                Text(feedback.optString("message"),color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            }
            val warning=attempt.optString("source_warning")
            if(warning.isNotBlank()&&warning!="null") Notice(warning,Tone.Red)
            // Only the newest attempt may be confirmed; source invalidation takes precedence.
            if(latest&&!stale&&!attempt.optBoolean("confirmed")) {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick={model.mutate("/cards/${card.getString("id")}/confirm",JSONObject().put("attempt_id",attempt.getString("id")).put("self_rating","again"))},enabled=model.canWrite(),shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Color(0xFFBBD4C9))) { Text("还需练习") }
                    PrimaryAction("已核对",{model.mutate("/cards/${card.getString("id")}/confirm",JSONObject().put("attempt_id",attempt.getString("id")).put("self_rating","understood"))},model.canWrite(),Glyph.Check)
                }
            }
        }
    }
}
