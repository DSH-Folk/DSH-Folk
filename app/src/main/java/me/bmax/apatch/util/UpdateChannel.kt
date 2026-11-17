package me.bmax.apatch.util

import android.content.Context
import android.content.SharedPreferences
import me.bmax.apatch.APApplication
import me.bmax.apatch.R

/**
 * 更新通道。
 *
 * 历史包袱先说清楚：这里原来只有一个布尔 `accept_beta_update`（见 [LEGACY_KEY_ACCEPT_BETA]），
 * 「开」= 收测试版、「关」= 只收正式版。加了 Alpha 之后布尔表达不了三种状态，
 * 所以改成枚举，但**旧的布尔键必须继续被读**——存量用户升级上来时那个键是有值的，
 * 直接换成新键会让所有测试版用户静默掉回正式版，而他们本来是主动选择过测试版的。
 * 迁移见 [read]。
 *
 * ## 「上级覆盖下级」是什么意思
 *
 * 用户选 Alpha 时**同时**收 Alpha/Beta/Stable：越靠上越激进，包含下面所有层的版本。
 * 所以取候选版本时不能只按 tag 后缀精确过滤（那会让 Alpha 用户看不到正式版），
 * 而是「按通道取一个上限，凡是这个上限之内的都算候选」，再用 semver 比出最新的那个。
 * 见 [allows]。
 *
 * 通道之间**没有**「同版本号谁优先」的问题：版本号本身已经唯一标识了一个发布，
 * 同一个 versionName 不会同时出现在两个通道里（beta.292 就只是 beta.292）。
 * 唯一需要裁决的是「哪些版本对该用户可见」，那由 [allows] 回答。
 */
enum class UpdateChannel(
    /** 持久化的值。不要用 enum 的 name：改名就丢配置，而这个字符串会落盘。 */
    val id: String,
) {
    /** 只收正式版。默认值，与历史行为一致（旧布尔键缺省就是 false）。 */
    STABLE("stable"),

    /** 收 Beta + 正式版。 */
    BETA("beta"),

    /** 收 Alpha + Beta + 正式版。 */
    ALPHA("alpha"),
    ;

    /** 这个通道是否接受 [other] 这一档的发布。越靠上的通道接受越多。 */
    fun allows(other: UpdateChannel): Boolean = other.ordinal <= this.ordinal

    /**
     * 通道显示名。放在 util 层而不是 UI 层，是为了让"这个通道叫什么"只有一处定义 ——
     * 设置页、更新对话框、关于页若各写一份，改了通道名总有一处会漏。
     */
    val labelRes: Int
        get() = when (this) {
            STABLE -> R.string.update_channel_stable
            BETA -> R.string.update_channel_beta
            ALPHA -> R.string.update_channel_alpha
        }

    /** 通道的一句话说明（选择面板里用）。 */
    val descRes: Int
        get() = when (this) {
            STABLE -> R.string.update_channel_stable_desc
            BETA -> R.string.update_channel_beta_desc
            ALPHA -> R.string.update_channel_alpha_desc
        }

    companion object {
        /** 新键。落盘值见各枚举的 [id]。 */
        const val KEY_CHANNEL = "update_channel"

        /** 旧键，只读不写（除非做迁移）。见类注释。 */
        const val LEGACY_KEY_ACCEPT_BETA = "accept_beta_update"

        fun fromId(id: String?): UpdateChannel =
            entries.firstOrNull { it.id == id } ?: STABLE

        private fun prefs(context: Context): SharedPreferences {
            // 复用 APApplication 那一份：设置页写的是它，检查逻辑读的也必须是它。
            // 各写一份 getSharedPreferences(SP_NAME) 也能通，但两份来源一旦漂移（比如
            // 有人改了 SP_NAME 却只改一处），就变成「界面显示开了、检查读到关」。
            val app = context.applicationContext
            return if (app is APApplication) app.sharedPreferences
            else app.getSharedPreferences(APApplication.SP_NAME, Context.MODE_PRIVATE)
        }

        /**
         * 读当前通道，**并把旧布尔键迁移过来**。
         *
         * 迁移只在「新键还不存在」时发生一次：之后用户在新界面里的选择就是唯一事实，
         * 再回头读旧键会把它覆盖掉（旧键此后不再被写，永远是迁移前的残留值）。
         *
         * 注意 [APApplication.sharedPreferences] 与 [SP_NAME] 必须是同一份 ——
         * 写进一份、读另一份是这类开关最经典的 bug（界面显示开了、检查逻辑读到关）。
         * 这里刻意复用 [APApplication.sharedPreferences]，与设置页保持同一来源。
         */
        fun read(context: Context): UpdateChannel {
            val p = prefs(context)
            val stored = p.getString(KEY_CHANNEL, null)
            if (stored != null) return fromId(stored)
            // 新键不存在 = 首次跑带通道的版本，把旧布尔翻译过来。
            val migrated = if (p.getBoolean(LEGACY_KEY_ACCEPT_BETA, false)) BETA else STABLE
            p.edit().putString(KEY_CHANNEL, migrated.id).apply()
            return migrated
        }

        fun write(context: Context, channel: UpdateChannel) {
            prefs(context).edit().putString(KEY_CHANNEL, channel.id).apply()
        }
    }
}
