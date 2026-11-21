package me.bmax.apatch.dsh

/**
 * 一项能力的授权档位。
 *
 * ## 为什么从 DshNativeBridge 抽出来
 *
 * 与 [Cap] 同理：它是纯数据（id + 语义），但使用方横跨三层 ——
 * 桥按它 gate、PrivPolicy 按它判「要不要弹窗」、设置页按它渲染开关。
 * 留在 :app 里，:core-kernel 的 PrivPolicy 与 :ext-* 的能力实现都够不着。
 *
 * ## 档位的含义
 *
 * - [OFF]：能力关闭，调用一律拒绝；
 * - [READ]：只读（读剪贴板、列通知、看相册）；
 * - [WRITE]：只写（发通知、写剪贴板）；
 * - [READ_WRITE]：读写；
 * - [CONTROL]：动手类（点击、滑动、启动 App）—— 风险最高的一档。
 */
enum class Access(val id: String) {
    OFF("off"),
    WRITE("write"),
    READ("read"),
    READ_WRITE("read_write"),
    CONTROL("control"),
}
