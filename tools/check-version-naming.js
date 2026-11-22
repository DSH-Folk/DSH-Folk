#!/usr/bin/env node
/**
 * 版本号命名规范的自动校验。
 *
 * ## 为什么需要它
 *
 * 版本号在**四处**被解析，任何一处规则漂移都会让用户收到错的东西，而症状都很难查：
 *
 *  1. `.github/workflows/beta.yml` —— 从 target_version 推导 versionCode 与 tag
 *  2. `.github/workflows/build.yml` —— 正式版同样推导一次
 *  3. `app/build.gradle.kts` 的 `baseVersionCode()` —— 下限，防止 beta 低于已发正式版
 *  4. `UpdateChecker.channelOf()` —— 从 tag 后缀判定通道
 *
 * 典型事故：beta 的 versionCode 推导低于已发正式版 → 用户撞
 * `INSTALL_FAILED_VERSION_DOWNGRADE`，测试版通道对那批用户等于失效。
 *
 * ## 规范（唯一事实）
 *
 * ```
 *   正式版  v<major>.<minor>.<patch>            例 v2.2.1
 *   Beta    v<major>.<minor>.<patch>-beta.<n>   例 v2.2.1-beta.296
 *   Alpha   v<major>.<minor>.<patch>-alpha.<n>  例 v2.2.1-alpha.12
 *   四段补丁 v<major>.<minor>.<patch>.<rev>     例 v1.9.2.2（历史遗留，仍需接受）
 * ```
 *
 * 通道判定只看**后缀前缀**：无后缀 = 正式，`-alpha` = Alpha，其余 = Beta。
 * 这与 `UpdateChecker.channelOf` 必须一致 —— 那边改了这里要跟着改。
 *
 * ## 序号 <n> 的规则（2026-11 起）
 *
 * <n> 是「**同一核心版本**下已发布的预发布 tag 数 + 1」，发正式版（推进
 * `baseVersionName()`）后自动归零。不再用仓库全局的 `GITHUB_RUN_NUMBER` ——
 * 那个永不归零，序号会失去「这版迭代了几次」的含义，且 alpha 与 beta 混在一起
 * 无法判断先后（beta.309 与 alpha.317 谁新？光看号答不出来）。
 *
 * 排序由 `compareAppVersions`（不是 `compareVersions`）负责：核心版本优先，
 * 同核心版本只比序号、不看通道名。所以 alpha.317 > beta.309 成立。
 */
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
let failures = 0;
const ok = (cond, msg) => {
  if (!cond) {
    console.error("  ✗ " + msg);
    failures++;
  }
};
const read = (p) => require("./_paths.js").read(p);

console.log("版本号命名规范");

// ── 规范本身：三个通道的正则，与 channelOf 一一对应 ──
const RE_STABLE = /^v?\d+\.\d+\.\d+(\.\d+)?$/;
const RE_BETA = /^v?\d+\.\d+\.\d+(\.\d+)?-beta\.\d+$/;
const RE_ALPHA = /^v?\d+\.\d+\.\d+(\.\d+)?-alpha\.\d+$/;

const classify = (tag) => {
  if (RE_ALPHA.test(tag)) return "alpha";
  if (RE_BETA.test(tag)) return "beta";
  if (RE_STABLE.test(tag)) return "stable";
  return null;
};

// 自检：分类函数本身对四种合法形态都要认得
for (const [tag, want] of [
  ["v2.2.1", "stable"],
  ["v1.9.2.2", "stable"],
  ["v2.2.1-beta.296", "beta"],
  ["v2.2.1-alpha.12", "alpha"],
]) {
  ok(classify(tag) === want, `分类自检：${tag} 应为 ${want}`);
}
// 非法形态必须被拒
for (const bad of ["v2.2", "v2.2.1-rc.1", "v2.2.1-beta", "v2.2.1-alpha.", "v2.2.1-", "v2.2.1-beta.x"]) {
  ok(classify(bad) === null, `分类自检：${bad} 应被判为非法`);
}

// ── 1. CI 里两个 workflow 的命名规则必须一致 ──
{
  const beta = read(".github/workflows/beta.yml");
  ok(beta.length > 0, "beta.yml 可读");
  // 正式版的版本号来自 build.gradle.kts 的 baseVersionName（build.yml 只负责构建与发布），
  // 所以这里只校验 beta.yml 的命名规则 —— 去查 build.yml 会是一个永远为假的断言。
}
{
  const beta = read(".github/workflows/beta.yml");
  // 预发布名必须是 <target>-<channel>.<run_number>：后缀少了通道名就会被当成正式版推给所有人。
  //
  // 通道由 workflow 输入决定（默认 alpha）。断言要求两件事：
  //   1. 名字里带 ${CHANNEL} 变量而不是写死某个通道 —— 写死的话另一个通道发出来的包
  //      会被 App 当成同一个通道，更新提示就乱了；
  //   2. 输入校验里 alpha / beta 都在白名单内。
  // 序号已改为「同核心版本已发布预发布 tag 数 + 1」（发正式版即归零），
  // 不再是仓库全局的 GITHUB_RUN_NUMBER（那个永不归零，序号失去含义）。
  ok(/\$\{TARGET\}-\$\{CHANNEL\}\.\$\{SEQ\}/.test(beta) && /git tag -l/.test(beta),
    "预发布版本名形如 <target>-<channel>.<n>（缺通道后缀会被当成正式版）");
  ok(/default: alpha/.test(beta), "默认发 alpha（beta 只在明确指定时才出）");
  ok(/alpha\|beta\) ;;/.test(beta), "通道输入只接受 alpha / beta");
  // tag 必须是 v 前缀：channelOf 按 tag 判通道，没有 v 也能过，但发布习惯要统一
  ok(/tag=v\$\{?NAME/.test(beta) || /echo "tag=v\$NAME"/.test(beta),
    "beta tag 带 v 前缀（与正式版统一）");
  // 输入校验必须存在，且不允许任意字符
  ok(/target_version must look like/.test(beta),
    "beta 对 target_version 做格式校验（自由文本输入必须过闸）");
}

// ── 2. baseVersionCode 的下限保护 ──
{
  const gradle = read("build.gradle.kts");
  ok(/fun baseVersionCode\(\): Int = \d+/.test(gradle),
    "app/build.gradle.kts 有 baseVersionCode()");
  const m = gradle.match(/fun baseVersionCode\(\): Int = (\d+)/);
  const base = m ? parseInt(m[1], 10) : 0;
  ok(base > 0, "baseVersionCode 是正数");
  // beta 的推导公式：major*10000 + minor*100 + patch (+rev)
  // 只要 base 大于「最小可能推导值」就说明它真的在起下限作用
  ok(base >= 10000, "baseVersionCode 量级与 major*10000 一致（公式没被改坏）");
  // beta.yml 必须真的读它并抬号，否则下限只是一句注释
  const beta = read(".github/workflows/beta.yml");
  ok(/baseVersionCode\(\)/.test(beta) && /CODE=\$BASE/.test(beta),
    "beta.yml 真的读 baseVersionCode 并把 versionCode 抬到基准（否则下限只是注释）");
}

// ── 3. channelOf 的判定必须与上面的正则一致 ──
{
  const checker = read("app/src/main/java/me/bmax/apatch/util/UpdateChecker.kt");
  const fn = (() => {
    const at = checker.indexOf("private fun channelOf(");
    const end = checker.indexOf("\n    }", at);
    return at >= 0 && end > at ? checker.slice(at, end) : "";
  })();
  ok(fn.length > 0, "能定位到 channelOf()");
  ok(/startsWith\("alpha"\)/.test(fn), "channelOf 认 -alpha 前缀");
  ok(/UpdateChannel\.ALPHA/.test(fn), "channelOf 返回 ALPHA");
  ok(/UpdateChannel\.BETA/.test(fn), "channelOf 返回 BETA（未知后缀归 Beta，不能漏给 Stable）");
  ok(/UpdateChannel\.STABLE/.test(fn), "channelOf 返回 STABLE");
  // 关键：没有后缀但 prerelease=true 时必须当 Beta —— 发布流程打错 tag 时不能让
  // Stable 用户收到一个未标记的预发布
  ok(/optBoolean\("prerelease"\)/.test(fn),
    "无后缀但 prerelease=true 时按 Beta 处理（发布流程打错 tag 时保护 Stable 用户）");
}

// ── 4. 三档继承：越靠上的通道接受越多 ──
{
  const channel = read("app/src/main/java/me/bmax/apatch/util/UpdateChannel.kt");
  ok(/fun allows\(other: UpdateChannel\): Boolean = other\.ordinal <= this\.ordinal/.test(channel),
    "allows() 用 ordinal 比较实现「上级包含下级」（手写 when 容易漏一档）");
  // 枚举顺序即继承关系，顺序错了 allows 就反了
  const order = channel.match(/STABLE\("stable"\)[\s\S]*?BETA\("beta"\)[\s\S]*?ALPHA\("alpha"\)/);
  ok(order !== null, "枚举顺序是 STABLE < BETA < ALPHA（allows 依赖 ordinal）");
}

// ── 5. 强制过渡版本 ──
{
  const checker = read("app/src/main/java/me/bmax/apatch/util/UpdateChecker.kt");
  ok(/BRIDGE_MARKER/.test(checker), "有强制过渡版本标记");
  ok(/private fun pickWithMandatoryBridge/.test(checker),
    "取候选时会优先挑强制过渡版本（跳过它会让数据迁移丢失）");
  ok(/isMandatoryBridge: Boolean = false/.test(checker),
    "Status 带 isMandatoryBridge，UI 才能禁掉「跳过此版本」");
}

if (failures > 0) {
  console.error("\n" + failures + " 处问题");
  process.exit(1);
}
console.log("全部通过");
