package me.bmax.apatch.dsh

import me.bmax.apatch.R
import me.bmax.apatch.res.R as CoreR

/**
 * 工作流的**动作目录**：一张「有哪些动作、各自要什么参数、用哪个能力」的表。
 *
 * ## 它解决什么问题
 *
 * 参数编辑器（[me.bmax.apatch.ui.component.WorkflowNodeFields]）解决的是"节点内部字段"，
 * 这一张解决的是"一次执行具体调哪个端点、传什么参数"。两者分开是因为它们的归属方不同：
 * 前者属于 DSL（validator 认的键），后者属于**桥**（端点与 Cap）。
 *
 * ## 为什么每个条目都必须有真实端点
 *
 * 卡片上写着「点击」，实际却拼一条 shell 去调别的东西，是最坏的形态：用户在界面上看到的是
 * A、设备上执行的是 B，而批准弹窗按 A 渲染。所以每个 [Action] 都带着**桥的真实 method/path**，
 * 门禁 `check-workflow-actions.js` 会把它和 [DshNativeBridge] 的 dispatch 表逐条对。
 *
 * ## 与 NativeCapCatalog 的关系
 *
 * [NativeCapCatalog] 是给「点一下抄个命令」用的文案目录（一张 Cap 一条命令）；
 * 这里是给**编辑器**用的结构化目录（一个 Cap 可以有多条动作，每条有各自的参数）。
 * 两者的 Cap 集合必须一致，但粒度不同：例如 A11Y 在文案目录里是一条，
 * 这里拆成读树 / 点击 / 滑动 / 输入 / 全局操作 / 截图六条。
 */
internal object WorkflowActionCatalog {

    /** 参数的种类。决定编辑器渲染什么控件、怎么校验。 */
    enum class ParamKind { TEXT, NUMBER, CHOICE, BOOL }

    /**
     * 一条动作的参数。
     *
     * [required] 为 true 时编辑器会标出来（空值仍能保存 —— 用户可能正写到一半；
     * 真正拦运行的是引擎与桥）。
     */
    data class Param(
        val key: String,
        val labelRes: Int,
        val kind: ParamKind,
        val choices: List<String> = emptyList(),
        val required: Boolean = false,
        val default: String = "",
    )

    /**
     * 一条可执行动作。
     *
     * [groupTitleRes] 决定它落在面板的哪一类（对齐点击助手的 15 类，顺序见
     * WorkflowEditorSurface 的 EDITOR_CATEGORY_ORDER）。
     *
     * ## [titleRes] 是**动作名**，[descRes] 是**它要的能力**
     *
     * 这两个曾经是反的：标题取的是能力名（"无障碍"），副标题才是动作名（"截屏"）。
     * 后果在画布上很具体 —— 卡片的标题就是 [titleRes]（见 defaultNodeName），于是
     * 无障碍那 7 个动作在画布里全叫「无障碍」，只有卡片下面那行小字能区分。
     * 点击助手里每张卡的标题就是动作名，所以这里也必须是动作名。
     *
     * 现在：`titleRes = R.string.<token>_wf_act_<动作>`（31 条互不重复，门禁钉着），
     *      `descRes  = R.string.<token>_cap_<能力>_title`（说明这一步要用到哪个系统能力）。
     */
    data class Action(
        val id: String,
        val groupTitleRes: Int,
        val titleRes: Int,
        val descRes: Int,
        val cap: DshNativeBridge.Cap,
        val method: String,
        val path: String,
        val params: List<Param> = emptyList(),
    ) {
        /** 写进节点 config 的键前缀：动作 id 与参数键组合成唯一键，避免跨动作撞名。 */
        fun configKey(param: Param): String = "p_" + param.key
    }

    /**
     * 参数构造。
     *
     * [choices] 收 `List` 而不是 vararg，是因为调用点都在**具名参数**位置写它
     * （`p("action", R.string.x, ParamKind.CHOICE, choices = listOf(...))`）——
     * Kotlin 不允许在具名位置传 vararg，写成 `vararg` 会直接编译不过。
     */
    private fun p(
        key: String,
        labelRes: Int,
        kind: ParamKind = ParamKind.TEXT,
        required: Boolean = false,
        default: String = "",
        choices: List<String> = emptyList(),
    ) = Param(key, labelRes, kind, choices, required, default)

    /**
     * 全部动作。
     *
     * **同一分类内的顺序即卡片顺序**（分类之间的先后由面板的 EDITOR_CATEGORY_ORDER 决定）。
     * 每个 path 都必须在 [DshNativeBridge] 的 dispatch 里真实存在 ——
     * 这不是注释约定，是门禁在核对。
     */
    // 这是一张**平表**：分类由每条的 groupTitleRes 决定。新增动作时把它插到同类动作附近，
    // 面板里它就会跟着那一类出现（跨类顺序见 WorkflowEditorSurface 的 EDITOR_CATEGORY_ORDER）。
    val ACTIONS: List<Action> = listOf(
        Action(
            "a11y_tree", CoreR.string.dsh_wf_cat_widget, CoreR.string.dsh_wf_act_a11y_tree, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "GET", "/native/a11y/tree",
        ),
        Action(
            "a11y_click", CoreR.string.dsh_wf_cat_widget, CoreR.string.dsh_wf_act_a11y_click, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/click",
            listOf(p("text", CoreR.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "a11y_tap", CoreR.string.dsh_wf_cat_position, CoreR.string.dsh_wf_act_a11y_tap, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/tap",
            listOf(
                p("x", CoreR.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y", CoreR.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
            ),
        ),
        Action(
            "a11y_swipe", CoreR.string.dsh_wf_cat_position, CoreR.string.dsh_wf_act_a11y_swipe, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/swipe",
            listOf(
                p("x1", CoreR.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y1", CoreR.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
                p("x2", CoreR.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y2", CoreR.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
                p("durationMs", CoreR.string.dsh_wf_param_duration, ParamKind.NUMBER, default = "300"),
            ),
        ),
        Action(
            "a11y_text", CoreR.string.dsh_wf_cat_widget, CoreR.string.dsh_wf_act_a11y_text, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/text",
            listOf(p("text", CoreR.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "a11y_global", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_a11y_global, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "POST", "/native/a11y/global",
            listOf(
                p(
                    "action", CoreR.string.dsh_wf_param_action, ParamKind.CHOICE, required = true,
                    choices = listOf("back", "home", "recents", "notifications"),
                ),
            ),
        ),
        Action(
            "a11y_shot", CoreR.string.dsh_wf_cat_image, CoreR.string.dsh_wf_act_a11y_shot, CoreR.string.dsh_cap_a11y_title,
            DshNativeBridge.Cap.A11Y, "GET", "/native/a11y/screenshot",
        ),
        Action(
            "display_shot", CoreR.string.dsh_wf_cat_image, CoreR.string.dsh_wf_act_display_shot, CoreR.string.dsh_cap_display_title,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/screenshot",
        ),
        Action(
            "display_tap", CoreR.string.dsh_wf_cat_position, CoreR.string.dsh_wf_act_display_tap, CoreR.string.dsh_cap_display_title,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/tap",
            listOf(
                p("x", CoreR.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y", CoreR.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
            ),
        ),
        Action(
            "display_swipe", CoreR.string.dsh_wf_cat_position, CoreR.string.dsh_wf_act_display_swipe, CoreR.string.dsh_cap_display_title,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/swipe",
            listOf(
                p("x1", CoreR.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y1", CoreR.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
                p("x2", CoreR.string.dsh_wf_param_x, ParamKind.NUMBER, required = true),
                p("y2", CoreR.string.dsh_wf_param_y, ParamKind.NUMBER, required = true),
            ),
        ),
        Action(
            "display_key", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_display_key, CoreR.string.dsh_cap_display_title,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/key",
            listOf(p("key", CoreR.string.dsh_wf_param_key, required = true)),
        ),
        Action(
            "display_stop", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_display_stop, CoreR.string.dsh_cap_display_title,
            DshNativeBridge.Cap.DISPLAY, "POST", "/native/display/stop",
        ),
        Action(
            "toast", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_toast, CoreR.string.dsh_cap_toast_title,
            DshNativeBridge.Cap.TOAST, "POST", "/native/toast",
            listOf(p("text", CoreR.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "vibrate", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_vibrate, CoreR.string.dsh_cap_vibrate_title,
            DshNativeBridge.Cap.VIBRATE, "POST", "/native/vibrate",
            listOf(p("ms", CoreR.string.dsh_wf_param_ms, ParamKind.NUMBER, default = "300")),
        ),
        Action(
            "notify_full", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_notify_full, CoreR.string.dsh_cap_fullscreens_title,
            DshNativeBridge.Cap.FULL_SCREEN_NOTIFY, "POST", "/native/notify/full-screen",
            listOf(
                p("title", CoreR.string.dsh_wf_param_notify_title, required = true),
                p("body", CoreR.string.dsh_wf_param_notify_body),
            ),
        ),
        Action(
            "clip_set", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_clip_set, CoreR.string.dsh_cap_clip_title,
            DshNativeBridge.Cap.CLIPBOARD, "POST", "/native/clipboard",
            listOf(p("text", CoreR.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "torch", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_torch, CoreR.string.dsh_cap_torch_title,
            DshNativeBridge.Cap.TORCH, "POST", "/native/torch",
            listOf(p("on", CoreR.string.dsh_wf_param_on, ParamKind.CHOICE, choices = listOf("on", "off"))),
        ),
        Action(
            "volume", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_volume, CoreR.string.dsh_cap_volume_title,
            DshNativeBridge.Cap.VOLUME, "POST", "/native/volume",
            listOf(
                p("level", CoreR.string.dsh_wf_param_level, ParamKind.NUMBER, required = true),
                p(
                    "stream", CoreR.string.dsh_wf_param_stream, ParamKind.CHOICE,
                    default = "music", choices = listOf("music", "ring", "alarm", "notification"),
                ),
            ),
        ),
        Action(
            "ringer", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_ringer, CoreR.string.dsh_cap_volume_title,
            DshNativeBridge.Cap.VOLUME, "POST", "/native/ringer",
            listOf(
                p(
                    "mode", CoreR.string.dsh_wf_param_mode, ParamKind.CHOICE, required = true,
                    choices = listOf("normal", "silent", "vibrate"),
                ),
            ),
        ),
        Action(
            "brightness", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_brightness, CoreR.string.dsh_cap_settings_title,
            DshNativeBridge.Cap.SETTINGS, "POST", "/native/settings/brightness",
            listOf(p("value", CoreR.string.dsh_wf_param_value, ParamKind.NUMBER, required = true)),
        ),
        Action(
            "rotation", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_rotation, CoreR.string.dsh_cap_settings_title,
            DshNativeBridge.Cap.SETTINGS, "POST", "/native/settings/rotation",
            listOf(p("locked", CoreR.string.dsh_wf_param_locked, ParamKind.CHOICE, choices = listOf("0", "1"))),
        ),
        Action(
            "camera", CoreR.string.dsh_wf_cat_image, CoreR.string.dsh_wf_act_camera, CoreR.string.dsh_cap_camera_title,
            DshNativeBridge.Cap.CAMERA, "POST", "/native/camera/photo",
            listOf(p("facing", CoreR.string.dsh_wf_param_facing, ParamKind.CHOICE, default = "back", choices = listOf("back", "front"))),
        ),
        Action(
            "tts", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_tts, CoreR.string.dsh_cap_tts_title,
            DshNativeBridge.Cap.TTS, "POST", "/native/tts/speak",
            listOf(p("text", CoreR.string.dsh_wf_param_text, required = true)),
        ),
        Action(
            "media_list", CoreR.string.dsh_wf_cat_list, CoreR.string.dsh_wf_act_media_list, CoreR.string.dsh_cap_media_title,
            DshNativeBridge.Cap.MEDIA, "GET", "/native/media/list",
            listOf(
                p("type", CoreR.string.dsh_wf_param_type, ParamKind.CHOICE, default = "image", choices = listOf("image", "video", "audio")),
                p("limit", CoreR.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "10"),
            ),
        ),
        Action(
            "sensors_read", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_sensors_read, CoreR.string.dsh_cap_sensors_title,
            DshNativeBridge.Cap.SENSORS, "GET", "/native/sensors/read",
            listOf(p("id", CoreR.string.dsh_wf_param_id, required = true)),
        ),
        Action(
            "network", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_network, CoreR.string.dsh_cap_network_title,
            DshNativeBridge.Cap.NETWORK, "GET", "/native/network",
        ),
        Action(
            "usage", CoreR.string.dsh_wf_cat_system, CoreR.string.dsh_wf_act_usage, CoreR.string.dsh_cap_usage_title,
            DshNativeBridge.Cap.USAGE, "GET", "/native/usage/list",
            listOf(
                p("days", CoreR.string.dsh_wf_param_days, ParamKind.NUMBER, default = "1"),
                p("limit", CoreR.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "10"),
            ),
        ),
        Action(
            "calendar_list", CoreR.string.dsh_wf_cat_list, CoreR.string.dsh_wf_act_calendar_list, CoreR.string.dsh_cap_calendar_title,
            DshNativeBridge.Cap.CALENDAR, "GET", "/native/calendar/list",
            listOf(p("days", CoreR.string.dsh_wf_param_days, ParamKind.NUMBER, default = "7")),
        ),
        Action(
            "contacts_list", CoreR.string.dsh_wf_cat_list, CoreR.string.dsh_wf_act_contacts_list, CoreR.string.dsh_cap_contacts_title,
            DshNativeBridge.Cap.CONTACTS, "GET", "/native/contacts/list",
            listOf(p("limit", CoreR.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "20")),
        ),
        Action(
            "location", CoreR.string.dsh_wf_cat_position, CoreR.string.dsh_wf_act_location, CoreR.string.dsh_cap_location_title,
            DshNativeBridge.Cap.LOCATION, "GET", "/native/location",
        ),
        Action(
            "sms_list", CoreR.string.dsh_wf_cat_list, CoreR.string.dsh_wf_act_sms_list, CoreR.string.dsh_cap_sms_title,
            DshNativeBridge.Cap.SMS, "GET", "/native/sms/list",
            listOf(p("limit", CoreR.string.dsh_wf_param_limit, ParamKind.NUMBER, default = "10")),
        ),
    )

    /** 按一级分组（界面按这个顺序铺 chip）。 */
    fun byGroup(): List<Pair<Int, List<Action>>> {
        val out = LinkedHashMap<Int, MutableList<Action>>()
        for (a in ACTIONS) out.getOrPut(a.groupTitleRes) { mutableListOf() }.add(a)
        return out.map { it.key to it.value }
    }

    fun byId(id: String): Action? = if (id.isBlank()) null else ACTIONS.firstOrNull { it.id == id }
}
