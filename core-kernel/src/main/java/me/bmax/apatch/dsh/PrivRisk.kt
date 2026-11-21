package me.bmax.apatch.dsh


/**
 * 一条特权命令的风险等级。
 *
 * 原本声明在 `PrivPolicy.kt` 里。抽出来单独成文件，是因为 `PrivilegedShell`
 * （已下沉 :core-kernel）要按风险等级决定"要不要拦"，而 `PrivPolicy` 依赖
 * `DshNativeBridge.Cap` 枚举 —— 那个枚举在 :app 侧，PrivPolicy 就下沉不了。
 *
 * 枚举本身是纯数据，没有任何依赖，放底层最合适。
 */
@InternalKernelApi
enum class PrivRisk {
    /** 只读：无论怎么给参数都不改设备状态。 */
    READONLY,

    /** 会改状态，但影响范围清楚、容易恢复。 */
    WRITE,

    /** 改完不容易恢复，或者影响整机（卸载、重启、清数据）。 */
    DANGEROUS,
}
