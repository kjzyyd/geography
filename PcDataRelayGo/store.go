package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// LocationRecord 一条位置记录。字段与旧版 Python 中转站保持完全一致,
// 保证安卓客户端与服务端无需任何改动即可兼容。
type LocationRecord struct {
	DeviceID  string  `json:"device_id"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	Accuracy  float64 `json:"accuracy"`
	Speed     float64 `json:"speed"`
	Provider  string  `json:"provider"`
	Timestamp float64 `json:"timestamp"` // epoch 秒
	TimeStr   string  `json:"time_str"`
	Time      string  `json:"time"`
}

// Store 线程安全的内存存储: device_id -> 最新位置 + 备注
type Store struct {
	mu    sync.RWMutex
	data  map[string]*LocationRecord
	notes map[string]string
}

func NewStore() *Store {
	return &Store{
		data:  make(map[string]*LocationRecord),
		notes: make(map[string]string),
	}
}

func (s *Store) Upsert(rec *LocationRecord) {
	s.mu.Lock()
	s.data[rec.DeviceID] = rec
	s.mu.Unlock()
}

func (s *Store) Get(id string) *LocationRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if r, ok := s.data[id]; ok {
		cp := *r
		return &cp
	}
	return nil
}

// Snapshot 返回 {device_id: record} 映射(副本)
func (s *Store) Snapshot() map[string]*LocationRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string]*LocationRecord, len(s.data))
	for k, v := range s.data {
		cp := *v
		out[k] = &cp
	}
	return out
}

func (s *Store) Clear() int {
	s.mu.Lock()
	n := len(s.data)
	s.data = make(map[string]*LocationRecord)
	s.mu.Unlock()
	return n
}

func (s *Store) Delete(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.data[id]; ok {
		delete(s.data, id)
		return true
	}
	return false
}

func (s *Store) Count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.data)
}

// ---------------- 备注 ----------------

func (s *Store) SetNote(id, note string) {
	s.mu.Lock()
	note = trimSpace(note)
	if note != "" {
		s.notes[id] = note
	} else {
		delete(s.notes, id)
	}
	s.mu.Unlock()
}

func (s *Store) GetNote(id string) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.notes[id]
}

func (s *Store) AllNotes() map[string]string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string]string, len(s.notes))
	for k, v := range s.notes {
		out[k] = v
	}
	return out
}

func trimSpace(s string) string {
	// 简单去除首尾空白
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t' || s[0] == '\r' || s[0] == '\n') {
		s = s[1:]
	}
	for len(s) > 0 && (s[len(s)-1] == ' ' || s[len(s)-1] == '\t' || s[len(s)-1] == '\r' || s[len(s)-1] == '\n') {
		s = s[:len(s)-1]
	}
	return s
}

// ---------------- 备注持久化 ----------------

func (s *Store) SaveNotes(path string) {
	s.mu.RLock()
	data := make(map[string]string, len(s.notes))
	for k, v := range s.notes {
		data[k] = v
	}
	s.mu.RUnlock()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return
	}
	b, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return
	}
	_ = os.Rename(tmp, path)
}

func (s *Store) LoadNotes(path string) {
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var data map[string]string
	if err := json.Unmarshal(b, &data); err != nil {
		return
	}
	s.mu.Lock()
	s.notes = data
	s.mu.Unlock()
}

// formatLocalTime 用本机时区格式化时间为 "2006-01-02 15:04:05"
func formatLocalTime(ts float64) string {
	return time.Unix(int64(ts), 0).Format("2006-01-02 15:04:05")
}
