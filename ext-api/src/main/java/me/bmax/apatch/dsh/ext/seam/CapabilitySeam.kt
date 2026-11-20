package me.bmax.apatch.dsh.ext.seam
/**
 * 能力自省：扩展问"宿主现在有什么"。
 *
 * 为什么需要：扩展是独立编译的，装了新版宿主之后它可能用上更多能力。
 * 硬编码假设会导致"在老宿主上崩溃"或"在新宿主上白不用"。
 */
interface CapabilitySeam {

    /** 宿主声明的全部能力名（稳定标识符，如 `host:runtime`）。 */
    fun declared(): List<String>

    /** 某个能力是否可用（宿主版本支持 + 用户已授权）。 */
    fun available(name: String): Boolean

    /** 宿主版本名，用于按版本分支。 */
    fun hostVersion(): String
}
