package me.bmax.apatch.dsh.ext.seam

/**
 * 宿主侧 Seam 实现的提供者。
 *
 * ## 为什么需要这层间接
 *
 * [SeamRegistry] 必须在 `:ext-api`（扩展要能调它），但 `Host*Seam` 的实现在 `:app`
 * （它们要摸 Binder、rootfs、Activity）。`ext-api` 不能依赖 `:app`，所以注册表
 * 不能自己 new 那些实现 —— 只能由宿主在启动时把一份实现塞进来。
 *
 * 这就是依赖倒置：注册表定义「要什么」，宿主决定「给什么」。
 *
 * ## 各字段可空的意义
 *
 * `runtime` / `event` / `capability` 无状态，宿主永远能提供，所以非空。
 * 其余几个要 Context：宿主还没拿到 Context 时给 null，注册表会退化成
 * 「能力不可用」实现 —— 让扩展降级，而不是让 App 崩。
 */
interface SeamProvider {
    val runtime: RuntimeSeam
    val event: EventSeam
    val capability: CapabilitySeam
    val shell: ShellSeam?
    val device: DeviceSeam?
    val ui: UiSeam?
    val backup: BackupSeam?
    val themeBackup: ThemeBackupSeam?
    val nativeBridge: NativeBridgeSeam?
}
