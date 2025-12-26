package com.open.agent.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.open.agent.parser.ParsedAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 统一AI服务
 * 支持智谱AI、Ollama、OpenAI等多种提供商
 */
class AIService {
    
    companion object {
        private const val TAG = "AIService"
        private const val TIMEOUT = 120L  // 视觉模型需要更长超时
    }
    
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var currentConfig: ProviderConfig? = null
    
    // 系统提示词
    private val systemPrompt = """你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

操作指令及其作用如下(你只能执行我给定的操作指令)：
- do(action="Tap", element=[x,y])  
    Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y], message="重要操作")  
    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
- do(action="Type", text="xxx")  
    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前，请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。重要提示：手机可能正在使用 ADB 键盘，该键盘不会像普通键盘那样占用屏幕空间。要确认键盘已激活，请查看屏幕底部是否显示 'ADB Keyboard {ON}' 类似的文本，或者检查输入框是否处于激活/高亮状态。不要仅仅依赖视觉上的键盘显示。自动清除文本：当你使用输入操作时，输入框中现有的任何文本（包括占位符文本和实际输入）都会在输入新文本前自动清除。你无需在输入前手动清除文本——直接使用输入操作输入所需文本即可。操作完成后，你将自动收到结果状态的截图。
- do(action="Type_Name", text="xxx")  
    Type_Name是输入人名的操作，基本功能同Type。
- do(action="Interact")  
    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  
    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。可用于滚动内容、在屏幕之间导航、下拉通知栏以及项目栏或进行基于手势的导航。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。滑动持续时间会自动调整以实现自然的移动。此操作完成后，您将自动收到结果状态的截图。
- do(action="Note", message="True")  
    记录当前页面内容以便后续总结。
- do(action="Call_API", instruction="xxx")  
    总结或评论当前页面或已记录的内容。
- do(action="Long Press", element=[x,y])  
    Long Pres是长按操作，在屏幕上的特定点长按指定时间。可用于触发上下文菜单、选择文本或激活长按交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
- do(action="Double Tap", element=[x,y])  
    Double Tap在屏幕上的特定点快速连续点按两次。使用此操作可以激活双击交互，如缩放、选择文本或打开项目。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Take_over", message="xxx")  
    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
- do(action="Back")  
    导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。使用此操作可以从更深的屏幕返回、关闭弹出窗口或退出当前上下文。此操作完成后，您将自动收到结果状态的截图。
- do(action="Home") 
    Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。使用此操作可退出当前应用并返回启动器，或从已知状态启动新任务。此操作完成后，您将自动收到结果状态的截图。
- do(action="Wait", duration="x seconds")  
    等待页面加载，x为需要等待多少秒。
- finish(message="xxx")  
    finish是结束任务的操作，表示准确完整完成任务，message是终止信息。 

必须遵循的规则：
0. 打开应用不要用do(action="Launch", app="xxx"),而是退回到桌面,点击图标进入
1. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
2. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
3. 如果页面显示网络问题，需要重新加载，请点击重新加载。
4. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
5. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
6. 在做小红书总结类任务时一定要筛选图文笔记。
7. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
8. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
9. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
10. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将“群”字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
11. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
12. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
13. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish message说明点击不生效。
14. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish message说明但没找到要求的项目。
15. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
16. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish(message="原因")。
17. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。
"""

    /**
     * 设置AI提供商配置
     */
    fun configure(config: ProviderConfig) {
        currentConfig = config
        Log.d(TAG, "AI配置已更新: ${config::class.simpleName}")
    }
    
    // 屏幕尺寸，用于坐标转换
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    
    /**
     * 分析屏幕并直接返回可执行动作
     */
    suspend fun analyzeScreen(
        screenshotBase64: String,
        instruction: String,
        screenWidth: Int,
        screenHeight: Int,
        history: List<String> = emptyList()
    ): AnalyzeResult = withContext(Dispatchers.IO) {
        
        // 保存屏幕尺寸用于坐标转换
        this@AIService.screenWidth = screenWidth
        this@AIService.screenHeight = screenHeight
        
        val config = currentConfig ?: throw AIException("AI服务未配置")
        
        val historyText = if (history.isNotEmpty()) {
            "\n\n最近操作: ${history.takeLast(5).joinToString(" -> ")}"
        } else ""
        
        val userMessage = "用户任务: $instruction\n屏幕尺寸: ${screenWidth}x${screenHeight}$historyText\n\n请分析截图并返回下一步操作。"
        
        when (config) {
            is ProviderConfig.ZhipuConfig -> callZhipu(config, screenshotBase64, userMessage)
            is ProviderConfig.OllamaConfig -> callOllama(config, screenshotBase64, userMessage)
            is ProviderConfig.OpenAIConfig -> callOpenAI(config, screenshotBase64, userMessage)
            is ProviderConfig.CustomConfig -> callCustom(config, screenshotBase64, userMessage)
        }
    }
    
    /**
     * 将归一化坐标(0-999)转换为实际屏幕坐标
     */
    private fun normalizedToActual(normX: Int, normY: Int): Pair<Int, Int> {
        val actualX = (normX * screenWidth / 999).coerceIn(0, screenWidth)
        val actualY = (normY * screenHeight / 999).coerceIn(0, screenHeight)
        return Pair(actualX, actualY)
    }
    
    /**
     * 调用智谱AI GLM-4V
     */
    private suspend fun callZhipu(
        config: ProviderConfig.ZhipuConfig,
        imageBase64: String,
        userMessage: String
    ): AnalyzeResult {
        val requestBody = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", gson.toJsonTree(listOf(
                mapOf(
                    "role" to "system",
                    "content" to systemPrompt
                ),
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to userMessage),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64")
                        )
                    )
                )
            )))
            addProperty("max_tokens", 1024)
        }
        
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeAndParse(request)
    }
    
    /**
     * 调用Ollama
     */
    private suspend fun callOllama(
        config: ProviderConfig.OllamaConfig,
        imageBase64: String,
        userMessage: String
    ): AnalyzeResult {
        val requestBody = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("prompt", "$systemPrompt\n\n$userMessage")
            add("images", gson.toJsonTree(listOf(imageBase64)))
            addProperty("stream", false)
        }
        
        val request = Request.Builder()
            .url("${config.baseUrl}/api/generate")
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeOllamaAndParse(request)
    }
    
    /**
     * 调用OpenAI兼容接口
     */
    private suspend fun callOpenAI(
        config: ProviderConfig.OpenAIConfig,
        imageBase64: String,
        userMessage: String
    ): AnalyzeResult {
        val requestBody = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", gson.toJsonTree(listOf(
                mapOf(
                    "role" to "system",
                    "content" to systemPrompt
                ),
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to userMessage),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64")
                        )
                    )
                )
            )))
            addProperty("max_tokens", 1024)
        }
        
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeAndParse(request)
    }
    
    /**
     * 调用自定义API
     */
    private suspend fun callCustom(
        config: ProviderConfig.CustomConfig,
        imageBase64: String,
        userMessage: String
    ): AnalyzeResult {
        // 使用OpenAI兼容格式
        val openAIConfig = ProviderConfig.OpenAIConfig(
            apiKey = config.apiKey ?: "",
            model = config.model ?: "gpt-4-vision-preview",
            baseUrl = config.baseUrl
        )
        return callOpenAI(openAIConfig, imageBase64, userMessage)
    }
    
    /**
     * 执行请求并解析OpenAI格式响应
     */
    private fun executeAndParse(request: Request): AnalyzeResult {
        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d(TAG, "API响应: $body")
            
            if (!response.isSuccessful) {
                return AnalyzeResult(action = ParsedAction.Error("API错误: ${response.code} - $body"))
            }
            
            if (body.isNullOrEmpty()) {
                return AnalyzeResult(action = ParsedAction.Error("空响应"))
            }
            
            // 解析OpenAI格式响应
            val jsonResponse = JsonParser.parseString(body).asJsonObject
            val content = jsonResponse
                .getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
            
            return parseAIContent(content)
            
        } catch (e: Exception) {
            Log.e(TAG, "请求失败", e)
            return AnalyzeResult(action = ParsedAction.Error("请求失败: ${e.message}"))
        }
    }
    
    /**
     * 执行Ollama请求并解析
     */
    private fun executeOllamaAndParse(request: Request): AnalyzeResult {
        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d(TAG, "Ollama响应: $body")
            
            if (!response.isSuccessful) {
                return AnalyzeResult(action = ParsedAction.Error("Ollama错误: ${response.code} - $body"))
            }
            
            if (body.isNullOrEmpty()) {
                return AnalyzeResult(action = ParsedAction.Error("空响应"))
            }
            
            val jsonResponse = JsonParser.parseString(body).asJsonObject
            val content = jsonResponse.get("response").asString
            
            return parseAIContent(content)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ollama请求失败", e)
            return AnalyzeResult(action = ParsedAction.Error("请求失败: ${e.message}"))
        }
    }
    
    /**
     * 解析AI返回的内容
     * 新格式: <think>{think}</think><answer>{action}</answer>
     * action格式: do(action="Tap", element=[x,y]) 或 finish(message="xxx")
     */
    private fun parseAIContent(content: String): AnalyzeResult {
        try {
            Log.d(TAG, "解析AI内容: $content")
            
            // 提取<think>内容
            val thinkPattern = """<think>(.*?)</think>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val thinkMatch = thinkPattern.find(content)
            val thought = thinkMatch?.groupValues?.get(1)?.trim()
            
            // 提取<answer>内容
            val answerPattern = """<answer>(.*?)</answer>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val answerMatch = answerPattern.find(content)
            val actionStr = answerMatch?.groupValues?.get(1)?.trim()
            
            if (actionStr.isNullOrEmpty()) {
                // 如果没有标准格式，尝试直接解析
                return parseDirectAction(content, thought)
            }
            
            // 解析action字符串
            return parseActionString(actionStr, thought)
            
        } catch (e: Exception) {
            Log.e(TAG, "解析AI内容失败: $content", e)
            return AnalyzeResult(action = ParsedAction.Error("解析失败: ${e.message}"))
        }
    }
    
    /**
     * 解析action字符串
     * 支持格式: do(action="Tap", element=[x,y]) 或 finish(message="xxx")
     */
    private fun parseActionString(actionStr: String, thought: String?): AnalyzeResult {
        
        // 检查是否是finish操作
        if (actionStr.startsWith("finish(")) {
            val messagePattern = """message\s*=\s*["']([^"']*)["']""".toRegex()
            val messageMatch = messagePattern.find(actionStr)
            val message = messageMatch?.groupValues?.get(1) ?: "任务完成"
            
            return AnalyzeResult(
                action = ParsedAction.Complete(message),
                thought = thought
            )
        }
        
        // 解析do(...)格式
        if (!actionStr.startsWith("do(")) {
            return AnalyzeResult(
                action = ParsedAction.Think(thought ?: actionStr.take(200)),
                thought = thought
            )
        }
        
        // 提取action类型
        val actionTypePattern = """action\s*=\s*["']([^"']+)["']""".toRegex()
        val actionTypeMatch = actionTypePattern.find(actionStr)
        val actionType = actionTypeMatch?.groupValues?.get(1)?.lowercase() ?: return AnalyzeResult(
            action = ParsedAction.Error("无法解析action类型"),
            thought = thought
        )
        
        // 根据不同的action类型返回对应的ParsedAction
        val parsedAction: ParsedAction = when (actionType) {
            "tap" -> {
                val (x, y) = extractElementCoords(actionStr) ?: return AnalyzeResult(
                    action = ParsedAction.Error("Tap操作需要坐标"),
                    thought = thought
                )
                val (actualX, actualY) = normalizedToActual(x, y)
                ParsedAction.Tap(actualX, actualY)
            }
            "long press" -> {
                val (x, y) = extractElementCoords(actionStr) ?: return AnalyzeResult(
                    action = ParsedAction.Error("Long Press操作需要坐标"),
                    thought = thought
                )
                val (actualX, actualY) = normalizedToActual(x, y)
                ParsedAction.LongPress(actualX, actualY, 1000)
            }
            "double tap" -> {
                val (x, y) = extractElementCoords(actionStr) ?: return AnalyzeResult(
                    action = ParsedAction.Error("Double Tap操作需要坐标"),
                    thought = thought
                )
                val (actualX, actualY) = normalizedToActual(x, y)
                ParsedAction.DoubleTap(actualX, actualY)
            }
            "swipe" -> {
                val coords = extractSwipeCoords(actionStr) ?: return AnalyzeResult(
                    action = ParsedAction.Error("Swipe操作需要起点和终点坐标"),
                    thought = thought
                )
                val (actualStartX, actualStartY) = normalizedToActual(coords.first.first, coords.first.second)
                val (actualEndX, actualEndY) = normalizedToActual(coords.second.first, coords.second.second)
                ParsedAction.Swipe(actualStartX, actualStartY, actualEndX, actualEndY, 300)
            }
            "type", "type_name" -> {
                val text = extractTextParam(actionStr) ?: return AnalyzeResult(
                    action = ParsedAction.Error("Type操作需要文本参数"),
                    thought = thought
                )
                ParsedAction.InputText(text)
            }
            "back" -> ParsedAction.PressBack
            "home" -> ParsedAction.PressHome
            "wait" -> {
                val duration = extractDuration(actionStr) ?: 1000L
                ParsedAction.Wait(duration)
            }
            "take_over" -> {
                val message = extractMessageParam(actionStr) ?: "需要用户操作"
                ParsedAction.TakeOver(message)
            }
            "interact" -> ParsedAction.Interact
            "note" -> {
                val message = extractMessageParam(actionStr) ?: ""
                ParsedAction.Note(message)
            }
            "call_api" -> {
                val instruction = extractInstructionParam(actionStr) ?: ""
                ParsedAction.CallAPI(instruction)
            }
            "launch" -> {
                val appName = extractAppParam(actionStr) ?: return AnalyzeResult(
                    action = ParsedAction.Error("Launch操作需要应用名称参数"),
                    thought = thought
                )
                ParsedAction.LaunchApp(appName)
            }
            else -> ParsedAction.Unknown(actionType, emptyMap())
        }
        
        return AnalyzeResult(
            action = parsedAction,
            thought = thought
        )
    }
    
    // 提取element=[x,y]坐标
    private fun extractElementCoords(actionStr: String): Pair<Int, Int>? {
        val pattern = """element\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]""".toRegex()
        val match = pattern.find(actionStr) ?: return null
        return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }
    
    // 提取start=[x1,y1], end=[x2,y2]坐标
    private fun extractSwipeCoords(actionStr: String): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val startPattern = """start\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]""".toRegex()
        val endPattern = """end\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]""".toRegex()
        val startMatch = startPattern.find(actionStr) ?: return null
        val endMatch = endPattern.find(actionStr) ?: return null
        return Pair(
            Pair(startMatch.groupValues[1].toInt(), startMatch.groupValues[2].toInt()),
            Pair(endMatch.groupValues[1].toInt(), endMatch.groupValues[2].toInt())
        )
    }
    
    // 提取text="xxx"参数，支持更复杂的文本内容
    private fun extractTextParam(actionStr: String): String? {
        // 先尝试匹配双引号
        val doubleQuotePattern = """text\s*=\s*"([^"]*)"""".toRegex()
        val doubleMatch = doubleQuotePattern.find(actionStr)
        if (doubleMatch != null) {
            return doubleMatch.groupValues[1]
        }
        
        // 再尝试匹配单引号
        val singleQuotePattern = """text\s*=\s*'([^']*)'""".toRegex()
        val singleMatch = singleQuotePattern.find(actionStr)
        if (singleMatch != null) {
            return singleMatch.groupValues[1]
        }
        
        return null
    }
    
    // 提取duration参数
    private fun extractDuration(actionStr: String): Long? {
        val pattern = """duration\s*=\s*["']?(\d+)""".toRegex()
        val match = pattern.find(actionStr) ?: return null
        return match.groupValues[1].toLong() * 1000
    }
    
    // 提取message参数
    private fun extractMessageParam(actionStr: String): String? {
        val pattern = """message\s*=\s*["']([^"']*)["']""".toRegex()
        return pattern.find(actionStr)?.groupValues?.get(1)
    }
    
    // 提取instruction参数
    private fun extractInstructionParam(actionStr: String): String? {
        val pattern = """instruction\s*=\s*["']([^"']*)["']""".toRegex()
        return pattern.find(actionStr)?.groupValues?.get(1)
    }
    
    // 提取app参数（用于Launch操作）
    private fun extractAppParam(actionStr: String): String? {
        // 匹配 app="xxx" 或 app='xxx'
        val pattern = """app\s*=\s*["']([^"']*)["']""".toRegex()
        return pattern.find(actionStr)?.groupValues?.get(1)
    }
    
    /**
     * 直接解析没有标签的内容
     */
    private fun parseDirectAction(content: String, thought: String?): AnalyzeResult {
        // 尝试直接匹配do(...)或finish(...)
        val doPattern = """do\s*\([^)]+\)""".toRegex()
        val finishPattern = """finish\s*\([^)]+\)""".toRegex()
        
        val doMatch = doPattern.find(content)
        if (doMatch != null) {
            return parseActionString(doMatch.value, thought ?: content.substringBefore("do(").trim())
        }
        
        val finishMatch = finishPattern.find(content)
        if (finishMatch != null) {
            return parseActionString(finishMatch.value, thought ?: content.substringBefore("finish(").trim())
        }
        
        // 无法解析，返回原始内容作为思考
        return AnalyzeResult(
            action = ParsedAction.Think(thought ?: content.take(200)),
            thought = thought
        )
    }
    
    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            when (val config = currentConfig) {
                is ProviderConfig.OllamaConfig -> {
                    val request = Request.Builder()
                        .url("${config.baseUrl}/api/tags")
                        .get()
                        .build()
                    httpClient.newCall(request).execute().isSuccessful
                }
                is ProviderConfig.ZhipuConfig -> {
                    // 智谱没有健康检查接口，假设配置了就能用
                    config.apiKey.isNotEmpty()
                }
                is ProviderConfig.OpenAIConfig -> {
                    config.apiKey.isNotEmpty()
                }
                is ProviderConfig.CustomConfig -> {
                    true
                }
                null -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接测试失败", e)
            false
        }
    }
    
    /**
     * 获取当前配置的提供商类型
     */
    fun getCurrentProvider(): AIProvider? {
        return when (currentConfig) {
            is ProviderConfig.ZhipuConfig -> AIProvider.ZHIPU
            is ProviderConfig.OllamaConfig -> AIProvider.OLLAMA
            is ProviderConfig.OpenAIConfig -> AIProvider.OPENAI
            is ProviderConfig.CustomConfig -> AIProvider.CUSTOM
            null -> null
        }
    }
    
    /**
     * 关闭服务
     */
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

/**
 * AI分析结果
 * 包含可执行的动作和AI的思考过程
 */
data class AnalyzeResult(
    val action: ParsedAction,
    val thought: String? = null
)

/**
 * AI异常
 */
class AIException(message: String, cause: Throwable? = null) : Exception(message, cause)
