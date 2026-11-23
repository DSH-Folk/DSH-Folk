package me.bmax.apatch.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「首页正有一个说明性弹窗开着」的全局信号。
 *
 * ## 为什么需要它
 *
 * 应用里有两处会弹「需要用户读一下」的框，它们分属不同的组合层级：
 *
 *  - **首页那条互斥链**（首启引导 → 更新内容 → 权限策略变更 → 彩蛋），在 `:app` 的
 *    `Home.kt` 里，用 `if / else if` 保证彼此不叠；
 *  - **运行时更新提示**，在 `MainActivity` 里，由自动检查异步触发。
 *
 * 两者互不知情。真机反馈：运行时提示会**盖在更新内容提示上面** —— 用户先看到的
 * 是「运行时 0.2.3 可用」，而本该先读的「本次更新改了什么」被压在下面。
 * Dialog 是独立窗口，叠放顺序按创建时间而不是 Compose 声明顺序，所以不能靠
 * 「把谁写到后面」来解决。
 *
 * ## 为什么用一个 object 而不是 CompositionLocal
 *
 * `MainActivity` 与 `Home` 之间隔着 `DestinationsNavHost`，塞 CompositionLocal 要
 * 穿过整棵导航树，还要在 NavHost 内外各提供一次。这里只是一个布尔可见性，用
 * 进程内的普通状态最省事，也不引入额外的组合约束。
 *
 * 代价是它跨 Activity 共享 —— 本应用只有一个 Activity 会渲染首页，可以接受。
 */
object HomeDialogGate {

    /**
     * 首页的说明性弹窗此刻是否开着。
     *
     * 由 [Home] 在组合期写入（`SideEffect`），由 `MainActivity` 的运行时提示读取。
     */
    var homeDialogVisible: Boolean by mutableStateOf(false)
        internal set
}
