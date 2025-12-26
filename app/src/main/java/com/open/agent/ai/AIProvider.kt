package com.open.agent.ai

/**
 * AI提供商枚举
 */
enum class AIProvider(val displayName: String, val description: String) {
    ZHIPU("智谱AI (GLM-4V)", "支持视觉理解的国产大模型"),
    OLLAMA("Ollama", "本地运行的开源大模型"),
    OPENAI("OpenAI", "GPT-4 Vision"),
    CUSTOM("自定义", "自定义API端点")
}

/**
 * AI提供商配置
 */
sealed class ProviderConfig {
    
    /**
     * 智谱AI配置
     * 官网: https://open.bigmodel.cn/
     */
    data class ZhipuConfig(
        val apiKey: String,
        val model: String = "glm-4v-flash",  // glm-4v, glm-4v-plus, glm-4v-flash
        val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4"
    ) : ProviderConfig()
    
    /**
     * Ollama配置
     * 支持本地运行的视觉模型如 llava, bakllava
     */
    data class OllamaConfig(
        val baseUrl: String = "http://localhost:11434",
        val model: String = "llava"  // llava, bakllava, llava-llama3
    ) : ProviderConfig()
    
    /**
     * OpenAI配置
     */
    data class OpenAIConfig(
        val apiKey: String,
        val model: String = "gpt-4o",
        val baseUrl: String = "https://api.openai.com/v1"
    ) : ProviderConfig()
    
    /**
     * 自定义配置
     */
    data class CustomConfig(
        val baseUrl: String,
        val apiKey: String? = null,
        val model: String? = null
    ) : ProviderConfig()
}
