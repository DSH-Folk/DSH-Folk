package me.bmax.apatch.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DshEnv.containerRelPath] 的单元测试：容器路径 → rootfs 相对路径。
 *
 * ## 为什么这个函数值得单独测
 *
 * 它是**安全边界**。插件跑在 proot 容器里，给 App 的路径都是容器视角的
 * （`/root/.dsh/...`），App 必须把它们映射进 rootfs 才能读写。映射错一个字符的后果：
 *
 * - 漏判 `..` → 插件能往 rootfs **外面**写文件（`/data/data/.../files/../../..`）；
 * - 漏判绝对路径 → `File("/root/.dsh")` 指向 Android 根下不存在的目录，写入即 ENOENT；
 * - 空路径放行 → 映射到 rootfs 本身，后续 `deleteRecursively` 会删掉整个运行时。
 *
 * 这三种错都不会立刻崩，而是"某个插件功能莫名不生效"或"用户数据没了"，极难回溯。
 *
 * ## 为什么不测 containerToHost
 *
 * 那个版本要 `Context`。用 Robolectric 能把测试跑起来，但要多一份依赖、
 * 单次启动几百毫秒 —— 为了一段纯字符串逻辑不值得。所以把判定抽成
 * [DshEnv.containerRelPath]（零依赖），Context 那层只剩 `File(rootfs, rel)` 一步。
 */
class DshEnvPathTest {

    @Test
    fun `正常容器路径映射成相对路径`() {
        assertEquals("root/.dsh", DshEnv.containerRelPath("/root/.dsh"))
        assertEquals("root/.dsh/profiles/web", DshEnv.containerRelPath("/root/.dsh/profiles/web"))
        assertEquals("tmp/x.tar.gz", DshEnv.containerRelPath("/tmp/x.tar.gz"))
    }

    @Test
    fun `相对路径一律拒绝`() {
        // 容器视角必须是绝对路径。相对路径进来要么是调用方拼错了，
        // 要么是插件在试探 —— 两种都不该放行。
        assertNull(DshEnv.containerRelPath("root/.dsh"))
        assertNull(DshEnv.containerRelPath("./root/.dsh"))
        assertNull(DshEnv.containerRelPath(""))
    }

    @Test
    fun `含双点段的路径一律拒绝（越界防线）`() {
        // 不看它规范化后是否还在内部：符号链接存在时 `/a/../../b` 的行为
        // 不可预测（proot 的 --link2symlink 会制造大量符号链接）。
        assertNull(DshEnv.containerRelPath("/.."))
        assertNull(DshEnv.containerRelPath("/root/../.."))
        assertNull(DshEnv.containerRelPath("/root/.dsh/../../../etc/passwd"))
        assertNull(DshEnv.containerRelPath("/a/b/../c"))
    }

    @Test
    fun `单独的斜杠拒绝`() {
        // "/" → rel 为空 → 映射到 rootfs 本身。放行的话，任何基于返回值的
        // 递归删除都会把整个运行时删掉。
        assertNull(DshEnv.containerRelPath("/"))
    }

    @Test
    fun `名字里含两个点但不是双点段的要放行`() {
        // ".." 是逃逸，但 "..foo" / "a..b" 只是普通文件名。
        // 用 == 比较而不是 contains，就是为了不误杀这类名字。
        assertEquals("root/..hidden", DshEnv.containerRelPath("/root/..hidden"))
        assertEquals("a..b/c", DshEnv.containerRelPath("/a..b/c"))
        assertEquals(".dsh", DshEnv.containerRelPath("/.dsh"))
    }

    @Test
    fun `尾随斜杠保留为空段（不 panic）`() {
        // 真实调用里会出现 "/root/.dsh/" 这种。空段不是 ".."，所以放行；
        // File 构造会自己处理。这里只钉住"不抛异常、不被误判为越界"。
        assertEquals("root/.dsh/", DshEnv.containerRelPath("/root/.dsh/"))
    }
}
