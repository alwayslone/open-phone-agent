package com.open.agent.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open.agent.ai.AIProvider
import com.open.agent.ai.ProviderConfig
import com.open.agent.config.ConfigManager
import com.open.agent.controller.AgentEvent
import com.open.agent.controller.AgentState
import com.open.agent.parser.ParsedAction
import com.open.agent.service.AgentService
import com.open.agent.voice.VoiceEvent
import com.open.agent.voice.VoiceServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 主界面ViewModel
 * 通过绑定AgentService来执行后台任务
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    // 配置管理器
    private val configManager = ConfigManager(application)
    
    // 服务绑定
    private var agentService: AgentService? = null
    private var isBound = false
    
    // UI状态
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState
    
    // 日志列表
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs
    
    // 语音状态
    private val _voiceState = MutableStateFlow(VoiceServiceState.DISABLED)
    val voiceState: StateFlow<VoiceServiceState> = _voiceState
    
    private val _isVoiceEnabled = MutableStateFlow(false)
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled
    
    // 截图预览
    private val _screenshotPreview = MutableStateFlow<String?>(null)
    val screenshotPreview: StateFlow<String?> = _screenshotPreview
    
    // 服务连接
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AgentService.AgentBinder
            agentService = binder.getService()
            isBound = true
            _uiState.value = _uiState.value.copy(isServiceBound = true)
            addLog(LogEntry(LogLevel.SUCCESS, "服务已连接"))
            
            // 绑定服务后开始监听状态
            startObservingService()
            
            // 开始监听语音状态
            startObservingVoice()
            
            // 自动应用已保存的配置
            applySavedConfig()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            isBound = false
            _uiState.value = _uiState.value.copy(isServiceBound = false)
            addLog(LogEntry(LogLevel.WARNING, "服务已断开"))
        }
    }
    
    init {
        // 加载已保存的配置
        loadSavedConfig()
        // 启动并绑定服务
        startAndBindService()
    }
    
    /**
     * 加载已保存的AI配置
     */
    private fun loadSavedConfig() {
        val savedData = configManager.getSavedUiData()
        if (savedData != null) {
            _uiState.value = _uiState.value.copy(
                selectedProvider = savedData.provider,
                apiKey = savedData.apiKey,
                aiServerUrl = savedData.baseUrl,
                modelName = savedData.model,
                isAIConfigured = false  // 还没有真正配置到服务
            )
            addLog(LogEntry(LogLevel.INFO, "已加载上次保存的配置: ${savedData.provider.displayName}"))
        }
    }
    
    /**
     * 应用已保存的配置到服务
     * 在服务绑定成功后调用
     */
    private fun applySavedConfig() {
        val savedConfig = configManager.loadConfig()
        if (savedConfig != null) {
            val (provider, config) = savedConfig
            agentService?.configureAI(config)
            _uiState.value = _uiState.value.copy(isAIConfigured = true)
            addLog(LogEntry(LogLevel.SUCCESS, "已自动应用保存的AI配置: ${provider.displayName}"))
        }
    }
    
    /**
     * 启动并绑定服务
     */
    fun startAndBindService() {
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_START
        }
        
        // 启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        // 绑定服务
        context.bindService(
            Intent(context, AgentService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        
        addLog(LogEntry(LogLevel.INFO, "正在启动服务..."))
    }
    
    /**
     * 开始监听服务状态
     */
    private fun startObservingService() {
        val service = agentService ?: return
        
        // 监听Agent状态
        viewModelScope.launch {
            service.getAgentState().collectLatest { state ->
                _uiState.value = _uiState.value.copy(
                    agentState = state,
                    isRunning = state == AgentState.RUNNING
                )
            }
        }
        
        // 监听ROOT状态
        viewModelScope.launch {
            service.isRootAvailable().collectLatest { available ->
                _uiState.value = _uiState.value.copy(isRootAvailable = available)
            }
        }
        
        // 监听当前任务
        viewModelScope.launch {
            service.getCurrentTask().collectLatest { task ->
                _uiState.value = _uiState.value.copy(currentTask = task)
            }
        }
        
        // 监听步数
        viewModelScope.launch {
            service.getCurrentStep().collectLatest { step ->
                _uiState.value = _uiState.value.copy(currentStep = step)
            }
        }
        
        // 监听事件
        viewModelScope.launch {
            service.getEvents().collectLatest { event ->
                handleEvent(event)
            }
        }
    }
    
    /**
     * 初始化Agent
     */
    fun initialize() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInitializing = true)
            val success = agentService?.initializeAgent() ?: false
            _uiState.value = _uiState.value.copy(
                isInitializing = false,
                isInitialized = success
            )
        }
    }
    
    /**
     * 配置AI服务 - 智谱AI
     */
    fun configureZhipu(apiKey: String, model: String = "glm-4v-flash") {
        if (apiKey.isBlank()) {
            addLog(LogEntry(LogLevel.WARNING, "请输入智谱AI的API Key"))
            return
        }
        val config = ProviderConfig.ZhipuConfig(
            apiKey = apiKey,
            model = model
        )
        agentService?.configureAI(config)
        
        // 保存配置
        configManager.saveConfig(AIProvider.ZHIPU, config)
        
        _uiState.value = _uiState.value.copy(
            selectedProvider = AIProvider.ZHIPU,
            apiKey = apiKey,
            modelName = model,
            isAIConfigured = true
        )
        addLog(LogEntry(LogLevel.SUCCESS, "智谱AI配置成功并已保存: $model"))
    }
    
    /**
     * 配置AI服务 - Ollama
     */
    fun configureOllama(baseUrl: String = "http://localhost:11434", model: String = "llava") {
        val config = ProviderConfig.OllamaConfig(
            baseUrl = baseUrl,
            model = model
        )
        agentService?.configureAI(config)
        
        // 保存配置
        configManager.saveConfig(AIProvider.OLLAMA, config)
        
        _uiState.value = _uiState.value.copy(
            selectedProvider = AIProvider.OLLAMA,
            aiServerUrl = baseUrl,
            modelName = model,
            isAIConfigured = true
        )
        addLog(LogEntry(LogLevel.SUCCESS, "Ollama配置成功并已保存: $model @ $baseUrl"))
    }
    
    /**
     * 配置AI服务 - OpenAI兼容接口
     */
    fun configureOpenAI(apiKey: String, baseUrl: String = "https://api.openai.com/v1", model: String = "gpt-4o") {
        if (apiKey.isBlank()) {
            addLog(LogEntry(LogLevel.WARNING, "请输入API Key"))
            return
        }
        val config = ProviderConfig.OpenAIConfig(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model
        )
        agentService?.configureAI(config)
        
        // 保存配置
        configManager.saveConfig(AIProvider.OPENAI, config)
        
        _uiState.value = _uiState.value.copy(
            selectedProvider = AIProvider.OPENAI,
            apiKey = apiKey,
            aiServerUrl = baseUrl,
            modelName = model,
            isAIConfigured = true
        )
        addLog(LogEntry(LogLevel.SUCCESS, "OpenAI配置成功并已保存: $model"))
    }
    
    /**
     * 配置AI服务 - 自定义
     */
    fun configureCustom(baseUrl: String, apiKey: String? = null, model: String? = null) {
        if (baseUrl.isBlank()) {
            addLog(LogEntry(LogLevel.WARNING, "请输入服务器地址"))
            return
        }
        val config = ProviderConfig.CustomConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model
        )
        agentService?.configureAI(config)
        
        // 保存配置
        configManager.saveConfig(AIProvider.CUSTOM, config)
        
        _uiState.value = _uiState.value.copy(
            selectedProvider = AIProvider.CUSTOM,
            aiServerUrl = baseUrl,
            apiKey = apiKey ?: "",
            modelName = model ?: "",
            isAIConfigured = true
        )
        addLog(LogEntry(LogLevel.SUCCESS, "自定义API配置成功并已保存: $baseUrl"))
    }
    
    /**
     * 更新选中的提供商
     */
    fun selectProvider(provider: AIProvider) {
        _uiState.value = _uiState.value.copy(selectedProvider = provider)
    }
    
    /**
     * 更新模型名称
     */
    fun updateModelName(model: String) {
        _uiState.value = _uiState.value.copy(modelName = model)
    }
    
    /**
     * 开始执行任务
     */
    fun startTask(instruction: String) {
        if (instruction.isBlank()) {
            addLog(LogEntry(LogLevel.WARNING, "请输入任务指令"))
            return
        }
        agentService?.startTask(instruction)
    }
    
    /**
     * 停止任务
     */
    fun stopTask() {
        agentService?.stopTask()
    }
    
    /**
     * 手动截图
     */
    fun takeScreenshot() {
        addLog(LogEntry(LogLevel.WARNING, "截图功能需要通过服务执行"))
    }
    
    /**
     * 手动执行动作
     */
    fun executeAction(action: ParsedAction) {
        viewModelScope.launch {
            val result = agentService?.executeManualAction(action) ?: false
            addLog(LogEntry(
                level = if (result) LogLevel.INFO else LogLevel.ERROR,
                message = "${action.getDescription()} - ${if (result) "成功" else "失败"}"
            ))
        }
    }
    
    /**
     * 测试点击
     */
    fun testTap(x: Int, y: Int) {
        executeAction(ParsedAction.Tap(x, y))
    }
    
    /**
     * 测试滑动
     */
    fun testSwipe(direction: String) {
        when (direction) {
            "up" -> executeAction(ParsedAction.SwipeUp(500, 300))
            "down" -> executeAction(ParsedAction.SwipeDown(500, 300))
            "left" -> executeAction(ParsedAction.SwipeLeft(500, 300))
            "right" -> executeAction(ParsedAction.SwipeRight(500, 300))
        }
    }
    
    /**
     * 测试按键
     */
    fun testKeyPress(key: String) {
        when (key) {
            "back" -> executeAction(ParsedAction.PressBack)
            "home" -> executeAction(ParsedAction.PressHome)
            "recent" -> executeAction(ParsedAction.PressRecent)
        }
    }
    
    /**
     * 更新服务器URL
     */
    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(aiServerUrl = url)
    }
    
    /**
     * 更新API Key
     */
    fun updateApiKey(key: String) {
        _uiState.value = _uiState.value.copy(apiKey = key)
    }
    
    /**
     * 更新任务指令
     */
    fun updateInstruction(instruction: String) {
        _uiState.value = _uiState.value.copy(currentInstruction = instruction)
    }
    
    /**
     * 清除日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
    }
    
    // ========== 语音控制 ==========
    
    /**
     * 监听语音状态
     */
    private fun startObservingVoice() {
        val service = agentService ?: return
        
        // 监听语音状态
        viewModelScope.launch {
            service.getVoiceState()?.collectLatest { state ->
                _voiceState.value = state
            }
        }
        
        // 监听语音启用状态
        viewModelScope.launch {
            service.isVoiceEnabled()?.collectLatest { enabled ->
                _isVoiceEnabled.value = enabled
            }
        }
        
        // 监听语音事件
        viewModelScope.launch {
            service.getVoiceEvents()?.collectLatest { event ->
                handleVoiceEvent(event)
            }
        }
    }
    
    /**
     * 处理语音事件
     */
    private fun handleVoiceEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.WakeWordDetected -> {
                addLog(LogEntry(LogLevel.INFO, "🎙️ 唤醒词已检测，请说出指令..."))
            }
            is VoiceEvent.CommandRecognized -> {
                addLog(LogEntry(LogLevel.SUCCESS, "🗣️ 语音指令: ${event.command}"))
            }
            is VoiceEvent.Error -> {
                addLog(LogEntry(LogLevel.ERROR, "语音识别错误: ${event.message}"))
            }
            is VoiceEvent.CommandTimeout -> {
                addLog(LogEntry(LogLevel.WARNING, "语音指令超时"))
            }
            is VoiceEvent.ServiceStarted -> {
                addLog(LogEntry(LogLevel.INFO, "🎙️ 语音服务已启动，等待唤醒词..."))
            }
            is VoiceEvent.ServiceStopped -> {
                addLog(LogEntry(LogLevel.INFO, "语音服务已停止"))
            }
        }
    }
    
    /**
     * 启动语音控制
     */
    fun startVoiceControl() {
        agentService?.startVoiceControl()
    }
    
    /**
     * 停止语音控制
     */
    fun stopVoiceControl() {
        agentService?.stopVoiceControl()
    }
    
    /**
     * 切换语音控制
     */
    fun toggleVoiceControl() {
        if (_isVoiceEnabled.value) {
            stopVoiceControl()
        } else {
            startVoiceControl()
        }
    }
    
    /**
     * 手动触发语音命令
     */
    fun triggerVoiceCommand() {
        agentService?.triggerVoiceCommand()
        addLog(LogEntry(LogLevel.INFO, "🎙️ 正在听取语音指令..."))
    }
    
    /**
     * 设置唤醒词
     */
    fun setWakeWords(words: List<String>) {
        agentService?.setWakeWords(words)
        addLog(LogEntry(LogLevel.INFO, "唤醒词已设置: $words"))
    }
    
    /**
     * 获取唤醒词
     */
    fun getWakeWords(): List<String> {
        return agentService?.getWakeWords() ?: emptyList()
    }
    
    /**
     * 处理Agent事件
     */
    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Log -> addLog(LogEntry(LogLevel.INFO, event.message))
            is AgentEvent.Warning -> addLog(LogEntry(LogLevel.WARNING, event.message))
            is AgentEvent.Error -> addLog(LogEntry(LogLevel.ERROR, event.message))
            is AgentEvent.TaskStarted -> addLog(LogEntry(LogLevel.INFO, "任务开始: ${event.instruction}"))
            is AgentEvent.TaskCompleted -> addLog(LogEntry(LogLevel.SUCCESS, "任务完成: ${event.message}"))
            is AgentEvent.StepStarted -> addLog(LogEntry(LogLevel.INFO, "--- 步骤 ${event.step} ---"))
            is AgentEvent.Screenshot -> _screenshotPreview.value = event.base64
            is AgentEvent.ActionParsed -> addLog(LogEntry(LogLevel.INFO, "动作: ${event.action.getDescription()}"))
            is AgentEvent.Thought -> addLog(LogEntry(LogLevel.THOUGHT, "AI思考: ${event.content}"))
        }
    }
    
    /**
     * 添加日志
     */
    private fun addLog(entry: LogEntry) {
        _logs.value = (_logs.value + entry).takeLast(200)  // 保留最近200条
    }
    
    /**
     * 停止服务
     */
    fun stopService() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, AgentService::class.java))
    }
    
    override fun onCleared() {
        super.onCleared()
        // 解绑服务（不停止，允许后台继续运行）
        if (isBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                // 忽略
            }
            isBound = false
        }
    }
}

/**
 * UI状态
 */
data class MainUiState(
    val isInitializing: Boolean = false,
    val isInitialized: Boolean = false,
    val isRootAvailable: Boolean = false,
    val isAIConfigured: Boolean = false,
    val isRunning: Boolean = false,
    val isServiceBound: Boolean = false,
    val agentState: AgentState = AgentState.IDLE,
    val currentTask: String? = null,
    val currentStep: Int = 0,
    // AI配置
    val selectedProvider: AIProvider = AIProvider.ZHIPU,
    val aiServerUrl: String = "http://localhost:11434",
    val apiKey: String = "",
    val modelName: String = "glm-4v-flash",
    val currentInstruction: String = "",
    // 语音控制
    val isVoiceEnabled: Boolean = false,
    val voiceState: VoiceServiceState = VoiceServiceState.DISABLED
)

/**
 * 日志条目
 */
data class LogEntry(
    val level: LogLevel,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 日志级别
 */
enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
    THOUGHT
}
