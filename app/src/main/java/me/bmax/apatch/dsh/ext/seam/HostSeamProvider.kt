package me.bmax.apatch.dsh.ext.seam

import android.content.Context

/**
 * 宿主对 [SeamProvider] 的实现：把六个能力各自绑到 [Host*Seam]。
 *
 * 为什么需要 Context：shell/device/ui/backup/nativeBridge 全都要摸系统服务，
 * 没有 Context 一个都建不起来。runtime/event/capability 无状态，先建好。
 */
internal class HostSeamProvider(ctx: Context) : SeamProvider {
    override val runtime: RuntimeSeam = HostRuntimeSeam()
    override val event: EventSeam = HostEventSeam()
    override val capability: CapabilitySeam = HostCapabilitySeam()
    override val shell: ShellSeam? = HostShellSeam(ctx)
    override val device: DeviceSeam? = HostDeviceSeam(ctx)
    override val ui: UiSeam? = HostUiSeam(ctx)
    override val backup: BackupSeam? = HostBackupSeam(ctx)
    override val nativeBridge: NativeBridgeSeam? = HostNativeBridgeSeam(ctx)
    override val themeBackup: ThemeBackupSeam = HostThemeBackupSeam
}
