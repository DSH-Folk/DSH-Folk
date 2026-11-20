/**
 * tools/_paths.js 的行为门禁。
 *
 * ## 为什么要给"门禁的辅助模块"也写门禁
 *
 * _paths.js 决定所有 check-*.js 读的是哪个文件。它一旦解析错，**大量断言会静默失效** ——
 * 读不到就抛是好事，但如果它"读到了错的文件"或者"该失败时没失败"，40 个脚本会一起假绿。
 * 这类故障不会有任何报错，只会让门禁变成摆设。所以它自己的行为必须被钉住。
 *
 * ## 三条不变量
 *
 * 1. **零配置跨模块迁移**：文件从 app/ 搬到 core-kernel/（包路径不变）后，
 *    老脚本传的完整路径仍能解析 —— 不用在 MOVED 里登记。
 * 2. **exists() 只回答字面路径**：不做模块回退。否则反向断言
 *    （"这个类不该在 displayserver 里"）会把 app/ 里的同名文件当命中，判成"存在"。
 * 3. **找不到必须抛**：绝不返回空串（空串会让 ok(!/x/.test(src)) 全部假绿）。
 */

const fs = require("fs");
const path = require("path");
const os = require("os");

const ROOT = path.resolve(__dirname, "..");
let pass = 0;
const failures = [];
const ok = (cond, msg) => {
  if (cond) { pass++; console.log("  ✓ " + msg); }
  else { failures.push(msg); console.log("  ✗ " + msg); }
};

console.log("── _paths.js 解析行为 ──");
{
  const p = require("./_paths.js");

  // 1) 老脚本传的完整路径能解析（重构前就存在的形态）
  const full = p.resolve("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt");
  ok(fs.existsSync(full), "完整源码路径能解析到真实文件");
  ok(full.endsWith(path.join("dsh", "DshEnv.kt")), "解析结果指向 DshEnv.kt");

  // 2) 逻辑名也能解析（新写法）
  const logical = p.resolve("dsh/DshEnv.kt");
  ok(fs.existsSync(logical), "逻辑名能解析到真实文件");
  ok(logical === full, "两种写法解析到同一个文件");

  // 3) 非源码路径不受模块化影响
  ok(p.exists("build.gradle.kts"), "仓库根的 build.gradle.kts 可解析");
  ok(p.exists("app/proguard-rules.pro"), "app 下的 proguard 规则可解析");
  ok(p.exists("app/src/main/assets/dsh-folk-host.mjs"), "assets 里的文件可解析");

  // 3b) step 1 必须真的查"直接存在"：非源码路径（assets / gradle / manifest）
  //     只有这条路能走，step 3 的模块搜索永远找不到它们。
  //     钉住它，否则有人删掉 step 1 时其它断言仍可能因巧合通过。
  ok(p.resolve("app/src/main/assets/dsh-folk-host.mjs")
      === path.join(ROOT, "app/src/main/assets/dsh-folk-host.mjs"),
    "step 1 对非源码路径直接命中（不经模块搜索）");
  ok(p.resolve("build.gradle.kts") === path.join(ROOT, "build.gradle.kts"),
    "step 1 对仓库根文件直接命中");

  // 4) 前缀剥离：只剥登记过的模块根
  ok(p.stripSourcePrefix("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt") === "dsh/DshEnv.kt",
    "app 源码前缀被剥离成包路径");
  ok(p.stripSourcePrefix("core-kernel/src/main/java/me/bmax/apatch/dsh/DshEnv.kt") === "dsh/DshEnv.kt",
    "core-kernel 源码前缀同样被剥离");
  ok(p.stripSourcePrefix("displayserver/src/main/java/me/bmax/apatch/display/X.java")
      === "displayserver/src/main/java/me/bmax/apatch/display/X.java",
    "displayserver 不是模块源码根，不剥离（否则两棵树会被混成一个）");
  ok(p.stripSourcePrefix("dsh/DshEnv.kt") === "dsh/DshEnv.kt", "已是逻辑名时原样返回");

  // 5) exists 不做模块回退 —— 这条是反向断言的命门
  //
  //    区分办法：把路径写成 core-kernel、而文件实际在 app。
  //    resolve 会剥前缀逐模块找，命中 app；exists 只回答"这个确切路径存在吗"，返回 false。
  //
  //    为什么重要：check-display-server.js 里有一批反向断言
  //    （"这个类只该在 app 源码树里，不该在 displayserver 里"）。exists 一旦会回退，
  //    那些"不该存在"会被判成"存在"，断言直接假红（反过来也会让该报的错漏报）。
  ok(p.resolve("core-kernel/src/main/java/me/bmax/apatch/dsh/DshEnv.kt")
      === p.resolve("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt"),
    "resolve 会跨模块回退（包路径相同就命中）");
  ok(!p.exists("core-kernel/src/main/java/me/bmax/apatch/dsh/DshEnv.kt"),
    "exists 对『文件不在这里』返回 false（不回退到 app）");
  ok(p.exists("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt"),
    "exists 对真实路径返回 true");

  // 6) 找不到必须抛，不能返回空串
  let threw = false;
  try { p.resolve("dsh/ThisFileDoesNotExist.kt"); } catch (_) { threw = true; }
  ok(threw, "找不到文件时抛异常（返回空串会让反向断言全部假绿）");

  let readThrew = false;
  try { p.read("dsh/ThisFileDoesNotExist.kt"); } catch (_) { readThrew = true; }
  ok(readThrew, "read 找不到文件时也抛");

  ok(p.exists("dsh/ThisFileDoesNotExist.kt") === false, "exists 对不存在的文件返回 false 而不是抛");
}

// 7) 零配置迁移：把文件临时搬到另一个模块，不填 MOVED，仍能解析
console.log("\n── 零配置跨模块迁移 ──");
{
  const rel = "app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt";
  const src = path.join(ROOT, rel);
  const dstDir = path.join(ROOT, "core-kernel/src/main/java/me/bmax/apatch/dsh");
  const dst = path.join(dstDir, "DshEnv.kt");

  if (!fs.existsSync(src)) {
    console.log("  ! 跳过：源文件不在预期位置（迁移已发生？）");
  } else {
    const body = fs.readFileSync(src, "utf8");
    let moved = false;
    try {
      fs.mkdirSync(dstDir, { recursive: true });
      fs.writeFileSync(dst, body);
      fs.unlinkSync(src);
      moved = true;

      // 清掉 require 缓存，重新加载（MOVED 表此时没有这条）
      delete require.cache[require.resolve("./_paths.js")];
      const fresh = require("./_paths.js");
      const hit = fresh.resolve(rel);
      ok(hit === dst, "文件搬到 core-kernel 后，完整老路径仍解析到新位置（无需登记 MOVED）");
      ok(fresh.read(rel) === body, "read 读到的是搬迁后的内容");
    } catch (e) {
      ok(false, "迁移模拟抛出异常：" + e.message);
    } finally {
      if (moved) {
        fs.writeFileSync(src, body);
        try { fs.unlinkSync(dst); } catch (_) {}
        try { fs.rmdirSync(dstDir); } catch (_) {}
        // 上层目录可能也是刚建的
        for (const d of [
          "core-kernel/src/main/java/me/bmax/apatch",
          "core-kernel/src/main/java/me/bmax",
          "core-kernel/src/main/java/me",
          "core-kernel/src/main/java",
          "core-kernel/src/main",
          "core-kernel/src",
          "core-kernel",
        ]) {
          try { fs.rmdirSync(path.join(ROOT, d)); } catch (_) {}
        }
      }
      delete require.cache[require.resolve("./_paths.js")];
    }
  }
}

console.log("");
if (failures.length) {
  console.log("失败 " + failures.length + " 条：");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
}
console.log("全部通过（" + pass + " 条）");
