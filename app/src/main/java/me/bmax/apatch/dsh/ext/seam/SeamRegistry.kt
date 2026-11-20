package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File
import me.bmax.apatch.dsh.ext.ExtensionServiceRegistry

/**
 * 把 8 个宿主 seam 实现装进 [ExtensionServiceRegistry]。
 *
 * ## 注册方向
 *
 * 这里注册的是**宿主默认实现**。扩展可以注册自己的实现来**整块替换**某个 seam
 * （`ExtensionServiceRegistry.register(id, RuntimeSeam::class.java, myImpl)`），
 * 之后任何走 `resolve()` 的地方都会拿到扩展的那份。
 *
 * ## 为什么宿主自己也要注册（而不是直接 new）
 *
 * 如果宿主内部直接 `HostRuntimeSeam()`，扩展就没法替换它 —— 注册进总线之后，
 * "整块替换"这个能力对**宿主自己**也成立，而不只是对别的扩展。
 *
 * ## 幂等
 *
 * `install()` 可以重复调用（App 每次启动都调一次）。后注册覆盖先注册，
 * 所以重复装同一个实例没有副作用。
 */
internal object SeamRegistry {

    private const val HOST_ID = "host"

    @Volatile
    private var installed = false

    /**
     * 安装宿主默认实现。可重复调用。
     *
     * @param extensionDir 给 [FsSeam] 用的扩展私有目录（由 ExtensionHostImpl 传入，
     *   每个扩展一个，不能共用）
     */
    @Synchronized
    fun install(ctx: Context, extensionDir: File) {
        // 注意：FsSeam 是**每个扩展一份**（extensionDir 不同），所以它不在这里注册 ——
        // 见 [installForExtension]。其余 7 个是无状态的，装一次就够。
        if (installed) return

        ExtensionServiceRegistry.register(HOST_ID, RuntimeSeam::class.java, HostRuntimeSeam())
        ExtensionServiceRegistry.register(HOST_ID, ShellSeam::class.java, HostShellSeam(ctx))
        ExtensionServiceRegistry.register(HOST_ID, DeviceSeam::class.java, HostDeviceSeam(ctx))
        ExtensionServiceRegistry.register(HOST_ID, UiSeam::class.java, HostUiSeam(ctx))
        ExtensionServiceRegistry.register(HOST_ID, BackupSeam::class.java, HostBackupSeam(ctx))
        ExtensionServiceRegistry.register(HOST_ID, EventSeam::class.java, HostEventSeam())
        ExtensionServiceRegistry.register(HOST_ID, CapabilitySeam::class.java, HostCapabilitySeam())

        installed = true
    }

    /** 取宿主运行时实现（宿主内部用；扩展通过 `host.platform` 走）。 */
    fun runtime(): RuntimeSeam = ExtensionServiceRegistry.resolve(RuntimeSeam::class.java) {
        error("SeamRegistry.install() 还没调用")
    }
}
