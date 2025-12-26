package com.open.agent.config

import android.content.Context
import android.content.SharedPreferences
import com.open.agent.ai.AIProvider
import com.open.agent.ai.ProviderConfig

/**
 * AI配置管理器
 * 使用SharedPreferences持久化保存AI服务配置
 */
class ConfigManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "ai_config"
        
        // 配置键
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_HAS_CONFIG = "has_config"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 是否有已保存的配置
     */
    fun hasConfig(): Boolean {
        return prefs.getBoolean(KEY_HAS_CONFIG, false)
    }
    
    /**
     * 保存AI配置
     */
    fun saveConfig(provider: AIProvider, config: ProviderConfig) {
        prefs.edit().apply {
            putString(KEY_PROVIDER, provider.name)
            putBoolean(KEY_HAS_CONFIG, true)
            
            when (config) {
                is ProviderConfig.ZhipuConfig -> {
                    putString(KEY_API_KEY, config.apiKey)
                    putString(KEY_BASE_URL, config.baseUrl)
                    putString(KEY_MODEL, config.model)
                }
                is ProviderConfig.OllamaConfig -> {
                    putString(KEY_API_KEY, "")
                    putString(KEY_BASE_URL, config.baseUrl)
                    putString(KEY_MODEL, config.model)
                }
                is ProviderConfig.OpenAIConfig -> {
                    putString(KEY_API_KEY, config.apiKey)
                    putString(KEY_BASE_URL, config.baseUrl)
                    putString(KEY_MODEL, config.model)
                }
                is ProviderConfig.CustomConfig -> {
                    putString(KEY_API_KEY, config.apiKey ?: "")
                    putString(KEY_BASE_URL, config.baseUrl)
                    putString(KEY_MODEL, config.model ?: "")
                }
            }
            
            apply()
        }
    }
    
    /**
     * 加载已保存的配置
     * @return Pair<AIProvider, ProviderConfig>? 如果没有配置返回null
     */
    fun loadConfig(): Pair<AIProvider, ProviderConfig>? {
        if (!hasConfig()) return null
        
        val providerName = prefs.getString(KEY_PROVIDER, null) ?: return null
        val provider = try {
            AIProvider.valueOf(providerName)
        } catch (e: Exception) {
            return null
        }
        
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val baseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
        val model = prefs.getString(KEY_MODEL, "") ?: ""
        
        val config: ProviderConfig = when (provider) {
            AIProvider.ZHIPU -> ProviderConfig.ZhipuConfig(
                apiKey = apiKey,
                model = model.ifEmpty { "glm-4v-flash" },
                baseUrl = baseUrl.ifEmpty { "https://open.bigmodel.cn/api/paas/v4" }
            )
            AIProvider.OLLAMA -> ProviderConfig.OllamaConfig(
                baseUrl = baseUrl.ifEmpty { "http://localhost:11434" },
                model = model.ifEmpty { "llava" }
            )
            AIProvider.OPENAI -> ProviderConfig.OpenAIConfig(
                apiKey = apiKey,
                model = model.ifEmpty { "gpt-4o" },
                baseUrl = baseUrl.ifEmpty { "https://api.openai.com/v1" }
            )
            AIProvider.CUSTOM -> ProviderConfig.CustomConfig(
                baseUrl = baseUrl,
                apiKey = apiKey.ifEmpty { null },
                model = model.ifEmpty { null }
            )
        }
        
        return Pair(provider, config)
    }
    
    /**
     * 获取保存的UI状态数据
     */
    fun getSavedUiData(): SavedUiData? {
        if (!hasConfig()) return null
        
        val providerName = prefs.getString(KEY_PROVIDER, null) ?: return null
        val provider = try {
            AIProvider.valueOf(providerName)
        } catch (e: Exception) {
            return null
        }
        
        return SavedUiData(
            provider = provider,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            model = prefs.getString(KEY_MODEL, "") ?: ""
        )
    }
    
    /**
     * 清除配置
     */
    fun clearConfig() {
        prefs.edit().clear().apply()
    }
}

/**
 * 保存的UI数据
 */
data class SavedUiData(
    val provider: AIProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String
)
