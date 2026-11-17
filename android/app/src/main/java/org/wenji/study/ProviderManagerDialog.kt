package org.wenji.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

private data class ProviderRemoval(val providerId:String,val name:String,val modelId:String?=null)

/** Catalog contains display-only values. An editor's unsaved key never enters saved instance state. */
@Composable
internal fun ProviderManagerDialog(
    catalog:ProviderCatalogView,
    busy:Boolean,
    error:String?,
    onSaveProvider:(ProviderEditForm,()->Unit)->Unit,
    onSaveModel:(ProviderModelForm,()->Unit)->Unit,
    onSelectModel:(String,String)->Unit,
    onDeleteProvider:(String,()->Unit)->Unit,
    onDeleteModel:(String,String,()->Unit)->Unit,
    onUseDemo:()->Unit,
    onDismiss:()->Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf(catalog.activeProviderId) }
    var showProviderEditor by rememberSaveable { mutableStateOf(false) }
    var editingProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingModelProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<ProviderRemoval?>(null) }
    var providerIdsBeforeAdd by remember { mutableStateOf<Set<String>?>(null) }
    val selected=catalog.providers.firstOrNull { it.id==selectedId }
    val activeProvider=catalog.providers.firstOrNull { it.id==catalog.activeProviderId }
    val activeModel=activeProvider?.models?.firstOrNull { it.id==catalog.activeModelId }
    LaunchedEffect(catalog.providers,showProviderEditor) {
        if(!showProviderEditor) providerIdsBeforeAdd?.let { before ->
            catalog.providers.firstOrNull { it.id !in before }?.let { selectedId=it.id }
            providerIdsBeforeAdd=null
        }
    }
    fun addProvider() { editingProviderId=null;showProviderEditor=true }
    fun editProvider(id:String) { editingProviderId=id;showProviderEditor=true }
    fun editModel(providerId:String,modelId:String?) { editingModelProviderId=providerId;editingModelId=modelId }

    Dialog(onDismissRequest={if(!busy) onDismiss()},properties=DialogProperties(
        usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn,
        dismissOnBackPress=!busy,dismissOnClickOutside=!busy,
    )) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.Center) {
            val wide=maxWidth>=760.dp
            Surface(Modifier.padding(if(wide) 24.dp else 8.dp).widthIn(max=1100.dp).fillMaxSize(),
                shape=RoundedCornerShape(if(wide) 24.dp else 18.dp),color=Color.White) {
                Column {
                    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("供应商与模型",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                            TextButton(onClick=onDismiss,enabled=!busy) { Text("完成") }
                        }
                        error?.takeIf { it.isNotBlank() }?.let {
                            Text(it,modifier=Modifier.heightIn(max=96.dp).verticalScroll(rememberScrollState()),color=WenjiColors.Red,style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                    if(wide) Row(Modifier.weight(1f)) {
                        Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            ProviderOverview(activeProvider?.name,activeModel?.model,busy,onUseDemo)
                            HorizontalDivider()
                            ProviderList(catalog,selectedId,busy,onSelect={selectedId=it},onAdd=::addProvider)
                        }
                        VerticalDivider()
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            if(selected==null) ProviderStartHint(catalog.providers.isEmpty(),::addProvider,busy)
                            else ProviderDetails(selected,catalog,busy,onEdit={editProvider(selected.id)},onDelete={pendingRemoval=ProviderRemoval(selected.id,selected.name)},onAddModel={editModel(selected.id,null)},
                                onEditModel={editModel(selected.id,it)},onDeleteModel={pendingRemoval=ProviderRemoval(selected.id,it.model,it.id)},onUseModel={onSelectModel(selected.id,it)})
                        }
                    } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        ProviderOverview(activeProvider?.name,activeModel?.model,busy,onUseDemo)
                        HorizontalDivider()
                        if(selected==null) ProviderList(catalog,selectedId,busy,onSelect={selectedId=it},onAdd=::addProvider)
                        else {
                            TextButton(onClick={selectedId=null},enabled=!busy) { Text("‹ 返回供应商列表") }
                            ProviderDetails(selected,catalog,busy,onEdit={editProvider(selected.id)},onDelete={pendingRemoval=ProviderRemoval(selected.id,selected.name)},onAddModel={editModel(selected.id,null)},
                                onEditModel={editModel(selected.id,it)},onDeleteModel={pendingRemoval=ProviderRemoval(selected.id,it.model,it.id)},onUseModel={onSelectModel(selected.id,it)})
                        }
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=WenjiColors.Teal)
                }
            }
        }
    }
    if(showProviderEditor) key(editingProviderId ?: "new-provider") {
        val initial=catalog.providers.firstOrNull { it.id==editingProviderId }
        ProviderEditorDialog(initial,busy,error,onSave={ form ->
            if(form.id==null) providerIdsBeforeAdd=catalog.providers.map { it.id }.toSet()
            onSaveProvider(form) { showProviderEditor=false }
        },onDismiss={showProviderEditor=false;providerIdsBeforeAdd=null})
    }
    editingModelProviderId?.let { id ->
        catalog.providers.firstOrNull { it.id==id }?.let { provider ->
            key(id,editingModelId ?: "new-model") {
                ProviderModelEditorDialog(provider,provider.models.firstOrNull { it.id==editingModelId },busy,error,
                    onSave={form->onSaveModel(form){editingModelProviderId=null;editingModelId=null}},onDismiss={editingModelProviderId=null;editingModelId=null})
            }
        }
    }
    pendingRemoval?.let { removal ->
        val active=removal.providerId==catalog.activeProviderId && (removal.modelId==null || removal.modelId==catalog.activeModelId)
        AlertDialog(onDismissRequest={if(!busy)pendingRemoval=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
            title={Text(if(removal.modelId==null) "删除供应商？" else "删除模型？")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(removal.name,style=MaterialTheme.typography.titleMedium)
                Text(if(removal.modelId==null) "将移除此供应商、加密密钥及模型列表。已有学习资料、答案和作答不会删除。" else "仅移除这项模型配置。已有学习记录和供应商密钥保留。",style=MaterialTheme.typography.bodyMedium)
                if(active) Notice("这是当前使用的配置。删除后转为离线案例，不会自动改用其他供应商。")
                error?.takeIf { it.isNotBlank() }?.let { Text(it,color=WenjiColors.Red) }
            }},confirmButton={TextButton(onClick={
                if(removal.modelId==null) onDeleteProvider(removal.providerId){pendingRemoval=null}
                else onDeleteModel(removal.providerId,removal.modelId){pendingRemoval=null}
            },enabled=!busy){Text("确认删除",color=WenjiColors.Red)}},dismissButton={TextButton(onClick={pendingRemoval=null},enabled=!busy){Text("取消")}})
    }
}

/** Everything except the title/actions scrolls, including on short landscape windows. */
@Composable private fun ProviderOverview(activeProviderName:String?,activeModelName:String?,busy:Boolean,onUseDemo:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("添加供应商 → 添加模型 → 选择使用",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
        Surface(color=WenjiColors.TealSoft,shape=RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(if(activeModelName==null) "当前：离线案例" else "当前：${activeProviderName.orEmpty()}",style=MaterialTheme.typography.titleSmall,color=WenjiColors.Teal)
                Text(activeModelName ?: "配置完成后，选择一个模型开始使用。",style=MaterialTheme.typography.bodyMedium)
                if(activeModelName!=null) TextButton(onClick=onUseDemo,enabled=!busy,contentPadding=PaddingValues(0.dp)) { Text("暂停模型，使用离线案例") }
            }
        }
        Text("保存、切换不调用模型；生成答案前仍会确认。",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
    }
}

@Composable private fun ProviderList(catalog:ProviderCatalogView,selectedId:String?,busy:Boolean,onSelect:(String)->Unit,onAdd:()->Unit) {
    Button(onClick=onAdd,enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(14.dp)) { Text("＋ 添加供应商") }
    if(catalog.providers.isEmpty()) {
        Text("先添加一个供应商",style=MaterialTheme.typography.titleMedium)
        Text("填写服务地址和 API Key，再添加该服务支持的模型。不同 Key 可以分别建成供应商。",style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Muted)
    }
    for(provider in catalog.providers) {
        val selected=provider.id==selectedId
        Surface(color=if(selected) WenjiColors.TealSoft else WenjiColors.Field,shape=RoundedCornerShape(14.dp),border=BorderStroke(if(selected) 2.dp else 1.dp,if(selected) WenjiColors.Teal else WenjiColors.Border)) {
            Column(Modifier.fillMaxWidth().clickable(enabled=!busy){onSelect(provider.id)}.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(provider.name,style=MaterialTheme.typography.titleMedium)
                Text("${provider.models.size} 个模型 · ${if(provider.hasSavedKey) "密钥已保存" else "待配置密钥"}",style=MaterialTheme.typography.bodySmall,color=WenjiColors.Muted)
                if(catalog.activeProviderId==provider.id) Tag("正在使用",Tone.Teal)
            }
        }
    }
}

@Composable private fun ProviderStartHint(empty:Boolean,onAdd:()->Unit,busy:Boolean) {
    Text(if(empty) "连接你的模型服务" else "选择一个供应商管理模型",style=MaterialTheme.typography.titleLarge)
    Text("每个供应商保存独立的地址、API 格式和加密 Key，可添加多个模型。只有你选中的模型会用于下一次请求。",style=MaterialTheme.typography.bodyLarge)
    if(empty) Button(onClick=onAdd,enabled=!busy) { Text("添加第一个供应商") }
}

@Composable private fun ProviderDetails(provider:ProviderView,catalog:ProviderCatalogView,busy:Boolean,onEdit:()->Unit,onDelete:()->Unit,onAddModel:()->Unit,onEditModel:(String)->Unit,onDeleteModel:(ProviderModelView)->Unit,onUseModel:(String)->Unit) {
    Text(provider.name,style=MaterialTheme.typography.titleLarge)
    Text(modelProtocols[provider.protocol] ?: provider.protocol,style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Muted)
    Surface(color=WenjiColors.Field,shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("服务地址",style=MaterialTheme.typography.titleSmall)
            Text(provider.baseUrl,style=MaterialTheme.typography.bodyMedium)
            Text(if(provider.hasSavedKey) "API Key 已加密保存，不显示明文。" else "尚未保存 API Key。",style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Muted)
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick=onEdit,enabled=!busy) { Text("编辑供应商") }
        TextButton(onClick=onDelete,enabled=!busy) { Text("删除",color=WenjiColors.Red) }
    }
    HorizontalDivider()
    Text("模型列表 · ${provider.models.size}",style=MaterialTheme.typography.titleMedium)
    Button(onClick=onAddModel,enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) { Text("＋ 添加模型") }
    if(provider.models.isEmpty()) Notice("下一步：添加服务商提供的模型名称，然后点“使用此模型”。",Tone.Teal)
    for(model in provider.models) {
        val active=catalog.activeProviderId==provider.id && catalog.activeModelId==model.id
        Surface(color=if(active) WenjiColors.TealSoft else Color.White,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,if(active) WenjiColors.Teal else WenjiColors.Border)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(active) Tag("当前使用",Tone.Teal)
                Text(model.model,style=MaterialTheme.typography.titleMedium)
                Text("本次输出上限：${model.maxOutputTokens} tokens",style=MaterialTheme.typography.bodyMedium,color=WenjiColors.Muted)
                if(!active) Button(onClick={onUseModel(model.id)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("使用此模型") }
                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    TextButton(onClick={onEditModel(model.id)},enabled=!busy,contentPadding=PaddingValues(0.dp)) { Text("编辑模型") }
                    TextButton(onClick={onDeleteModel(model)},enabled=!busy) { Text("删除",color=WenjiColors.Red) }
                }
            }
        }
    }
}

@Composable private fun ProviderEditorDialog(initial:ProviderView?,saving:Boolean,error:String?,onSave:(ProviderEditForm)->Unit,onDismiss:()->Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var protocol by rememberSaveable { mutableStateOf(initial?.protocol ?: "openai_chat") }
    var apiKey by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={EditorHeading(if(initial==null) "添加供应商" else "编辑供应商",validationError ?: error)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(if(initial==null) "先保存连接信息，再添加模型。保存不会调用 API。"
                else "留空 Key 可保留原密钥。保存会更新此供应商，后续生成前仍会确认。",style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name,{name=it.take(60)},label={Text("供应商名称")},placeholder={Text("例如：我的模型服务")},enabled=!saving,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(baseUrl,{baseUrl=it},label={Text("API 基址")},placeholder={Text("https://api.example.com/v1")},supportingText={Text("填写服务商提供的根地址，不含 /chat/completions。更换地址须重新输入 Key。")},enabled=!saving,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false,imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth())
            Text("API 格式",style=MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick={menu=true},enabled=!saving,modifier=Modifier.fillMaxWidth()) { Text(modelProtocols[protocol] ?: "选择格式");Spacer(Modifier.width(6.dp));LineIcon(Glyph.Down,Modifier.size(16.dp),WenjiColors.Teal) }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    modelProtocols.forEach { (id,label)-> DropdownMenuItem(text={Text(label)},onClick={protocol=id;menu=false}) }
                }
            }
            OutlinedTextField(apiKey,{apiKey=it},label={Text("API Key")},placeholder={Text(if(initial?.hasSavedKey==true) "已有密钥，留空保留" else "粘贴此供应商的 Key")},supportingText={Text("仅加密保存在本机；不会显示或回填已保存的 Key。关闭或旋转会清除未保存的 Key。")},enabled=!saving,singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false,imeAction=ImeAction.Done),modifier=Modifier.fillMaxWidth())
        }},
        confirmButton={TextButton(onClick={
            try {
                val form=ProviderEditForm(initial?.id,name,protocol,baseUrl,apiKey,initial?.hasSavedKey==true)
                validateProviderForm(form)
                if(apiKey.isEmpty()) {
                    if(initial?.hasSavedKey!=true) validateModelKey(apiKey)
                    else validateSavedKeyDestination(baseUrl,initial.baseUrl)
                }
                validationError=null
                onSave(form)
            } catch(failure:IllegalArgumentException) {validationError=failure.message ?: "请检查输入。"}
        },enabled=!saving){Text(if(saving) "保存中…" else "保存供应商")}},dismissButton={TextButton(onClick=onDismiss,enabled=!saving){Text("取消")}})
}

@Composable private fun ProviderModelEditorDialog(provider:ProviderView,initial:ProviderModelView?,saving:Boolean,error:String?,onSave:(ProviderModelForm)->Unit,onDismiss:()->Unit) {
    var model by rememberSaveable { mutableStateOf(initial?.model.orEmpty()) }
    var maxTokens by rememberSaveable { mutableStateOf((initial?.maxOutputTokens ?: 2048).toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={EditorHeading(if(initial==null) "添加模型" else "编辑模型",validationError ?: error)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("供应商：${provider.name}",style=MaterialTheme.typography.titleSmall)
            Text(if(initial==null) "填写服务商实际提供的模型名。添加后，点“使用此模型”才会切换。"
                else "填写服务商实际提供的模型名。若正在使用此模型，新设置将用于后续确认的请求。",style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(model,{model=it},label={Text("模型名称 / ID")},enabled=!saving,singleLine=true,keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Next),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(maxTokens,{maxTokens=it.filter(Char::isDigit).take(5)},label={Text("本次请求最大输出 Token")},supportingText={Text("1–16384，默认 2048。输出上限不同于上下文窗口；需符合服务商实际限制。")},enabled=!saving,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),modifier=Modifier.fillMaxWidth())
            ModelCapabilityInfo(model)
        }},
        confirmButton={TextButton(onClick={
            try {
                val tokens=maxTokens.toIntOrNull() ?: 0
                val form=ProviderModelForm(provider.id,initial?.id,model,tokens)
                validateProviderModelForm(form,provider)
                validationError=null
                onSave(form)
            } catch(failure:IllegalArgumentException) {validationError=failure.message ?: "请检查模型名称与输出上限。"}
        },enabled=!saving){Text(if(saving) "保存中…" else "保存模型")}},dismissButton={TextButton(onClick=onDismiss,enabled=!saving){Text("取消")}})
}

@Composable private fun EditorHeading(title:String,error:String?) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        error?.takeIf { it.isNotBlank() }?.let { Text(it,color=WenjiColors.Red,style=MaterialTheme.typography.bodyMedium) }
    }
}
