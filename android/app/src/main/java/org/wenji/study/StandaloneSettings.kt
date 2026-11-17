package org.wenji.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Plaintext exists only while configuring the embedded engine. Never log this object. */
internal class StandaloneProfile(val configJson:String?,val secretsJson:String="{}",internal val preparedCatalog:PreparedCatalog?=null) {
    override fun toString():String="StandaloneProfile(credentials=redacted)"
}
/** Configure activeProfile first, then commitPrepared. Secret snapshot is never UI state. */
internal class PreparedCatalog internal constructor(val catalog:ProviderCatalogView,val activeProfile:StandaloneProfile,
    internal val storedConfig:String,internal val storedSecrets:String,internal val previousStamp:String) {
    override fun toString():String="PreparedCatalog(credentials=redacted)"
}

internal class StandaloneSettings(context:Context,storageName:String="phone_model_settings_v1",
    private val keyAlias:String="wenji_phone_model_aes_v1") {
    init {
        require(storageName.matches(Regex("[A-Za-z0-9_-]+"))) { "模型设置存储名称无效" }
        require(keyAlias.matches(Regex("[A-Za-z0-9_-]+"))) { "模型设置密钥名称无效" }
    }
    private val preferences=context.applicationContext.getSharedPreferences(storageName,Context.MODE_PRIVATE)
    private class Record(val view:ProviderCatalogView,val keys:Map<String,String>,val stamp:String) {
        override fun toString():String="CatalogRecord(credentials=redacted)"
    }
    @Synchronized fun load():StandaloneProfile=secureOperation { val r=readRecord(); activeProfile(r.view,r.keys) }
    @Synchronized fun loadCatalog():ProviderCatalogView=secureOperation { readRecord().view }
    @Synchronized fun loadProviderForm(id:String):ProviderEditForm=secureOperation {
        val p=readRecord().view.providers.find { it.id==id } ?: throw ModelSettingsException("供应商不存在，请刷新列表。")
        ProviderEditForm(p.id,p.name,p.protocol,p.baseUrl,hasSavedKey=p.hasSavedKey)
    }
    @Synchronized fun loadModelForm(providerId:String,modelId:String):ProviderModelForm=secureOperation {
        val p=readRecord().view.providers.find { it.id==providerId } ?: throw ModelSettingsException("供应商不存在，请刷新列表。")
        val m=p.models.find { it.id==modelId } ?: throw ModelSettingsException("模型不存在，请刷新列表。")
        ProviderModelForm(p.id,m.id,m.model,m.maxOutputTokens)
    }
    @Synchronized fun prepareUpsertProvider(form:ProviderEditForm):PreparedCatalog=secureOperation {
        validateProviderForm(form)
        val r=readRecord()
        val old=form.id?.let { id->r.view.providers.find { it.id==id } ?: throw ModelSettingsException("供应商已变化，请重新打开设置。") }
        if(old==null && r.view.providers.size>=20) throw ModelSettingsException("最多保存20个供应商。")
        val id=old?.id ?: UUID.randomUUID().toString()
        val key=if(form.apiKey.isNotEmpty()) form.apiKey else {
            if(old==null) throw ModelSettingsException("新增供应商需要填写个人 API Key。")
            validateSavedKeyDestination(form.baseUrl,old.baseUrl)
            r.keys[id] ?: throw ModelSettingsException("保存的密钥不可用，请重新输入。")
        }
        validateModelKey(key)
        val p=ProviderView(id,form.name.trim(),form.protocol,form.baseUrl.trim().trimEnd('/'),true,old?.models ?: emptyList())
        val list=if(old==null) r.view.providers+p else r.view.providers.map { if(it.id==id) p else it }
        prepare(r,r.view.copy(providers=list),r.keys+(id to key))
    }
    @Synchronized fun prepareUpsertModel(form:ProviderModelForm):PreparedCatalog=secureOperation {
        val r=readRecord()
        val p=r.view.providers.find { it.id==form.providerId } ?: throw ModelSettingsException("供应商不存在，请刷新列表。")
        validateProviderModelForm(form,p)
        if(form.id!=null && p.models.none { it.id==form.id }) throw ModelSettingsException("模型已变化，请重新打开设置。")
        if(form.id==null && p.models.size>=40) throw ModelSettingsException("每个供应商最多保存40个模型。")
        if(p.models.any { it.id!=form.id && it.model==form.model.trim() }) throw ModelSettingsException("该供应商已保存同名模型。")
        val entry=ProviderModelView(form.id ?: UUID.randomUUID().toString(),form.model.trim(),form.maxOutputTokens)
        val models=if(form.id==null) p.models+entry else p.models.map { if(it.id==form.id) entry else it }
        prepare(r,r.view.copy(providers=r.view.providers.map { if(it.id==p.id) it.copy(models=models) else it }),r.keys)
    }
    @Synchronized fun prepareSelect(providerId:String,modelId:String):PreparedCatalog=secureOperation {
        val r=readRecord(); prepare(r,providerAfterSelection(r.view,providerId,modelId),r.keys)
    }
    @Synchronized fun prepareRemoveProvider(id:String):PreparedCatalog=secureOperation {
        val r=readRecord(); prepare(r,providerAfterRemoval(r.view,id),r.keys-id)
    }
    @Synchronized fun prepareRemoveModel(providerId:String,modelId:String):PreparedCatalog=secureOperation {
        val r=readRecord(); prepare(r,modelAfterRemoval(r.view,providerId,modelId),r.keys)
    }
    /** Disable model calls without erasing supplier profiles or encrypted credentials. */
    @Synchronized fun prepareDemo():PreparedCatalog=secureOperation {
        val r=readRecord(); prepare(r,r.view.copy(activeProviderId=null,activeModelId=null),r.keys)
    }
    @Synchronized fun commitPrepared(prepared:PreparedCatalog):Unit=secureOperation {
        if(stamp()!=prepared.previousStamp) throw ModelSettingsException("模型设置已变化，请重新打开后操作。")
        decodeRecord(prepared.storedConfig,prepared.storedSecrets,prepared.previousStamp)
        writeEncrypted(prepared.storedConfig,prepared.storedSecrets)
    }
    /** Compatibility with the original editor and isolated storage tests. */
    @Synchronized fun loadForm():ModelSettingsForm=secureOperation {
        val r=readRecord(); val p=r.view.providers.find { it.id==r.view.activeProviderId } ?: return@secureOperation ModelSettingsForm()
        val m=p.models.find { it.id==r.view.activeModelId } ?: throw IllegalStateException()
        ModelSettingsForm(p.protocol,p.baseUrl,m.model,maxOutputTokens=m.maxOutputTokens,hasSavedKey=true)
    }
    @Synchronized fun prepare(form:ModelSettingsForm):StandaloneProfile=secureOperation {
        validateModelSettings(form)
        val r=readRecord(); val old=r.view.providers.find { it.id==r.view.activeProviderId }
        val key=if(form.apiKey.isNotEmpty()) form.apiKey else {
            if(old!=null) validateSavedKeyDestination(form.baseUrl,old.baseUrl)
            old?.let { r.keys[it.id] } ?: ""
        }
        validateModelKey(key)
        val pid=old?.id ?: UUID.randomUUID().toString(); val mid=r.view.activeModelId ?: UUID.randomUUID().toString()
        val m=ProviderModelView(mid,form.model.trim(),form.maxOutputTokens)
        val models=old?.models?.map { if(it.id==mid) m else it } ?: listOf(m)
        val p=ProviderView(pid,old?.name ?: "已保存的供应商",form.protocol,form.baseUrl.trim().trimEnd('/'),true,models)
        val list=if(old==null) r.view.providers+p else r.view.providers.map { if(it.id==pid) p else it }
        val staged=prepare(r,ProviderCatalogView(list,pid,mid),r.keys+(pid to key))
        StandaloneProfile(staged.activeProfile.configJson,staged.activeProfile.secretsJson,staged)
    }
    @Synchronized fun save(profile:StandaloneProfile):Unit=secureOperation {
        if(profile.configJson==null) return@secureOperation clear()
        profile.preparedCatalog?.let { return@secureOperation commitPrepared(it) }
        val c=JSONObject(profile.configJson)
        val form=ModelSettingsForm(c.getString("protocol"),c.getString("base_url"),c.getString("model"),
            JSONObject(profile.secretsJson).getString(KEY_ENV),c.getJSONObject("generation").getInt("max_output_tokens"))
        commitPrepared(prepare(form).preparedCatalog!!)
    }
    /** Legacy destructive reset; new offline mode uses prepareDemo instead. */
    @Synchronized fun clear():Unit=secureOperation { commit(emptyMap()) }

    private fun prepare(old:Record,view:ProviderCatalogView,keys:Map<String,String>):PreparedCatalog {
        val config=encodeView(view); val secrets=JSONObject(keys).toString()
        val valid=decodeRecord(config,secrets,old.stamp)
        return PreparedCatalog(valid.view,activeProfile(valid.view,valid.keys),config,secrets,old.stamp)
    }
    private fun activeProfile(view:ProviderCatalogView,keys:Map<String,String>):StandaloneProfile {
        if(view.activeProviderId==null) return StandaloneProfile(null)
        val p=view.providers.first { it.id==view.activeProviderId }; val m=p.models.first { it.id==view.activeModelId }
        val config=JSONObject().put("protocol",p.protocol).put("base_url",p.baseUrl).put("model",m.model)
            .put("label",p.name).put("api_key_env",KEY_ENV).put("timeout_seconds",60)
            .put("generation",JSONObject().put("max_output_tokens",m.maxOutputTokens))
        return StandaloneProfile(config.toString(),JSONObject().put(KEY_ENV,keys.getValue(p.id)).toString())
    }
    private fun readRecord():Record {
        val config=preferences.getString(CONFIG,null); val ciphertext=preferences.getString(CIPHERTEXT,null); val iv=preferences.getString(IV,null)
        if(config==null && ciphertext==null && iv==null) return Record(ProviderCatalogView(),emptyMap(),stamp())
        if(config==null || ciphertext==null || iv==null) throw IllegalStateException()
        val cipher=Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE,readKey() ?: throw IllegalStateException(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
        cipher.updateAAD(config.toByteArray(Charsets.UTF_8))
        val plain=cipher.doFinal(Base64.decode(ciphertext,Base64.NO_WRAP))
        val secrets=try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
        if(JSONObject(config).has("version")) return decodeRecord(config,secrets,stamp())
        // Authenticate the entire v1 record before local, atomic migration.
        val legacy=JSONObject(config); val key=JSONObject(secrets).getString(KEY_ENV); validateModelKey(key)
        val form=ModelSettingsForm(legacy.getString("protocol"),legacy.getString("base_url"),legacy.getString("model"),
            maxOutputTokens=legacy.getJSONObject("generation").getInt("max_output_tokens"))
        validateModelSettings(form)
        val p=ProviderView("legacy-provider","已保存的供应商",form.protocol,form.baseUrl,true,listOf(ProviderModelView("legacy-model",form.model,form.maxOutputTokens)))
        val migrated=prepare(Record(ProviderCatalogView(),emptyMap(),stamp()),ProviderCatalogView(listOf(p),p.id,"legacy-model"),mapOf(p.id to key))
        writeEncrypted(migrated.storedConfig,migrated.storedSecrets)
        return Record(migrated.catalog,mapOf(p.id to key),stamp())
    }
    private fun encodeView(view:ProviderCatalogView):String=JSONObject().put("version",2)
        .put("active_provider_id",view.activeProviderId ?: JSONObject.NULL).put("active_model_id",view.activeModelId ?: JSONObject.NULL)
        .put("providers",JSONArray().also { suppliers->view.providers.forEach { p->
            suppliers.put(JSONObject().put("id",p.id).put("name",p.name).put("protocol",p.protocol).put("base_url",p.baseUrl)
                .put("models",JSONArray().also { models->p.models.forEach { m->models.put(JSONObject().put("id",m.id).put("model",m.model).put("max_output_tokens",m.maxOutputTokens)) } }))
        } }).toString()
    private fun decodeRecord(config:String,secrets:String,stamp:String):Record {
        val c=JSONObject(config); check(c.getInt("version")==2)
        val keys=JSONObject(secrets); val array=c.getJSONArray("providers"); check(array.length()<=20)
        val providers=(0 until array.length()).map { i->
            val p=array.getJSONObject(i); val id=p.getString("id"); check(validId(id))
            validateModelKey(keys.getString(id))
            val form=ProviderEditForm(id,p.getString("name"),p.getString("protocol"),p.getString("base_url"),hasSavedKey=true); validateProviderForm(form)
            val ma=p.getJSONArray("models"); check(ma.length()<=40)
            val models=(0 until ma.length()).map { j->
                val m=ma.getJSONObject(j); val mid=m.getString("id"); check(validId(mid))
                val model=m.getString("model"); val max=m.getInt("max_output_tokens")
                validateModelSettings(ModelSettingsForm(form.protocol,form.baseUrl,model,maxOutputTokens=max))
                ProviderModelView(mid,model,max)
            }
            check(models.map { it.id }.distinct().size==models.size && models.map { it.model }.distinct().size==models.size)
            ProviderView(id,form.name,form.protocol,form.baseUrl,true,models)
        }
        check(providers.map { it.id }.distinct().size==providers.size)
        check(keys.keys().asSequence().toSet()==providers.map { it.id }.toSet())
        val ap=if(c.isNull("active_provider_id")) null else c.getString("active_provider_id")
        val am=if(c.isNull("active_model_id")) null else c.getString("active_model_id")
        check((ap==null)==(am==null))
        val view=ProviderCatalogView(providers,ap,am); if(ap!=null) providerAfterSelection(view,ap,am!!)
        return Record(view,providers.associate { it.id to keys.getString(it.id) },stamp)
    }
    private fun validId(id:String)=id.matches(Regex("[A-Za-z0-9_-]{1,80}"))
    private fun stamp():String {
        val values=JSONArray(listOf(CONFIG,CIPHERTEXT,IV).map { preferences.getString(it,null) ?: JSONObject.NULL }).toString()
        return MessageDigest.getInstance("SHA-256").digest(values.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun writeEncrypted(config:String,secrets:String) {
        val cipher=Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE,readKey() ?: createKey())
        cipher.updateAAD(config.toByteArray(Charsets.UTF_8)); val plain=secrets.toByteArray(Charsets.UTF_8)
        val ciphertext=try { cipher.doFinal(plain) } finally { plain.fill(0) }
        commit(mapOf(CONFIG to config,CIPHERTEXT to Base64.encodeToString(ciphertext,Base64.NO_WRAP),IV to Base64.encodeToString(cipher.iv,Base64.NO_WRAP)))
    }
    private fun commit(values:Map<String,String>) {
        val previous=listOf(CONFIG,CIPHERTEXT,IV).associateWith { preferences.getString(it,null) }
        try {
            val edit=preferences.edit().clear(); values.forEach { (name,value)->edit.putString(name,value) }
            if(!edit.commit()) throw IllegalStateException()
        } catch(_:Exception) {
            val restore=preferences.edit().clear(); previous.forEach { (name,value)->if(value!=null) restore.putString(name,value) }
            try { restore.commit() } catch(_:Exception) { }
            throw ModelSettingsException("模型设置未能保存，请重试；当前操作未报告成功。")
        }
    }
    private fun readKey():SecretKey? {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; return store.getKey(keyAlias,null) as? SecretKey
    }
    private fun createKey():SecretKey=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build()); generateKey()
    }
    // Android shares this preferences object for the same file. The lock makes the
    // snapshot check and commit atomic even across two settings instances.
    private inline fun <T> secureOperation(action:()->T):T=synchronized(preferences) {
        try { action() }
        catch(error:ModelSettingsException) { throw error }
        catch(_:Exception) { throw ModelSettingsException("无法读取或保存加密模型设置，请保留应用数据并检查配置。") }
    }
    private companion object {
        const val KEY_ENV="WENJI_PHONE_KEY"
        const val TRANSFORMATION="AES/GCM/NoPadding"
        const val CONFIG="config"
        const val CIPHERTEXT="encrypted_secrets"
        const val IV="encryption_iv"
    }
}
