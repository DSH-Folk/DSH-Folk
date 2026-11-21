package me.bmax.apatch.dsh

/**
 * 内核需要的、只能由宿主提供的环境常量。
 *
 * ## 为什么要这一层
 *
 * `:core-kernel` 不能引用 `:app` 的 `BuildConfig`（那是 :app 模块生成的），
 * 也不能引用 `APApplication`（Application 子类，带 UI/崩溃处理等一堆依赖）。
 * 但内核里确实需要这两类值：版本号用于更新检查，APATCH_FOLDER 用于拼 PATH。
 *
 * 所以由宿主在启动时注入一次，内核只读。**不提供默认值** —— 忘了注入就是空串，
 * 会让版本比较和 PATH 拼装静默出错，不如让它显式失败。
 *
 * 注入点在 `ExtensionEngine` 的 bootstrap（那里已经有一批宿主注入）。
 */
object KernelEnv {
    /** 宿主版本名（BuildConfig.VERSION_NAME），更新检查要用它比对远端 tag。 */
    @Volatile
    var appVersionName: String = ""
        private set

    /** 是否 debug 构建（BuildConfig.DEBUG），决定 libsu 是否开 verbose 日志。 */
    @Volatile
    var isDebugBuild: Boolean = false
        private set

    /** 宿主版本号（BuildConfig.VERSION_CODE），启动迁移记录用。 */
    @Volatile
    var appVersionCode: Int = 0
        private set

    /**
     * 宿主 App 的 SharedPreferences（APApplication.sharedPreferences）。
     *
     * 由宿主注入而不是内核自己 getSharedPreferences：`APApplication` 是 Application 子类，
     * 内核不能引用它；而这两者必须用**同一份** prefs 文件，否则迁移记录两边看不见。
     */
    @Volatile
    var hostPrefs: android.content.SharedPreferences? = null
        private set

    /**
     * 宿主 prefs 的文件名（APApplication.SP_NAME）。
     *
     * 兜底路径要用它自己开一份 —— 名字必须与宿主完全一致，否则读的是另一份空文件，
     * 表现是"迁移记录写了但下次启动看不见"，每版都重跑一遍迁移。
     */
    @Volatile
    var hostPrefsName: String = ""
        private set

    /** APatch 数据目录（APApplication.APATCH_FOLDER），拼 PATH / BUSYBOX 用。 */
    @Volatile
    var apatchFolder: String = ""
        private set

    /** 是否已注入。内核里用到这些值的地方应当先检查。 */
    val installed: Boolean get() = appVersionName.isNotEmpty() && apatchFolder.isNotEmpty()

    fun install(
        appVersionName: String,
        isDebugBuild: Boolean,
        apatchFolder: String,
        appVersionCode: Int = 0,
        hostPrefs: android.content.SharedPreferences? = null,
        hostPrefsName: String = "",
    ) {
        this.hostPrefsName = hostPrefsName
        this.appVersionName = appVersionName
        this.isDebugBuild = isDebugBuild
        this.apatchFolder = apatchFolder
        this.appVersionCode = appVersionCode
        this.hostPrefs = hostPrefs
    }
}
