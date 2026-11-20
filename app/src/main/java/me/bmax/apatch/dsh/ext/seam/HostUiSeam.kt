package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import me.bmax.apatch.BuildConfig

/**
 * [UiSeam] 的宿主实现。
 *
 * ## 导航为什么需要一个"桥"
 *
 * `navController` 只在 Compose 组合里有意义（它绑在 NavHost 上）。扩展调 `navigate()`
 * 时可能在后台线程、也可能在 Activity 还没起来的时候。所以这里不直接持有 controller，
 * 而是由宿主 UI 层在 NavHost 建立时[注册][NavigationBridge]，销毁时注销。
 *
 * 没注册时 `navigate()` 返回 false —— **不缓存请求、不排队重放**。
 * 排队听起来贴心，实际会让"点了没反应"变成"过一会儿突然跳页"，更吓人。
 */
internal class HostUiSeam(private val ctx: Context) : UiSeam {

    /** 由宿主 UI 层注入。宿主没有 NavHost 时保持 null。 */
    interface NavigationBridge {
        /** 返回是否成功跳转（路由不存在 / 栈已销毁都返回 false）。 */
        fun navigate(route: String): Boolean
        fun openWebUi()
    }

    companion object {
        @Volatile
        var navigation: NavigationBridge? = null
    }

    override fun darkTheme(): Boolean = runCatching {
        val mode = AppCompatDelegate.getDefaultNightMode()
        when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val uiMode = ctx.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }.getOrDefault(false)

    override fun accentColor(): Long = runCatching {
        // 宿主主题色是动态生成的（materialKolor），没有静态常量可取。
        // 这里返回 0 表示"没有固定主色"，扩展该用自己的配色 —— 编一个假颜色更糟。
        0L
    }.getOrDefault(0L)

    override fun navigate(route: String): Boolean =
        runCatching { navigation?.navigate(route) ?: false }.getOrDefault(false)

    override fun openWebUi() {
        runCatching { navigation?.openWebUi() }
    }

    override fun toast(message: String) {
        runCatching {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun language(): String = runCatching {
        (AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()).toLanguageTag()
    }.getOrDefault("en")
}
