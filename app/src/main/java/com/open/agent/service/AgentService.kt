package com.open.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.open.agent.MainActivity
import com.open.agent.R
import com.open.agent.ai.AIProvider
import com.open.agent.ai.ProviderConfig
import com.open.agent.controller.AgentController
import com.open.agent.controller.AgentEvent
import com.open.agent.controller.AgentState
import com.open.agent.parser.ParsedAction
import com.open.agent.voice.VoiceEvent
import com.open.agent.voice.VoiceService
import com.open.agent.voice.VoiceServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent前台服务
 * 保持应用在后台时也能执行操作
 */
class AgentService : Service() {
    
    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "agent_service_channel"
        private const val CHANNEL_NAME = "Agent服务"
        
        // 服务操作
        const val ACTION_START = "com.open.agent.action.START"
        const val ACTION_STOP = "com.open.agent.action.STOP"
        const val ACTION_START_TASK = "com.open.agent.action.START_TASK"
        const val ACTION_STOP_TASK = "com.open.agent.action.STOP_TASK"
        
        const val EXTRA_INSTRUCTION = "instruction"
    }
    
    // Binder用于Activity绑定
    private val binder = AgentBinder()
    
    // Agent控制器
    private lateinit var agentController: AgentController
    
    // 语音服务
    private var voiceService: VoiceService? = null
    
    // WakeLock保持CPU唤醒
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AgentService创建")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 初始化Agent控制器
        agentController = AgentController(applicationContext)
        
        // 初始化语音服务
        initVoiceService()
        
        // 获取WakeLock
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                // 初始化Agent
                serviceScope.launch {
                    agentController.initialize()
                }
                // 自动启动语音控制（常开模式）
                startVoiceControlAlwaysOn()
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
            ACTION_START_TASK -> {
                val instruction = intent.getStringExtra(EXTRA_INSTRUCTION)
                if (!instruction.isNullOrEmpty()) {
                    agentController.startTask(instruction)
                    updateNotification("正在执行: $instruction")
                }
            }
            ACTION_STOP_TASK -> {
                agentController.stopTask()
                updateNotification("Agent已就绪")
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AgentService销毁")
        
        // 停止语音服务
        voiceService?.stop()
        voiceService = null
        
        releaseWakeLock()
        agentController.release()
        serviceScope.cancel()
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        val notification = createNotification("Agent已就绪")
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "前台服务已启动")
    }
    
    /**
     * 停止前台服务
     */
    private fun stopForegroundService() {
        agentController.stopTask()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "前台服务已停止")
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent服务运行状态"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(content: String): Notification {
        // 点击通知打开应用
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // 停止按钮
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenPhoneAgent")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 获取WakeLock保持CPU唤醒
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenPhoneAgent::AgentWakeLock"
        ).apply {
            // 无限期保持唤醒（确保屏幕关闭时也能监听）
            acquire()
        }
        Log.d(TAG, "WakeLock已获取（无限期）")
    }
    
    /**
     * 释放WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock已释放")
            }
        }
        wakeLock = null
    }
    
    // ========== 公开API供Activity/ViewModel调用 ==========
    
    /**
     * 获取Agent状态
     */
    fun getAgentState(): StateFlow<AgentState> = agentController.state
    
    /**
     * 获取ROOT可用状态
     */
    fun isRootAvailable(): StateFlow<Boolean> = agentController.isRootAvailable
    
    /**
     * 获取当前任务
     */
    fun getCurrentTask(): StateFlow<String?> = agentController.currentTask
    
    /**
     * 获取当前步骤
     */
    fun getCurrentStep(): StateFlow<Int> = agentController.currentStep
    
    /**
     * 获取事件流
     */
    fun getEvents(): SharedFlow<AgentEvent> = agentController.events
    
    /**
     * 配置AI服务
     */
    fun configureAI(config: ProviderConfig) {
        agentController.configureAI(config)
    }
    
    /**
     * 获取当前AI提供商
     */
    fun getCurrentAIProvider(): AIProvider? = agentController.getCurrentAIProvider()
    
    /**
     * 开始任务
     */
    fun startTask(instruction: String) {
        agentController.startTask(instruction)
        updateNotification("正在执行: $instruction")
    }
    
    /**
     * 停止任务
     */
    fun stopTask() {
        agentController.stopTask()
        updateNotification("Agent已就绪")
    }
    
    /**
     * 手动执行动作
     */
    suspend fun executeManualAction(action: ParsedAction): Boolean {
        return agentController.executeManualAction(action)
    }
    
    /**
     * 初始化Agent
     */
    suspend fun initializeAgent(): Boolean {
        return agentController.initialize()
    }
    
    // ========== 语音控制API ==========
    
    /**
     * 初始化语音服务
     */
    private fun initVoiceService() {
        voiceService = VoiceService(applicationContext) { command ->
            Log.d(TAG, "收到语音命令: $command")
            // 执行语音命令
            startTask(command)
        }
    }
    
    /**
     * 启动语音控制
     */
    fun startVoiceControl() {
        voiceService?.start()
        updateNotification("🎙️ 语音助手已启动，等待唤醒词...")
        Log.d(TAG, "语音控制已启动")
    }
    
    /**
     * 启动语音控制（常开模式）
     */
    fun startVoiceControlAlwaysOn() {
        voiceService?.startAlwaysOn()
        updateNotification("🎙️ 语音助手已启动（常开模式）")
        Log.d(TAG, "语音控制已启动（常开模式）")
    }
    
    /**
     * 停止语音控制
     */
    fun stopVoiceControl() {
        voiceService?.stop()
        updateNotification("Agent已就绪")
        Log.d(TAG, "语音控制已停止")
    }
    
    /**
     * 手动触发语音命令
     */
    fun triggerVoiceCommand() {
        voiceService?.triggerVoiceCommand()
    }
    
    /**
     * 获取语音服务状态
     */
    fun getVoiceState(): StateFlow<VoiceServiceState>? = voiceService?.state
    
    /**
     * 获取语音事件
     */
    fun getVoiceEvents(): SharedFlow<VoiceEvent>? = voiceService?.events
    
    /**
     * 语音是否启用
     */
    fun isVoiceEnabled(): StateFlow<Boolean>? = voiceService?.isVoiceEnabled
    
    /**
     * 设置唤醒词
     */
    fun setWakeWords(words: List<String>) {
        voiceService?.setWakeWords(words)
    }
    
    /**
     * 获取唤醒词
     */
    fun getWakeWords(): List<String> {
        return voiceService?.getWakeWords() ?: emptyList()
    }
}
