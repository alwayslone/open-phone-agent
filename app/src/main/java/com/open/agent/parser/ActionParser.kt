package com.open.agent.parser

/**
 * 解析后的动作
 * 这是AI分析屏幕后返回的可执行动作
 */
sealed class ParsedAction {
    // 点击操作
    data class Tap(val x: Int, val y: Int) : ParsedAction()
    
    // 双击操作
    data class DoubleTap(val x: Int, val y: Int) : ParsedAction()
    
    // 长按操作
    data class LongPress(val x: Int, val y: Int, val duration: Long) : ParsedAction()
    
    // 滑动操作
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val duration: Long
    ) : ParsedAction()
    
    // 快捷滑动
    data class SwipeUp(val distance: Int, val duration: Long) : ParsedAction()
    data class SwipeDown(val distance: Int, val duration: Long) : ParsedAction()
    data class SwipeLeft(val distance: Int, val duration: Long) : ParsedAction()
    data class SwipeRight(val distance: Int, val duration: Long) : ParsedAction()
    
    // 文本输入
    data class InputText(val text: String) : ParsedAction()
    
    // 按键操作
    object PressBack : ParsedAction()
    object PressHome : ParsedAction()
    object PressRecent : ParsedAction()
    object PressEnter : ParsedAction()
    object PressDelete : ParsedAction()
    data class PressKey(val keyCode: Int) : ParsedAction()
    
    // 等待
    data class Wait(val duration: Long) : ParsedAction()
    
    // 截图
    object Screenshot : ParsedAction()
    
    // AI思考
    data class Think(val thought: String) : ParsedAction()
    
    // 完成
    data class Complete(val message: String) : ParsedAction()
    
    // 错误
    data class Error(val message: String) : ParsedAction()
    
    // 未知操作
    data class Unknown(val actionType: String, val params: Map<String, Any>) : ParsedAction()
    
    // 需要用户接管
    data class TakeOver(val message: String) : ParsedAction()
    
    // 交互操作（需要用户选择）
    object Interact : ParsedAction()
    
    // 记录当前页面
    data class Note(val message: String) : ParsedAction()
    
    // 调用API总结
    data class CallAPI(val instruction: String) : ParsedAction()
    
    // 启动应用
    data class LaunchApp(val appName: String) : ParsedAction()
    
    /**
     * 获取动作描述
     */
    fun getDescription(): String = when (this) {
        is Tap -> "点击坐标 ($x, $y)"
        is DoubleTap -> "双击坐标 ($x, $y)"
        is LongPress -> "长按坐标 ($x, $y) ${duration}ms"
        is Swipe -> "滑动从 ($startX, $startY) 到 ($endX, $endY)"
        is SwipeUp -> "向上滑动 ${distance}px"
        is SwipeDown -> "向下滑动 ${distance}px"
        is SwipeLeft -> "向左滑动 ${distance}px"
        is SwipeRight -> "向右滑动 ${distance}px"
        is InputText -> "输入文本: $text"
        is PressBack -> "按下返回键"
        is PressHome -> "按下Home键"
        is PressRecent -> "按下最近任务键"
        is PressEnter -> "按下回车键"
        is PressDelete -> "按下删除键"
        is PressKey -> "按下按键 $keyCode"
        is Wait -> "等待 ${duration}ms"
        is Screenshot -> "截取屏幕"
        is Think -> "AI思考: $thought"
        is Complete -> "任务完成: $message"
        is Error -> "错误: $message"
        is Unknown -> "未知操作: $actionType"
        is TakeOver -> "需要用户接管: $message"
        is Interact -> "需要用户选择"
        is Note -> "记录页面: $message"
        is CallAPI -> "调用API: $instruction"
        is LaunchApp -> "启动应用: $appName"
    }
}
