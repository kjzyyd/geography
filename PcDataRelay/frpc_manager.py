"""
FRP (frpc.exe) 进程管理器
使用命令：frpc.exe -u RW5zcL9ZXqPFQmgtBsiTYvkb -p 331760
把本机 9178 端口通过 kjzyyd.fucku.top 暴露到公网。
"""

import os
import platform
import shutil
import subprocess
import threading
import time


FRPC_TOKEN = "RW5zcL9ZXqPFQmgtBsiTYvkb"
FRPC_SERVER_PORT = 331760
FRPC_SUBDIRS = ["", "frp", "bin", "tools"]


def find_frpc() -> str:
    """查找 frpc.exe（Windows）或 frpc（Linux/Mac）。"""
    base = os.path.dirname(os.path.abspath(__file__))
    exe_name = "frpc.exe" if platform.system() == "Windows" else "frpc"

    candidates = []
    for sub in FRPC_SUBDIRS:
        candidates.append(os.path.join(base, sub, exe_name))
    in_path = shutil.which(exe_name.replace(".exe", ""))
    if in_path:
        candidates.append(in_path)
    in_path2 = shutil.which(exe_name)
    if in_path2:
        candidates.append(in_path2)

    for p in candidates:
        if p and os.path.isfile(p):
            return p
    return ""


def build_args(frpc_path: str) -> list:
    return [
        frpc_path,
        "-u", FRPC_TOKEN,
        "-p", str(FRPC_SERVER_PORT),
    ]


class FrpcManager:
    def __init__(self, log_cb=None, status_cb=None):
        self._log = log_cb or (lambda s: None)
        self._status = status_cb or (lambda s: None)
        self._proc: subprocess.Popen = None
        self._stdout_thr = None
        self._stop_monitor = threading.Event()
        self._monitor_thr = None
        self._lock = threading.Lock()

    def is_running(self) -> bool:
        with self._lock:
            return self._proc is not None and self._proc.poll() is None

    def start(self, frpc_path: str = "") -> bool:
        with self._lock:
            if self.is_running():
                self._log("[FRP] 已经在运行")
                return True

            exe = frpc_path or find_frpc()
            if not exe:
                self._log(
                    "[FRP] 找不到 frpc.exe/frpc。请放到程序目录、frp/ 子目录或 PATH 中。"
                )
                self._status("未找到 frpc 可执行文件")
                return False

            args = build_args(exe)
            self._log("[FRP] 启动: " + " ".join(args))

            try:
                startupinfo = None
                creationflags = 0
                if platform.system() == "Windows":
                    startupinfo = subprocess.STARTUPINFO()
                    startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)

                self._proc = subprocess.Popen(
                    args,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    stdin=subprocess.DEVNULL,
                    bufsize=1,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    startupinfo=startupinfo,
                    creationflags=creationflags,
                )
            except Exception as e:
                self._log("[FRP] 启动异常: %s" % e)
                self._status("启动失败")
                self._proc = None
                return False

            self._stdout_thr = threading.Thread(
                target=self._reader_loop, name="frp-stdout", daemon=True
            )
            self._stdout_thr.start()

            self._stop_monitor.clear()
            self._monitor_thr = threading.Thread(
                target=self._monitor_loop, name="frp-monitor", daemon=True
            )
            self._monitor_thr.start()

            self._status("运行中")
            self._log("[FRP] 进程已启动, PID=%s" % self._proc.pid)
            self._log(
                "[FRP] 公网访问地址: http://kjzyyd.fucku.top  (端口按 FRP 服务器映射)"
            )
            return True

    def stop(self) -> None:
        self._stop_monitor.set()
        with self._lock:
            if self._proc is None:
                return
            pid = self._proc.pid
            try:
                self._proc.terminate()
            except Exception:
                pass
            deadline = time.time() + 3
            while time.time() < deadline and self._proc.poll() is None:
                time.sleep(0.1)
            if self._proc.poll() is None:
                try:
                    self._proc.kill()
                except Exception:
                    pass
            try:
                self._proc.wait(timeout=2)
            except Exception:
                pass
            self._log("[FRP] 进程已停止, PID=%s" % pid)
            self._proc = None
            self._status("已停止")

    def _reader_loop(self):
        try:
            proc = self._proc
            if not proc or not proc.stdout:
                return
            for line in proc.stdout:
                line = line.rstrip()
                if line:
                    if any(k in line for k in ("start", "success", "proxy", "listen",
                                                "连接", "映射", "上线", "login")):
                        self._log("[FRP] >> %s" % line)
                    else:
                        self._log("[FRP] %s" % line)
        except Exception as e:
            self._log("[FRP] 读输出异常: %s" % e)

    def _monitor_loop(self):
        while not self._stop_monitor.is_set():
            with self._lock:
                proc = self._proc
            if proc is not None and proc.poll() is not None:
                code = proc.poll()
                self._log("[FRP] 进程退出, code=%s" % code)
                self._status("已退出 (code %s)" % code)
                with self._lock:
                    self._proc = None
                return
            time.sleep(0.5)
