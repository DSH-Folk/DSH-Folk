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
  // 例：
  // "dsh/DshRuntime.kt": "core-kernel/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt",
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
  const tried = [];
  for (const root of SOURCE_ROOTS) {
    const abs = path.join(ROOT, root, clean);
    tried.push(root + "/" + clean);
    if (fs.existsSync(abs)) return abs;
  }

  throw new Error(
    "[_paths] 找不到 " + clean + "，试过：\n  " + tried.join("\n  ") +
    "\n如果这个文件已经搬到别的模块，请在 tools/_paths.js 的 MOVED 里登记。",
  );
}

/** 读一个文件（UTF-8）。找不到抛异常。 */
function read(p) {
  return fs.readFileSync(resolve(p), "utf8");
}

/** 是否存在（不抛）。 */
function exists(p) {
  try { resolve(p); return true; } catch (_) { return false; }
}

/** 仓库根。 */
function repoRoot() { return ROOT; }

module.exports = { resolve, read, exists, repoRoot, MOVED, SOURCE_ROOTS };
