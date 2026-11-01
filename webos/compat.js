/* Tivinator – Anpassungen für LG webOS (wird beim Bauen in die Oberfläche eingesetzt).
   - erkennt webOS (window.TVN_WEBOS)
   - Fernbedienung: Play/Pause/Stop/Spulen-Tasten, Zurück auf der Startseite = Beenden
   - Ersatz für CSS aspect-ratio (fehlt im Browser von webOS 22 und älter)
   - automatische Updates über GitHub (wie in der Fire-TV-App) */
(function () {
  'use strict';
  window.TVN_WEBOS = 1;
  try { navigator.storage && navigator.storage.persist && navigator.storage.persist(); } catch (e) {}
  var REPO = '__TVN_REPO__', VERSION = __TVN_VERSION__;

  /* ---------- Verbindung zu fremden Servern ----------
     Der Browser des Fernsehers sperrt direkte Abfragen an deinen IPTV-Server (CORS).
     Der mitinstallierte Hintergrunddienst leitet sie über 127.0.0.1 weiter. */
  var PROXY = null, proxyWait = null;
  var DIRECT = /^https?:\/\/(127\.0\.0\.1|localhost|wsrv\.nl|fonts\.googleapis\.com|fonts\.gstatic\.com|cdnjs\.cloudflare\.com)(:\d+)?\//i;
  function needProxy(u) { return typeof u === 'string' && /^https?:\/\//i.test(u) && !DIRECT.test(u); }
  function startProxy() {
    proxyWait = new Promise(function (res) {
      var done = false;
      function fin(v) { if (!done) { done = true; PROXY = v; res(v); } }
      if (!window.PalmServiceBridge) return fin(null);
      try {
        var b = new PalmServiceBridge();
        window.__tvnBridge = b;
        b.onservicecallback = function (m) {
          try { var j = JSON.parse(m); fin(j && j.port ? 'http://127.0.0.1:' + j.port + '/p?u=' : null); } catch (e) { fin(null); }
        };
        b.call('luna://com.tivinator.app.service/start', '{}');
      } catch (e) { fin(null); }
      setTimeout(function () { fin(null); }, 10000);
    });
    return proxyWait;
  }
  startProxy();
  window.TVN_PROXY = function () { return PROXY; };

  if (window.fetch) {
    var ofetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      var u = typeof input === 'string' ? input : null;
      if (!needProxy(u)) return ofetch(input, init);
      return (PROXY ? Promise.resolve(PROXY) : proxyWait).then(function (p) {
        if (!p) return ofetch(input, init);
        return ofetch(p + encodeURIComponent(u), init).catch(function (e) {
          // Dienst wurde evtl. beendet: neu starten und einmal wiederholen
          if (init && init.signal && init.signal.aborted) throw e;
          return startProxy().then(function (p2) { return ofetch(p2 ? p2 + encodeURIComponent(u) : u, init); });
        });
      });
    };
  }
  var oopen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    if (PROXY && needProxy(u)) { var a = Array.prototype.slice.call(arguments); a[1] = PROXY + encodeURIComponent(u); return oopen.apply(this, a); }
    return oopen.apply(this, arguments);
  };

  /* ---------- Fernbedienung ---------- */
  function vid() { return document.getElementById('vid'); }
  function send(key) {
    var t = document.activeElement || document.body;
    t.dispatchEvent(new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true }));
  }
  window.addEventListener('keydown', function (e) {
    if (!e.isTrusted) return;
    var kc = e.keyCode, map = null, v;
    if (kc === 415) { v = vid(); if (v && !v.paused) map = ''; else map = 'MediaPlayPause'; }      // Play
    else if (kc === 19) { v = vid(); if (v && v.paused) map = ''; else map = 'MediaPlayPause'; }   // Pause
    else if (kc === 413) map = 'Escape';                                                         // Stop
    else if (kc === 417 && e.key !== 'MediaFastForward') map = 'MediaFastForward';
    else if (kc === 412 && e.key !== 'MediaRewind') map = 'MediaRewind';
    if (map === null) return;
    e.preventDefault(); e.stopImmediatePropagation();
    if (map) send(map);
  }, true);

  // Zurück auf der obersten Ebene: zweimal drücken = App beenden
  var exitAt = 0;
  function wrapBack() {
    if (typeof window.goBack !== 'function' || window.goBack._tvn) return;
    var orig = window.goBack;
    var w = function () {
      var r = orig.apply(this, arguments);
      if (r === false) {
        if (Date.now() - exitAt < 2500) { try { window.close(); } catch (e) {} }
        else { exitAt = Date.now(); try { window.toast && window.toast('Nochmal Zurück drücken zum Beenden'); } catch (e) {} }
      }
      return r;
    };
    w._tvn = 1;
    window.goBack = w;
  }

  /* ---------- aspect-ratio-Ersatz ---------- */
  var AR_RE = /aspect-ratio\s*:\s*([\d.]+)\s*(?:\/\s*([\d.]+))?/i;
  function arSupported() { try { return CSS.supports('aspect-ratio', '1 / 1'); } catch (e) { return false; } }
  function startAspect() {
    if (arSupported()) return;
    var rules = [], extra = '';
    var styles = document.querySelectorAll('style');
    for (var i = 0; i < styles.length; i++) {
      var css = styles[i].textContent.replace(/\/\*[\s\S]*?\*\//g, ''), m, re = /([^{}]+)\{([^{}]*)\}/g;
      while ((m = re.exec(css))) {
        var a = AR_RE.exec(m[2]); if (!a) continue;
        var r = parseFloat(a[1]) / (a[2] ? parseFloat(a[2]) : 1); if (!(r > 0)) continue;
        var sels = m[1].split(',');
        for (var j = 0; j < sels.length; j++) {
          var s = sels[j].trim(); if (!s || s.charAt(0) === '@') continue;
          if (/::?(before|after)\b/.test(s)) {
            // Pseudo-Elemente: Höhe über padding-top (beide Prozente beziehen sich auf die Breite)
            var w = /(?:^|;)\s*width\s*:\s*([\d.]+)%/.exec(m[2]);
            if (w) extra += s + '{height:0!important;padding-top:' + (parseFloat(w[1]) / r).toFixed(3) + '%}';
          } else rules.push({ sel: s, r: r });
        }
      }
    }
    if (extra) { var st = document.createElement('style'); st.textContent = extra; document.head.appendChild(st); }
    var all = rules.map(function (x) { return x.sel; }).concat(['[style*="aspect-ratio"]']).join(',');
    try { document.querySelector(all); } catch (e) { all = '[style*="aspect-ratio"]'; }

    function ratioOf(el) {
      var st = el.getAttribute('style'), a = st && AR_RE.exec(st);
      if (a) return parseFloat(a[1]) / (a[2] ? parseFloat(a[2]) : 1);
      var r = 0;
      for (var i = 0; i < rules.length; i++) { try { if (el.matches(rules[i].sel)) r = rules[i].r; } catch (e) {} }
      return r;
    }
    // Höhe nur im ResizeObserver setzen (kein erzwungenes Layout pro Karte → flüssig)
    function fit(el, w) {
      var r = el.__tvnAr; if (!r || !w) return;
      if (el.__tvnPad == null) {
        var cs = getComputedStyle(el);
        el.__tvnPad = cs.boxSizing === 'border-box' ? 0 : (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0) + (parseFloat(cs.borderTopWidth) || 0) + (parseFloat(cs.borderBottomWidth) || 0);
      }
      var h = Math.max(0, Math.round((w / r - el.__tvnPad) * 10) / 10);
      if (el.__tvnH !== h) { el.__tvnH = h; el.style.height = h + 'px'; }
    }
    var ro = new ResizeObserver(function (en) { for (var i = 0; i < en.length; i++) { var t = en[i].target; fit(t, t.offsetWidth); } });
    function consider(el) {
      var r = ratioOf(el);
      if (r) { if (el.__tvnAr !== r) { el.__tvnAr = r; el.__tvnH = null; ro.unobserve(el); ro.observe(el); } }
      else if (el.__tvnAr) { el.__tvnAr = 0; el.__tvnH = null; ro.unobserve(el); el.style.height = ''; }
    }
    function scan(root) {
      if (root.nodeType !== 1) return;
      try { if (root.matches(all)) consider(root); } catch (e) {}
      var l = root.querySelectorAll(all);
      for (var i = 0; i < l.length; i++) consider(l[i]);
    }
    scan(document.documentElement);
    new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (m.type === 'childList') { for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j]); }
        else if (m.target.nodeType === 1 && (m.target.__tvnAr || (m.target.classList && m.target.classList.contains('hero')))) consider(m.target); // nur wenige Elemente prüfen (Fokuswechsel ändern ständig Klassen)
      }
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  }

  /* ---------- Bilder: verkleinert laden (der Fire TV macht das selbst, der LG nicht) ---------- */
  var TMDB = /^(https?:\/\/image\.tmdb\.org\/t\/p\/)(original|w\d+)(\/.*)$/i, SIZES = [92, 154, 185, 342, 500, 780, 1280];
  function smallImg(u, w) {
    if (!u || !/^https?:/i.test(u) || /^https?:\/\/(wsrv\.nl|nfk\.img)\//i.test(u)) return u;
    w = Math.max(64, Math.min(1280, +w || 300));
    var m = TMDB.exec(u);
    if (m) { var s = 1280; for (var i = 0; i < SIZES.length; i++) if (SIZES[i] >= w) { s = SIZES[i]; break; } return m[1] + 'w' + s + m[3]; }
    return 'https://wsrv.nl/?url=' + encodeURIComponent(u) + '&w=' + w + '&we&q=72&output=jpg&default=' + encodeURIComponent(u);
  }
  window.TVN_IMG = smallImg;
  (function () {
    var st = document.createElement('style');
    st.textContent = '::-webkit-scrollbar{width:0!important;height:0!important;display:none!important}html,body{-webkit-user-select:none;user-select:none}*{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}*,*::before,*::after{transition:none!important;scroll-behavior:auto!important}.m4::before,.m0::before,.m2::after,.m3::before{filter:none!important;box-shadow:none!important}input,textarea{-webkit-user-select:text;user-select:text}';
    (document.head || document.documentElement).appendChild(st);
  })();

  /* ---------- Updates (direkt am Fernseher, wie in der Fire-TV-App) ----------
     Die App fragt über UPD (index.html) nach; „Jetzt updaten“ lädt die neue Oberfläche von GitHub,
     speichert sie auf dem Fernseher und startet neu. Profile und Login bleiben erhalten. */
  function savePage(v, html) {
    return new Promise(function (res, rej) {
      try {
        var rq = indexedDB.open('tvn-web', 1);
        rq.onupgradeneeded = function () { rq.result.createObjectStore('f'); };
        rq.onerror = function () { rej(new Error('Speicher nicht verfügbar')); };
        rq.onsuccess = function () {
          try {
            var tx = rq.result.transaction('f', 'readwrite');
            tx.oncomplete = function () { res(); };
            tx.onerror = tx.onabort = function () { rej(new Error('Speichern fehlgeschlagen')); };
            tx.objectStore('f').put({ v: v, html: html }, 'app');
          } catch (e) { rej(e); }
        };
      } catch (e) { rej(e); }
    });
  }
  window.TVN_UPD = {
    ver: function () { return VERSION; },
    run: function (v, st) {
      var base = 'https://github.com/' + REPO + '/releases/latest/download/';
      return fetch(base + 'webos.html?t=' + Date.now(), { cache: 'no-store' })
        .then(function (r) { if (!r.ok) throw new Error('Download fehlgeschlagen (' + r.status + ')'); return r.text(); })
        .then(function (html) {
          if (html.length < 100000 || html.indexOf('TVN_WEBOS') < 0 || html.indexOf('</html>') < 0) throw new Error('Datei unvollständig');
          if (st) st.textContent = 'Wird installiert…';
          return savePage(+v || VERSION + 1, html);
        })
        .then(function () { setTimeout(function () { location.reload(); }, 300); });
    }
  };

  function boot() {
    wrapBack();
    try { startAspect(); } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else setTimeout(boot, 0);
})();
