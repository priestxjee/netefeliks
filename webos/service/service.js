/* Tivinator – Hintergrunddienst für LG webOS.
   Der Browser des Fernsehers sperrt Abfragen an fremde Server (CORS). Dieser Dienst
   startet einen kleinen Weiterleiter auf 127.0.0.1, über den die App deinen
   IPTV-Server, Programmführer usw. erreicht – wie die Fire-TV-App es direkt kann. */
'use strict';
var http = require('http');
var https = require('https');
var urlMod = require('url');

var CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': '*',
  'Access-Control-Expose-Headers': '*'
};
var PASS_REQ = ['user-agent', 'range', 'accept', 'accept-language', 'content-type'];
var PASS_RES = ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag', 'cache-control'];

function isM3u8(u, ct) {
  return /mpegurl/i.test(ct || '') || /\.m3u8(\?|$)/i.test(u);
}

// Relative Adressen in HLS-Playlisten absolut machen (die App leitet sie dann wieder über den Dienst)
function rewriteM3u8(text, base) {
  return text.split(/\r?\n/).map(function (line) {
    var t = line.trim();
    if (!t) return line;
    if (t.charAt(0) === '#') {
      return line.replace(/URI="([^"]+)"/g, function (all, u) {
        try { return 'URI="' + urlMod.resolve(base, u) + '"'; } catch (e) { return all; }
      });
    }
    try { return urlMod.resolve(base, t); } catch (e) { return line; }
  }).join('\n');
}

function upstream(target, req, res, hops) {
  var u;
  try { u = urlMod.parse(target); } catch (e) { u = null; }
  if (!u || !/^https?:$/.test(u.protocol)) { res.writeHead(400, CORS); return res.end('bad url'); }
  var mod = u.protocol === 'https:' ? https : http;
  var headers = {};
  PASS_REQ.forEach(function (h) { if (req.headers[h]) headers[h] = req.headers[h]; });
  if (!headers['user-agent']) headers['user-agent'] = 'Mozilla/5.0 (Web0S; Linux/SmartTV) Tivinator';
  var opts = {
    protocol: u.protocol, hostname: u.hostname, port: u.port, path: u.path,
    method: req.method === 'HEAD' ? 'HEAD' : req.method === 'POST' ? 'POST' : 'GET',
    headers: headers, rejectUnauthorized: false
  };
  var up = mod.request(opts, function (r) {
    var code = r.statusCode || 502;
    if (code >= 300 && code < 400 && r.headers.location && hops < 8) {
      r.resume();
      return upstream(urlMod.resolve(target, r.headers.location), req, res, hops + 1);
    }
    var out = {};
    Object.keys(CORS).forEach(function (k) { out[k] = CORS[k]; });
    PASS_RES.forEach(function (h) { if (r.headers[h]) out[h] = r.headers[h]; });
    out['X-Final-Url'] = target;
    if (code < 400 && isM3u8(target, r.headers['content-type'])) {
      var chunks = [];
      r.on('data', function (c) { chunks.push(c); });
      r.on('end', function () {
        var body = Buffer.from(rewriteM3u8(Buffer.concat(chunks).toString('utf8'), target), 'utf8');
        out['content-length'] = body.length;
        delete out['content-range'];
        res.writeHead(code, out);
        res.end(body);
      });
      r.on('error', function () { try { res.end(); } catch (e) {} });
      return;
    }
    res.writeHead(code, out);
    r.pipe(res);
  });
  up.setTimeout(30000, function () { up.destroy(new Error('timeout')); });
  up.on('error', function (e) {
    if (!res.headersSent) { res.writeHead(502, CORS); res.end(String(e && e.message || e)); }
    else { try { res.end(); } catch (x) {} }
  });
  req.on('close', function () { if (!(res.writableEnded || res.finished)) up.destroy(); });
  if (opts.method === 'POST') req.pipe(up); else up.end();
}

function handle(req, res) {
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
  var q = urlMod.parse(req.url, true);
  if (q.pathname === '/ping') { res.writeHead(200, CORS); return res.end('ok'); }
  var target = q.query && q.query.u;
  if (!target) { res.writeHead(400, CORS); return res.end('missing u'); }
  upstream(String(target), req, res, 0);
}

var server = null, port = 0;
function start(cb) {
  if (server && port) return cb(null, port);
  server = http.createServer(handle);
  server.on('error', function (e) { server = null; port = 0; cb(e); });
  server.listen(0, '127.0.0.1', function () { port = server.address().port; cb(null, port); });
}

module.exports = { start: start, handle: handle, rewriteM3u8: rewriteM3u8 };

// Auf dem Fernseher als webOS-Dienst anmelden
var Service = null;
try { Service = require('webos-service'); } catch (e) {}
if (Service) {
  var service = new Service('com.tivinator.app.service');
  var keep = null;
  // hält den Dienst am Leben, solange die App läuft
  try { service.activityManager.create('keepAlive', function (a) { keep = a; }); } catch (e) {}
  service.register('start', function (msg) {
    start(function (err, p) {
      if (err) msg.respond({ returnValue: false, errorText: String(err.message || err) });
      else msg.respond({ returnValue: true, port: p });
    });
  });
}
