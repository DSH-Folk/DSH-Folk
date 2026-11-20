/**
 * 配置备份与版本更新。
 */
interface BackupSeam {

    /** 导出 DSH 配置为加密归档。返回归档文件；失败返回 null。 */
    fun exportConfig(password: String): File?

    /** 从加密归档恢复。 */
    fun importConfig(archive: File, password: String): Boolean

    /** 触发一次更新检查。 */
    fun checkUpdate()

    /** 当前版本名。 */
    fun currentVersion(): String
}
