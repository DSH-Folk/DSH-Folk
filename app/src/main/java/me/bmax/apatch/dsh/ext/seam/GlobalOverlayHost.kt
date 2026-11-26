package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.bmax.apatch.dsh.OverlayLifecycleOwner

/**
 * 全局悬浮窗托管。
 *
 * ## 为什么由宿主托管，而不是让扩展自己起 Service
 *
 * 扩展自己起前台 Service 会遇到三个麻烦，而每一个都由宿主做更合适：
 *
 * 1. **通知栏**：Android 8+ 的前台 Service 必须挂一条常驻通知。用户装个桌宠却多出
 *    一条通知，体验很差。宿主已经有一条服务通知（容器在跑时），窗口挂在它下面即可。
 * 2. **生命周期**：扩展被停用/卸载时，谁负责摘窗口？扩展自己的代码已经不跑了 ——
 *    只有宿主知道"这个 owner 没了"，也只有宿主能可靠地摘干净。
 * 3. **安全模式**：熔断时所有扩展都不加载，它们挂的窗口必须一起消失。宿主统一处理
 *    比让每个扩展自己写一遍可靠得多。
 *
 * ## 挂载方式照搬 DisplayMirror
 *
 * Compose 画在非 Activity 窗口里需要三个 owner（Lifecycle / ViewModelStore /
 * SavedStateRegistry），缺一个就会在 `rememberSaveable` 之类的地方抛。
 * [OverlayLifecycleOwner] 就是为此写的，[me.bmax.apatch.dsh.display.DisplayMirror]
 * 已经用它跑通了投屏悬浮窗，这里用同一套。
 *
 * ## 已知限制
 *
 * 一个 owner 目前只挂**一个**窗口（同 owner 再次 show 会替换）。桌宠只需要一个；
 * 将来若有扩展要多窗口，把 map 的值换成 list 即可。
 */
object GlobalOverlayHost {

    private data class Entry(
        val view: ComposeView,
        val params: WindowManager.LayoutParams,
        val owner: OverlayLifecycleOwner,
    )

    private val mounted = mutableMapOf<String, Entry>()

    @Volatile
    private var appContext: Context? = null

    /** 由 Application 调一次。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 是否已获得悬浮窗权限。 */
    fun hasPermission(context: Context? = appContext): Boolean {
        val ctx = context ?: return false
        return runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
    }

    /** 跳到系统悬浮窗权限页。 */
    fun openSettings(context: Context? = appContext) {
        val ctx = context ?: return
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            me.bmax.apatch.dsh.ext.ExtLog.log("打开悬浮窗权限页失败：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    /**
     * 挂窗口。
     *
     * @return 成功与否。没权限、Context 未绑定、系统拒绝 addView 都返回 false
     *         （不抛 —— 调用方多在 UI 线程，抛出去等于整页崩）。
     */
    fun show(
        ownerId: String,
        content: @Composable (Modifier) -> Unit,
    ): Boolean {
        val ctx = appContext ?: return false
        if (!hasPermission(ctx)) return false

        // 同 owner 重复 show：先摘旧的，避免叠窗。
        hide(ownerId)

        return runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val owner = OverlayLifecycleOwner().apply {
                moveTo(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
                moveTo(androidx.lifecycle.Lifecycle.Event.ON_START)
                moveTo(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            }

            val cv = ComposeView(ctx).apply {
                // DisposeOnDetachedFromWindow：窗口被移除时把 Compose 树也销毁，
                // 否则组合会一直挂着，里面的协程和订阅都不会停。
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { content(Modifier) }
            }

            val params = WindowManager.LayoutParams().apply {
                // 先给个初始尺寸：真正的尺寸由内容自己决定，这里只要不报错。
                // WRAP_CONTENT 让 Compose 内容撑开窗口。
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                // NOT_FOCUSABLE：悬浮窗不该抢焦点。抢了的话，被盖住的应用会失去输入焦点、
                // 输入法也会打到悬浮窗这边来 —— 桌宠只负责显示和点击，不参与输入。
                // LAYOUT_NO_LIMITS：允许拖到屏幕边缘之外（贴边时窗口一部分在屏外）。
                // HARDWARE_ACCELERATED：非 Activity 窗口里画 Compose，显式声明比靠默认值稳。
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 200
            }

            wm.addView(cv, params)
            mounted[ownerId] = Entry(cv, params, owner)
            true
        }.onFailure {
            me.bmax.apatch.dsh.ext.ExtLog.log(
                "[" + ownerId + "] 挂全局悬浮窗失败：" + (it.message ?: it.javaClass.simpleName),
            )
        }.getOrDefault(false)
    }

    /** 摘掉某个 owner 的窗口。幂等。 */
    fun hide(ownerId: String) {
        val entry = mounted.remove(ownerId) ?: return
        removeEntry(entry)
    }

    /** 摘掉某个 owner 的全部窗口（目前一个 owner 只有一个，语义上保留）。 */
    fun hideAllOf(ownerId: String) = hide(ownerId)

    /** 摘掉所有窗口（安全模式熔断、进程退出前用）。 */
    fun hideAll() {
        for (id in mounted.keys.toList()) hide(id)
    }

    private fun removeEntry(entry: Entry) {
        runCatching {
            val ctx = appContext ?: return@runCatching
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(entry.view as View)
        }
        // owner 要走到 DESTROYED，否则 lifecycle 的观察者不会被清掉。
        runCatching {
            entry.owner.moveTo(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            entry.owner.moveTo(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            entry.owner.moveTo(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        }
    }
}
