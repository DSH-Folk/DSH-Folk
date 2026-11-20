/**
 * 提权 shell（su）与无线 ADB。
 *
 * 与 [RuntimeSeam] 的分工：那个是**容器内**执行（proot/chroot），这个是**设备上**执行。
 * 两者权限模型完全不同，混在一起会让扩展作者写错。
 */
interface ShellSeam {

    /** 设备上是否有可用的 su（Magisk / KernelSU / APatch）。 */
    fun hasRoot(): Boolean

    /** 以 root 执行一条命令并返回输出。无 root 时抛异常。 */
    fun root(command: String, timeoutMs: Long = 15_000L): String

    /** 是否有 Shizuku 授权。 */
    fun hasShizuku(): Boolean

    /** 通过 Shizuku 以 shell 身份执行。 */
    fun shizuku(command: String, timeoutMs: Long = 15_000L): String

    /** 无线 ADB 是否已连接。 */
    fun adbConnected(): Boolean

    /** 通过 ADB 执行一条命令。 */
    fun adb(command: String, timeoutMs: Long = 15_000L): String
}
