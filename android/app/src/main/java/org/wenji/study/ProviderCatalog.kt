package org.wenji.study

/** Public UI snapshots deliberately contain no credential values. */
internal data class ProviderModelView(val id:String, val model:String, val maxOutputTokens:Int=2048)
internal data class ProviderView(val id:String, val name:String, val protocol:String, val baseUrl:String,
    val hasSavedKey:Boolean, val models:List<ProviderModelView> = emptyList())
internal data class ProviderCatalogView(val providers:List<ProviderView> = emptyList(),
    val activeProviderId:String?=null, val activeModelId:String?=null)
internal class ProviderEditForm(val id:String?=null, val name:String="", val protocol:String="openai_chat",
    val baseUrl:String="", val apiKey:String="", val hasSavedKey:Boolean=false) {
    override fun toString():String="ProviderEditForm(credentials=redacted)"
}
internal data class ProviderModelForm(val providerId:String, val id:String?=null, val model:String="", val maxOutputTokens:Int=2048)

internal fun validateProviderForm(form:ProviderEditForm) {
    val name=form.name.trim()
    if(name.isEmpty() || name.codePointCount(0,name.length)>60 || name.any { it.isISOControl() } ||
        name.codePoints().anyMatch { it in 0xd800..0xdfff } ||
        !name.codePoints().anyMatch { Character.isLetterOrDigit(it) || Character.getType(it)==Character.OTHER_SYMBOL.toInt() })
        throw ModelSettingsException("请填写供应商名称（1至60个可见字符）。")
    validateModelSettings(ModelSettingsForm(protocol=form.protocol,baseUrl=form.baseUrl,model="validation-placeholder",apiKey=form.apiKey))
}
internal fun validateProviderModelForm(form:ProviderModelForm,provider:ProviderView) {
    if(form.providerId!=provider.id) throw ModelSettingsException("供应商已变化，请重新打开模型设置。")
    validateModelSettings(ModelSettingsForm(provider.protocol,provider.baseUrl,form.model,maxOutputTokens=form.maxOutputTokens))
}
internal fun providerAfterSelection(catalog:ProviderCatalogView,providerId:String,modelId:String):ProviderCatalogView {
    val provider=catalog.providers.find { it.id==providerId } ?: throw ModelSettingsException("供应商不存在，请刷新列表。")
    if(!provider.hasSavedKey || provider.models.none { it.id==modelId }) throw ModelSettingsException("模型或密钥不可用，请先完善供应商配置。")
    return catalog.copy(activeProviderId=providerId,activeModelId=modelId)
}
internal fun providerAfterRemoval(catalog:ProviderCatalogView,providerId:String):ProviderCatalogView {
    if(catalog.providers.none { it.id==providerId }) throw ModelSettingsException("供应商不存在，请刷新列表。")
    val removed=catalog.activeProviderId==providerId
    return catalog.copy(providers=catalog.providers.filterNot { it.id==providerId },
        activeProviderId=if(removed) null else catalog.activeProviderId,activeModelId=if(removed) null else catalog.activeModelId)
}
internal fun modelAfterRemoval(catalog:ProviderCatalogView,providerId:String,modelId:String):ProviderCatalogView {
    val provider=catalog.providers.find { it.id==providerId } ?: throw ModelSettingsException("供应商不存在，请刷新列表。")
    if(provider.models.none { it.id==modelId }) throw ModelSettingsException("模型不存在，请刷新列表。")
    val removed=catalog.activeProviderId==providerId && catalog.activeModelId==modelId
    return catalog.copy(providers=catalog.providers.map { if(it.id==providerId) it.copy(models=it.models.filterNot { m->m.id==modelId }) else it },
        activeProviderId=if(removed) null else catalog.activeProviderId,activeModelId=if(removed) null else catalog.activeModelId)
}
