package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const defaultPort = 9178

// LogBuf 简单的环形日志缓冲,供 Web 面板展示
type LogBuf struct {
	mu   sync.Mutex
	msgs []string
	max  int
}

func NewLogBuf(max int) *LogBuf {
	return &LogBuf{msgs: make([]string, 0, max), max: max}
}

func (l *LogBuf) Add(msg string) {
	l.mu.Lock()
	l.msgs = append(l.msgs, time.Now().Format("15:04:05")+" "+msg)
	if len(l.msgs) > l.max {
		l.msgs = l.msgs[len(l.msgs)-l.max:]
	}
	l.mu.Unlock()
}

func (l *LogBuf) Snapshot() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]string, len(l.msgs))
	copy(out, l.msgs)
	return out
}

// Server 中转站整体
type Server struct {
	store  *Store
	log    *LogBuf
	frp    *FrpManager
	notes  string
	port   int
	center []float64 // 报警中心 [lat, lng]
	thresh float64   // 报警阈值(米)
}

func NewServer(store *Store, log *LogBuf, frp *FrpManager, notesPath string, port int) *Server {
	return &Server{
		store:  store,
		log:    log,
		frp:    frp,
		notes:  notesPath,
		port:   port,
		thresh: 500,
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/healthz", s.handleHealth)
	mux.HandleFunc("/location", s.handleLocation)
	mux.HandleFunc("/locations", s.handleLocations)
	mux.HandleFunc("/location/", s.handleLocationOne)
	mux.HandleFunc("/notes", s.handleNotes)
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/settings", s.handleSettings)
	mux.HandleFunc("/frp/start", s.handleFrpStart)
	mux.HandleFunc("/frp/stop", s.handleFrpStop)
	mux.HandleFunc("/api/log", s.handleApiLog)
	mux.HandleFunc("/map", s.handleMap)
	mux.HandleFunc("/", s.handleIndex)
	return corsMiddleware(mux)
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, code int, obj interface{}) {
	b, _ := json.Marshal(obj)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func writeHTML(w http.ResponseWriter, code int, html string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(html))
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]interface{}{"ok": true, "port": s.port})
}

// GET /location -> {device_id: record}
func (s *Server) handleLocation(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, 200, s.store.Snapshot())
	case http.MethodPost:
		s.postLocation(w, r)
	case http.MethodDelete:
		n := s.store.Clear()
		s.log.Add("[HTTP] 清空所有位置数据: " + itoa(n) + " 个客户端")
		writeJSON(w, 200, map[string]interface{}{"ok": true, "cleared": n})
	default:
		writeJSON(w, 405, map[string]interface{}{"error": "method not allowed"})
	}
}

// DELETE /location/<id> 删除单个客户端
func (s *Server) handleLocationOne(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/location/")
	id, _ = url.PathUnescape(id)
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, map[string]interface{}{"error": "method not allowed"})
		return
	}
	if s.store.Delete(id) {
		s.log.Add("[HTTP] 删除客户端: " + id)
		writeJSON(w, 200, map[string]interface{}{"ok": true, "deleted": id})
	} else {
		writeJSON(w, 404, map[string]interface{}{"error": "not found"})
	}
}

// GET /locations 服务端兼容接口
func (s *Server) handleLocations(w http.ResponseWriter, r *http.Request) {
	snap := s.store.Snapshot()
	items := make([]*LocationRecord, 0, len(snap))
	for _, v := range snap {
		items = append(items, v)
	}
	writeJSON(w, 200, map[string]interface{}{"locations": items})
}

// POST /location 接收客户端上报(JSON 或表单)
func (s *Server) postLocation(w http.ResponseWriter, r *http.Request) {
	ctype := r.Header.Get("Content-Type")
	payload := map[string]interface{}{}

	if strings.Contains(strings.ToLower(ctype), "application/json") {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if len(body) > 0 {
			if err := json.Unmarshal(body, &payload); err != nil {
				writeJSON(w, 400, map[string]interface{}{"error": "bad json: " + err.Error()})
				return
			}
		}
	} else {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		values, _ := url.ParseQuery(string(body))
		// 合并 query string
		for k, vs := range r.URL.Query() {
			if len(vs) > 0 {
				values[k] = vs
			}
		}
		for k, vs := range values {
			if len(vs) > 0 {
				payload[k] = vs[0]
			}
		}
	}

	if len(payload) == 0 {
		writeJSON(w, 400, map[string]interface{}{"error": "empty body"})
		return
	}

	deviceID := strField(payload, "device_id", "id")
	lat, lng, err := latLng(payload)
	if err != nil {
		writeJSON(w, 400, map[string]interface{}{"error": "latitude/longitude missing or invalid"})
		return
	}
	if deviceID == "" {
		deviceID = fmt.Sprintf("anon_%s_%s", trimFloat(lat), trimFloat(lng))
	}

	acc := floatField(payload, "accuracy")
	speed := floatField(payload, "speed")
	provider := strField(payload, "provider", "src")

	ts := floatField(payload, "timestamp")
	if ts2, ok := payload["ts"].(float64); ok && ts == 0 {
		ts = ts2
	}
	if ts == 0 {
		ts = float64(time.Now().Unix())
	}
	if ts > 1e12 { // 毫秒 -> 秒
		ts /= 1000
	}

	timeStr := strField(payload, "time", "time_str")
	if timeStr == "" {
		timeStr = formatLocalTime(ts)
	}

	rec := &LocationRecord{
		DeviceID:  deviceID,
		Latitude:  lat,
		Longitude: lng,
		Accuracy:  acc,
		Speed:     speed,
		Provider:  provider,
		Timestamp: ts,
		TimeStr:   timeStr,
		Time:      timeStr,
	}
	s.store.Upsert(rec)
	s.log.Add(fmt.Sprintf("[HTTP] 收到位置上报: %s -> %.6f,%.6f (精度±%.0fm)", deviceID, lat, lng, acc))
	writeJSON(w, 200, map[string]interface{}{"ok": true, "device_id": deviceID, "ts": ts})
}

// POST /notes 设置备注; GET /notes 返回全部备注
func (s *Server) handleNotes(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeJSON(w, 200, s.store.AllNotes())
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]interface{}{"error": "method not allowed"})
		return
	}
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	var payload map[string]interface{}
	if err := json.Unmarshal(body, &payload); err != nil || payload == nil {
		writeJSON(w, 400, map[string]interface{}{"error": "bad json"})
		return
	}
	did := strField(payload, "device_id", "id")
	if did == "" {
		writeJSON(w, 400, map[string]interface{}{"error": "device_id missing"})
		return
	}
	note := strField(payload, "note", "remark", "value")
	s.store.SetNote(did, note)
	s.store.SaveNotes(s.notes)
	s.log.Add(fmt.Sprintf("[HTTP] 设置备注: %s -> %s", did, noteOrClear(note)))
	writeJSON(w, 200, map[string]interface{}{"ok": true, "device_id": did, "note": s.store.GetNote(did)})
}

func noteOrClear(n string) string {
	if n == "" {
		return "(清除)"
	}
	return n
}

// GET /status 面板状态
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]interface{}{
		"http":      "运行中",
		"port":      s.port,
		"lan":       "http://" + lanIP() + ":" + itoa(s.port),
		"public":    publicBase,
		"frp":       s.frp.StatusText(),
		"clients":   s.store.Count(),
		"center":    s.center,
		"threshold": s.thresh,
	})
}

// POST /settings 保存报警中心/阈值
func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]interface{}{"error": "method not allowed"})
		return
	}
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	var payload map[string]interface{}
	_ = json.Unmarshal(body, &payload)
	if lat, ok := payload["lat"].(float64); ok {
		if lng, ok2 := payload["lng"].(float64); ok2 {
			s.center = []float64{lat, lng}
			s.log.Add(fmt.Sprintf("[GUI] 报警中心已设置: %.6f,%.6f", lat, lng))
		}
	}
	if t, ok := payload["threshold"].(float64); ok && t >= 5 {
		s.thresh = t
		s.log.Add(fmt.Sprintf("[GUI] 报警阈值已应用: %.0f 米", t))
	}
	writeJSON(w, 200, map[string]interface{}{"ok": true})
}

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	writeHTML(w, 200, dashboardHTML)
}

func (s *Server) handleFrpStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]interface{}{"error": "method not allowed"})
		return
	}
	ok := s.frp.Start()
	writeJSON(w, 200, map[string]interface{}{"ok": ok, "frp": s.frp.StatusText()})
}

func (s *Server) handleFrpStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]interface{}{"error": "method not allowed"})
		return
	}
	s.frp.Stop()
	writeJSON(w, 200, map[string]interface{}{"ok": true, "frp": s.frp.StatusText()})
}

// GET /api/log 面板日志
func (s *Server) handleApiLog(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]interface{}{"log": s.log.Snapshot()})
}

func (s *Server) handleMap(w http.ResponseWriter, r *http.Request) {
	writeHTML(w, 200, mapHTML)
}

// ---------------- 工具函数 ----------------

func strField(p map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		if v, ok := p[k]; ok {
			return strings.TrimSpace(fmt.Sprintf("%v", v))
		}
	}
	return ""
}

func floatField(p map[string]interface{}, key string) float64 {
	if v, ok := p[key]; ok {
		switch t := v.(type) {
		case float64:
			return t
		case string:
			var f float64
			fmt.Sscanf(t, "%f", &f)
			return f
		case json.Number:
			f, _ := t.Float64()
			return f
		}
	}
	return 0
}

func latLng(p map[string]interface{}) (float64, float64, error) {
	lat := floatField(p, "latitude")
	if lat == 0 {
		lat = floatField(p, "lat")
	}
	lng := floatField(p, "longitude")
	if lng == 0 {
		lng = floatField(p, "lng")
	}
	if lng == 0 {
		lng = floatField(p, "lon")
	}
	if lat == 0 && lng == 0 {
		return 0, 0, fmt.Errorf("invalid latlng")
	}
	return lat, lng, nil
}

func trimFloat(f float64) string {
	return fmt.Sprintf("%.6f", f)
}

func lanIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String()
}
