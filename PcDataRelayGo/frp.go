package main

import (
	"bufio"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

const (
	frpcToken      = "RW5zcL9ZXqPFQmgtBsiTYvkb"
	frpcServerPort = "331760"
	publicBase     = "http://kjzyyd.fucku.top"
)

// FrpManager 管理 frpc.exe 进程(可选)。找到 frpc.exe 时自动接管,
// 找不到则中转站仍可在局域网独立工作。
type FrpManager struct {
	mu       sync.Mutex
	cmd      *exec.Cmd
	logCb    func(string)
	running  bool
	pid      int
	lastPath string
}

func NewFrpManager(logCb func(string)) *FrpManager {
	if logCb == nil {
		logCb = func(string) {}
	}
	return &FrpManager{logCb: logCb}
}

// FindFrpc 在 exe 同目录、frp/bin/tools 子目录、PATH 中查找 frpc
func (f *FrpManager) FindFrpc() string {
	exeName := "frpc"
	if runtime.GOOS == "windows" {
		exeName = "frpc.exe"
	}
	dirs := []string{exeDir(), filepath.Join(exeDir(), "frp"), filepath.Join(exeDir(), "bin"), filepath.Join(exeDir(), "tools")}
	for _, d := range dirs {
		p := filepath.Join(d, exeName)
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
			return p
		}
	}
	if p, err := exec.LookPath(exeName); err == nil {
		return p
	}
	return ""
}

func (f *FrpManager) IsRunning() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.running
}

// Start 启动 frpc; 已运行则直接返回成功; 找不到 frpc 返回 false
func (f *FrpManager) Start() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.running {
		f.logCb("[FRP] 已在运行")
		return true
	}
	path := f.lastPath
	if path == "" {
		path = f.FindFrpc()
	}
	if path == "" {
		f.logCb("[FRP] 未找到 frpc.exe。请把它放到程序目录、frp/ 子目录或 PATH 中(否则只能局域网使用)。")
		return false
	}
	args := []string{"-u", frpcToken, "-p", frpcServerPort}
	f.logCb("[FRP] 启动: " + filepath.Base(path) + " " + strings.Join(args, " "))

	cmd := exec.Command(path, args...)
	cmd.SysProcAttr = hideWindowAttr()
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		f.logCb("[FRP] 启动异常: " + err.Error())
		return false
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		f.logCb("[FRP] 启动异常: " + err.Error())
		return false
	}
	f.cmd = cmd
	f.pid = cmd.Process.Pid
	f.lastPath = path
	f.running = true

	go f.readLoop(stdout)
	go f.waitLoop(cmd)
	f.logCb("[FRP] 已启动, PID=" + itoa(cmd.Process.Pid) + ", 公网: " + publicBase)
	return true
}

func (f *FrpManager) Stop() {
	f.mu.Lock()
	cmd := f.cmd
	f.cmd = nil
	f.running = false
	pid := f.pid
	f.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
		f.logCb("[FRP] 已停止, PID=" + itoa(pid))
	}
}

func (f *FrpManager) StatusText() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.running {
		return "运行中 (PID " + itoa(f.pid) + ")"
	}
	return "未运行"
}

func (f *FrpManager) readLoop(r io.Reader) {
	br := bufio.NewReader(r)
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		if line != "" {
			f.logCb("[FRP] " + line)
		}
	}
}

func (f *FrpManager) waitLoop(cmd *exec.Cmd) {
	_ = cmd.Wait()
	f.mu.Lock()
	if f.cmd == cmd {
		f.cmd = nil
		f.running = false
	}
	f.mu.Unlock()
	f.logCb("[FRP] 进程已退出")
}
