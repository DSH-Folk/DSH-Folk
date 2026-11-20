/**
 * 文件系统访问：容器路径 ↔ 宿主路径、扩展私有目录。
 *
 * 路径穿越（`..`）必须在实现里挡掉 —— 扩展拿到的是宿主的真实文件系统，
 * 一个 `../../databases/` 就能读到别的东西。
 */
interface FsSeam {

    /** 扩展自己的私有目录（已创建，随卸载删除）。 */
    val extensionDir: File

    /** 容器根目录（未安装时为 null）。 */
    val rootfsDir: File?

    /**
     * 容器内路径 → 宿主真实路径。
     *
     * @param containerPath 形如 `/root/.dsh/config.json`
     * @return 宿主路径；路径非法或越界时返回 null（**不抛**：调用方常在 UI 线程上）
     */
    fun containerToHost(containerPath: String): File?

    /** 宿主路径 → 容器内路径；不在容器里时返回 null。 */
    fun hostToContainer(hostPath: File): String?

    /** 读容器内文本文件；不存在或越界返回 null。 */
    fun readContainerText(containerPath: String): String?

    /** 写容器内文本文件。返回是否成功（越界、无权限、磁盘满都返回 false）。 */
    fun writeContainerText(containerPath: String, content: String): Boolean

    /** 列出容器内目录；不存在或越界返回空列表。 */
    fun listContainer(containerPath: String): List<String>
}
