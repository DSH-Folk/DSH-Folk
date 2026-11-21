package me.bmax.apatch.dsh

/**
 * 原生能力清单：每一项是宿主真实实现的一个能力域。
 *
 * ## 为什么从 DshNativeBridge 抽出来
 *
 * 这个枚举是**纯数据**（id + 语义），但它的使用方横跨三层：
 * - `DshNativeBridge`（:app）按它分发到具体实现；
 * - `WorkflowActionCatalog` / `WorkflowPermissions`（:ext-workflow）按它声明
 *   「这一步工作流要用哪个系统能力」；
 * - 设置页与扩展市场按它渲染开关。
 *
 * 留在 :app 里，:ext-workflow 就必须反向依赖 :app —— 而 :ext-* 的架构约定是
 * 只依赖 :ext-api 与 :core-kernel。抽到 :core-kernel 之后三方都够得着，
 * 且它本身零依赖（没有 Context、没有资源 id），搬过来不牵扯任何东西。
 *
 * ## 加新能力时的顺序
 *
 * 1. 在这里加一项（id 必须与 `DshNativeBridge` 的 dispatch 表一致）；
 * 2. 在 `DshNativeBridge` 的 dispatch 里加路由；
 * 3. 在 `WorkflowActionCatalog` 里登记用到它的动作；
 * 4. 门禁 `check-workflow-actions.js` 会逐条对这三处，漏一处就红。
 */
enum class Cap(val id: String) {
        NOTIFY("notify"),
        /** 全屏通知：Android 14+ 需要用户在特殊权限页明确允许。 */
        FULL_SCREEN_NOTIFY("full_screen_notify"),
        TOAST("toast"),
        VIBRATE("vibrate"),
        /** 手电筒：把摄像头闪光灯当电筒开/关。不需要任何权限（CameraManager.setTorchMode）。 */
        TORCH("torch"),
        CLIPBOARD("clipboard"),
        /** 分享面板与「打开链接/文件」共用一项：两者都是拉起外部 Activity。 */
        INTENT("intent"),
        DEVICE("device"),
        /** 媒体库（相册/视频/音频）的读取。 */
        MEDIA("media"),
        /** 麦克风录音。 */
        MIC("mic"),
        /** 无预览拍照。 */
        CAMERA("camera"),
        /**
         * 语音合成：把文字交给系统 TTS 引擎读出来，或合成成文件。
         *
         * 不需要任何权限 —— 它调的是系统引擎，而放声音不是受管控的操作。也是少见的
         * **不要求前台**的能力：手机在口袋里时让 agent 说一声，正是这项能力的用处。
         */
        TTS("tts"),
        /** 日历：读事件 + 新建事件。 */
        CALENDAR("calendar"),
        /** 通讯录：只读。 */
        CONTACTS("contacts"),
        /** 位置。 */
        LOCATION("location"),
        /** 电话状态（运营商/制式/SIM/通话状态），不含拨号与短信。 */
        PHONE("phone"),
        /** 传感器读数。 */
        SENSORS("sensors"),
        /** 网络状态，只读。 */
        NETWORK("network"),
        /** 音量与响铃模式。 */
        VOLUME("volume"),
        /** 系统设置：亮度、休眠时间、自动旋转。 */
        SETTINGS("settings"),
        /**
         * 「允许安装未知应用」的状态查询。
         *
         * 只读一个开关，不代替用户安装 —— 装 APK 仍然由系统安装器弹窗确认。
         * 给它一项开关是因为「这台机器允不允许侧载」本身就是设备指纹的一部分。
         */
        INSTALL("install"),
        /** 应用使用统计（Usage Access）。 */
        USAGE("usage"),
        /**
         * SAF 桥：对**挂不上**的那一类第三方提供器（典型 Termux），经宿主 App 用
         * ContentResolver / DocumentsContract 代办简单文件操作（list/stat/read/write/mkdir/rm/mv/cp）。
         *
         * 挂得上的（共享存储、App 读得到真实路径的）仍走 bind 挂载；这一项只补「挂不上但有
         * 持久化 SAF 授权」的那棵树（见 [DshSafSources]）。读档只放行 sources/list/stat/read，
         * 写（write/mkdir/rm/mv/cp）要读写档。不需要任何 Android 运行时权限 —— 授权是逐棵树的
         * SAF 持久化授权，不是系统权限。端点挂在 [DshFsBridge] 的 `/saf/…`（同一 socket、同一
         * token、同一回环守卫），写操作在服务端按本能力的读写档 gate（见 [safAccess]）。
         */
        SAF("saf"),
        /** 短信读取权限。 */
        SMS("sms"),
        /**
         * 特权命令：把宿主已经拿到的那条通道（root / Shizuku / 无线 ADB）借给 agent 用。
         *
         * 不需要任何 Android 权限 —— 通道本身就是「已经拿到权限的进程」，见
         * [PrivilegedShell]。档位只区分读（只读诊断命令）与读写（还能改设备状态）；
         * 「要不要每次都问一声」由 [PrivPolicy] 的严格程度决定，与档位是两件事。
         */
        SHELL("shell"),
        /**
         * 无障碍：读当前屏幕的节点树，或对它做一次操作（点按/滑动/输入/系统动作）。
         *
         * 目标**不是本应用**，是用户此刻正在看的界面，所以只读（看屏幕）与读写（动手操作）
         * 的差别比别的能力大得多；需要用户单独打开那个无障碍服务（[Special.A11Y_SERVICE]）。
         */
        A11Y("a11y"),
        /**
         * 虚拟屏：截屏、在屏幕上点击/滑动/按键、以及在虚拟屏上启动目标 App。
         *
         * 和 [A11Y] 是两条不同的路，不是替代关系：A11Y 走无障碍节点树（只认得出有节点、
         * 无障碍服务还开着的界面，而且它读不到画面本身）；这一项走以特权身份运行的
         * app_process 服务端，直接创建/镜像一块显示并注入原始输入事件 —— 因此不受
         * 「界面有没有节点」限制，代价是需要一条提权通道（root / Shizuku / 无线 ADB）。
         *
         * 读档位只放行截屏与查询，动手（点击/滑动/按键/启动 App）要读写档位。
         */
        DISPLAY("display"),
}
