package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.io.OutputStream
import me.bmax.apatch.dsh.ext.ExtensionHookRegistry
import me.bmax.apatch.res.R as CoreR

/**
 * 进容器执行：argv 拼装、环境变量、pty、输出收集。
 *
 * ## 拆出来的理由
 *
 * 这是 `DshRuntime` 里耦合最低的一块：只依赖 [DshKernelState]（Context）、
 * [DshRuntime.runtime]（选中的容器运行时）和 [DshLogging.supported]（硬链接能力）。
 * 拆出来之后 `DshRuntime` 少了 268 行、11 个函数，调用点一个不用改。
 *
 * ## 为什么 argv 拼装和环境变量必须在一起
 *
 * [baseArgv] 决定 proot 命令行，[applyEnv] 决定它看到的环境。两者一旦分家，
 * 调其中一个就容易忘了另一个 —— 而 `--link2symlink` 与 `PROOT_TMP_DIR` 这种
 * 开关错配的表现是"文件写成功但读不出来"，极难定位。放同一个类里，改动时
 * 至少会被一起看到。
 */
object DshExec {

    /** 短名，避免每行都写 [DshKernelState]。 */
    private val K get() = DshKernelState


    private fun baseArgv(): MutableList<String> =
        DshRuntime.runtime().baseArgv(DshEnv.rootfs(K.appContext), DshLogging.supported()).toMutableList()

    /**
     * **proot** 的 argv 前缀，用于「当前运行时无法在这条路径上工作」时的显式回退。
     *
     * 为什么需要单独一个：chroot 的 [ContainerRuntime.baseArgv] 是**空列表**（它不走宿主 argv），
     * 所以凡是「spawnArgv 返回 null 就 baseArgv() + guest」的写法，在 chroot 下都会拼出只有一个
     * 元素的非法命令。PTY、execRootfs、execRootfsInteractive 三条路统一用这个兜底。
     */
    private fun prootBaseArgv(): MutableList<String> =
        ContainerRuntime.Proot(K.appContext, DshRuntime.nativeLibFor("libproot.so"))
            .baseArgv(DshEnv.rootfs(K.appContext), DshLogging.supported()).toMutableList()

    /** 容器内与宿主无关的 guest 环境 + 运行时专用环境。 */
    private fun applyEnv(pb: ProcessBuilder) {
        val rt = DshRuntime.runtime()
        val libDir = File(K.appContext.filesDir, "lib")
        val env = pb.environment()
        // proot 的启动前提**无条件**设，不按「当前选中的运行时」跳过。
        //
        // 与 ensureRuntimeFiles() 里那条同一个道理：`prootBaseArgv()` 是全局兜底（chroot 的
        // PTY 回退、spawnArgv 返回 null 时的回退、运行时不可用时的回退），所以即使选的是
        // chroot/proroot，这个进程也可能真的去 exec libproot.so。缺了会这样：
        //  - 缺 LD_LIBRARY_PATH → linker 找不到 filesDir/lib/libtalloc.so.2。真机（issue #12，
        //    chroot 模式）：
        //      CANNOT LINK EXECUTABLE ".../lib/arm64/libproot.so":
        //      library "libtalloc.so.2" not found
        //  - 缺 PROOT_LOADER → proot 直接起不来
        // 多设三个环境变量没有任何副作用（别的运行时根本不读它们）。
        env["PROOT_TMP_DIR"] = DshEnv.tmpDir(K.appContext).absolutePath
        env["PROOT_LOADER"] = DshRuntime.nativeLibFor("libprootloader.so").absolutePath
        env["PROOT_LOADER_32"] = DshRuntime.nativeLibFor("libprootloader32.so").absolutePath
        env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${DshEnv.nativeLibDir(K.appContext).absolutePath}"
        // 无硬链接时把 l2s 中间文件集中到 rootfs 内（默认就近存放会随 tmp 被清掉）
        if (!DshLogging.supported()) {
            val l2s = DshEnv.l2sDir(K.appContext).apply { mkdirs() }
            env["PROOT_L2S_DIR"] = l2s.absolutePath
        }
        runCatching { rt.applyEnv(env, K.appContext.filesDir, libDir, DshEnv.tmpDir(K.appContext)) }
        // 别让 dsh 的原生插件加载器把 .node 二进制「materialize」一遍。
        //
        // node-addon-native-custom-loader 的 materializedNativeBinaryPath() 是这么干的：
        //   读源文件 → 写一份 temp → link(temp, destination) → 删掉 temp
        // 在无硬链接设备上容器里的 link() 被 --link2symlink 改写，destination 于是成了符号链接
        // （真身被挪进 rootfs/.l2s），temp 一删，那份链接就指向了加载不了的东西。真机实测
        // （dsh 0.1.7-rc.2 + r5 运行时，proroot）：
        //   /tmp/node-addon-native-custom-loader-0/native-cache/.../linux-arm64-gnu-napi-v9.node
        //     -> /.l2s/.l2s..linux-arm64-gnu-napi-v9.node.<pid>.<hex>.tmp0001
        // 内层报错是 "Invalid or unexpected token"（Node 把那个路径当 JS 解析了），外层表现为
        // `No usable native binding found for node-addon-require-builtin-linux-arm64-gnu`，
        // dsh 启动即失败。proot 因为路径翻译恰好还能解析同一个链接，所以只在 proroot 下暴露。
        //
        // 为什么以前不犯：materialize 只在缓存里**没有**这条记录时才做（命中且哈希一致就直接用）。
        // 运行时更新会换掉整个 rootfs，而 rootfs/tmp 不在 DshEnv.PRESERVED_PATHS 里 —— 缓存一清空，
        // 每次启动都要重来一遍这个链接把戏，于是稳定失败。
        //
        // 关掉缓存后加载器直接从包路径 require（真机实测 NARB_DISABLE_NATIVE_CACHE=1 可加载），
        // 既没有 link/unlink、也没有那层链接间接；代价只是少一次到短路径的物化复制 ——
        // 在 Linux 上本来也没有 Windows 那种路径长度/杀软扫描的理由。
        env["NARB_DISABLE_NATIVE_CACHE"] = "1"
        // guest 侧环境：PATH 必须覆盖，否则继承 Android 的 /system/bin 找不到 bash 工具链
        env["HOME"] = "/root"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TMPDIR"] = "/tmp"
        env["LANG"] = "C.UTF-8"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["TERM"] = "xterm-256color"
        // 扩展拦截点：让扩展在**最后**改写容器环境（此时 proot/proroot/chroot 各自需要的那批
        // 变量都已经设好）。放在最后而不是中间，是为了让扩展看到完整的环境再做决定；
        // 代价是扩展改不到「已经设好之后又被下方覆盖」这种情况 —— 但下方没有覆盖。
        if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.RUNTIME_APPLY_ENV)) {
            val payload = mutableMapOf<String, Any?>("env" to env)
            ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.RUNTIME_APPLY_ENV, payload)
        }
    }

    /** 在 rootfs 内执行一条 bash 命令（stderr 合并进 stdout）。 */
    fun execRootfs(bashCommand: String): Process {
        ensureRuntimeFiles()
        val rt = DshRuntime.runtime()
        // 扩展拦截点（热路径！每次容器命令都会过这里）：
        // 先判有没有人注册（一次 map 查空就返回，不分配对象），再跑 Hook。
        // 拦截器可以改写 "cmd" 键；返回 false 则**拦下这条命令**，宿主按「拒绝执行」处理。
        var command = bashCommand
        if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.SHELL_BEFORE_EXEC)) {
            val payload = mutableMapOf<String, Any?>("cmd" to bashCommand)
            if (!ExtensionHookRegistry.runAround(ExtensionHookRegistry.Point.SHELL_BEFORE_EXEC, payload)) {
                throw IllegalStateException("命令被扩展拦下：" + bashCommand)
            }
            ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.SHELL_BEFORE_EXEC, payload)
            (payload["cmd"] as? String)?.takeIf { it.isNotBlank() }?.let { command = it }
        }
        // chroot 这类运行时自己接管 argv（命令要写进脚本、经真 root 执行）；
        // 返回 null 就是 proot/proroot 的老路 —— 行为一个字节不变。
        val guest = listOf("/bin/bash", "-c", command)
        // spawnArgv 返回 null = 这个运行时不接管 argv。注意**不能**简单回退 baseArgv()：
        // chroot 的 baseArgv 是空列表（它本来就不走宿主 argv），拼出来只有一个元素的非法命令。
        // 所以这里显式回退到 proot —— 与 PTY 那条路同一套策略。
        val argv = rt.spawnArgv(guest, guestEnv()) ?: (prootBaseArgv() + guest)
        // RUNTIME_BUILD_ARGV：让扩展在**真正启动之前**改写 argv。
        // 这里只做「改写」，不重复启动逻辑 —— 启动路径保持唯一，否则改一处漏一处。
        val finalArgv = if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.RUNTIME_BUILD_ARGV)) {
            val payload = mutableMapOf<String, Any?>("argv" to argv)
            ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.RUNTIME_BUILD_ARGV, payload)
            @Suppress("UNCHECKED_CAST")
            (payload["argv"] as? List<String>)?.takeIf { it.isNotEmpty() } ?: argv
        } else {
            argv
        }
        val pb = ProcessBuilder(finalArgv).redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        applyEnv(pb)
        return pb.start()
    }

    /** 启动交互式 bash（供终端页；cd/export 状态保持）。 */
    fun execRootfsInteractive(): Process {
        ensureRuntimeFiles()
        val rt = DshRuntime.runtime()
        val guest = listOf("/bin/bash")
        val argv = rt.spawnArgv(guest, guestEnv()) ?: (prootBaseArgv() + guest)
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb)
        pb.environment()["DSH_INTERACTIVE"] = "1"
        return pb.start()
    }

    /**
     * 容器内的 guest 环境（供 [ContainerRuntime.spawnArgv] 用，不经 ProcessBuilder）。
     *
     * 为什么单独抽出来：`su` 会重置环境，所以 chroot 只能把环境写进启动脚本；而 proot/proroot
     * 走 ProcessBuilder 继承。两者需要的**内容**是同一份 —— 各写一份必然分叉（以前就出现过
     * 「PTY 里少一个变量就起不来」），所以这里把内容抽成一份，谁用谁取。
     */
    private fun guestEnv(): Map<String, String> = linkedMapOf(
        "HOME" to "/root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TMPDIR" to "/tmp",
        "LANG" to "C.UTF-8",
        "DEBIAN_FRONTEND" to "noninteractive",
        "TERM" to "xterm-256color",
    )

    /**
     * PTY 会话的 argv（终端页用）。默认 `bash -l`，也可指定 guest 命令。
     *
     * 与 [execRootfs] 共用 [baseArgv]：各写一份的话，PTY 里的 shell 会跑在和普通命令
     * 不一样的环境里，少一个 PROOT_LOADER 就直接起不来。
     */
    fun ptyArgv(vararg guestCmd: String): Array<String> {
        ensureRuntimeFiles()
        // chroot 走不了 PTY：Termux 的 terminal-emulator 是 openpty + fork + execvp，而 chroot
        // 需要先 su 提权、再 mount --bind 组装挂载视图 —— 这两步没法塞进一个 execvp。所以这里
        // 显式退回 proot 的 argv：终端页是「进容器敲命令」的入口，宁可用 proot 也必须能用。
        val argv = baseArgv()
        if (argv.isEmpty()) argv += prootBaseArgv()
        if (guestCmd.isEmpty()) {
            argv += "/bin/bash"
            argv += "-l"
        } else {
            argv += guestCmd
        }
        return argv.toTypedArray()
    }

    /**
     * PTY 会话的环境变量（`KEY=VALUE` 形式）。
     *
     * 借一个临时 ProcessBuilder 收集，而不是重抄一份：环境构造分散在 [applyEnv] 与
     * [ContainerRuntime.applyEnv]，还随 proot/proroot 分叉，手抄必漏。
     */
    fun ptyEnv(): Array<String> {
        val probe = ProcessBuilder("/system/bin/true")
        applyEnv(probe)
        return probe.environment()
            .filter { it.key != null && it.value != null }
            .map { "${it.key}=${it.value}" }
            .toTypedArray()
    }

    /**
     * 同步执行并读回输出（带超时，默认 60s）。
     *
     * 读流必须放到单独线程：readText() 会一直阻塞到 EOF，先读再 waitFor(timeout) 的写法
     * 里超时是彻底无效的 —— 子进程挂住不退出（等 stdin、卡在网络、pnpm 死锁）就会把调用
     * 线程永久钉死，容器进程也泄漏。这里改成读线程 + join(超时) + destroyForcibly。
     *
     * 超时返回已读到的部分而不是空串：pnpm / pip 那种半途卡死的情况，前面几百行输出
     * 往往正好说明卡在哪。
     */
    fun execRootfsForOutput(bashCommand: String, timeoutMs: Long = 60_000L): String =
        execRootfsStreaming(bashCommand, timeoutMs) {}

    /**
     * 同上，但每读到一整行就回调一次。
     *
     * 存在的理由：[execRootfsForOutput] 把输出攒到结束才一次返回，而
     * `dsh plugin add` 背后的 pnpm 可能跑几分钟——这段时间里界面拿不到
     * 任何进展，用户无法判断是在装还是卡死了。
     *
     * onLine 在读线程上调用（不是主线程），回调里不要直接碰 Compose 状态。
     */
    fun execRootfsStreaming(
        bashCommand: String,
        timeoutMs: Long = 60_000L,
        onLine: (String) -> Unit,
    ): String = runCatching {
        val p = execRootfs(bashCommand)
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    // 手写行切分而不用 readLine()：pnpm 的进度行以 \r 结尾且不带 \n，
                    // readLine() 会一直等到下一个 \n 才吐——进度就又成了攒一批才出。
                    val line = StringBuilder()
                    fun flush() {
                        if (line.isEmpty()) return
                        val text = line.toString()
                        line.setLength(0)
                        runCatching { onLine(text) }
                    }
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { sb.appendRange(buf, 0, n) }
                        for (i in 0 until n) {
                            val ch = buf[i]
                            if (ch == '\n' || ch == '\r') flush() else line.append(ch)
                        }
                    }
                    flush()
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            // 超时：先杀进程让读流拿到 EOF，再给读线程一点时间收尾
            p.destroyForcibly()
            reader.join(1_000)
            logWarn(CoreR.string.dsh_log_cmd_timeout, timeoutMs / 1000, bashCommand.take(120))
        } else if (!p.waitFor(2, TimeUnit.SECONDS)) {
            // 流已关但进程还在（少见：子孙进程继承了 stdout 又提前关掉）
            p.destroyForcibly()
        }
        val output = synchronized(sb) { sb.toString() }
        // 扩展拦截点：命令跑完之后给扩展一次观察/改写机会。
        //
        // 为什么放在这里而不是 execRootfs：execRootfs 返回的是 Process，输出还没读；
        // 要拿到结果必须等到这里。**注意不能换成 after** —— after 是「事后通知」，
        // 而这一条允许把输出改掉（例如给敏感内容打码），所以走 before 语义的 payload 改写：
        // 扩展读 "output"，写回 "output" 就生效。
        if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.SHELL_AFTER_EXEC)) {
            val payload = mutableMapOf<String, Any?>(
                "cmd" to bashCommand,
                "output" to output,
                "exitCode" to runCatching { p.exitValue() }.getOrNull(),
            )
            ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.SHELL_AFTER_EXEC, payload)
            (payload["output"] as? String) ?: output
        } else {
            output
        }
    }.getOrElse { "" }
}
