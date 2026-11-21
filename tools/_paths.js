/**
 * 门禁脚本的统一源码路径解析。
 *
 * ## 为什么需要它
 *
 * `tools/check-*.js` 里有 40 个脚本、430 处硬编码 `app/src/main/java/me/bmax/apatch/...`。
 * 架构重构要把文件搬进 `:core-kernel` / `:ext-*` 模块，每搬一个文件就要改一堆脚本 ——
 * 而漏改的表现是**断言静默失效**（读不到文件 → 断言基于空串 → 有些反而"通过"）。
 * 这是重构期最危险的一类错误。
 *
 * ## 解析顺序（关键：同时认两种形态）
 *
 * 1. **仓库相对路径**（`app/src/main/assets/x.mjs`、`build.gradle.kts`、
 *    `app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt`）—— 直接存在就返回。
 *    非源码路径（assets / gradle / manifest）走这条，**永远不受模块化影响**。
 * 2. **逻辑名**（`dsh/DshEnv.kt`）—— 按 [SOURCE_ROOTS] 逐个模块找。
 *    文件搬进 `:core-kernel` 之后仍能解析，脚本不用改。
 * 3. **[MOVED] 显式覆盖** —— 文件改名或换目录时在这里登记。
 *
 * 于是迁移期间两种写法都可用：老脚本不动也能跑，新写法面向未来。
 *
 * ## 找不到就抛，绝不返回空串
 *
 * 这是本模块最重要的约定。返回空串会让 `src.includes("x")` 变成 false，
 * 而不少断言是 `ok(!/x/.test(src))` 这种"不该出现"的形式 —— 空串让它们**全部通过**。
 * 抛异常则立刻在 CI 红掉，且报错直接说找不到什么。
 */

const fs = require("fs");
const path = require("path");

/** 仓库根（本文件在 tools/ 下）。 */
const ROOT = path.resolve(__dirname, "..");

/**
 * 已迁移 / 已改名的显式覆盖：逻辑名或旧路径 → 新路径（都相对仓库根）。
 *
 * 重构每搬一个文件，在这里加一行。这是**唯一**需要跟着改动的地方。
 */
const MOVED = {
  // Batch C：dsh_strings.xml 迁入 :core-res。
  //
  // 为什么迁：nonTransitiveRClass 下每个模块只能看自己的 R，而 DshRuntime / DshSource /
  // ContainerRuntime 要下沉到 :core-kernel，Batch D 的 6 个 :ext-* 也都要用这些字符串。
  //
  // 为什么登记 MOVED 而不是改 20 个脚本：脚本传的是**完整仓库相对路径**，
  // 不是逻辑名，step 3 的模块搜索接不住 —— MOVED 就是为"整棵子树搬走"准备的。
  "app/src/main/res/values/dsh_strings.xml":
    "core-res/src/main/res/values/dsh_strings.xml",
  "app/src/main/res/values-zh-rCN/dsh_strings.xml":
    "core-res/src/main/res/values-zh-rCN/dsh_strings.xml",
  // Batch D-1（Checkpoint 2）：Workflow / Userscripts 迁入各自 :ext-* 模块。
  // 同批还下沉了 Cap 枚举与 DshKernelState / DshWebCompat（都进 :core-kernel）。
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowActionCatalog.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowActionCatalog.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowApprovals.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowApprovals.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowCompute.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowCompute.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowGrants.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowGrants.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowModels.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowStore.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowStore.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WorkflowValidator.kt":
    "ext-workflow/src/main/java/me/bmax/apatch/dsh/workflow/WorkflowValidator.kt",
  "app/src/main/java/me/bmax/apatch/dsh/Userscripts.kt":
    "ext-userscripts/src/main/java/me/bmax/apatch/dsh/userscripts/Userscripts.kt",
  "app/src/main/java/me/bmax/apatch/dsh/WebScripts.kt":
    "ext-userscripts/src/main/java/me/bmax/apatch/dsh/userscripts/WebScripts.kt",
  "app/src/main/java/me/bmax/apatch/dsh/ScriptMarket.kt":
    "ext-userscripts/src/main/java/me/bmax/apatch/dsh/userscripts/ScriptMarket.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DshKernelState.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/DshKernelState.kt",
  "app/src/main/java/me/bmax/apatch/util/DshWebCompat.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/DshWebCompat.kt",
  // Batch D-2（Checkpoint 3）：Display → :ext-display，Backup → :ext-backup。
  // 同批下沉 OverlayLifecycleOwner / A11yOwn（投屏浮层要用，:ext-display 够不着 :app）。
  "app/src/main/java/me/bmax/apatch/dsh/DisplayMirror.kt":
    "ext-display/src/main/java/me/bmax/apatch/dsh/display/DisplayMirror.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DisplayServer.kt":
    "ext-display/src/main/java/me/bmax/apatch/dsh/display/DisplayServer.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DisplayVideoSink.kt":
    "ext-display/src/main/java/me/bmax/apatch/dsh/display/DisplayVideoSink.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DshBackupCrypto.kt":
    "ext-backup/src/main/java/me/bmax/apatch/dsh/backup/DshBackupCrypto.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DshCloudBackup.kt":
    "ext-backup/src/main/java/me/bmax/apatch/dsh/backup/DshCloudBackup.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DshBackupArchive.kt":
    "ext-backup/src/main/java/me/bmax/apatch/dsh/backup/DshBackupArchive.kt",
  "app/src/main/java/me/bmax/apatch/dsh/OverlayLifecycleOwner.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/OverlayLifecycleOwner.kt",
  // Batch D-3（Checkpoint 4）：原生桥拆分 + 若干共享件下沉 :core-kernel。
  "app/src/main/java/me/bmax/apatch/dsh/PrivPolicy.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/PrivPolicy.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DshElevationRequests.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/DshElevationRequests.kt",
  "app/src/main/java/me/bmax/apatch/dsh/DshNotificationListener.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/DshNotificationListener.kt",
  "app/src/main/java/me/bmax/apatch/util/AppLaunchIntent.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/AppLaunchIntent.kt",
  "app/src/main/java/me/bmax/apatch/util/ui/ToastExt.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/ToastExt.kt",
  "app/src/main/java/me/bmax/apatch/dsh/A11yOwn.kt":
    "core-kernel/src/main/java/me/bmax/apatch/dsh/A11yOwn.kt",
};

/** 源码根目录候选（重构前一个，重构后逐模块增加）。 */
const SOURCE_ROOTS = [
  "app/src/main/java/me/bmax/apatch",
  "core-kernel/src/main/java/me/bmax/apatch",
  "ext-api/src/main/java/me/bmax/apatch",
  "ext-workflow/src/main/java/me/bmax/apatch",
  "ext-display/src/main/java/me/bmax/apatch",
  "ext-userscripts/src/main/java/me/bmax/apatch",
  "ext-backup/src/main/java/me/bmax/apatch",
  "ext-device-caps/src/main/java/me/bmax/apatch",
  "ext-theme-media/src/main/java/me/bmax/apatch",
];

/**
 * 剥掉源码目录前缀，只留包路径。
 *
 * `app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt` → `dsh/DshEnv.kt`
 * `dsh/DshEnv.kt` → 原样返回
 *
 * 任何模块的源码根都是 `<module>/src/main/java/me/bmax/apatch`，所以前缀不同、
 * 包路径相同 —— 剥掉之后就能拿包路径去每个模块试。
 *
 * 只剥 `SOURCE_ROOTS` 里登记过的模块前缀。像 `displayserver/` 这种**不是**模块源码根的
 * 目录（它有自己的构建，且刻意与 app 源码树分离）不该被剥 —— 剥了会把两个不同的树
 * 混成一个，反向断言（"这个类不该在 displayserver 里"）就会失去意义。
 */
function stripSourcePrefix(p) {
  const clean = String(p).replace(/^\/+/, "");
  for (const root of SOURCE_ROOTS) {
    if (clean.startsWith(root + "/")) return clean.slice(root.length + 1);
  }
  return clean;
}

/**
 * 解析成绝对路径。找不到抛异常（见文件头）。
 *
 * @param {string} p 仓库相对路径或逻辑名
 */
function resolve(p) {
  const clean = String(p).replace(/^\/+/, "");

  // 1) 仓库相对路径：直接存在就用（assets / gradle / 重构前的源码路径都走这条）
  const direct = path.join(ROOT, clean);
  if (fs.existsSync(direct) && fs.statSync(direct).isFile()) return direct;

  // 2) 显式覆盖
  const moved = MOVED[clean];
  if (moved) {
    const abs = path.join(ROOT, moved);
    if (fs.existsSync(abs)) return abs;
    throw new Error(
      "[_paths] " + clean + " 登记在 MOVED 里但文件不存在：" + moved +
      "（搬迁后忘了更新这张表？）",
    );
  }

  // 3) 逻辑名：逐模块找
  //
  // 老脚本传的是**完整路径**（`app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt`）。
  // 文件搬进 `core-kernel/` 后模块变了、包路径没变，所以先把源码前缀剥掉再逐模块拼 ——
  // 这样"只换模块不改包"的迁移零配置命中，MOVED 只留给真正改名或拆分的文件。
  const logical = stripSourcePrefix(clean);
  const tried = [];
  for (const root of SOURCE_ROOTS) {
    const abs = path.join(ROOT, root, logical);
    tried.push(root + "/" + logical);
    if (fs.existsSync(abs)) return abs;
  }

  throw new Error(
    "[_paths] 找不到 " + clean + "，试过：\n  " + tried.join("\n  ") +
    "\n如果这个文件已经搬到别的模块，请确认包路径没变（变了就登记到 MOVED）。",
  );
}

/** 读一个文件（UTF-8）。找不到抛异常。 */
function read(p) {
  return fs.readFileSync(resolve(p), "utf8");
}

/**
 * 这个**确切路径**是否存在（不抛）。
 *
 * 与 [resolve] 的关键区别：**不做模块回退**。
 *
 * 为什么必须这样：不少断言是反向的 —— 「这个文件只该在 app 源码树里，不该在 displayserver 里」：
 *
 *     must(!exists('displayserver/src/main/java/.../IDisplayService.java'), ...)
 *
 * 如果 exists 也走模块搜索，它会把 `displayserver/` 前缀剥掉、在 `app/` 里找到同名文件，
 * 于是"不该存在"判成"存在"，断言假红（更糟的情况是反向的断言假绿）。
 * "存在"就该只回答"这个路径存在吗"。
 *
 * MOVED 仍然生效：登记过的改名要能被问到。
 */
function exists(p) {
  const clean = String(p).replace(/^\/+/, "");
  const direct = path.join(ROOT, clean);
  if (fs.existsSync(direct)) return true;
  const moved = MOVED[clean];
  if (moved && fs.existsSync(path.join(ROOT, moved))) return true;
  return false;
}

/**
 * 能不能解析到这个文件（**会**跨模块查找，与 [exists] 不同）。
 *
 * 两个函数的区别要说清，否则会互相误用：
 *  - [exists]：这个**确切路径**存在吗？用于反向断言（"这个类不该在 displayserver 里"）。
 *  - [findable]：这个名字的文件**在某处**存在吗？用于"迁移期间两处位置都认"的断言。
 */
function findable(p) {
  try { resolve(p); return true; } catch (_) { return false; }
}

/** 仓库根。 */
function repoRoot() { return ROOT; }

module.exports = { resolve, read, exists, findable, repoRoot, stripSourcePrefix, MOVED, SOURCE_ROOTS };
