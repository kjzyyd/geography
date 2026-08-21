package main

const dashboardHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>位置数据中转服务 | PcDataRelay</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",Helvetica,Arial,sans-serif;background:#f0f2f5;color:#222}
header{background:#1976d2;color:#fff;padding:12px 18px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
header h1{font-size:17px;font-weight:600}
.badge{padding:3px 10px;border-radius:12px;font-size:12px;font-weight:600;background:#888;color:#fff}
.badge.ok{background:#2e7d32}.badge.bad{background:#c62828}
main{display:grid;grid-template-columns:2fr 1fr;gap:12px;padding:12px;max-width:1280px;margin:0 auto}
@media(max-width:900px){main{grid-template-columns:1fr}}
.card{background:#fff;border-radius:10px;padding:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.card h2{font-size:14px;margin-bottom:10px;color:#333;display:flex;align-items:center;gap:8px}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:7px 8px;border-bottom:1px solid #e8eaed;text-align:left;vertical-align:middle}
th{color:#666;font-weight:600;white-space:nowrap;background:#fafbfc}
tr:hover td{background:#f7fafd}
.dev{font-family:Consolas,Menlo,monospace;font-size:12px}
.note{color:#1976d2}
.alert{color:#c62828;font-weight:700}
.offline{color:#999}
.btn{border:none;border-radius:6px;padding:5px 10px;font-size:12px;cursor:pointer;color:#fff}
.btn-edit{background:#1976d2}.btn-del{background:#c62828}.btn-sm{background:#e0e0e0;color:#333}
.btn-sm:hover{background:#cfcfcf}
.row{display:flex;gap:8px;align-items:center;margin-bottom:8px;flex-wrap:wrap}
input[type=text]{border:1px solid #ccc;border-radius:6px;padding:6px 8px;font-size:13px}
input.wide{flex:1;min-width:160px}
label{font-size:13px;color:#444}
.logbox{background:#1e1e1e;color:#dcdcdc;font-family:Consolas,Menlo,monospace;font-size:12px;padding:8px;border-radius:6px;height:300px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
.empty{color:#999;font-size:13px;padding:20px 0;text-align:center}
.ops{display:flex;gap:6px}
</style>
</head>
<body>
<header>
  <h1>位置数据中转服务 · PcDataRelay</h1>
  <span class="badge ok" id="b-http">HTTP 运行中</span>
  <span class="badge" id="b-frp">FRP 未运行</span>
  <span style="font-size:12px;opacity:.9" id="addr">地址: 读取中...</span>
  <span style="margin-left:auto;font-size:12px" id="now"></span>
</header>
<main>
  <div style="display:flex;flex-direction:column;gap:12px">
    <div class="card">
      <h2>客户端列表 (<span id="cnt">0</span>)</h2>
      <table id="tbl">
        <thead><tr>
          <th>设备</th><th>备注</th><th>位置</th><th>精度</th><th>距离报警中心</th><th>上报时间</th><th>操作</th>
        </tr></thead>
        <tbody id="tbody"></tbody>
      </table>
      <div class="empty" id="empty" style="display:none">暂无客户端数据。请确保客户端已安装并上报位置。</div>
    </div>
    <div class="card">
      <h2>运行日志</h2>
      <div class="logbox" id="log"></div>
    </div>
  </div>
  <div style="display:flex;flex-direction:column;gap:12px">
    <div class="card">
      <h2>报警设置</h2>
      <div class="row">
        <label>报警中心坐标 (lat,lng)</label>
        <input type="text" class="wide" id="center" placeholder="30.274100,120.155100">
      </div>
      <div class="row">
        <label>阈值(米)</label>
        <input type="text" id="thresh" style="width:90px" value="500">
        <button class="btn btn-edit" onclick="useMyIP()">使用本机IP定位</button>
        <button class="btn btn-edit" onclick="saveSettings()">应用</button>
      </div>
    </div>
    <div class="card">
      <h2>网络 / FRP</h2>
      <div class="row"><label>公网地址:</label><span id="pub" class="note"></span></div>
      <div class="row"><label>局域网地址:</label><span id="lan" class="note"></span></div>
      <div class="row">
        <button class="btn btn-sm" id="btn-frp" onclick="toggleFrp()">启动 FRP</button>
        <a href="/map" target="_blank"><button class="btn btn-sm">打开地图</button></a>
        <button class="btn btn-sm" onclick="clearAll()">清空所有位置</button>
      </div>
    </div>
    <div class="card">
      <h2>说明</h2>
      <div style="font-size:12px;color:#555;line-height:1.7">
        <p>· 手机客户端通过 公网/局域网 上报位置到本程序。</p>
        <p>· 服务端手机每 10 秒从这里拉取所有客户端位置。</p>
        <p>· 数据仅保存在本机内存与备注文件 notes.json。</p>
        <p>· FRP 需要 frpc.exe 放在程序目录、frp/ 子目录或 PATH 中。</p>
      </div>
    </div>
  </div>
</main>
<script>
var center=null, thresh=500;
function haversine(a,b,c,d){if(!a&&!b)return -1;if(!c&&!d)return -1;var R=6371008.8;
  var r1=a*Math.PI/180,r2=c*Math.PI/180,d1=(c-a)*Math.PI/180,d2=(d-b)*Math.PI/180;
  var h=Math.sin(d1/2)**2+Math.cos(r1)*Math.cos(r2)*Math.sin(d2/2)**2;
  return 2*R*Math.asin(Math.sqrt(h));}
function fmtDist(m){if(m<0)return '<span class="offline">未设中心</span>';
  return m>=1000?(m/1000).toFixed(2)+' km':Math.round(m)+' 米';}
function fmtAge(ts){if(!ts)return '-';var a=Date.now()/1000-ts;
  if(a<0)a=0;if(a<60)return Math.floor(a)+'秒前';
  if(a<3600)return (a/60).toFixed(0)+'分钟前';
  return (a/3600).toFixed(1)+'小时前';}
function esc(s){return (s||'').toString().replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
function refresh(){
  Promise.all([fetch('/location').then(r=>r.json()).catch(()=>({})),
               fetch('/notes').then(r=>r.json()).catch(()=>({})),
               fetch('/status').then(r=>r.json()).catch(()=>({}))])
  .then(function(a){
    var data=a[0]||{}, notes=a[1]||{}, st=a[2]||{};
    document.getElementById('cnt').textContent=Object.keys(data).length;
    document.getElementById('addr').textContent='公网: '+(st.public||'')+' · 局域网: '+(st.lan||'');
    document.getElementById('lan').textContent=st.lan||'-';
    document.getElementById('pub').textContent=st.public||'-';
    var b=document.getElementById('b-frp');
    b.className='badge'+(st.frp&&st.frp.indexOf('运行')>=0?' ok':'');
    b.textContent='FRP '+ (st.frp||'');
    document.getElementById('now').textContent=new Date().toLocaleTimeString();
    if(st.center&&st.center.length==2){center=st.center;
      document.getElementById('center').value=st.center[0].toFixed(6)+','+st.center[1].toFixed(6);}
    if(st.threshold){thresh=st.threshold;document.getElementById('thresh').value=st.threshold;}
    var tb=document.getElementById('tbody');tb.innerHTML='';
    var empty=document.getElementById('empty');
    var items=Object.keys(data).map(k=>data[k]);
    items.sort((x,y)=>(y.timestamp||0)-(x.timestamp||0));
    empty.style.display=items.length?'none':'';
    items.forEach(function(it){
      var id=it.device_id||'';
      var note=notes[id]||'';
      var dist=haversine(center?center[0]:0,center?center[1]:0,it.latitude,it.longitude);
      var alertf='';
      if(dist>=0&&thresh>0&&dist<=thresh) alertf=' <span class="alert">⚠进范围</span>';
      var tr=document.createElement('tr');
      tr.innerHTML='<td class="dev">'+esc(id)+alertf+'</td>'+
        '<td class="note">'+esc(note)+'</td>'+
        '<td class="dev">'+(it.latitude?it.latitude.toFixed(6):'-')+', '+(it.longitude?it.longitude.toFixed(6):'-')+'</td>'+
        '<td>±'+Math.round(it.accuracy||0)+'m</td>'+
        '<td>'+fmtDist(dist)+'</td>'+
        '<td>'+(it.time_str||'-')+' ('+fmtAge(it.timestamp)+')</td>'+
        '<td><div class="ops"><button class="btn btn-edit" onclick="editNote(\''+esc(id).replace(/'/g,"\\'")+'\')">备注</button>'+
        '<button class="btn btn-del" onclick="delClient(\''+esc(id).replace(/'/g,"\\'")+'\')">删除</button></div></td>';
      tb.appendChild(tr);
    });
  });
  fetch('/api/log').then(r=>r.json()).then(function(d){
    var box=document.getElementById('log');
    var txt=(d.log||[]).map(function(l){return '['+l+']';}).join('\n');
    box.textContent=txt;box.scrollTop=box.scrollHeight;
  }).catch(function(){});
}
function editNote(id){var cur='';fetch('/notes').then(r=>r.json()).then(function(n){cur=n[id]||'';
  var v=prompt('为设备 ['+id+'] 设置备注(留空清除):',cur);
  if(v===null)return;
  fetch('/notes',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({device_id:id,note:v})})
    .then(function(){refresh();});
});}
function delClient(id){if(!confirm('确定删除客户端 ['+id+']?'))return;
  fetch('/location/'+encodeURIComponent(id),{method:'DELETE'}).then(refresh);}
function clearAll(){if(!confirm('确定清空所有客户端位置数据?'))return;
  fetch('/location',{method:'DELETE'}).then(refresh);}
function parseCenter(){var s=document.getElementById('center').value.trim();
  var p=s.split(',').map(parseFloat);if(p.length>=2&&!isNaN(p[0])&&!isNaN(p[1]))return [p[0],p[1]];
  return null;}
function saveSettings(){var c=parseCenter();var t=parseFloat(document.getElementById('thresh').value)||0;
  fetch('/settings',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify(c?{lat:c[0],lng:c[1],threshold:t}:{threshold:t})}).then(refresh);}
function useMyIP(){fetch('http://ip-api.com/json/?lang=zh-CN').then(r=>r.json()).then(function(d){
  if(d.status==='success'){document.getElementById('center').value=d.lat.toFixed(6)+','+d.lon.toFixed(6);saveSettings();}
  else{alert('IP 定位失败');}}).catch(function(){alert('无法访问 ip-api.com');});}
function toggleFrp(){fetch('/frp/start').then(r=>r.json()).then(function(d){
  if(!d.ok)alert('FRP 启动失败: 未找到 frpc.exe 或启动异常');refresh();});}
setInterval(refresh,5000);refresh();
</script>
</body>
</html>`

const mapHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>位置中转 · 实时地图</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
body{margin:0;font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
#header{padding:10px 14px;background:#1976d2;color:#fff;display:flex;justify-content:space-between;align-items:center}
#map{height:calc(100vh - 52px)}
#info{font-size:12px;opacity:.9}
</style>
</head>
<body>
<div id="header"><div><b>位置中转服务</b> · 实时地图</div><div id="info">初始加载...</div></div>
<div id="map"></div>
<script>
var map=L.map('map').setView([30.2741,120.1551],12);
L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:19,attribution:'© 高德地图'}).addTo(map);
var markers={},notes={};
function refresh(){
  Promise.all([fetch('/location').then(r=>r.json()).catch(()=>({})),fetch('/notes').then(r=>r.json()).catch(()=>({}))])
  .then(function(resps){var data=resps[0]||{};notes=resps[1]||{};
    var items=Object.values(data||{});
    document.getElementById('info').textContent='设备数: '+items.length+' · 最后刷新: '+new Date().toLocaleTimeString();
    items.forEach(function(it){if(!(it.latitude&&it.longitude))return;
      var ll=[it.latitude,it.longitude],note=notes[it.device_id]||'';
      var title=note?(note+' ('+it.device_id+')'):it.device_id;
      var html='<b>'+title+'</b><br>'+it.latitude.toFixed(6)+', '+it.longitude.toFixed(6);
      if(it.time_str)html+='<br>'+it.time_str;
      if(it.accuracy)html+='<br>精度±'+Math.round(it.accuracy)+'m';
      var key=it.device_id;
      if(markers[key]){markers[key].setLatLng(ll);markers[key].bindPopup(html);
        markers[key].unbindTooltip();markers[key].bindTooltip(title);}
      else{markers[key]=L.marker(ll).addTo(map).bindPopup(html);markers[key].bindTooltip(title);}
    });}).catch(function(){});
}
refresh();setInterval(refresh,30000);
</script>
</body>
</html>`
