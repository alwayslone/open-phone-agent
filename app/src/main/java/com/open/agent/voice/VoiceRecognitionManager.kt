package com.open.agent.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException


/**
 * 语音识别状态
 */
enum class VoiceRecognitionState {
    IDLE,           // 空闲状态
    LOADING,        // 模型加载中
    LISTENING,      // 正在监听
    PROCESSING,     // 处理中
    ERROR           // 错误状态
}

/**
 * 语音识别结果
 */
sealed class VoiceRecognitionResult {
    data class Success(val text: String, val confidence: Float = 1.0f) : VoiceRecognitionResult()
    data class Error(val errorCode: Int, val message: String) : VoiceRecognitionResult()
    object Partial : VoiceRecognitionResult()
}

/**
 * Vosk 离线语音识别管理器
 * 完全离线运行，不需要网络连接
 * 模型位置: assets/model-cn/
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val onResult: (VoiceRecognitionResult) -> Unit
) : RecognitionListener {
    
    companion object {
        private const val TAG = "VoskVoiceManager"
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_PATH = "model-cn"
    }
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    
    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    
    private val _lastRecognizedText = MutableStateFlow<String?>(null)
    val lastRecognizedText: StateFlow<String?> = _lastRecognizedText.asStateFlow()
    
    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()
    
    // 是否持续监听模式
    @Volatile
    private var continuousListening = false
    
    // 是否已释放资源（防止崩溃）
    @Volatile
    private var isReleased = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 检查语音识别是否可用
     */
    fun isAvailable(): Boolean {
        return true // Vosk 离线识别始终可用
    }
    
    /**
     * 初始化语音识别器（加载模型）
     */
    fun initialize() {
        initializeAndStart(null)
    }
    
    /**
     * 初始化并在完成后执行回调
     * @param onReady 模型加载完成后的回调
     */
    fun initializeAndStart(onReady: (() -> Unit)?) {
        if (model != null) {
            Log.d(TAG, "模型已加载")
            onReady?.invoke()
            return
        }
        
        // 如果已经在加载中，等待加载完成
        if (_state.value == VoiceRecognitionState.LOADING) {
            Log.d(TAG, "模型正在加载中，等待完成...")
            scope.launch {
                while (_state.value == VoiceRecognitionState.LOADING) {
                    delay(100)
                }
                if (_modelLoaded.value) {
                    withContext(Dispatchers.Main) {
                        onReady?.invoke()
                    }
                }
            }
            return
        }
        
        _state.value = VoiceRecognitionState.LOADING
        
        scope.launch {
            try {
                Log.d(TAG, "开始加载Vosk中文模型...")
                
                // 从 assets 解压并加载模型
                StorageService.unpack(context, MODEL_PATH, "model",
                    { loadedModel ->
                        model = loadedModel
                        _modelLoaded.value = true
                        _state.value = VoiceRecognitionState.IDLE
                        Log.d(TAG, "Vosk中文模型加载完成")
                        onReady?.invoke()
                    },
                    { exception ->
                        Log.e(TAG, "模型加载失败", exception)
                        _state.value = VoiceRecognitionState.ERROR
                        onResult(VoiceRecognitionResult.Error(-1, "模型加载失败: ${exception.message}"))
                    }
                )
            } catch (e: IOException) {
                Log.e(TAG, "模型解压失败", e)
                _state.value = VoiceRecognitionState.ERROR
                onResult(VoiceRecognitionResult.Error(-1, "模型解压失败: ${e.message}"))
            }
        }
    }
    
    /**
     * 开始单次语音识别
     */
    fun startListening() {
        continuousListening = false
        startRecognition()
    }
    
    /**
     * 开始持续语音识别（用于唤醒词检测）
     */
    fun startContinuousListening() {
        continuousListening = true
        startRecognition()
    }
    
    /**
     * 开始识别
     */
    private fun startRecognition() {
        if (model == null) {
            Log.d(TAG, "模型未加载，先初始化并启动")
            initializeAndStart {
                doStartRecognition()
            }
            return
        }
        
        doStartRecognition()
    }
    
    private fun doStartRecognition() {
        if (isReleased) return
        
        if (_state.value == VoiceRecognitionState.LISTENING) {
            return  // 已经在监听中，不重复启动
        }
        
        if (model == null) {
            Log.w(TAG, "模型未加载，无法启动识别")
            return
        }
        
        try {
            // 先释放之前的 SpeechService
            safeStopSpeechService()
            
            if (isReleased) return
            
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            
            _state.value = VoiceRecognitionState.LISTENING
            if (!continuousListening) {
                Log.d(TAG, "开始语音识别")
            }
        } catch (e: Exception) {
            if (!isReleased) {
                Log.e(TAG, "启动语音识别失败", e)
                _state.value = VoiceRecognitionState.ERROR
                onResult(VoiceRecognitionResult.Error(-1, "启动语音识别失败: ${e.message}"))
            }
        }
    }
    
    /**
     * 停止语音识别
     */
    fun stopListening() {
        continuousListening = false
        safeStopSpeechService()
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "停止语音识别")
    }
    
    /**
     * 取消语音识别
     */
    fun cancel() {
        continuousListening = false
        safeStopSpeechService()
        _state.value = VoiceRecognitionState.IDLE
        Log.d(TAG, "取消语音识别")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "开始释放语音识别器...")
        isReleased = true  // 先标记为已释放
        continuousListening = false
        
        // 安全停止 SpeechService
        safeStopSpeechService()
        
        // 关闭模型
        try {
            model?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 Model 异常", e)
        }
        model = null
        
        _modelLoaded.value = false
        _state.value = VoiceRecognitionState.IDLE
        scope.cancel()
        Log.d(TAG, "语音识别器已释放")
    }
    
    /**
     * 安全停止 SpeechService（避免崩溃）
     */
    private fun safeStopSpeechService() {
        val service = speechService
        speechService = null
        
        if (service != null) {
            try {
                // 先调用 stop，它会设置内部标志停止线程
                service.stop()
                // 等待一小段时间让线程结束
                Thread.sleep(100)
            } catch (e: Exception) {
                Log.w(TAG, "停止 SpeechService 异常", e)
            }
        }
    }
    
    // ========== RecognitionListener 回调 ==========
    
    /**
     * 收到部分识别结果
     */
    override fun onPartialResult(hypothesis: String?) {
        if (isReleased || hypothesis.isNullOrEmpty()) return
        
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "")
            if (partial.isNotEmpty()) {
                Log.d(TAG, "部分识别结果: $partial")
                onResult(VoiceRecognitionResult.Partial)
            }
        } catch (e: Exception) {
            if (!isReleased) {
                Log.e(TAG, "解析部分结果失败", e)
            }
        }
    }
    
    /**
     * 收到最终识别结果
     */
    override fun onResult(hypothesis: String?) {
        if (isReleased || hypothesis.isNullOrEmpty()) return
        
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            
            if (text.isNotEmpty()) {
                Log.d(TAG, "识别结果: $text")
                _lastRecognizedText.value = text
                _state.value = VoiceRecognitionState.IDLE
                onResult(VoiceRecognitionResult.Success(text))
            }
        } catch (e: Exception) {
            if (!isReleased) {
                Log.e(TAG, "解析结果失败", e)
            }
        }
    }
    
    /**
     * 最终结果（语音结束）
     */
    override fun onFinalResult(hypothesis: String?) {
        if (isReleased) return
        
        // 解析结果
        if (!hypothesis.isNullOrEmpty()) {
            try {
                val json = JSONObject(hypothesis)
                val text = json.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    Log.d(TAG, "最终结果: $text")
                    _lastRecognizedText.value = text
                    onResult(VoiceRecognitionResult.Success(text))
                }
            } catch (e: Exception) {
                if (!isReleased) {
                    Log.e(TAG, "解析最终结果失败", e)
                }
            }
        }
        
        // 持续监听模式下重新启动
        if (continuousListening && !isReleased) {
            _state.value = VoiceRecognitionState.IDLE
            scope.launch {
                delay(500)
                withContext(Dispatchers.Main) {
                    if (continuousListening && !isReleased) {
                        doStartRecognition()
                    }
                }
            }
        } else if (!isReleased) {
            _state.value = VoiceRecognitionState.IDLE
        }
    }
    
    /**
     * 识别错误
     */
    override fun onError(exception: Exception?) {
        if (isReleased) return
        
        Log.e(TAG, "识别错误", exception)
        
        safeStopSpeechService()
        
        _state.value = VoiceRecognitionState.ERROR
        if (!isReleased) {
            onResult(VoiceRecognitionResult.Error(-1, exception?.message ?: "未知错误"))
        }
        
        // 持续监听模式下重新开始
        if (continuousListening && !isReleased) {
            scope.launch {
                delay(2000)
                withContext(Dispatchers.Main) {
                    if (continuousListening && !isReleased) {
                        _state.value = VoiceRecognitionState.IDLE
                        doStartRecognition()
                    }
                }
            }
        }
    }
    
    /**
     * 识别超时
     */
    override fun onTimeout() {
        if (isReleased) return
        
        Log.d(TAG, "识别超时")
        _state.value = VoiceRecognitionState.IDLE
        
        // 持续监听模式下重新开始
        if (continuousListening && !isReleased) {
            scope.launch {
                delay(500)
                withContext(Dispatchers.Main) {
                    if (continuousListening && !isReleased) {
                        doStartRecognition()
                    }
                }
            }
        }
    }
}
