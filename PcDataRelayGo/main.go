package main

import (
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func exeDir() string {
	ex, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(ex)
}

func main() {
	port := flag.Int("port", defaultPort, "监听端口")
	noFrp := flag.Bool("nofrp", false, "不自动启动 frpc")
	flag.Parse()

	log := NewLogBuf(500)
	store := NewStore()

	// 备注文件与 exe 同目录
	notesPath := filepath.Join(exeDir(), "notes.json")
	store.LoadNotes(notesPath)

	frp := NewFrpManager(func(m string) { log.Add(m) })
	srv := NewServer(store, log, frp, notesPath, *port)

	// 启动 HTTP 服务
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", *port))
	if err != nil {
		fmt.Fprintf(os.Stderr, "[HTTP] 监听端口 %d 失败: %v\n", *port, err)
		os.Exit(1)
	}
	hs := &http.Server{Handler: srv.Handler()}
	go func() { _ = hs.Serve(ln) }()
	log.Add(fmt.Sprintf("HTTP 已启动, 监听 http://0.0.0.0:%d", *port))
	log.Add(fmt.Sprintf("局域网地址: http://%s:%d", lanIP(), *port))
	log.Add("公网地址: " + publicBase)
	log.Add("管理面板: http://127.0.0.1:" + itoa(*port) + "/")

	// 自动启动 FRP(可选)
	if !*noFrp {
		if frp.FindFrpc() != "" {
			frp.Start()
		} else {
			log.Add("[FRP] 未找到 frpc.exe,仅局域网可用(可稍后在面板中手动启动)")
		}
	}

	// 定期持久化备注(每 30 秒)
	go func() {
		for {
			time.Sleep(30 * time.Second)
			store.SaveNotes(notesPath)
		}
	}()

	// 优雅退出: 保存备注并关闭 FRP
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	<-c
	log.Add("正在退出...")
	store.SaveNotes(notesPath)
	frp.Stop()
	_ = hs.Close()
}
