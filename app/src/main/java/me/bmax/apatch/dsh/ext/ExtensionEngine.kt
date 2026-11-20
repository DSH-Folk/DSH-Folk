package me.bmax.apatch.dsh.ext

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 扩展系统的编排器：唯一负责「加载哪些、加载成没成、怎么全部关掉」。
 *
 * 加载策略三条：
 * 1. **安全模式短路**：熔断后一个都不加载（[ExtensionSafetyGuard]）；
 * 2. **失败隔离**：单个扩展加载/onLoad 失败只记日志并跳过，不影响其它扩展与宿主启动；
 * 3. **全量卸载**：停用 / 移除 / 熔断时把所有注册（SPI/Hook/UI/启动管线）一并撤掉，
 *    否则会留下指向已卸载扩展的悬空回调 —— 那是崩溃的常见来源。
 */
object ExtensionEngine {

    private val loaded = ConcurrentHashMap<String, DshExtension>()

    /** 本次进程是否已经 bootstrap 过（宿主 onCreate 只该调一次）。 */
    @Volatile
    private var bootstrapped = false

    /** 已加载的扩展 id（界面/诊断用）。 */
    val loadedIds: Set<String> get() = loaded.keys.toSet()

    /**
     * 启动时加载所有「已启用且没被熔断」的扩展。
     *
     * 由 APatchApp.onCreate 在 [ExtensionSafetyGuard.checkAndEnforce] 之后调用。
     * 全程不抛异常：扩展系统坏掉也不能让 App 起不来。
     */
    fun bootstrap(ctx: Context) {
        if (bootstrapped) return
        bootstrapped = true
        runCatching {
            // 诊断日志：扩展「装了但没生效」时，这几行是唯一能区分
            // 「没启用 / 熔断拦了 / 加载失败 / 加载了但界面没接」的依据。
            val all = ExtensionRegistry.listInstalled(ctx)
            ExtLog.info(
                "bootstrap：已安装 " + all.size + " 个（" +
                    all.joinToString { it.id + (if (it.enabled) "=启用" else "=停用") } + "）",
            )
            if (ExtensionSafetyGuard.isSafeMode(ctx)) {
                // 只进 logcat：dsh.log 属于 dsh 运行时自己的启动日志，用户会去读它
                ExtLog.log(
                    "安全模式，跳过全部第三方扩展；原因=" +
                        ExtensionSafetyGuard.reason.ifEmpty {
                            ExtensionSafetyGuard.findKillSwitch(ctx)?.absolutePath ?: "(未知)"
                        },
                )
                return
            }
            // 装宿主 seam 默认实现 —— 必须排在**任何扩展 onLoad 之前**。
            //
            // 扩展在 onLoad 里就会通过 host.seam 取能力。晚装的话它拿到的是
            // HostSeams 的 fallback 分支（每次 new 一个），虽然能用，
            // 但扩展注册的替换实现不会生效（总线里还没有宿主那份，resolve 语义不一致）。
            //
            // 用 dirOf(ctx, "host") 当目录：宿主实现不属于任何扩展，
            // 给它一个独立目录，避免和真实扩展 id 撞车。
            runCatching {
                me.bmax.apatch.dsh.ext.seam.SeamRegistry.setFallbackContext(ctx)
                me.bmax.apatch.dsh.ext.seam.SeamRegistry.install(ctx)
                // 「已引导过」的落盘实现：prefs 文件名是宿主细节，
                // 不该写进 :ext-api（那会把它固化成公共契约）。
                ExtensionBootPipeline.setBootstrappedRecorder {
                    ctx.getSharedPreferences("dsh_ext_state", Context.MODE_PRIVATE)
                        .edit().putBoolean("bootstrapped", true).apply()
                }
            }.onFailure {
                ExtLog.log("装宿主 seam 失败（扩展仍可走反射）：" + (it.message ?: it.javaClass.simpleName))
            }

            val records = all.filter { it.enabled && it.installed }
            if (records.isEmpty()) {
                ExtLog.info("没有启用的扩展")
                ExtensionBootPipeline.markBootstrapped()
                return
            }
            for (rec in records) {
                val ext = ExtensionLoader.boot(ctx, rec) ?: continue
                loaded[rec.id] = ext
            }
            ExtensionBootPipeline.markBootstrapped()
            ExtLog.info("已加载 " + loaded.size + " 个扩展")
            // 运行时如果已经装好，立刻通知一次：扩展靠这个时机往容器里装命令。
            // 没装好就等 install 完成时的那次通知（见 notifyContainerReady）。
            notifyContainerReady(ctx)
            // 启动管线第一段就在此时跑（APP_CREATE 阶段）
            ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.APP_CREATE)
        }.onFailure {
            ExtLog.log("bootstrap 异常（已忽略）：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    /**
     * 通知所有已加载的扩展「容器就绪了」。
     *
     * 两个调用点：启动时运行时已就绪、以及运行时安装/重装完成之后。
     * 幂等由扩展自己保证（见 [DshExtension.onContainerReady] 的约定）——
     * 宿主不做去重，因为"重装运行时"本来就该让扩展重新装一遍命令。
     */
    fun notifyContainerReady(ctx: Context) {
        if (!me.bmax.apatch.dsh.DshEnv.isRuntimeInstalled(ctx)) return
        for ((id, ext) in loaded) {
            runCatching {
                ext.onContainerReady(ExtensionHostImpl(id, ctx.applicationContext, capsOf(ctx, id)))
            }.onFailure {
                ExtLog.log("[" + id + "] onContainerReady 抛异常（已忽略）：" + it.message)
            }
        }
    }

    /** 取某个扩展声明的能力（重装 host 时要用）。 */
    private fun capsOf(ctx: Context, id: String): List<String> =
        runCatching {
            ExtensionLayout.manifestOf(ctx, id).takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
                ?.let { ExtensionManifest.parse(it)?.declaredCaps }
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** 卸载单个扩展（停用/移除时先调它，再改注册表）。 */
    fun unload(ctx: Context, id: String) {
        runCatching {
            loaded.remove(id)?.let { ext ->
                runCatching { ext.onUnload() }.onFailure {
                    ExtLog.log("[" + id + "] onUnload 抛异常（已忽略）")
                }
            }
            ExtensionServiceRegistry.unregisterAll(id)
            ExtensionHookRegistry.unregisterAll(id)
            ExtensionUiRegistry.unregisterAll(id)
            ExtensionBootPipeline.unregisterAll(id)
            // 容器命令按落盘的归属标记收回：进程可能重启过，内存里没有记录，
            // 只有标记文件能告诉宿主"这些命令是它装的"。
            AppExtensionContainer.removeAllOf(ctx, id)
            ExtLog.info("已卸载：" + id)
        }
    }

    /** 卸载全部（安全模式 / 用户一键禁用时用）。 */
    fun unloadAll(ctx: Context) {
        for (id in loaded.keys.toList()) unload(ctx, id)
        ExtensionServiceRegistry.clearAll()
        ExtensionHookRegistry.clearAll()
        ExtensionUiRegistry.clearAll()
        ExtensionBootPipeline.clearAll()
    }

    /**
     * 用户点了「禁用全部扩展并重启」/ 崩溃页自救按钮。
     *
     * 先落标志文件（保证下次冷启动仍然安全），再卸载内存里的全部扩展。
     */
    fun disableAllAndLatch(ctx: Context, why: String) {
        runCatching {
            ExtensionSafetyGuard.latch(ctx, why, writeMarker = true)
            unloadAll(ctx)
        }
    }

    /** 重新启用：清掉标志文件，等下次冷启动重新加载（运行期不做热加载 —— 会引入状态不一致）。 */
    fun reEnable(ctx: Context) {
        ExtensionSafetyGuard.clearSafeMode(ctx)
        ExtLog.info("已允许扩展，重启 App 后生效")
    }
}
