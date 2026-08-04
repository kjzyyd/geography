"""
FRP (frpc.exe) 进程管理器
使用命令：frpc.exe -u RW5zcL9ZXqPFQmgtBsiTYvkb -p 331760
把本机 9178 端口通过 kjzyyd.fucku.top 暴露到公网。

支持:
  1. 本程序启动/停止 frpc (内部模式)
  2. 自动识别用户在程序外部手动启动的 frpc (外部模式)
     - 启动前先扫描系统进程,如已运行则直接接管显示
     - 停止时外部模式默认不杀进程(避免误伤用户手动管理的frp)
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


def _is_frpc_proc_name(name: str) -> bool:
    if not name:
        return False
    n = name.lower()
    return n == "frpc" or n == "frpc.exe"


def find_external_frpc():
    """
    扫描当前系统中的 frpc 进程。
    返回: (pid: int, cmdline: str) 的列表,按 PID 升序。
          未找到返回空列表。失败返回空列表(不抛异常)。
    """
    found = []
    try:
        import psutil
        for p in psutil.process_iter(attrs=["pid", "name", "cmdline"]):
            try:
                info = p.info or {}
            except Exception:
                continue
            name = info.get("name") or ""
            if not _is_frpc_proc_name(name):
                continue
            cmd_parts = info.get("cmdline") or []
            if isinstance(cmd_parts, (list, tuple)):
                cmd_str = " ".join(str(x) for x in cmd_parts)
            else:
                cmd_str = str(cmd_parts)
            found.append((int(info.get("pid", 0)), cmd_str))
    except Exception:
        # psutil 不可用时回退到平台原生命令
        try:
            if platform.system() == "Windows":
                # Windows: tasklist + wmic (避免依赖第三方库)
                out = subprocess.check_output(
                    ["wmic", "process", "where",
                     "name='frpc.exe' or name='frpc'",
                     "get", "ProcessId,CommandLine", "/format:list"],
                    text=True, encoding="utf-8", errors="replace",
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
                pid = None
                cmd = ""
                for raw in out.splitlines():
                    line = raw.strip()
                    if not line:
                        if pid is not None:
                            found.append((pid, cmd))
                        pid = None
                        cmd = ""
                        continue
                    if "=" in line:
                        k, v = line.split("=", 1)
                        if k.strip().lower() == "processid":
                            try:
                                pid = int(v.strip())
                            except Exception:
                                pid = None
                        elif k.strip().lower() == "commandline":
                            cmd = v.strip()
                if pid is not None:
                    found.append((pid, cmd))
            else:
                # Unix: ps -eo pid,args
                out = subprocess.check_output(
                    ["ps", "-eo", "pid,args"],
                    text=True, encoding="utf-8", errors="replace",
                )
                for line in out.splitlines()[1:]:
                    line = line.rstrip()
                    if not line:
                        continue
                    try:
                        sp = line.split(None, 1)
                        pid_s = sp[0]
                        args = sp[1] if len(sp) > 1 else ""
                    except Exception:
                        continue
                    base = os.path.basename(args.split(None, 1)[0]) if args else ""
                    if _is_frpc_proc_name(base):
                        try:
                            found.append((int(pid_s), args))
                        except Exception:
                            pass
        except Exception:
            pass
    found.sort(key=lambda x: x[0])
    return found


def _pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        import psutil
        return psutil.pid_exists(pid)
    except Exception:
        pass
    # fallback: 发信号 0 (POSIX) / tasklist 查询 (Windows)
    try:
        if platform.system() == "Windows":
            out = subprocess.run(
                ["tasklist", "/FI", "PID eq %d" % pid, "/NH"],
                capture_output=True, text=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            return str(pid) in (out.stdout or "")
        else:
            os.kill(pid, 0)
            return True
    except Exception:
        return False


class FrpcManager:
    def __init__(self, log_cb=None, status_cb=None):
        self._log = log_cb or (lambda s: None)
        self._status = status_cb or (lambda s: None)
        # 内部启动(托管)模式
        self._proc: subprocess.Popen = None
        self._stdout_thr = None
        self._stop_monitor = threading.Event()
        self._monitor_thr = None
        # 外部启动(只读)模式
        self._external_pid: int = 0
        self._external_cmd: str = ""
        self._lock = threading.Lock()

    # ---------------- 对外状态 API ----------------

    def is_running(self) -> bool:
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                return True
            return self._external_pid > 0 and _pid_alive(self._external_pid)

    def mode(self) -> str:
        """'internal' / 'external' / 'stopped'"""
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                return "internal"
            if self._external_pid > 0 and _pid_alive(self._external_pid):
                return "external"
            return "stopped"

    def current_pid(self) -> int:
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                return self._proc.pid
            if self._external_pid > 0 and _pid_alive(self._external_pid):
                return self._external_pid
            return 0

    # ---------------- 启动 ----------------

    def start(self, frpc_path: str = "") -> bool:
        with self._lock:
            # 1) 内部已运行 → 直接返回成功
            if self._proc is not None and self._proc.poll() is None:
                self._log("[FRP] 已经在运行 (本程序启动, PID=%s)" % self._proc.pid)
                return True
            # 2) 先探测外部是否已经有 frpc 在跑
            ext = find_external_frpc()
            if ext:
                pid, cmd = ext[0]
                # 如果外部进程命令行参数与我们的配置匹配,则直接接管显示
                same_cfg = (FRPC_TOKEN in cmd and str(FRPC_SERVER_PORT) in cmd)
                tip = "参数匹配,直接接管" if same_cfg else "参数可能不同,依然接管显示"
                self._external_pid = pid
                self._external_cmd = cmd
                if len(ext) > 1:
                    self._log("[FRP] 检测到 %d 个 frpc 进程,接管 PID=%d (%s)" %
                              (len(ext), pid, tip))
                else:
                    self._log("[FRP] 检测到已在外部启动的 frpc, PID=%d (%s)" % (pid, tip))
                self._log("[FRP] 命令行: %s" % (cmd[:300] if len(cmd) > 300 else cmd))
                self._log("[FRP] 公网访问地址: http://kjzyyd.fucku.top")
                self._status("外部运行 (PID %d)" % pid)
                # 启动外部模式监控器(探测进程什么时候退出)
                self._stop_monitor.clear()
                if self._monitor_thr is None or not self._monitor_thr.is_alive():
                    self._monitor_thr = threading.Thread(
                        target=self._monitor_loop, name="frp-monitor", daemon=True
                    )
                    self._monitor_thr.start()
                return True
            # 3) 没有外部也没有内部 → 正常启动
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
                self._external_pid = 0
                self._external_cmd = ""
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
            if self._monitor_thr is None or not self._monitor_thr.is_alive():
                self._monitor_thr = threading.Thread(
                    target=self._monitor_loop, name="frp-monitor", daemon=True
                )
                self._monitor_thr.start()

            self._status("运行中")
            self._log("[FRP] 进程已启动, PID=%s" % self._proc.pid)
            self._log("[FRP] 公网访问地址: http://kjzyyd.fucku.top")
            return True

    # ---------------- 停止 ----------------

    def stop(self, kill_external: bool = False) -> None:
        """
        停止 frpc。
        :param kill_external: True=外部模式也强制杀进程; False=外部模式只取消接管
        """
        self._stop_monitor.set()
        with self._lock:
            # 内部模式:直接杀进程
            if self._proc is not None:
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
                return
            # 外部模式
            if self._external_pid > 0:
                pid = self._external_pid
                alive = _pid_alive(pid)
                if kill_external and alive:
                    try:
                        import psutil
                        psutil.Process(pid).terminate()
                        deadline = time.time() + 3
                        while time.time() < deadline and _pid_alive(pid):
                            time.sleep(0.1)
                        if _pid_alive(pid):
                            psutil.Process(pid).kill()
                        self._log("[FRP] 已结束外部 frpc 进程 PID=%d" % pid)
                    except Exception as e:
                        # 回退: taskkill / POSIX kill
                        try:
                            if platform.system() == "Windows":
                                subprocess.run(
                                    ["taskkill", "/F", "/PID", str(pid)],
                                    capture_output=True,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                                    timeout=5,
                                )
                            else:
                                os.kill(pid, 15)
                                time.sleep(1)
                                if _pid_alive(pid):
                                    os.kill(pid, 9)
                            self._log("[FRP] 已结束外部 frpc 进程 PID=%d" % pid)
                        except Exception as e2:
                            self._log("[FRP] 结束外部进程失败: %s / %s" % (e, e2))
                elif alive:
                    self._log("[FRP] 外部启动的 frpc(PID=%d)保留运行,仅取消本程序接管。" % pid)
                    self._log("[FRP] 如需强制结束,再次点击停止时请在弹窗中确认。" if False else
                              "[FRP] 如需强制结束该进程,请在外部关闭它,或右键菜单/命令行 kill。")
                self._external_pid = 0
                self._external_cmd = ""
                self._status("已停止")

    # ---------------- 内部线程 ----------------

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
                ext_pid = self._external_pid
            if proc is not None and proc.poll() is not None:
                code = proc.poll()
                self._log("[FRP] 进程退出, code=%s" % code)
                self._status("已退出 (code %s)" % code)
                with self._lock:
                    self._proc = None
                return
            if ext_pid > 0:
                if not _pid_alive(ext_pid):
                    self._log("[FRP] 外部 frpc 进程 PID=%d 已退出" % ext_pid)
                    self._status("外部进程已退出")
                    with self._lock:
                        self._external_pid = 0
                        self._external_cmd = ""
                    return
            time.sleep(0.5)
