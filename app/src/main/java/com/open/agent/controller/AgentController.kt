package com.open.agent.controller

import android.content.Context
import android.util.Log
import com.open.agent.ai.AIProvider
import com.open.agent.ai.AnalyzeResult
import com.open.agent.ai.AIService
import com.open.agent.ai.ProviderConfig
import com.open.agent.device.DeviceController
import com.open.agent.parser.ParsedAction
import com.open.agent.root.RootExecutor
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent控制器
 * 协调ROOT执行器、设备控制器、AI服务和指令解析器
 */
class AgentController(
    private val context: Context
) {
    companion object {
        private const val TAG = "AgentController"
        private const val MAX_STEPS = 50  // 最大执行步数
        private const val ACTION_DELAY = 500L  // 每个动作之间的延迟
    }
    
    // 核心组件
    private val rootExecutor = RootExecutor()
    private val deviceController = DeviceController(context, rootExecutor)
    private val aiService = AIService()
    
    // 状态
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state
    
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable
    
    private val _currentTask = MutableStateFlow<String?>(null)
    val currentTask: StateFlow<String?> = _currentTask
    
    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep
    
    // 事件流
    private val _events = MutableSharedFlow<AgentEvent>()
    val events: SharedFlow<AgentEvent> = _events
    
    // 操作历史
    private val actionHistory = mutableListOf<String>()
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null
    
    /**
     * 初始化Agent
     */
    suspend fun initialize(): Boolean {
        _state.value = AgentState.INITIALIZING
        emitEvent(AgentEvent.Log("正在初始化Agent..."))
        
        // 检查ROOT权限
        val hasRoot = rootExecutor.checkRootAccess()
        _isRootAvailable.value = hasRoot
        
        if (!hasRoot) {
            _state.value = AgentState.ERROR
            emitEvent(AgentEvent.Error("设备未获取ROOT权限"))
            return false
        }
        
        emitEvent(AgentEvent.Log("ROOT权限验证成功"))
        
        // 初始化ROOT Shell
        val shellInit = rootExecutor.initRootShell()
        if (!shellInit) {
            _state.value = AgentState.ERROR
            emitEvent(AgentEvent.Error("初始化ROOT Shell失败"))
            return false
        }
        
        _state.value = AgentState.IDLE
        emitEvent(AgentEvent.Log("Agent初始化完成"))
        return true
    }
    
    /**
     * 配置AI服务
     */
    fun configureAI(config: ProviderConfig) {
        aiService.configure(config)
        val provider = aiService.getCurrentProvider()
        emitEvent(AgentEvent.Log("AI服务已配置: ${provider?.displayName ?: "Unknown"}"))
    }
    
    /**
     * 获取当前AI提供商
     */
    fun getCurrentAIProvider(): AIProvider? = aiService.getCurrentProvider()
    
    /**
     * 开始执行任务
     */
    fun startTask(instruction: String) {
        if (_state.value == AgentState.RUNNING) {
            emitEvent(AgentEvent.Warning("任务正在执行中"))
            return
        }
        
        if (aiService.getCurrentProvider() == null) {
            emitEvent(AgentEvent.Error("AI服务未配置"))
            return
        }
        
        currentJob = scope.launch {
            executeTask(instruction)
        }
    }
    
    /**
     * 执行任务
     */
    private suspend fun executeTask(instruction: String) {
        _state.value = AgentState.RUNNING
        _currentTask.value = instruction
        _currentStep.value = 0
        actionHistory.clear()
        
        emitEvent(AgentEvent.TaskStarted(instruction))
        emitEvent(AgentEvent.Log("开始执行任务: $instruction"))
        
        try {
            var isComplete = false
            
            while (!isComplete && _currentStep.value < MAX_STEPS) {
                if (!coroutineContext.isActive) {
                    emitEvent(AgentEvent.Log("任务被取消"))
                    break
                }
                
                _currentStep.value++
                emitEvent(AgentEvent.StepStarted(_currentStep.value))
                
                // 截取屏幕
                emitEvent(AgentEvent.Log("正在截取屏幕..."))
                val screenshot = deviceController.takeScreenshotBase64()
                
                if (screenshot == null) {
                    emitEvent(AgentEvent.Error("截屏失败"))
                    delay(1000)
                    continue
                }
                
                emitEvent(AgentEvent.Screenshot(screenshot))
                
                // 发送给AI分析
                emitEvent(AgentEvent.Log("正在分析屏幕内容..."))
                val analyzeResult: AnalyzeResult
                val (screenWidth, screenHeight) = deviceController.getScreenSize()
                try {
                    analyzeResult = aiService.analyzeScreen(
                        screenshotBase64 = screenshot,
                        instruction = instruction,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        history = actionHistory.takeLast(10)
                    )
                } catch (e: Exception) {
                    emitEvent(AgentEvent.Error("AI请求失败: ${e.message}"))
                    delay(2000)
                    continue
                }
                
                // 获取解析后的动作
                val action = analyzeResult.action
                emitEvent(AgentEvent.ActionParsed(action))
                emitEvent(AgentEvent.Log("AI决策: ${action.getDescription()}"))
                
                // 如果AI有思考过程，显示出来
                analyzeResult.thought?.let {
                    emitEvent(AgentEvent.Thought(it))
                }
                
                // 执行动作
                val result = executeAction(action)
                
                // 记录历史
                actionHistory.add("${action.getDescription()}: ${if (result) "success" else "failed"}")
                
                // 检查是否完成
                if (action is ParsedAction.Complete) {
                    isComplete = true
                    emitEvent(AgentEvent.TaskCompleted(action.message))
                } else if (action is ParsedAction.Error) {
                    emitEvent(AgentEvent.Error(action.message))
                }
                
                // 动作之间的延迟
                delay(ACTION_DELAY)
            }
            
            if (_currentStep.value >= MAX_STEPS && !isComplete) {
                emitEvent(AgentEvent.Warning("达到最大步数限制"))
            }
            
        } catch (e: CancellationException) {
            emitEvent(AgentEvent.Log("任务已取消"))
        } catch (e: Exception) {
            Log.e(TAG, "执行任务时发生错误", e)
            emitEvent(AgentEvent.Error("执行错误: ${e.message}"))
        } finally {
            _state.value = AgentState.IDLE
            _currentTask.value = null
        }
    }
    
    /**
     * 执行解析后的动作
     */
    private suspend fun executeAction(action: ParsedAction): Boolean {
        return when (action) {
            is ParsedAction.Tap -> {
                emitEvent(AgentEvent.Log("执行点击: (${action.x}, ${action.y})"))
                deviceController.tap(action.x, action.y)
            }
            is ParsedAction.LongPress -> {
                emitEvent(AgentEvent.Log("执行长按: (${action.x}, ${action.y})"))
                deviceController.longPress(action.x, action.y, action.duration)
            }
            is ParsedAction.Swipe -> {
                emitEvent(AgentEvent.Log("执行滑动"))
                deviceController.swipe(
                    action.startX, action.startY,
                    action.endX, action.endY,
                    action.duration
                )
            }
            is ParsedAction.SwipeUp -> {
                emitEvent(AgentEvent.Log("向上滑动"))
                deviceController.swipeUp(action.distance, action.duration)
            }
            is ParsedAction.SwipeDown -> {
                emitEvent(AgentEvent.Log("向下滑动"))
                deviceController.swipeDown(action.distance, action.duration)
            }
            is ParsedAction.SwipeLeft -> {
                emitEvent(AgentEvent.Log("向左滑动"))
                deviceController.swipeLeft(action.distance, action.duration)
            }
            is ParsedAction.SwipeRight -> {
                emitEvent(AgentEvent.Log("向右滑动"))
                deviceController.swipeRight(action.distance, action.duration)
            }
            is ParsedAction.InputText -> {
                emitEvent(AgentEvent.Log("输入文本: ${action.text}"))
                deviceController.inputText(action.text)
            }
            is ParsedAction.PressBack -> {
                emitEvent(AgentEvent.Log("按下返回键"))
                deviceController.pressBack()
            }
            is ParsedAction.PressHome -> {
                emitEvent(AgentEvent.Log("按下Home键"))
                deviceController.pressHome()
            }
            is ParsedAction.PressRecent -> {
                emitEvent(AgentEvent.Log("按下最近任务键"))
                deviceController.pressRecent()
            }
            is ParsedAction.PressEnter -> {
                emitEvent(AgentEvent.Log("按下回车键"))
                deviceController.pressEnter()
            }
            is ParsedAction.PressDelete -> {
                emitEvent(AgentEvent.Log("按下删除键"))
                deviceController.pressDelete()
            }
            is ParsedAction.PressKey -> {
                emitEvent(AgentEvent.Log("按下按键: ${action.keyCode}"))
                deviceController.pressKey(action.keyCode)
            }
            is ParsedAction.Wait -> {
                emitEvent(AgentEvent.Log("等待 ${action.duration}ms"))
                delay(action.duration)
                true
            }
            is ParsedAction.Screenshot -> {
                emitEvent(AgentEvent.Log("截取屏幕"))
                deviceController.takeScreenshot() != null
            }
            is ParsedAction.Think -> {
                emitEvent(AgentEvent.Thought(action.thought))
                true
            }
            is ParsedAction.Complete -> true
            is ParsedAction.Error -> false
            is ParsedAction.Unknown -> {
                emitEvent(AgentEvent.Warning("未知动作: ${action.actionType}"))
                false
            }
            // 新增的动作类型
            is ParsedAction.DoubleTap -> {
                emitEvent(AgentEvent.Log("执行双击: (${action.x}, ${action.y})"))
                deviceController.doubleTap(action.x, action.y)
            }
            is ParsedAction.TakeOver -> {
                emitEvent(AgentEvent.Warning("需要用户接管: ${action.message}"))
                // 暂停任务，等待用户操作
                _state.value = AgentState.PAUSED
                false
            }
            is ParsedAction.Interact -> {
                emitEvent(AgentEvent.Warning("需要用户选择"))
                _state.value = AgentState.PAUSED
                false
            }
            is ParsedAction.Note -> {
                emitEvent(AgentEvent.Log("记录页面: ${action.message}"))
                // 记录当前页面内容到历史
                actionHistory.add("note: ${action.message}")
                true
            }
            is ParsedAction.CallAPI -> {
                emitEvent(AgentEvent.Log("调用API总结: ${action.instruction}"))
                // 这里可以调用AI进行总结
                true
            }
            is ParsedAction.LaunchApp -> {
                emitEvent(AgentEvent.Log("启动应用: ${action.appName}"))
                val success = deviceController.launchApp(action.appName)
                if (!success) {
                    emitEvent(AgentEvent.Warning("找不到或无法启动应用: ${action.appName}"))
                }
                success
            }
        }
    }
    
    /**
     * 停止当前任务
     */
    fun stopTask() {
        currentJob?.cancel()
        _state.value = AgentState.IDLE
        _currentTask.value = null
        emitEvent(AgentEvent.Log("任务已停止"))
    }
    
    /**
     * 手动执行单个动作（用于测试）
     */
    suspend fun executeManualAction(action: ParsedAction): Boolean {
        return executeAction(action)
    }
    
    /**
     * 获取设备控制器（用于手动操作）
     */
    fun getDeviceController(): DeviceController = deviceController
    
    /**
     * 获取ROOT执行器（用于高级操作）
     */
    fun getRootExecutor(): RootExecutor = rootExecutor
    
    /**
     * 发送事件
     */
    private fun emitEvent(event: AgentEvent) {
        scope.launch {
            _events.emit(event)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopTask()
        scope.cancel()
        aiService.close()
        rootExecutor.close()
        deviceController.clearScreenshotCache()
    }
}

/**
 * Agent状态
 */
enum class AgentState {
    IDLE,           // 空闲
    INITIALIZING,   // 初始化中
    RUNNING,        // 运行中
    PAUSED,         // 暂停
    ERROR           // 错误
}

/**
 * Agent事件
 */
sealed class AgentEvent {
    data class Log(val message: String) : AgentEvent()
    data class Warning(val message: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class TaskStarted(val instruction: String) : AgentEvent()
    data class TaskCompleted(val message: String) : AgentEvent()
    data class StepStarted(val step: Int) : AgentEvent()
    data class Screenshot(val base64: String) : AgentEvent()
    data class ActionParsed(val action: ParsedAction) : AgentEvent()
    data class Thought(val content: String) : AgentEvent()
}
