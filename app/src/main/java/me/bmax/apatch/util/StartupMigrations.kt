package me.bmax.apatch.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.dsh.PrivPolicy

/**
 * 启动阶段的**集中式**兼容性迁移。
 *
 * ## 为什么要有这个文件
 *
 * 历史版本兼容代码原本散在各处：有的在 [PrivPolicy.migrateOnce]，有的在
 * [PermissionManager] 的初始化路径里，有的藏在某个模块第一次被读时才跑。后果是
 * **"迁移到底跑没跑过"这件事无法从一处回答** —— 排查时要在整个代码库里 grep，
 * 而且新加一条迁移时没人知道该放哪，于是又散出去一处。
 *
 * 现在统一在这里：启动时按固定顺序跑一遍，每条各自带幂等标志。要加新的迁移，
 * 就往 [MIGRATIONS] 里加一条，不用再去改 [me.bmax.apatch.APatchApp]。
 *
 * ## 为什么每条都带自己的幂等标志，而不是统一比对版本号
 *
 * 最初的设想是「记下上次运行的 versionName，变了才跑迁移」。实测下来那样**不安全**：
 *  - 版本号会回退（用户装回旧版、或从 beta 退回 stable），回退时"变了"又会再跑一遍，
 *    而有些迁移是不可重复的（比如把旧键读完即删，第二次跑时旧键已经没了）。
 *  - 一次版本升级里可能只该跑其中几条，另几条属于更早的版本 —— 版本号比对只能回答
 *    "整体变没变"，回答不了"这一条该不该跑"。
 *
 * 所以每条迁移**自带**一个 `xxx_migrated` 布尔标志（这是它们原本就有的写法，
 * 也是真正幂等的做法）。本文件负责的是**集中调度**，不是替它们判断该不该跑。
 *
 * 版本号仍然记一份（[KEY_LAST_VERSION]），但只用于**诊断**：出问题时能看出用户是
 * 从哪个版本升上来的。它不参与任何迁移判定。
 */
object StartupMigrations {

    private const val TAG = "StartupMigrations"

    /** 上次运行的 versionName。**仅用于诊断**，不参与迁移判定（理由见类注释）。 */
    const val KEY_LAST_VERSION = "startup_last_version_name"

    /** 上次运行的 versionCode。同上。 */
    private const val KEY_LAST_VERSION_CODE = "startup_last_version_code"

    /** 一条迁移。 */
    private data class Migration(
        /** 稳定标识，只用于日志。改名字不影响幂等性（幂等靠各迁移自己的标志）。 */
        val name: String,
        /** 迁移体。**必须幂等**：重复执行不能产生额外副作用。 */
        val run: (Context) -> Unit,
    )

    /**
     * 迁移清单，按顺序执行。
     *
     * 顺序有意义的：后一条若依赖前一条改过的键，就必须排在后面。目前四条互不依赖，
     * 但保留顺序执行（而不是并发）—— 启动路径上并发省不了多少时间，却会让
     * "失败时到底哪条先跑的"变得难以复现。
     */
    private val MIGRATIONS: List<Migration> = listOf(
        Migration("priv_policy_v2") { ctx -> PrivPolicy.migrateOnce(ctx) },
        Migration("permission_channel") { ctx -> PermissionManager.migrateOnStartup(ctx) },
    )

    /**
     * 启动时调用。**静默、不阻塞、不弹 UI**。
     *
     * 单条失败绝不影响启动：这是应用冷启动路径，任何一条迁移抛异常都会让用户看到
     * "应用打不开"，而迁移失败最多是某项设置没转过来 —— 后者可恢复，前者不可接受。
     * 所以每条都 [runCatching] 包住，失败只记日志。
     *
     * 调用点见 [me.bmax.apatch.APatchApp.onCreate]。
     */
    fun runAll(context: Context) {
        for (m in MIGRATIONS) {
            try {
                m.run(context)
            } catch (t: Throwable) {
                // 记 warn 而不是 error：迁移失败是"某设置没转过来"，不是崩溃。
                // 但必须留痕 —— 静默吞掉会让用户报"设置莫名重置"时无从查起。
                Log.w(TAG, "启动迁移 " + m.name + " 失败（已跳过，不影响启动）", t)
            }
        }
        recordVersion(context)
    }

    /**
     * 记下本次运行的版本号。
     *
     * **必须在所有迁移跑完之后写**：如果写在前面，迁移中途崩溃时版本号已经前进，
     * 下次启动看到"版本没变"就可能跳过本该重试的逻辑。虽然当前每条迁移都有独立的
     * 幂等标志、不依赖这个值，但把顺序做对是零成本的，而反过来（先写后跑）是
     * 典型的"将来加一条依赖版本号的迁移时才会炸"的隐患。
     */
    private fun recordVersion(context: Context) {
        try {
            prefs(context).edit()
                .putString(KEY_LAST_VERSION, BuildConfig.VERSION_NAME)
                .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "记录版本号失败", t)
        }
    }

    /** 上次运行的版本名；首次安装返回 null。仅供诊断/关于页展示。 */
    fun lastVersion(context: Context): String? =
        prefs(context).getString(KEY_LAST_VERSION, null)

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        return if (app is APApplication) app.sharedPreferences
        else app.getSharedPreferences(APApplication.SP_NAME, Context.MODE_PRIVATE)
    }
}
