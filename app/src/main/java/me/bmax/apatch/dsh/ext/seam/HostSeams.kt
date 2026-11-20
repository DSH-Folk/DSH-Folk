package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File
import me.bmax.apatch.dsh.ext.ExtensionSeams

/**
 * [ExtensionSeams] 的宿主实现：把 8 个 seam 聚合成一个对象交给扩展。
 *
 * ## 为什么 fs 在这里单独 new 而不从总线取
 *
 * [FsSeam] 带 `extensionDir`，每个扩展一份。注册进全局总线的话，
 * 后加载的扩展会把前面的覆盖掉，前面那个就读到别人的私有目录了 —— 那是越权。
 * 其余 7 个无状态，从总线取（这样扩展可以替换它们）。
 */
internal class HostSeams(
    /**
     * 必须是 `private val`，不能只是构造参数。
     *
     * 下面那几个 `get()` 访问器在**方法体**里执行，而普通构造参数在方法体里不可见
     * （只在属性初始化表达式中可见）。写成裸参数的话第 31 行能编译、第 23-26 行全红，
     * 报 "Unresolved reference: ctx" —— 看起来像拼写错，其实是作用域问题。
     */
    private val ctx: Context,
    extensionDir: File,
) : ExtensionSeams {

    override val runtime: RuntimeSeam get() = SeamRegistry.runtime()

    override val shell: ShellSeam get() = SeamRegistry.shell()
    override val device: DeviceSeam get() = SeamRegistry.device()
    override val ui: UiSeam get() = SeamRegistry.ui()
    override val backup: BackupSeam get() = SeamRegistry.backup()
    override val event: EventSeam get() = SeamRegistry.event()
    override val capability: CapabilitySeam get() = SeamRegistry.capability()

    /** 每个扩展自己的文件系统视图。 */
    override val fs: FsSeam = HostFsSeam(ctx, extensionDir)
}
