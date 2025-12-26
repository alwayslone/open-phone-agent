package com.open.agent.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.open.agent.root.RootExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 设备操作控制器
 * 使用ROOT权限执行截图、点击、滑动等操作
 */
class DeviceController(
    private val context: Context,
    private val rootExecutor: RootExecutor
) {
    
    // 截图保存路径
    private val screenshotPath: String
        get() = "${context.cacheDir.absolutePath}/screenshot.png"
    
    // 屏幕尺寸
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    
    init {
        // 获取屏幕尺寸
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }
    
    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> = Pair(screenWidth, screenHeight)
    
    /**
     * 截取屏幕截图
     * @return 截图文件路径，失败返回null
     */
    suspend fun takeScreenshot(): String? = withContext(Dispatchers.IO) {
        val result = rootExecutor.executeCommand("screencap -p $screenshotPath")
        if (result.success && File(screenshotPath).exists()) {
            screenshotPath
        } else {
            null
        }
    }
    
    /**
     * 截取屏幕并返回Bitmap
     */
    suspend fun takeScreenshotBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val path = takeScreenshot()
        if (path != null) {
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 截取屏幕并返回Base64编码
     * @param quality 图片质量 (0-100)
     * @param maxWidth 最大宽度（用于压缩）
     */
    suspend fun takeScreenshotBase64(quality: Int = 80, maxWidth: Int = 1080): String? = withContext(Dispatchers.IO) {
        val bitmap = takeScreenshotBitmap() ?: return@withContext null
        
        try {
            // 压缩图片
            val scaledBitmap = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val newHeight = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
            } else {
                bitmap
            }
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
            
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 点击屏幕指定位置
     * @param x X坐标
     * @param y Y坐标
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        return rootExecutor.executeCommandNoOutput("input tap $x $y")
    }
    
    /**
     * 长按屏幕指定位置
     * @param x X坐标
     * @param y Y坐标
     * @param duration 长按持续时间(毫秒)
     */
    suspend fun longPress(x: Int, y: Int, duration: Long = 1000): Boolean {
        return rootExecutor.executeCommandNoOutput("input swipe $x $y $x $y $duration")
    }
    
    /**
     * 双击屏幕指定位置
     * @param x X坐标
     * @param y Y坐标
     */
    suspend fun doubleTap(x: Int, y: Int): Boolean {
        // 执行两次快速点击
        val firstTap = rootExecutor.executeCommandNoOutput("input tap $x $y")
        delay(100)  // 双击间隔
        val secondTap = rootExecutor.executeCommandNoOutput("input tap $x $y")
        return firstTap && secondTap
    }
    
    /**
     * 滑动操作
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param duration 滑动持续时间(毫秒)
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        return rootExecutor.executeCommandNoOutput("input swipe $startX $startY $endX $endY $duration")
    }
    
    /**
     * 向上滑动（滚动查看更多内容）
     */
    suspend fun swipeUp(distance: Int = 500, duration: Long = 300): Boolean {
        val centerX = screenWidth / 2
        val startY = screenHeight / 2 + distance / 2
        val endY = screenHeight / 2 - distance / 2
        return swipe(centerX, startY, centerX, endY, duration)
    }
    
    /**
     * 向下滑动
     */
    suspend fun swipeDown(distance: Int = 500, duration: Long = 300): Boolean {
        val centerX = screenWidth / 2
        val startY = screenHeight / 2 - distance / 2
        val endY = screenHeight / 2 + distance / 2
        return swipe(centerX, startY, centerX, endY, duration)
    }
    
    /**
     * 向左滑动
     */
    suspend fun swipeLeft(distance: Int = 500, duration: Long = 300): Boolean {
        val centerY = screenHeight / 2
        val startX = screenWidth / 2 + distance / 2
        val endX = screenWidth / 2 - distance / 2
        return swipe(startX, centerY, endX, centerY, duration)
    }
    
    /**
     * 向右滑动
     */
    suspend fun swipeRight(distance: Int = 500, duration: Long = 300): Boolean {
        val centerY = screenHeight / 2
        val startX = screenWidth / 2 - distance / 2
        val endX = screenWidth / 2 + distance / 2
        return swipe(startX, centerY, endX, centerY, duration)
    }
    
    /**
     * 输入文本
     * @param text 要输入的文本
     * 支持中文等非ASCII字符，使用多种方式尝试输入
     */
    suspend fun inputText(text: String): Boolean {
        android.util.Log.d("DeviceController", "准备输入文本: $text")
        
        // 检查是否包含非ASCII字符（如中文）
        val hasNonAscii = text.any { it.code > 127 }
        
        return if (hasNonAscii) {
            // 中文输入：优先使用剪贴板方式（最可靠）
            android.util.Log.d("DeviceController", "检测到中文，使用剪贴板方式")
            inputTextViaClipboard(text)
        } else {
            // 纯ASCII字符先尝试标准input text命令
            val result = inputTextViaCommand(text)
            if (!result) inputTextViaClipboard(text) else true
        }
    }
    
    /**
     * 使用input text命令输入（仅支持ASCII）
     */
    private suspend fun inputTextViaCommand(text: String): Boolean {
        // 转义特殊字符
        val escapedText = text
            .replace(" ", "%s")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")
        
        return rootExecutor.executeCommandNoOutput("input text \"$escapedText\"")
    }
    
    /**
     * 使用剪贴板方式输入文本（最可靠的中文输入方式）
     * 利用ROOT权限直接操作剪贴板，然后粘贴
     */
    private suspend fun inputTextViaClipboard(text: String): Boolean {
        android.util.Log.d("DeviceController", "使用剪贴板方式输入: $text")
        
        // 方法: 使用Android的ClipboardManager通过app_process调用
        // 先将文本Base64编码以避免特殊字符问题
        val base64Text = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        
        // 方式1: 使用am broadcast配合ADBKeyboard
        val adbKeyboardSuccess = tryAdbKeyboard(text, base64Text)
        if (adbKeyboardSuccess) {
            android.util.Log.d("DeviceController", "ADBKeyboard输入成功")
            return true
        }
        
        // 方式2: 使用content命令写入剪贴板，然后模拟长按+粘贴
        val clipboardSuccess = tryClipboardPaste(text)
        if (clipboardSuccess) {
            android.util.Log.d("DeviceController", "剪贴板粘贴成功")
            return true
        }
        
        // 方式3: 逐字符输入（最后手段）
        android.util.Log.d("DeviceController", "尝试逐字符输入")
        return inputCharByChar(text)
    }
    
    /**
     * 尝试使用ADBKeyboard输入
     */
    private suspend fun tryAdbKeyboard(text: String, base64Text: String): Boolean {
        android.util.Log.d("DeviceController", "尝试ADBKeyboard输入...")
        
        // 先检查ADBKeyboard是否安装
        val packageCheck = rootExecutor.executeCommand("pm list packages | grep adbkeyboard")
        if (!packageCheck.output.contains("adbkeyboard", ignoreCase = true)) {
            android.util.Log.w("DeviceController", "ADBKeyboard未安装，跳过")
            return false
        }
        
        // 检查是否是当前输入法
        val imeResult = rootExecutor.executeCommand("settings get secure default_input_method")
        val currentIme = imeResult.output.trim()
        val isAdbKeyboard = currentIme.contains("adbkeyboard", ignoreCase = true)
        
        android.util.Log.d("DeviceController", "当前输入法: $currentIme, 是否ADBKeyboard: $isAdbKeyboard")
        
        if (!isAdbKeyboard) {
            // 尝试切换到ADBKeyboard
            android.util.Log.d("DeviceController", "切换到ADBKeyboard...")
            rootExecutor.executeCommandNoOutput(
                "ime set com.android.adbkeyboard/.AdbIME"
            )
            delay(500)  // 等待输入法切换
        }
        
        // 发送ADBKeyboard广播 - 使用Base64方式（更可靠）
        android.util.Log.d("DeviceController", "发送ADB_INPUT_B64广播: $base64Text")
        val broadcastResult = rootExecutor.executeCommand(
            "am broadcast -a ADB_INPUT_B64 --es msg '$base64Text'"
        )
        android.util.Log.d("DeviceController", "广播结果: ${broadcastResult.output}")
        
        // 检查广播是否成功处理
        if (broadcastResult.output.contains("result=0") || 
            broadcastResult.output.contains("Broadcast completed")) {
            delay(300)
            return true
        }
        
        // 尝试文本方式
        android.util.Log.d("DeviceController", "尝试ADB_INPUT_TEXT广播")
        val escapedText = text.replace("\"", "\\\"")
        val textResult = rootExecutor.executeCommand(
            "am broadcast -a ADB_INPUT_TEXT --es msg \"$escapedText\""
        )
        android.util.Log.d("DeviceController", "文本广播结果: ${textResult.output}")
        
        val success = textResult.output.contains("result=0") || 
                       textResult.output.contains("Broadcast completed")
        
        if (success) delay(300)
        return success
    }
    
    /**
     * 尝试剪贴板粘贴方式
     * 利用ROOT权限调用Android的ClipboardService
     */
    private suspend fun tryClipboardPaste(text: String): Boolean {
        android.util.Log.d("DeviceController", "尝试剪贴板粘贴方式...")
        
        // 转义特殊字符
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("'", "'\"'\"'")
            .replace("\"", "\\\"")
        
        // 方案A: 使用cmd clipboard命令（Android 10+）
        android.util.Log.d("DeviceController", "尝试cmd clipboard set命令")
        val cmdResult = rootExecutor.executeCommand("cmd clipboard set \"$escapedText\" 2>&1")
        android.util.Log.d("DeviceController", "cmd clipboard结果: ${cmdResult.output}, 错误: ${cmdResult.error}")
        
        if (cmdResult.success && cmdResult.error.isEmpty()) {
            delay(200)
            android.util.Log.d("DeviceController", "执行粘贴键(279)")
            val pasteResult = rootExecutor.executeCommandNoOutput("input keyevent 279")
            if (pasteResult) {
                android.util.Log.d("DeviceController", "粘贴成功")
                return true
            }
        }
        
        // 方案B: 使用am start启动内置的剪贴板服务
        android.util.Log.d("DeviceController", "尝试am broadcast clipper方式")
        rootExecutor.executeCommand("am broadcast -a clipper.set -e text \"$escapedText\"")
        delay(200)
        val pasteResult2 = rootExecutor.executeCommandNoOutput("input keyevent 279")
        if (pasteResult2) {
            android.util.Log.d("DeviceController", "clipper方式粘贴成功")
            return true
        }
        
        android.util.Log.w("DeviceController", "剪贴板方式失败")
        return false
    }
    
    /**
     * 逐字符输入（最后手段）
     * 对每个字符使用Unicode编码输入
     */
    private suspend fun inputCharByChar(text: String): Boolean {
        for (char in text) {
            if (char.code <= 127) {
                // ASCII字符直接输入
                val escaped = when (char) {
                    ' ' -> "%s"
                    '"' -> "\\\""
                    '\'' -> "\\'"
                    else -> char.toString()
                }
                rootExecutor.executeCommandNoOutput("input text \"$escaped\"")
            } else {
                // 非ASCII字符使用Unicode输入
                val unicode = char.code
                // 尝试使用input text的unicode支持
                rootExecutor.executeCommandNoOutput("input text \"\\u${String.format("%04x", unicode)}\"")
            }
            delay(50) // 字符间的延迟
        }
        return true
    }
    
    /**
     * 使用ADBKeyboard广播方式输入（支持中文）
     * 需要设备安装ADBKeyboard并设为默认输入法
     */
    private suspend fun inputTextViaBroadcast(text: String): Boolean {
        return inputTextViaClipboard(text)
    }
    
    /**
     * 发送按键事件
     * @param keyCode 按键代码
     */
    suspend fun pressKey(keyCode: Int): Boolean {
        return rootExecutor.executeCommandNoOutput("input keyevent $keyCode")
    }
    
    /**
     * 按下返回键
     */
    suspend fun pressBack(): Boolean = pressKey(KeyCode.KEYCODE_BACK)
    
    /**
     * 按下Home键
     */
    suspend fun pressHome(): Boolean = pressKey(KeyCode.KEYCODE_HOME)
    
    /**
     * 按下最近任务键
     */
    suspend fun pressRecent(): Boolean = pressKey(KeyCode.KEYCODE_APP_SWITCH)
    
    /**
     * 按下电源键
     */
    suspend fun pressPower(): Boolean = pressKey(KeyCode.KEYCODE_POWER)
    
    /**
     * 按下音量加
     */
    suspend fun pressVolumeUp(): Boolean = pressKey(KeyCode.KEYCODE_VOLUME_UP)
    
    /**
     * 按下音量减
     */
    suspend fun pressVolumeDown(): Boolean = pressKey(KeyCode.KEYCODE_VOLUME_DOWN)
    
    /**
     * 按下回车键
     */
    suspend fun pressEnter(): Boolean = pressKey(KeyCode.KEYCODE_ENTER)
    
    /**
     * 按下删除键
     */
    suspend fun pressDelete(): Boolean = pressKey(KeyCode.KEYCODE_DEL)
    
    /**
     * 获取当前前台应用包名
     */
    suspend fun getCurrentPackage(): String? {
        val result = rootExecutor.executeCommand(
            "dumpsys activity activities | grep mResumedActivity"
        )
        if (result.success && result.output.isNotEmpty()) {
            // 解析输出获取包名
            val regex = "\\{[^}]+\\s+([\\w.]+)/".toRegex()
            val match = regex.find(result.output)
            return match?.groupValues?.getOrNull(1)
        }
        return null
    }
    
    /**
     * 获取屏幕状态（是否亮屏）
     */
    suspend fun isScreenOn(): Boolean {
        val result = rootExecutor.executeCommand("dumpsys power | grep 'Display Power'")
        return result.output.contains("state=ON")
    }
    
    /**
     * 唤醒屏幕
     */
    suspend fun wakeUp(): Boolean {
        return rootExecutor.executeCommandNoOutput("input keyevent KEYCODE_WAKEUP")
    }
    
    /**
     * 清理截图缓存
     */
    fun clearScreenshotCache() {
        try {
            File(screenshotPath).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // ========== 应用管理功能 ==========
    
    // 缓存应用列表（应用名 -> 包名）
    private var appListCache: Map<String, String>? = null
    
    /**
     * 获取设备上所有已安装的应用列表
     * @return Map<应用名, 包名>
     */
    suspend fun getInstalledApps(): Map<String, String> = withContext(Dispatchers.IO) {
        // 如果有缓存，直接返回
        appListCache?.let { return@withContext it }
        
        val appMap = mutableMapOf<String, String>()
        
        try {
            // 使用pm list packages -3只获取第三方应用（排除系统应用）
            val packagesResult = rootExecutor.executeCommand("pm list packages -3")
            if (!packagesResult.success) {
                android.util.Log.e("DeviceController", "获取应用列表失败: ${packagesResult.error}")
                return@withContext appMap
            }
            
            // 解析包名列表 - 格式: package:com.example.app
            val packageNames = packagesResult.output.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() && !it.contains("/") && !it.contains("=") }
            
            android.util.Log.d("DeviceController", "找到 ${packageNames.size} 个应用")
            
            // 获取每个应用的名称
            for (packageName in packageNames) {
                val appName = getAppName(packageName)
                if (appName.isNotEmpty()) {
                    appMap[appName] = packageName
                    android.util.Log.d("DeviceController", "找到 ${appName} ")
                    // 也添加包名作为键，方便直接使用包名查找
                    appMap[packageName] = packageName
                }
            }
            
            // 缓存结果
            appListCache = appMap
            
        } catch (e: Exception) {
            android.util.Log.e("DeviceController", "获取应用列表异常", e)
        }
        
        appMap
    }
    
    /**
     * 获取应用名称
     * 优先使用PackageManager获取中文名称
     */
    private suspend fun getAppName(packageName: String): String {
        try {
            // 优先使用PackageManager获取应用名称（这是最可靠的方法）
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.isNotEmpty() && label != packageName) {
                    android.util.Log.d("DeviceController", "应用名称: $label")
                    return label
                }
            } catch (e: Exception) {
                // PackageManager失败，继续尝试其他方法
            }
            
            // 备用方案：使用dumpsys package获取应用信息
            val result = rootExecutor.executeCommand(
                "dumpsys package $packageName | grep -E 'labelRes|nonLocalizedLabel'"
            )
            
            if (result.success && result.output.isNotEmpty()) {
                // 查找labelRes或者nonLocalizedLabel
                val labelPattern = """nonLocalizedLabel=([^\s,]+)""".toRegex()
                val match = labelPattern.find(result.output)
                if (match != null) {
                    val label = match.groupValues[1]
                    if (label.isNotEmpty() && label != "null") {
                        return label
                    }
                }
            }
            
            // 备用方案：使用aapt获取应用名
            val aaptResult = rootExecutor.executeCommand(
                "aapt dump badging \$(pm path $packageName | sed 's/package://') 2>/dev/null | grep 'application-label:'"
            )
            if (aaptResult.success && aaptResult.output.isNotEmpty()) {
                val aaptPattern = """application-label:'([^']+)'""".toRegex()
                val aaptMatch = aaptPattern.find(aaptResult.output)
                if (aaptMatch != null) {
                    return aaptMatch.groupValues[1]
                }
            }
            
            // 如果都失败，返回包名的最后一部分作为名称
            return packageName.substringAfterLast(".")
            
        } catch (e: Exception) {
            return packageName.substringAfterLast(".")
        }
    }
    
    /**
     * 根据应用名称查找包名
     * 支持精确匹配、模糊匹配、相同字符匹配
     */
    suspend fun findPackageByName(appName: String): String? {
        val apps = getInstalledApps()
        
        android.util.Log.d("DeviceController", "开始匹配应用: $appName, 已安装应用数: ${apps.size}")
        
        // 1. 精确匹配
        apps[appName]?.let { 
            android.util.Log.d("DeviceController", "精确匹配成功: $appName")
            return it 
        }
        
        // 2. 忽略大小写匹配
        val lowerName = appName.lowercase()
        for ((name, pkg) in apps) {
            if (name.lowercase() == lowerName) {
                android.util.Log.d("DeviceController", "忽略大小写匹配成功: $name")
                return pkg
            }
        }
        
        // 3. 包含匹配
        for ((name, pkg) in apps) {
            if (name.lowercase().contains(lowerName) || lowerName.contains(name.lowercase())) {
                android.util.Log.d("DeviceController", "包含匹配成功: $name")
                return pkg
            }
        }
        
        // 4. 常用应用名称映射（包含谐音）
        val commonApps = mapOf(
            // 微信（含谐音）
            "微信" to "com.tencent.mm",
            "威信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "weixin" to "com.tencent.mm",
            "wx" to "com.tencent.mm",
            // QQ
            "qq" to "com.tencent.mobileqq",
            "QQ" to "com.tencent.mobileqq",
            "扣扣" to "com.tencent.mobileqq",
            // 支付宝
            "支付宝" to "com.eg.android.AlipayGphone",
            "alipay" to "com.eg.android.AlipayGphone",
            "zfb" to "com.eg.android.AlipayGphone",
            // 淘宝
            "淘宝" to "com.taobao.taobao",
            "taobao" to "com.taobao.taobao",
            "tb" to "com.taobao.taobao",
            // 抖音（含谐音）
            "抖音" to "com.ss.android.ugc.aweme",
            "押音" to "com.ss.android.ugc.aweme",
            "斗音" to "com.ss.android.ugc.aweme",
            "tiktok" to "com.ss.android.ugc.aweme",
            "douyin" to "com.ss.android.ugc.aweme",
            "dy" to "com.ss.android.ugc.aweme",
            // 快手
            "快手" to "com.smile.gifmaker",
            "ks" to "com.smile.gifmaker",
            // 美团
            "美团" to "com.sankuai.meituan",
            "meituan" to "com.sankuai.meituan",
            // 饿了么（含谐音）
            "饿了么" to "me.ele",
            "饿了吗" to "me.ele",
            "eleme" to "me.ele",
            // 其他常用应用
            "大众点评" to "com.dianping.v1",
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "京东" to "com.jingdong.app.mall",
            "jd" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "pdd" to "com.xunmeng.pinduoduo",
            "小红书" to "com.xingin.xhs",
            "xhs" to "com.xingin.xhs",
            "微博" to "com.sina.weibo",
            "weibo" to "com.sina.weibo",
            "知乎" to "com.zhihu.android",
            "zhihu" to "com.zhihu.android",
            "bilibili" to "tv.danmaku.bili",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bili" to "tv.danmaku.bili",
            "网易云音乐" to "com.netease.cloudmusic",
            "网易云" to "com.netease.cloudmusic",
            "云音乐" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            "qqmusic" to "com.tencent.qqmusic",
            // 系统应用
            "设置" to "com.android.settings",
            "settings" to "com.android.settings",
            "相机" to "com.android.camera",
            "camera" to "com.android.camera",
            "相册" to "com.android.gallery3d",
            "图库" to "com.android.gallery3d",
            "gallery" to "com.android.gallery3d",
            "文件管理" to "com.android.documentsui",
            "files" to "com.android.documentsui",
            "日历" to "com.android.calendar",
            "calendar" to "com.android.calendar",
            "计算器" to "com.android.calculator2",
            "calculator" to "com.android.calculator2",
            "时钟" to "com.android.deskclock",
            "clock" to "com.android.deskclock",
            "闹钟" to "com.android.deskclock",
            "备忘录" to "com.android.notes"
        )
        
        commonApps[appName]?.let { 
            android.util.Log.d("DeviceController", "常用应用映射成功: $appName")
            return it 
        }
        commonApps[appName.lowercase()]?.let { 
            android.util.Log.d("DeviceController", "常用应用映射成功(小写): $appName")
            return it 
        }
        
        // 5. 相同汉字/单词匹配 - 找出与app名称相同字符最多的应用
        val bestMatch = findBestMatchByCommonChars(appName, apps)
        if (bestMatch != null) {
            android.util.Log.d("DeviceController", "相同字符匹配成功: $appName -> $bestMatch")
            return bestMatch
        }
        
        android.util.Log.w("DeviceController", "未找到匹配的应用: $appName")
        return null
    }
    
    /**
     * 通过相同汉字/单词数量找最佳匹配
     * 计算查询词与每个应用名称和包名的相同字符数，返回相同字符最多的应用包名
     */
    private fun findBestMatchByCommonChars(query: String, apps: Map<String, String>): String? {
        if (query.isBlank()) return null
        
        // 提取查询词中的所有字符（汉字和英文单词）
        val queryChars = extractCharsAndWords(query)
        if (queryChars.isEmpty()) return null
        
        android.util.Log.d("DeviceController", "查询词字符集: $queryChars")
        
        var bestPkg: String? = null
        var bestScore = 0
        var bestName = ""
        
        // 记录已检查的包名，避免重复
        val checkedPkgs = mutableSetOf<String>()
        
        for ((name, pkg) in apps) {
            // 避免重复检查同一个包名
            if (pkg in checkedPkgs) continue
            checkedPkgs.add(pkg)
            
            // 同时从应用名和包名中提取字符
            val nameChars = extractCharsAndWords(name)
            val pkgChars = extractCharsAndWords(pkg)
            val allChars = nameChars
            
            // 计算相同字符/单词数量
            val commonCount = countCommonElements(queryChars, allChars)
            
            if (commonCount > bestScore) {
                bestScore = commonCount
                bestPkg = pkg
                bestName = if (name != pkg) name else pkg
            }
        }
        
        // 至少要有1个相同字符才算匹配成功
        if (bestScore >= 1) {
            android.util.Log.d("DeviceController", "最佳匹配: '$query' -> '$bestName' ($bestPkg) (相同字符数: $bestScore)")
            return bestPkg
        }
        
        return null
    }
    
    /**
     * 提取字符串中的所有汉字和英文单词
     */
    private fun extractCharsAndWords(text: String): Set<String> {
        val result = mutableSetOf<String>()
        
        // 提取每个汉字（作为单独的字符）
        for (char in text) {
            if (char.code in 0x4E00..0x9FFF) {
                result.add(char.toString())
            }
        }
        
        // 提取英文单词（连续的字母序列，按.分割包名）
        val wordPattern = """[a-zA-Z]+""".toRegex()
        wordPattern.findAll(text).forEach { match ->
            val word = match.value.lowercase()
            if (word.length >= 2) {  // 只保留2个字母以上的单词
                result.add(word)
            }
        }
        
        return result
    }
    
    /**
     * 计算两个集合的相同元素数量
     */
    private fun countCommonElements(set1: Set<String>, set2: Set<String>): Int {
        return set1.intersect(set2).size
    }
    
    /**
     * 根据应用名称启动应用
     */
    suspend fun launchApp(appName: String): Boolean {
        android.util.Log.d("DeviceController", "尝试启动应用: $appName")
        
        val packageName = findPackageByName(appName)
        if (packageName == null) {
            android.util.Log.w("DeviceController", "找不到应用: $appName")
            return false
        }
        
        android.util.Log.d("DeviceController", "找到包名: $packageName，开始启动")
        return launchAppByPackage(packageName)
    }
    
    /**
     * 根据包名启动应用
     */
    suspend fun launchAppByPackage(packageName: String): Boolean {
        // 使用monkey命令启动应用（最简单可靠）
        val monkeyResult = rootExecutor.executeCommandNoOutput(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        )
        if (monkeyResult) {
            delay(500) // 等待应用启动
            return true
        }
        
        // 备用方案：使用am start
        val result = rootExecutor.executeCommand(
            "am start -n \$(cmd package resolve-activity --brief $packageName | tail -n 1) 2>/dev/null"
        )
        if (result.success) {
            delay(500)
            return true
        }
        
        // 再备用：直接使用包名启动
        val amResult = rootExecutor.executeCommandNoOutput(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName"
        )
        if (amResult) {
            delay(500)
            return true
        }
        
        return false
    }
    
    /**
     * 清除应用列表缓存
     */
    fun clearAppListCache() {
        appListCache = null
    }
}

/**
 * Android KeyCode常量
 */
object KeyCode {
    const val KEYCODE_BACK = 4
    const val KEYCODE_HOME = 3
    const val KEYCODE_APP_SWITCH = 187
    const val KEYCODE_POWER = 26
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_MENU = 82
    const val KEYCODE_SEARCH = 84
    const val KEYCODE_TAB = 61
    const val KEYCODE_SPACE = 62
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
}
