package me.bmax.apatch.util

import android.content.Context
import android.content.Intent

/**
 * 拿到"打开本应用主界面"的 Intent，**不 import MainActivity**。
 *
 * ## 为什么不能直接 `Intent(ctx, MainActivity::class.java)`
 *
 * `dsh` 包里有多处要跳回主界面（原生能力桥的"去设置里开权限"、前台服务的
 * 通知点击）。直接引用 `MainActivity` 会让 `dsh` 反向依赖 `ui` ——
 * 而 `dsh` 的绝大部分要下沉到 `:core-kernel`，一旦反向依赖 `ui`，
 * 整块都搬不动（Checkpoint 1 前的实测：33 个文件被这个依赖卡住）。
 *
 * ## 为什么用 packageManager 而不是写死类名
 *
 * 写死 `"me.bmax.apatch.ui.MainActivity"` 是字符串，重命名类时编译器不会报错，
 * 运行时才 NoClassDefFoundError / ActivityNotFoundException。走
 * `getLaunchIntentForPackage` 拿的是 manifest 里声明的那个 launcher activity，
 * 与 `android.intent.action.MAIN` 声明保持一致 —— 改类名只要 manifest 跟着改就对。
 *
 * 拿不到时（极端情况：manifest 没声明 launcher）返回 null，调用方自己兜底。
 */
fun appLaunchIntent(ctx: Context): Intent? {
    val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return intent
}
