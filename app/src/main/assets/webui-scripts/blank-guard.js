// 白屏自愈：页面渲染崩掉之后自动刷新一次，而不是等用户手动刷。
//
// ## 现象
//
// 真机（chroot + Android 15）上每隔约 40 秒整页白一次，控制台同时出现：
//   Error: conversation.input: sessions service unavailable
//   Uncaught Error: scope 'session-maybe' rendered without an installed adapter
//   slot entry crashed in 'conversation.composer.bar'
//
// 这些错误来自 dsh 前端自己的插件树重建（bundle 的 rev 每次都在变），重建期间
// sessions 服务短暂不可用，订阅者抛错，整个 #root 被卸载 —— 于是白屏。
// 重建完成后错误不再出现，但 React 树已经空了，**只有刷新才能恢复**。
//
// ## 为什么由页面自己刷
//
// 宿主侧（Kotlin）当然也能检测并 reload，但那要知道「什么算白屏」—— 而空 DOM 有
// 很多合法原因（首帧还没渲染、路由切换中、模态盖住）。在页面里判断能直接看
// #root 有没有子节点、以及它是不是**持续**为空，误判面小得多。
//
// ## 只在真的空着时才动手
//
// 三重条件同时满足才刷：① #root 存在；② 它没有任何元素子节点；③ 这个状态持续
// 超过 GRACE_MS。首帧、路由切换都远短于这个窗口，所以不会误刷。
// 另外同一个会话里最多刷 RECOVER_MAX 次 —— 如果刷新本身救不回来（比如服务真挂了），
// 无限刷新会把用户困在闪烁的页面上，比白屏更糟。
(function(){
  if (window.__dshFolkBlankGuard) return; window.__dshFolkBlankGuard = 1;

  var GRACE_MS = 6000;     // 持续空白多久才认为是真白屏
  var POLL_MS = 1500;      // 检查间隔
  var RECOVER_MAX = 3;     // 同一次页面加载内最多自动刷新次数
  var KEY = '__dshFolkBlankRecover';

  var blankSince = 0;
  var recovered = 0;
  try { recovered = Number(sessionStorage.getItem(KEY) || 0) || 0; } catch (e) { recovered = 0; }

  function rootEmpty(){
    var root = document.getElementById('root');
    // 没有 #root：可能还没解析到，也可能这根本不是 dsh 的页面 —— 都不该由我们刷
    if (!root) return false;
    // 有任何元素子节点就不算空。文本节点不算：React 卸载后常留空白文本。
    if (root.firstElementChild) return false;
    return true;
  }

  function note(msg){
    try { console.log('[DSH-Folk blank-guard] ' + msg); } catch (e) {}
  }

  function tick(){
    // 页面还没进入可用状态时不要判断：document.readyState 未完成说明还在加载
    if (document.readyState === 'loading') { blankSince = 0; return; }

    if (!rootEmpty()) { blankSince = 0; return; }

    var now = Date.now();
    if (!blankSince) { blankSince = now; return; }
    if (now - blankSince < GRACE_MS) return;

    if (recovered >= RECOVER_MAX) {
      // 刷够次数还是白：说明不是"一次性的树崩了"，别再刷，把结论留在控制台
      note('已自动恢复 ' + recovered + ' 次仍然空白，停止重试（请检查服务端日志）');
      blankSince = 0;
      return;
    }

    recovered++;
    try { sessionStorage.setItem(KEY, String(recovered)); } catch (e) {}
    note('检测到页面持续空白 ' + Math.round((now - blankSince) / 1000) + 's，自动刷新（第 ' + recovered + ' 次）');
    try { location.reload(); } catch (e) {}
  }

  // 页面正常渲染过就把计数清零：下一次白屏应该重新获得完整的重试额度。
  // 用 MutationObserver 而不是轮询判断"渲染过"，因为首帧可能在我们第一次 tick 之前。
  try {
    var seenContent = false;
    new MutationObserver(function(){
      if (seenContent) return;
      var root = document.getElementById('root');
      if (root && root.firstElementChild) {
        seenContent = true;
        recovered = 0;
        try { sessionStorage.setItem(KEY, '0'); } catch (e) {}
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}

  setInterval(tick, POLL_MS);
})();
