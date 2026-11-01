/* Tivinator – Anpassungen für LG webOS (wird beim Bauen in die Oberfläche eingesetzt).
   - erkennt webOS (window.TVN_WEBOS)
   - Fernbedienung: Play/Pause/Stop/Spulen-Tasten, Zurück auf der Startseite = Beenden
   - Ersatz für CSS aspect-ratio (fehlt im Browser von webOS 22 und älter)
   - automatische Updates über GitHub (wie in der Fire-TV-App) */
(function () {
  'use strict';
  window.TVN_WEBOS = 1;
  var REPO = '__TVN_REPO__', VERSION = __TVN_VERSION__;

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
    function fit(el) {
      var r = el.__tvnAr; if (!r) return;
      var w = el.getBoundingClientRect().width; if (!w) return;
      var cs = getComputedStyle(el), h = w / r;
      if (cs.boxSizing !== 'border-box') h -= (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0) + (parseFloat(cs.borderTopWidth) || 0) + (parseFloat(cs.borderBottomWidth) || 0);
      h = Math.max(0, h);
      var cur = parseFloat(el.style.height) || 0;
      if (Math.abs(cur - h) > 0.5) el.style.height = h + 'px';
    }
    var ro = new ResizeObserver(function (en) { for (var i = 0; i < en.length; i++) fit(en[i].target); });
    function consider(el) {
      var r = ratioOf(el);
      if (r) { if (el.__tvnAr !== r) { el.__tvnAr = r; ro.observe(el); fit(el); } }
      else if (el.__tvnAr) { el.__tvnAr = 0; ro.unobserve(el); el.style.height = ''; }
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
        else if (m.target.nodeType === 1 && (m.target.__tvnAr || (m.target.matches && m.target.matches(all)))) consider(m.target);
      }
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  }

  /* ---------- Updates ---------- */
  var pending = 0;
  function db(cb) {
    try {
      var rq = indexedDB.open('tvn-web', 1);
      rq.onupgradeneeded = function () { rq.result.createObjectStore('f'); };
      rq.onsuccess = function () { cb(rq.result); };
    } catch (e) {}
  }
  function stored(cb) {
    db(function (d) {
      try { var g = d.transaction('f', 'readonly').objectStore('f').get('app'); g.onsuccess = function () { cb(g.result && g.result.v || 0); }; g.onerror = function () { cb(0); }; }
      catch (e) { cb(0); }
    });
  }
  function check() {
    if (!REPO || REPO.indexOf('/') < 0) return;
    var base = 'https://github.com/' + REPO + '/releases/latest/download/';
    fetch(base + 'version.json?t=' + Date.now(), { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (j) {
      var nv = +j.version || 0;
      if (nv <= VERSION) return;
      stored(function (have) {
        if (have >= nv) { pending = nv; return; }
        fetch(base + 'webos.html?t=' + Date.now(), { cache: 'no-store' }).then(function (r) { return r.text(); }).then(function (html) {
          if (html.length < 100000 || html.indexOf('TVN_WEBOS') < 0) return;
          db(function (d) {
            try {
              var tx = d.transaction('f', 'readwrite');
              tx.objectStore('f').put({ v: nv, html: html }, 'app');
              tx.oncomplete = function () {
                pending = nv;
                try { window.toast && window.toast('Update geladen – wird beim nächsten Öffnen von Tivinator aktiv'); } catch (e) {}
              };
            } catch (e) {}
          });
        }).catch(function () {});
      });
    }).catch(function () {});
  }
  // Kommt die App aus dem Hintergrund zurück und es liegt ein Update bereit (kein Video läuft): neu laden
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && pending && !document.getElementById('player')) location.reload();
  });

  function boot() {
    wrapBack();
    try { startAspect(); } catch (e) {}
    setTimeout(check, 20000);
    setInterval(check, 6 * 3600 * 1000);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else setTimeout(boot, 0);
})();
