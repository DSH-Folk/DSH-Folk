package me.bmax.apatch.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import me.bmax.apatch.APApplication
import me.bmax.apatch.R

object LauncherIconUtils {
    private const val MAIN_ACTIVITY = ".ui.MainActivityDefault"
    private const val ALIAS_ACTIVITY = ".ui.MainActivityAlias"
    private const val ALIAS_ACTIVITY_SU = ".ui.MainActivityAliasSu"
    private const val ALIAS_ACTIVITY_ALT_SU = ".ui.MainActivityAliasAltSu"
    // DeepDroid / DSHD 两组的别名（各含主图标与备用图标）。
    private const val ALIAS_DEEPDROID = ".ui.MainActivityAliasDeepDroid"
    private const val ALIAS_ALT_DEEPDROID = ".ui.MainActivityAliasAltDeepDroid"
    private const val ALIAS_DSHD = ".ui.MainActivityAliasDshd"
    private const val ALIAS_ALT_DSHD = ".ui.MainActivityAliasAltDshd"

    /**
     * 桌面名称的可选值。
     *
     * 为什么这些名字必须各有一套 activity-alias：`android:label` 是**安装时**从资源解析的，
     * 运行期没有任何 API 能改它。所以「换桌面名字」唯一的实现方式就是声明多个别名、
     * 每次只启用一个 —— 桌面看到的是被启用的那个别名的 label。
     *
     * 图标与名字是两个独立维度（用户可能想用 DeepDroid 配**备用**图标），
     * 所以每个名字都要「主图标 + 备用图标」两份别名，一共 4 名字 × 2 = 8 个。
     */
    val NAMES = listOf("DSH-Folk", "DSH", "DeepDroid", "DSHD")

    /** 旧版本存过的、现在已没有对应别名的名字，一律折回默认值。 */
    fun normalizeName(stored: String?): String =
        if (stored != null && stored in NAMES) stored else "DSH-Folk"

    /**
     * 「设置 → 常规 → 备用图标」的 prefs 键。
     *
     * 这个开关的取值在两处要被读到：这里（决定启用哪个 launcher 别名）和关于页（决定显示哪个
     * 图标）。字面量只能有一份 —— 分散写的话改了一个另一个不会跟着变，而症状正是「关于页的图标
     * 和主屏上的不是同一个设计」这种要用户截图来对比才发现的偏差。
     */
    const val KEY_USE_ALT_ICON = "use_alt_icon"

    /** 当前用的是不是备用图标。关于页跟着它走。 */
    fun usesAltIcon(): Boolean = APApplication.sharedPreferences.getBoolean(KEY_USE_ALT_ICON, false)

    /**
     * 当前生效的启动器图标**前景**位图 —— 就是 `mipmap-anydpi-v26/ic_launcher*.xml` 里 `<foreground>`
     * 引的那一份。
     *
     * 关于页必须用它，而不是直接 `R.mipmap.ic_launcher`：后者在 API 26+ 解析到的是 adaptive-icon
     * XML，Compose 的 `painterResource` 画不了它（既不是位图也不是 vector，会抛 unsupported type）。
     * 前景位图自带那圈浅色圆角底，单独显示就是用户在主屏上看到的那个图标。
     */
    @DrawableRes
    fun currentIconForeground(): Int =
        if (usesAltIcon()) R.mipmap.ic_launcher_alt_foreground else R.mipmap.ic_launcher_foreground

    fun updateLauncherState(context: Context) {
        val prefs = APApplication.sharedPreferences
        val useAlt = prefs.getBoolean(KEY_USE_ALT_ICON, false)
        // normalizeName：旧版本可能存过已没有对应别名的名字（那时只有两个选项）。
        // 不折回默认值的话，下面 when 会落到 else 分支、启用 DSH-Folk 的别名，
        // 而偏好里仍写着旧名字 —— 下次读又对不上，来回抖。
        val appName = normalizeName(prefs.getString("desktop_app_name", "DSH-Folk"))

        val pm = context.packageManager
        val basePackage = APApplication::class.java.`package`?.name ?: "me.bmax.apatch"

        val mainComponent = ComponentName(context.packageName, basePackage + MAIN_ACTIVITY)
        val aliasComponent = ComponentName(context.packageName, basePackage + ALIAS_ACTIVITY)
        val aliasSuComponent = ComponentName(context.packageName, basePackage + ALIAS_ACTIVITY_SU)
        val aliasAltSuComponent = ComponentName(context.packageName, basePackage + ALIAS_ACTIVITY_ALT_SU)
        val aliasDeepDroid = ComponentName(context.packageName, basePackage + ALIAS_DEEPDROID)
        val aliasAltDeepDroid = ComponentName(context.packageName, basePackage + ALIAS_ALT_DEEPDROID)
        val aliasDshd = ComponentName(context.packageName, basePackage + ALIAS_DSHD)
        val aliasAltDshd = ComponentName(context.packageName, basePackage + ALIAS_ALT_DSHD)

        // 目标 = (名字, 是否备用图标) 的笛卡尔积。用 when 逐条列出而不是查表拼字符串：
        // 别名常量与 manifest 里的 android:name 必须一字不差，拼错了会启用一个不存在的
        // 组件（setComponentEnabledSetting 静默失败，桌面图标直接消失）。
        val targetComponent = when (appName) {
            "DSH" -> if (useAlt) aliasAltSuComponent else aliasSuComponent
            "DeepDroid" -> if (useAlt) aliasAltDeepDroid else aliasDeepDroid
            "DSHD" -> if (useAlt) aliasAltDshd else aliasDshd
            else -> if (useAlt) aliasComponent else mainComponent
        }

        val allComponents = listOf(
            mainComponent, aliasComponent,
            aliasSuComponent, aliasAltSuComponent,
            aliasDeepDroid, aliasAltDeepDroid,
            aliasDshd, aliasAltDshd,
        )

        try {
            // Enable target
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // Disable others
            allComponents.filter { it != targetComponent }.forEach {
                pm.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Deprecated but kept for compatibility if needed, redirects to updateLauncherState
    fun toggleLauncherIcon(context: Context, useAlt: Boolean) {
        updateLauncherState(context)
    }

    fun applySaved(context: Context) {
        updateLauncherState(context)
    }
}
