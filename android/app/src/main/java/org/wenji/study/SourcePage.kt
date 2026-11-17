package org.wenji.study

import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun Sources(model:WenjiModel,wide:Boolean) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    val text = model.editors.importText
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            reading=true;importError=""
            try {
                val (bytes,fileName)=withContext(Dispatchers.IO) {
                    val name=runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                    val data=context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) { val count=stream.read(buffer);if(count<0) break;out.write(buffer,0,count);require(out.size()<=5_000_000){"文件不能超过 5 MB"} };out.toByteArray()
                    }?:error("文件不可读")
                    data to name
                }
                require(bytes.size<=5_000_000){"文件不能超过 5 MB"}
                val body=JSONObject().put("title",title.ifBlank{fileName?:"主动选取的资料"}.take(200))
                if(bytes.take(5).toByteArray().toString(Charsets.US_ASCII)=="%PDF-") body.put("pdf_base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
                else {
                    val importedText=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    require(importedText.length<=500_000){"文本最多 50 万字符"};body.put("text",importedText)
                }
                check(!model.busy) { "服务正在处理其他操作，请稍后重新选择文件" }
                model.mutate("/sources",body){importError="";title=""}
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { importError=e.message?:"导入失败，请检查文件格式";model.reportFailure("资料导入失败：$importError") }
            finally { reading=false }
        }
    }
    PageHeading("课程资料","让每一次解释，都能回到原始依据。",model,wide)
    if (model.state.objects("sources").none { !it.optBoolean("deleted") }) {
        WhiteCard {
            Text("先用一份合成教材体验",style=MaterialTheme.typography.titleMedium)
            Text("内置两页资源分配图材料，供演示引用、反例和复习。手机离线即可导入，不是真实课堂采样。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
            PrimaryAction("导入内置合成教材",model::importDemoMaterial,model.canWrite(),Glyph.Book)
        }
    }
    WhiteCard {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Surface(color=WenjiColors.TealSoft,shape=RoundedCornerShape(16.dp),modifier=Modifier.size(56.dp)) { Box(contentAlignment=Alignment.Center){LineIcon(Glyph.File,Modifier.size(27.dp),WenjiColors.Teal)} }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) { Text("把课堂资料放进来",style=MaterialTheme.typography.titleMedium);Text("可复制文本的 PDF / UTF-8 文本",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PrimaryAction(if(reading) "读取文件中…" else "选择文件",{picker.launch(arrayOf("application/pdf","text/plain"))},model.canWrite()&&!reading,Glyph.Plus)
            TextButton(onClick={pasteOpen=!pasteOpen},enabled=!reading) { Text(if(pasteOpen) "收起" else "粘贴文本 / 命名",style=MaterialTheme.typography.bodySmall) }
        }
        Text("仅导入有权使用的资料 · 文件 ≤ 5 MB · 扫描件暂不支持",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
        if(pasteOpen) {
            HorizontalDivider()
            InputField(title,{title=it.take(200)},"资料名称（选填，默认使用文件名）",maxLines=1)
            InputField(text,model.editors::updateImport,"粘贴可核对的课程原文，最多 50 万字符",minLines=4,maxLines=8)
            Text("未提交草稿仅在本次会话保留，结束应用后可能丢失。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            PrimaryAction("导入文本",{model.mutate("/sources",JSONObject().put("title",title.ifBlank{"文本资料"}).put("text",text)){model.editors.finishImport(text);title="";pasteOpen=false}},model.canWrite()&&text.isNotBlank(),Glyph.Book)
        }
        if(importError.isNotBlank()) Notice(importError,Tone.Red)
    }
    val sources=model.state.objects("sources")
    SectionTitle("我的资料","${sources.count{!it.optBoolean("deleted")}} 份当前资料")
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        InputField(searchQuery,{searchQuery=it.take(2000)},"查找当前课程原文",Modifier.weight(1f),maxLines=1,leading=Glyph.Search)
        TextButton(onClick={model.search(searchQuery)},enabled=model.connected&&!model.busy&&searchQuery.isNotBlank()) { Text("检索") }
    }
    if(model.searchResult.optString("query").isNotBlank()) {
        WhiteCard {
            Text("检索结果",style=MaterialTheme.typography.titleSmall)
            Text("词项匹配只用于寻找片段，不代表答案正确。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            if(model.searchResult.objects("candidates").isEmpty()) Text("未找到匹配原文。试试更具体的词，或补充资料。",style=MaterialTheme.typography.bodyMedium)
            for(candidate in model.searchResult.objects("candidates")) {
                Text("${candidate.optString("source_title")} · v${candidate.optInt("revision")} · 第 ${candidate.optInt("page")} 页",color=WenjiColors.Teal,style=MaterialTheme.typography.titleSmall)
                Text(candidate.optString("quote"),style=MaterialTheme.typography.bodyMedium);HorizontalDivider()
            }
        }
    }
    if(sources.isEmpty()) EmptyState(Glyph.Book,"资料库还是空的","导入一份讲义，为疑问找到第一条依据。")
    for(source in sources.reversed()) key(source.optString("id")) { SourceCard(source,model) }
}

@Composable private fun SourceCard(source:JSONObject,model:WenjiModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sourceId = source.getString("id")
    val revisionDraft = model.editors.revision(sourceId)
    var menu by remember { mutableStateOf(false) }
    var deletePrompt by remember { mutableStateOf(false) }
    val deleted=source.optBoolean("deleted")
    WhiteCard {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(color=if(deleted) WenjiColors.Field else WenjiColors.TealSoft,shape=RoundedCornerShape(12.dp),modifier=Modifier.size(42.dp)) { Box(contentAlignment=Alignment.Center){LineIcon(Glyph.File,Modifier.size(21.dp),if(deleted) WenjiColors.Muted else WenjiColors.Teal)} }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(source.optString("title"),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("v${source.optInt("revision")}  ·  ${if(deleted) "资料已撤回" else "${source.objects("pages").size} 页 · 当前版本"}",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
            }
            if(!deleted) Box {
                IconButton(onClick={menu=true},modifier=Modifier.semantics { contentDescription="资料操作" }) { LineIcon(Glyph.More) }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("修订资料")},onClick={menu=false;model.editors.beginRevision(sourceId,source.getInt("revision"),source.objects("pages").joinToString("\u000c"){it.optString("text")})})
                    DropdownMenuItem(text={Text("撤回资料",color=WenjiColors.Red)},onClick={menu=false;deletePrompt=true},enabled=model.canWrite())
                }
            }
        }
        if(!deleted) {
            TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) { Text(if(expanded) "收起原文" else "查看原文");Spacer(Modifier.width(6.dp));LineIcon(if(expanded) Glyph.Down else Glyph.Right,Modifier.size(16.dp),WenjiColors.Teal) }
            if(expanded) {
                HorizontalDivider()
                for(page in source.objects("pages")) { Tag("第 ${page.optInt("page")} 页");Text(page.optString("text"),style=MaterialTheme.typography.bodyMedium) }
            }
            if(revisionDraft!=null) {
                HorizontalDivider();Text("修订为新版本 · 草稿基于 v${revisionDraft.baseRevision}",style=MaterialTheme.typography.titleSmall)
                Notice("保存后，依赖此资料的解释和复习卡会进入待复核。")
                if(source.getInt("revision")!=revisionDraft.baseRevision) Notice("资料已有新版本。当前草稿仍基于 v${revisionDraft.baseRevision}，保存时会校验版本冲突。",Tone.Red)
                InputField(revisionDraft.text,{model.editors.updateRevision(sourceId,it)},"新版本全文",minLines=5,maxLines=10)
                Text("未提交草稿仅在本次会话保留，结束应用后可能丢失。",color=WenjiColors.Muted,style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    PrimaryAction("保存新版本",{model.mutate("/sources/$sourceId/revisions",JSONObject().put("base_revision",revisionDraft.baseRevision).put("text",revisionDraft.text)){model.editors.finishRevision(sourceId,revisionDraft)}},model.canWrite()&&revisionDraft.text.isNotBlank())
                    TextButton(onClick={model.editors.discardRevision(sourceId)}) { Text("取消") }
                }
            }
        }
    }
    if(deletePrompt) AlertDialog(onDismissRequest={deletePrompt=false},title={Text("撤回这份资料？")},text={Text("资料正文和引用内容将被清除，相关解释与复习卡进入待复核。历史作答会保留并标记依据失效。")},confirmButton={TextButton(onClick={model.mutate("/sources/${source.getString("id")}/delete",JSONObject().put("base_revision",source.getInt("revision"))){deletePrompt=false}},enabled=model.canWrite()){Text("确认撤回",color=WenjiColors.Red)}},dismissButton={TextButton(onClick={deletePrompt=false}){Text("取消")}})
}
