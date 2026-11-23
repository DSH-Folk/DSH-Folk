#!/usr/bin/env node
/**
 * 检查「Compose / Android 符号用了但没 import」。
 *
 * ## 为什么需要这个脚本
 *
 * 本项目本地没有 JDK，唯一的真编译器是 CI。`kt-lint.js` 只查括号配平与词法，
 * 查不出「用了某个符号但没 import」—— 而这恰好是最常见的 CI 失败原因：
 * 连续三轮 CI 分别因缺 `clickable`、缺 `padding`、缺 `FontFamily` 被打回，
 * 每轮代价是改一次 + 等 8 分钟。
 *
 * 这个脚本维护一张「常见 Compose/Android 符号 → 正确 import 路径」的对照表，
 * 对每个 .kt 文件做纯文本推断：
 *   1. 剥掉 import 行、注释、字符串，得到"真正在用的代码"；
 *   2. 对照表里的符号若在代码里出现，且文件没有对应的 import → 报错。
 *
 * ## 局限（必须说清楚）
 *
 * - 只看对照表里的符号，表外的不管；
 * - 不做类型推断：同名的局部变量、参数、成员会被误判成"用了这个符号"；
 *   所以对局部声明做了一次排除（`val X` / `fun X` / `class X`）；
 * - 因此它报的**不一定**是错（可能误报），但漏报的它管不了。
 * 定位是「把最常见的漏 import 提前到本地」，不是替代 CI。
 *
 * 误报时应当**修断言/加排除**，而不是删掉这条检查 —— 它拦下的都是真会红 CI 的。
 */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

/** 常见符号 → 正确 import 路径。新增符号时往这里加。 */
const SYMBOLS = {
  // ── foundation.layout ──
  Arrangement: 'androidx.compose.foundation.layout.Arrangement',
  Box: 'androidx.compose.foundation.layout.Box',
  Column: 'androidx.compose.foundation.layout.Column',
  Row: 'androidx.compose.foundation.layout.Row',
  Spacer: 'androidx.compose.foundation.layout.Spacer',
  PaddingValues: 'androidx.compose.foundation.layout.PaddingValues',
  WindowInsets: 'androidx.compose.foundation.layout.WindowInsets',
  fillMaxSize: 'androidx.compose.foundation.layout.fillMaxSize',
  fillMaxWidth: 'androidx.compose.foundation.layout.fillMaxWidth',
  fillMaxHeight: 'androidx.compose.foundation.layout.fillMaxHeight',
  height: 'androidx.compose.foundation.layout.height',
  heightIn: 'androidx.compose.foundation.layout.heightIn',
  width: 'androidx.compose.foundation.layout.width',
  widthIn: 'androidx.compose.foundation.layout.widthIn',
  size: 'androidx.compose.foundation.layout.size',
  padding: 'androidx.compose.foundation.layout.padding',
  offset: 'androidx.compose.foundation.layout.offset',
  wrapContentSize: 'androidx.compose.foundation.layout.wrapContentSize',
  requiredSize: 'androidx.compose.foundation.layout.requiredSize',
  navigationBars: 'androidx.compose.foundation.layout.navigationBars',
  statusBars: 'androidx.compose.foundation.layout.statusBars',
  safeDrawing: 'androidx.compose.foundation.layout.safeDrawing',
  displayCutout: 'androidx.compose.foundation.layout.displayCutout',
  asPaddingValues: 'androidx.compose.foundation.layout.asPaddingValues',
  calculateStartPadding: 'androidx.compose.foundation.layout.calculateStartPadding',
  calculateEndPadding: 'androidx.compose.foundation.layout.calculateEndPadding',
  statusBarsPadding: 'androidx.compose.foundation.layout.statusBarsPadding',
  navigationBarsPadding: 'androidx.compose.foundation.layout.navigationBarsPadding',
  windowInsetsPadding: 'androidx.compose.foundation.layout.windowInsetsPadding',
  imePadding: 'androidx.compose.foundation.layout.imePadding',
  imeAnimationTarget: 'androidx.compose.foundation.layout.imeAnimationTarget',
  // ── foundation ──
  clickable: 'androidx.compose.foundation.clickable',
  background: 'androidx.compose.foundation.background',
  verticalScroll: 'androidx.compose.foundation.verticalScroll',
  horizontalScroll: 'androidx.compose.foundation.horizontalScroll',
  rememberScrollState: 'androidx.compose.foundation.rememberScrollState',
  LazyColumn: 'androidx.compose.foundation.lazy.LazyColumn',
  LazyRow: 'androidx.compose.foundation.lazy.LazyRow',
  items: 'androidx.compose.foundation.lazy.items',
  // ── shape / draw ──
  RoundedCornerShape: 'androidx.compose.foundation.shape.RoundedCornerShape',
  CircleShape: 'androidx.compose.foundation.shape.CircleShape',
  clip: 'androidx.compose.ui.draw.clip',
  alpha: 'androidx.compose.ui.draw.alpha',
  // ── ui ──
  Alignment: 'androidx.compose.ui.Alignment',
  Modifier: 'androidx.compose.ui.Modifier',
  dp: 'androidx.compose.ui.unit.dp',
  sp: 'androidx.compose.ui.unit.sp',
  Dp: 'androidx.compose.ui.unit.Dp',
  Color: 'androidx.compose.ui.graphics.Color',
  LocalContext: 'androidx.compose.ui.platform.LocalContext',
  LocalDensity: 'androidx.compose.ui.platform.LocalDensity',
  LocalLayoutDirection: 'androidx.compose.ui.platform.LocalLayoutDirection',
  stringResource: 'androidx.compose.ui.res.stringResource',
  painterResource: 'androidx.compose.ui.res.painterResource',
  FontFamily: 'androidx.compose.ui.text.font.FontFamily',
  FontWeight: 'androidx.compose.ui.text.font.FontWeight',
  TextOverflow: 'androidx.compose.ui.text.style.TextOverflow',
  TextAlign: 'androidx.compose.ui.text.style.TextAlign',
  // ── material3 ──
  AlertDialog: 'androidx.compose.material3.AlertDialog',
  Badge: 'androidx.compose.material3.Badge',
  BadgedBox: 'androidx.compose.material3.BadgedBox',
  Button: 'androidx.compose.material3.Button',
  Card: 'androidx.compose.material3.Card',
  Checkbox: 'androidx.compose.material3.Checkbox',
  Divider: 'androidx.compose.material3.Divider',
  HorizontalDivider: 'androidx.compose.material3.HorizontalDivider',
  Icon: 'androidx.compose.material3.Icon',
  IconButton: 'androidx.compose.material3.IconButton',
  LinearProgressIndicator: 'androidx.compose.material3.LinearProgressIndicator',
  MaterialTheme: 'androidx.compose.material3.MaterialTheme',
  OutlinedButton: 'androidx.compose.material3.OutlinedButton',
  OutlinedTextField: 'androidx.compose.material3.OutlinedTextField',
  Scaffold: 'androidx.compose.material3.Scaffold',
  Slider: 'androidx.compose.material3.Slider',
  Surface: 'androidx.compose.material3.Surface',
  Switch: 'androidx.compose.material3.Switch',
  Text: 'androidx.compose.material3.Text',
  TextButton: 'androidx.compose.material3.TextButton',
  TopAppBar: 'androidx.compose.material3.TopAppBar',
  // ── runtime ──
  Composable: 'androidx.compose.runtime.Composable',
  DisposableEffect: 'androidx.compose.runtime.DisposableEffect',
  LaunchedEffect: 'androidx.compose.runtime.LaunchedEffect',
  SideEffect: 'androidx.compose.runtime.SideEffect',
  getValue: 'androidx.compose.runtime.getValue',
  key: 'androidx.compose.runtime.key',
  mutableFloatStateOf: 'androidx.compose.runtime.mutableFloatStateOf',
  mutableIntStateOf: 'androidx.compose.runtime.mutableIntStateOf',
  mutableStateListOf: 'androidx.compose.runtime.mutableStateListOf',
  mutableStateOf: 'androidx.compose.runtime.mutableStateOf',
  remember: 'androidx.compose.runtime.remember',
  setValue: 'androidx.compose.runtime.setValue',
  // ── animation ──
  AnimatedVisibility: 'androidx.compose.animation.AnimatedVisibility',
  animateDpAsState: 'androidx.compose.animation.core.animateDpAsState',
  animateFloatAsState: 'androidx.compose.animation.core.animateFloatAsState',
  slideInVertically: 'androidx.compose.animation.slideInVertically',
  slideOutVertically: 'androidx.compose.animation.slideOutVertically',
};

/**
 * 这些符号是 Kotlin 成员（`ColumnScope.weight` 之类）或常见局部名，
 * 出现在代码里不代表需要 import。列在这里跳过。
 */
const SKIP = new Set([
  // Kotlin 成员（ColumnScope.weight / RowScope.align），不需要 import
  'weight', 'align', 'items',
  // 常见局部名 / 属性名，出现在代码里不代表要 import：
  // `key` 在 Map 遍历、SharedPreferences 读写里到处都是（`for (key in map)`），
  // 把它当 Compose 的 key() 会造成大面积误报。
  // 这些是「Compose 扩展函数」与「同名属性」撞名的：
  // `colorScheme.background`（ColorScheme 属性）vs `Modifier.background(...)`，
  // `Color.alpha` vs `Modifier.alpha(...)`，`Modifier.clip(...)` vs `clipData.clip`。
  // 纯文本分不开（驼峰标识符后面跟点，与包名限定名同形），只能跳过。
  // 代价是这四类漏 import 抓不到 —— 接受，因为它们的误报会把整张检查表淹掉。
  'key', 'size', 'padding', 'height', 'width', 'offset', 'alpha', 'clip',
  'background', 'divider', 'sp', 'dp', 'Color',
]);

const MODULES = ['app', 'core-kernel', 'core-res', 'ext-api', 'ext-workflow', 'ext-display', 'ext-userscripts', 'ext-backup'];

/**
 * 按文件排除的「同名但不同物」符号。
 *
 * 例：`ext-display` 的投屏模块用的是 `android.view.Surface`（原生渲染目标），
 * 与 Compose 的 `material3.Surface` 同名但毫无关系。这类只能按文件排除 ——
 * 按符号排除会让真正的漏 import 溜过去。
 */
const FILE_SKIP = {
  'ext-display/src/main/java/me/bmax/apatch/dsh/display/DisplayMirror.kt': ['Surface'],
  'ext-display/src/main/java/me/bmax/apatch/dsh/display/DisplayVideoSink.kt': ['Surface'],
  // 这个文件用的是 `Icons.Filled.Badge`（图标），不是 `material3.Badge`（组件）。
  'app/src/main/java/me/bmax/apatch/ui/screen/settings/BehaviorSettings.kt': ['Badge'],
};

function walk(dir, out) {
  if (!fs.existsSync(dir)) return;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.kt')) out.push(p);
  }
}

/** 剥掉 import / 注释 / 字符串，只留"真正在用的代码"。 */
function codeOnly(src) {
  return src
    .replace(/^import .*$/gm, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .replace(/"""([\s\S]*?)"""/g, '""')
    .replace(/"(\\.|[^"\\])*"/g, '""')
    .replace(/'(\\.|[^'\\])*'/g, "''");
}

const errors = [];
const files = [];
for (const m of MODULES) walk(path.join(ROOT, m, 'src'), files);

for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  const code = codeOnly(src);
  const rel = path.relative(ROOT, f);
  for (const [name, imp] of Object.entries(SYMBOLS)) {
    if (SKIP.has(name)) continue;
    const skipHere = FILE_SKIP[rel];
    if (skipHere && skipHere.indexOf(name) >= 0) continue;
    // ── 这个符号在代码里真的被「当成符号用」了吗？──
    //
    // 三个必须排除的误报来源，都是纯文本推断绕不过去的：
    //
    // 1. 全限定名调用：`androidx.compose.foundation.layout.Row(...)`、
    //    `androidx.compose.ui.platform.LocalDensity.current` —— 本来就不需要 import。
    //    判据：符号前面紧跟「小写开头的包名段 + 点」。
    // 2. 枚举成员：`Role.Switch` 里的 Switch 是无障碍角色枚举，与 Compose 的 Switch 组件无关。
    //    **不能**用「大写开头.符号」笼统排除 —— `Icons.Filled.Add`、`Modifier.padding`
    //    也是这个形状，一排除就把真漏检放过去了。所以只认枚举类白名单。
    // 3. 局部声明：`fun SwitchItem(` 里的 Switch 只是名字的一部分（已由 \\b 保证）。
    const qualified = new RegExp('[a-z][A-Za-z0-9_]*(\\.[a-z][A-Za-z0-9_]*)+\\.' + name + '\\b').test(code);
    if (qualified) continue;
    // 只放**枚举类**。`Icons` 不能放：`Icons.Filled.Add` / `Icons.Outlined.Refresh`
    // 里的 Add/Refresh 正是需要 import 的图标属性，放进去等于把真漏检全部放过。
    const memberHosts = ['Role'];
    const asMember = memberHosts.some(function (h) {
      return new RegExp(h + '\\.[A-Za-z0-9_.]*\\b' + name + '\\b').test(code);
    });
    if (asMember) continue;
    // 尾随 lambda 也算使用：`Badge { Text(..) }`、`Scaffold { .. }` 是很常见的写法，
    // 第一版只认 `(` / `.` / `>` / `,` / `)`，把 `Badge {` 漏掉了。
    const used = new RegExp('(^|[^A-Za-z0-9_])' + name + '\\s*(\\(|\\{|\\.|>|,|\\))').test(code);
    if (!used) continue;
    // 本文件自己声明了同名符号 → 不是 import 问题
    if (new RegExp('(fun|val|var|class|object|interface|typealias)\\s+' + name + '\\b').test(code)) continue;
    // 同包内可能有同名顶层符号，这种情况无法从文本判断，跳过
    // import 检测：把 import 行拆成「路径 + 别名」，三种写法都认：
    //   import a.b.C
    //   import a.b.C as D
    //   import a.b.*
    // 用 includes 直接比对整行会把别名与通配符写法误判成「没导入」。
    const importLines = src.split("\n").filter(function (l) { return l.indexOf("import ") === 0; });
    const imported = importLines.some(function (l) {
      const body = l.slice("import ".length).trim();
      const parts = body.split(/\s+as\s+/);
      const p = parts[0].trim();
      if (p === imp) return true;
      if (p.endsWith(".*") && imp.indexOf(p.slice(0, -1)) === 0) return true;
      return false;
    });
    if (imported) continue;
    errors.push(rel + ': 用了 ' + name + ' 但没 import ' + imp);
  }
}

// ── 图标专项检查 ──
//
// 图标符号不放进 SYMBOLS 表：`Close` / `Info` / `Settings` 这些名字太通用
// （撞 `android.provider.Settings`、撞局部变量、撞方法名），逐个核对路径变体
// （filled / outlined / rounded / automirrored）又极易误报。
// 改成只查一件事：用了 `Icons.<Style>.<Name>` 就必须有 `import ...icons.<style>.<Name>`。
// 这是唯一确定、不会误判的形态 —— 因为 `Icons.X.Y` 只可能是图标。
const iconUse = /Icons\.(Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Za-z0-9_]+)/g;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  const code = codeOnly(src);
  const rel = path.relative(ROOT, f);
  const seen = new Set();
  let m;
  iconUse.lastIndex = 0;
  while ((m = iconUse.exec(code)) !== null) {
    const style = m[1];
    const nm = m[2];
    const key2 = style + '.' + nm;
    if (seen.has(key2)) continue;
    seen.add(key2);
    // AutoMirrored 是另一条路径（automirrored.<style>.<Name>），单独判
    const direct = 'androidx.compose.material.icons.' + style.toLowerCase() + '.' + nm;
    const mirrored = 'androidx.compose.material.icons.automirrored.' + style.toLowerCase() + '.' + nm;
    const importLines2 = src.split("\n").filter(function (l) { return l.indexOf("import ") === 0; });
    // 通配符导入也算：`import androidx.compose.material.icons.outlined.*` 覆盖了
    // 该 style 下的全部图标，这是项目里常见的写法（HomeCircle / HomeStats 都是）。
    const wildcard = 'androidx.compose.material.icons.' + style.toLowerCase() + '.*';
    const wildcardMirrored = 'androidx.compose.material.icons.automirrored.' + style.toLowerCase() + '.*';
    const has = importLines2.some(function (l) {
      const body = l.slice("import ".length).trim().split(/\s+as\s+/)[0].trim();
      return body === direct || body === mirrored ||
        body === wildcard || body === wildcardMirrored;
    });
    if (!has) {
      errors.push(rel + ': 用了 Icons.' + style + '.' + nm + ' 但没 import ' + direct);
    }
  }
}

if (errors.length) {
  console.error('✗ ' + errors.length + ' 处「用了但没 import」（CI 会报 Unresolved reference）：');
  for (const e of errors) console.error('   ' + e);
  console.error('\n修复：补上对应的 import 行；若是误报（同名局部变量），把该符号加进本脚本的 SKIP。');
  process.exit(1);
}
console.log('✓ import 检查通过（' + files.length + ' 个文件，' + Object.keys(SYMBOLS).length + ' 个符号）');
