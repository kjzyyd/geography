"""
内置 HTTP 服务器 (基于标准库 http.server)
端口 9178

接口:
  POST /location
       form/json: device_id, latitude, longitude, [accuracy, provider, time]
  GET  /location           -> 全部设备最新位置 JSON
  GET  /location/<id>      -> 指定设备最新位置 JSON
  GET  /health             -> OK
  GET  /map                -> 简易内置地图页
  DELETE /location         -> 清空
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.parse import unquote

from location_store import LocationStore, LocationRecord


DEFAULT_PORT = 9178
PUBLIC_BASE = "http://kjzyyd.fucku.top"


MAP_HTML = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>位置中转 - 实时地图</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{margin:0;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;}
#header{padding:10px 14px;background:#1976d2;color:#fff;display:flex;justify-content:space-between;align-items:center;}
#map{height:calc(100vh - 52px);}
#info{font-size:12px;opacity:.9}
</style>
</head>
<body>
<div id="header">
  <div><b>位置中转服务</b> · 实时地图</div>
  <div id="info">初始加载...</div>
</div>
<div id="map"></div>
<script>
var map = L.map('map').setView([30.2741, 120.1551], 12);
L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{
  subdomains:['1','2','3','4'],maxZoom:19,attribution:'© 高德地图'
}).addTo(map);
var markers = {};
var notes = {};
function fmtTime(ts){if(!ts)return '';var d=new Date(ts*1000);return d.toLocaleString('zh-CN');}
function refresh(){
  Promise.all([
    fetch('/location').then(r=>r.json()).catch(()=>({})),
    fetch('/notes').then(r=>r.json()).catch(()=>({}))
  ]).then(function(resps){
    var data = resps[0] || {};
    notes = resps[1] || {};
    var items = Object.values(data || {});
    document.getElementById('info').textContent = '设备数: '+items.length+' · 最后刷新: '+new Date().toLocaleTimeString();
    items.forEach(it=>{
      if(!(it.latitude&&it.longitude)) return;
      var ll=[it.latitude, it.longitude];
      var note = notes[it.device_id] || '';
      // 标题: 有备注则显示「备注名 (device_id)」
      var title = note ? (note + ' (' + it.device_id + ')') : it.device_id;
      var html='<b>'+title+'</b><br>'+it.latitude.toFixed(6)+', '+it.longitude.toFixed(6);
      if(note) html+='<br>备注: '+note;
      if(it.time_str) html+='<br>'+it.time_str;
      if(it.accuracy) html+='<br>精度±'+Math.round(it.accuracy)+'m';
      // 使用 device_id 作为内部 key，避免同名备注互相覆盖
      var key = it.device_id;
      if(markers[key]){
        markers[key].setLatLng(ll);
        markers[key].bindPopup(html);
        // leaflet 没有直接 setTitle, 用 bindTooltip 显示悬浮提示
        markers[key].unbindTooltip();
        markers[key].bindTooltip(title);
      }else{
        markers[key]=L.marker(ll).addTo(map).bindPopup(html);
        markers[key].bindTooltip(title);
        map.panTo(ll);
      }
    });
  }).catch(()=>{});
}
refresh();
setInterval(refresh, 30000);
</script>
</body>
</html>
"""


def _make_handler(store: LocationStore, log_cb):

    class Handler(BaseHTTPRequestHandler):
        server_version = "PcDataRelay/1.0"

        def log_message(self, fmt, *args):  # 替换内置 stderr 日志
            try:
                log_cb("[HTTP] %s - %s" % (self.address_string(), fmt % args))
            except Exception:
                pass

        def _send_json(self, obj, code=200):
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def _send_html(self, html, code=200):
            body = html.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/health" or path == "/healthz":
                return self._send_json({"ok": True, "port": DEFAULT_PORT})
            if path == "/" or path == "/index.html":
                return self._send_html(MAP_HTML)
            if path == "/map":
                return self._send_html(MAP_HTML)
            if path == "/location":
                return self._send_json(store.snapshot_dict())
            if path.startswith("/location/"):
                did = unquote(path[len("/location/"):])
                rec = store.get(did)
                if rec is None:
                    return self._send_json({"error": "not found"}, 404)
                from dataclasses import asdict
                return self._send_json(asdict(rec))
            if path == "/notes":
                # 返回全部设备备注 {device_id: note}
                return self._send_json(store.get_all_notes())
            return self._send_json({"error": "not found", "path": path}, 404)

        def do_DELETE(self):
            path = self.path.split("?", 1)[0]
            if path == "/location":
                n = store.count()
                store.clear()
                return self._send_json({"ok": True, "cleared": n})
            return self._send_json({"error": "not found"}, 404)

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.end_headers()

        def do_POST(self):
            path = self.path.split("?", 1)[0]

            payload = None
            ctype = self.headers.get("Content-Type", "")
            length = int(self.headers.get("Content-Length") or "0")

            if length > 0:
                raw = self.rfile.read(length)
            else:
                raw = b""

            if "application/json" in ctype.lower():
                try:
                    payload = json.loads(raw.decode("utf-8"))
                except Exception as e:
                    return self._send_json({"error": "bad json: " + str(e)}, 400)
            else:
                # form 或 query
                try:
                    payload = {}
                    if raw:
                        text = raw.decode("utf-8")
                        for kv in text.split("&"):
                            if "=" in kv:
                                k, v = kv.split("=", 1)
                                payload[unquote(k)] = unquote(v)
                    # query string 合并
                    qs = self.path.split("?", 1)[1] if "?" in self.path else ""
                    for kv in qs.split("&"):
                        if "=" in kv:
                            k, v = kv.split("=", 1)
                            payload[unquote(k)] = unquote(v)
                except Exception:
                    payload = {}

            if not payload:
                return self._send_json({"error": "empty body"}, 400)

            # POST /notes: 设置设备备注, JSON {device_id, note}
            if path == "/notes":
                did = str(payload.get("device_id") or payload.get("id") or "").strip()
                if not did:
                    return self._send_json({"error": "device_id missing"}, 400)
                note = str(payload.get("note") or payload.get("remark") or payload.get("value") or "")
                store.set_note(did, note)
                log_cb("[HTTP] 设置备注: %s -> %s" % (did, note or "(清除)"))
                return self._send_json({"ok": True, "device_id": did, "note": store.get_note(did)})

            if path != "/location":
                return self._send_json({"error": "not found"}, 404)

            device_id = str(payload.get("device_id") or payload.get("id") or "").strip()
            try:
                lat = float(payload.get("latitude") or payload.get("lat"))
                lng = float(payload.get("longitude") or payload.get("lng") or payload.get("lon"))
            except (TypeError, ValueError):
                return self._send_json({"error": "latitude/longitude missing or invalid"}, 400)

            if not device_id:
                device_id = "anon_%s_%s" % (lat, lng)

            try:
                acc = float(payload.get("accuracy") or 0)
            except Exception:
                acc = 0.0
            try:
                speed = float(payload.get("speed") or 0)
            except Exception:
                speed = 0.0
            provider = str(payload.get("provider") or payload.get("src") or "")
            ts = payload.get("timestamp") or payload.get("ts")
            try:
                ts = float(ts) if ts else time.time()
            except Exception:
                ts = time.time()
            # 安卓客户端可能以毫秒为单位上报时间戳，统一转换为秒
            if ts > 1e12:
                ts = ts / 1000.0
            t_str = str(payload.get("time") or payload.get("time_str") or time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts)))

            rec = LocationRecord(
                device_id=device_id,
                latitude=lat,
                longitude=lng,
                accuracy=acc,
                speed=speed,
                provider=provider,
                timestamp=ts,
                time_str=t_str,
            )
            store.upsert(rec)
            log_cb("[HTTP] 收到位置上报: %s -> %.6f,%.6f (精度±%.0fm)" % (device_id, lat, lng, acc))
            return self._send_json({"ok": True, "device_id": device_id, "ts": ts})

    return Handler


class HttpServerThread:
    """后台 HTTP 服务线程"""

    def __init__(self, store: LocationStore, port: int = DEFAULT_PORT, log_cb=None):
        self.store = store
        self.port = port
        self.log_cb = log_cb or (lambda s: None)
        self._server: ThreadingHTTPServer = None
        self._thread: Thread = None

    @property
    def running(self) -> bool:
        return self._server is not None

    def start(self) -> bool:
        if self.running:
            return True
        try:
            handler = _make_handler(self.store, self.log_cb)
            self._server = ThreadingHTTPServer(("0.0.0.0", self.port), handler)
            self._server.daemon_threads = True
        except OSError as e:
            self.log_cb("[HTTP] 监听端口失败 (%s): %s" % (self.port, e))
            self._server = None
            return False

        self._thread = Thread(target=self._server.serve_forever, name="httpd", daemon=True)
        self._thread.start()
        self.log_cb("[HTTP] 已启动, 监听 http://0.0.0.0:%s" % self.port)
        self.log_cb("[HTTP] 局域网:  http://<本机IP>:%s" % self.port)
        self.log_cb("[HTTP] 公网:    %s:%s" % (PUBLIC_BASE, self.port))
        return True

    def stop(self) -> None:
        if not self.running:
            return
        try:
            self._server.shutdown()
            self._server.server_close()
        except Exception:
            pass
        self._server = None
        self._thread = None
        self.log_cb("[HTTP] 已停止")
