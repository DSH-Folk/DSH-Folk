package me.bmax.apatch.dsh.ext

import android.content.Context
import java.lang.reflect.InvocationTargetException

/**
 * [ExtensionPlatform] 的宿主实现：把能力名解析成 [DshPlatform] 上的真实单例。
 *
 * ## 实现方式：反射读 DshPlatform 的 getter
 *
 * [DshPlatform] 已经有 33 个能力属性，全部是 `val xxx get() = 单例`。这里不逐个转发
 * （那会退化成"每加一个能力就要回来补一行"，扩展永远落后于宿主），而是直接反射调
 * 那些 getter —— 宿主今天新增一个属性，扩展明天就能用，不需要改这个文件。
 *
 * ## 为什么必须 keep 住这些 getter
 *
 * release 构建开 R8，`DshPlatform` 在 `me.bmax.apatch.dsh.ext.**` 包里，已有整包 keep
 * （见 proguard-rules.pro），所以 getter 名字与类名都保留。**这条依赖必须成立**：
 * 一旦那条 keep 规则被删，这里所有反射都会失败，而表现是"扩展调不到任何能力"，
 * 不是编译错误。
 *
 * ## 调用失败一律折进 PlatformResult
 *
 * 扩展与宿主同进程：一个没接住的异常会带走整个 App。所以这里捕获 InvocationTargetException
 * 并把原因（含底层异常类型与消息）放进 error，让扩展自己决定降级还是上报。
 */
internal class AppExtensionPlatform(
    private val appContext: Context,
    private val extensionId: String,
) : ExtensionPlatform {

    private val facade: DshPlatform by lazy { DshPlatform(appContext, extensionId) }

    /**
     * 能力名 → getter。用 `DshPlatform::class.java.methods` 枚举，不写死列表。
     *
     * 只认无参、返回非 void 的 public 方法，且名字不以 get 之外的形式出现 ——
     * `DshPlatform` 里的能力都是 `val x get() = ...`，编译成 `getX()`。
     */
    private val getters: Map<String, java.lang.reflect.Method> by lazy {
        val out = LinkedHashMap<String, java.lang.reflect.Method>()
        for (m in DshPlatform::class.java.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType == Void.TYPE) continue
            val name = m.name
            if (!name.startsWith("get") || name.length <= 3) continue
            // getState() 这类是方法不是能力，但 DshPlatform 里没有 get* 形式的业务方法，
            // 唯一例外是下面 exec/execCapture 这些 —— 它们不以 get 开头，自然被排除。
            val prop = name[3].lowercaseChar() + name.substring(4)
            out[prop] = m
        }
        out
    }

    override fun capabilities(): List<String> = getters.keys.sorted()

    override fun capability(capability: String): Any? = runCatching {
        getters[capability]?.invoke(facade)
    }.getOrNull()

    override fun invoke(capability: String, method: String, vararg args: Any?): PlatformResult =
        runCatching {
            val target = getters[capability]?.invoke(facade)
                ?: return PlatformResult.failure(
                    "没有这个宿主能力：" + capability + "（可用：" + capabilities().joinToString(", ") + "）",
                )
            val m = findMethod(target.javaClass, method, args)
                ?: return PlatformResult.failure(
                    capability + "." + method + " 找不到匹配的方法（参数个数 " + args.size + "）",
                )
            m.isAccessible = true
            PlatformResult.success(m.invoke(target, *args))
        }.getOrElse { unwrap(it, capability + "." + method) }

    override fun property(capability: String, name: String): PlatformResult = runCatching {
        val target = getters[capability]?.invoke(facade)
            ?: return PlatformResult.failure("没有这个宿主能力：" + capability)
        val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
        val m = target.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?: return PlatformResult.failure(capability + "." + name + " 不存在")
        m.isAccessible = true
        PlatformResult.success(m.invoke(target))
    }.getOrElse { unwrap(it, capability + "." + name) }

    /**
     * 按名字与实参挑一个重载。
     *
     * 匹配规则故意宽松：**先按参数个数，再按类型可赋值性**。不做严格签名匹配的理由是
     * 扩展传进来的常常是 Kotlin 的 Any?（已装箱），严格匹配会一个都对不上。
     * 代价是重载歧义时可能选错 —— 那种情况下调用会抛 IllegalArgumentException，
     * 由外层折成 error，扩展能看到。
     */
    private fun findMethod(
        type: Class<*>,
        name: String,
        args: Array<out Any?>,
    ): java.lang.reflect.Method? {
        val candidates = type.methods.filter { it.name == name && it.parameterCount == args.size }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        return candidates.firstOrNull { m ->
            m.parameterTypes.withIndex().all { (i, p) ->
                val a = args[i]
                a == null || boxed(p).isInstance(a)
            }
        } ?: candidates[0]
    }

    private fun boxed(t: Class<*>): Class<*> = when (t) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> t
    }

    /** 剥掉反射的两层包装，把真正的失败原因交给扩展。 */
    private fun unwrap(e: Throwable, what: String): PlatformResult {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        val msg = cause.message ?: cause.javaClass.simpleName
        ExtLog.log("[" + extensionId + "] " + what + " 失败：" + msg)
        return PlatformResult.failure(what + " 失败：" + msg)
    }
}
