"""
PcDataRelay - 电脑位置数据中转服务（图形界面）
功能:
  * 启动 HTTP 服务器（端口 9178）, 接收安卓客户端上报位置
  * 启动 frpc.exe 把 9178 端口暴露到公网（域名 kjzyyd.fucku.top）
  * GUI 实时显示: 运行状态、客户端列表、访问地址、日志、距离报警
  * 一键打开浏览器查看内置地图
  * 托盘最小化

运行方式:  python main.py   (Windows 可用 pythonw 无黑框)
"""

import json
import os
import queue
import sys
import threading
import time
import tkinter as tk
import webbrowser
from datetime import datetime
from tkinter import ttk, messagebox, filedialog

from http_server import HttpServerThread, DEFAULT_PORT
from frpc_manager import FrpcManager, find_frpc, FRPC_SERVER_PORT, FRPC_TOKEN
from location_store import LocationStore
from geo_utils import haversine_meters, format_distance


PUBLIC_BASE = "http://kjzyyd.fucku.top"


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("位置数据中转服务 | PcDataRelay")
        self.root.geometry("960x680")
        self.root.minsize(820, 560)

        # 跨线程 UI 刷新队列
        self.log_queue: "queue.Queue[str]" = queue.Queue()

        # HTTP 服务 & FRP
        self.http_thread: HttpServerThread = None
        self.frpc = FrpcManager(log_cb=self._log_any, status_cb=self._on_frpc_status)

        # 报警状态
        self.alert_states = {}
        self.alert_center = None
        self.alert_threshold_meters = 500.0
        self.store = LocationStore()

        # 自定义 frpc 路径
        self._custom_frpc_path = ""

        self._build_ui()
        self._schedule_drain_queue()
        self._schedule_refresh_ui()

        # 启动时自动启动 HTTP
        self.root.after(300, self._auto_start)

    # ----------------- UI -----------------
    def _build_ui(self):
        top = ttk.Frame(self.root, padding=10)
        top.pack(fill=tk.X)

        ttk.Label(top, text="位置数据中转服务", font=("Segoe UI", 16, "bold")).pack(side=tk.LEFT)

        # 状态徽章
        self.badge_http = tk.Label(top, text="HTTP: 未启动", bg="#999", fg="white",
                                    padx=8, pady=2, font=("Segoe UI", 9, "bold"))
        self.badge_http.pack(side=tk.LEFT, padx=(16, 4))
        self.badge_frp = tk.Label(top, text="FRP: 未启动", bg="#999", fg="white",
                                   padx=8, pady=2, font=("Segoe UI", 9, "bold"))
        self.badge_frp.pack(side=tk.LEFT, padx=4)

        # 中间区域: 左侧信息 + 右侧控制
        body = ttk.PanedWindow(self.root, orient=tk.HORIZONTAL)
        body.pack(fill=tk.BOTH, expand=True, padx=10, pady=4)

        # 左侧: 信息面板
        left = ttk.Frame(body)
        body.add(left, weight=3)

        info = ttk.LabelFrame(left, text="访问地址 / 状态", padding=10)
        info.pack(fill=tk.X)

        self.lbl_lan = ttk.Label(info, text="局域网: 等待 HTTP 启动")
        self.lbl_lan.pack(anchor=tk.W)
        self.lbl_public = ttk.Label(info, text="公网:  %s" % PUBLIC_BASE)
        self.lbl_public.pack(anchor=tk.W)
        self.lbl_count = ttk.Label(info, text="客户端数: 0", font=("Segoe UI", 10, "bold"))
        self.lbl_count.pack(anchor=tk.W, pady=(6, 0))

        # 报警中心
        alert = ttk.LabelFrame(left, text="报警设置", padding=10)
        alert.pack(fill=tk.X, pady=(8, 0))

        r1 = ttk.Frame(alert); r1.pack(fill=tk.X)
        ttk.Label(r1, text="报警中心坐标 (lat,lng): ").pack(side=tk.LEFT)
        self.var_center = tk.StringVar()
        ttk.Entry(r1, textvariable=self.var_center).pack(side=tk.LEFT, fill=tk.X, expand=True, padx=4)
        ttk.Button(r1, text="使用当前本机IP定位", width=16, command=self._use_my_ip).pack(side=tk.LEFT)

        r2 = ttk.Frame(alert); r2.pack(fill=tk.X, pady=(6, 0))
        ttk.Label(r2, text="阈值(米): ").pack(side=tk.LEFT)
        self.var_threshold = tk.StringVar(value="500")
        ttk.Entry(r2, textvariable=self.var_threshold, width=10).pack(side=tk.LEFT)
        ttk.Button(r2, text="应用阈值", command=self._apply_threshold).pack(side=tk.LEFT, padx=8)

        # 客户端列表
        clist = ttk.LabelFrame(left, text="客户端列表", padding=6)
        clist.pack(fill=tk.BOTH, expand=True, pady=(8, 0))
        self.client_list = tk.Text(clist, height=10, wrap=tk.WORD, font=("Consolas", 9))
        self.client_list.pack(fill=tk.BOTH, expand=True)

        # 右侧: 控制 + 日志
        right = ttk.Frame(body)
        body.add(right, weight=2)

        ctrl = ttk.LabelFrame(right, text="控制", padding=10)
        ctrl.pack(fill=tk.X)

        self.btn_toggle_http = ttk.Button(ctrl, text="启动 HTTP", command=self._toggle_http)
        self.btn_toggle_http.pack(fill=tk.X, pady=2)
        self.btn_toggle_frpc = ttk.Button(ctrl, text="启动 FRP", command=self._toggle_frpc)
        self.btn_toggle_frpc.pack(fill=tk.X, pady=2)
        self.btn_pick_frpc = ttk.Button(ctrl, text="选择 frpc.exe 路径…", command=self._pick_frpc)
        self.btn_pick_frpc.pack(fill=tk.X, pady=2)
        self.btn_open_map = ttk.Button(ctrl, text="打开浏览器看地图", command=self._open_map)
        self.btn_open_map.pack(fill=tk.X, pady=2)
        self.btn_clear = ttk.Button(ctrl, text="清空位置 / 日志", command=self._clear_all)
        self.btn_clear.pack(fill=tk.X, pady=2)

        logf = ttk.LabelFrame(right, text="运行日志", padding=6)
        logf.pack(fill=tk.BOTH, expand=True, pady=(8, 0))
        self.log_txt = tk.Text(logf, height=14, wrap=tk.WORD, font=("Consolas", 9),
                                state=tk.DISABLED, bg="#1e1e1e", fg="#dcdcdc")
        self.log_txt.pack(fill=tk.BOTH, expand=True)

        # 状态栏
        self.status = ttk.Label(self.root, text="就绪", anchor=tk.W, relief=tk.SUNKEN)
        self.status.pack(fill=tk.X, side=tk.BOTTOM)

    # ----------------- 自动启动 -----------------
    def _auto_start(self):
        self._toggle_http()  # 启动 HTTP

    # ----------------- HTTP -----------------
    def _toggle_http(self):
        if self.http_thread and self.http_thread.running:
            self.http_thread.stop()
            self.http_thread = None
            self.btn_toggle_http.config(text="启动 HTTP")
            self._set_badge(self.badge_http, "HTTP: 已停止", "#C62828")
            self.lbl_lan.config(text="局域网: HTTP 未启动")
        else:
            t = HttpServerThread(self.store, port=DEFAULT_PORT, log_cb=self._log_any)
            if t.start():
                self.http_thread = t
                self.btn_toggle_http.config(text="停止 HTTP")
                self._set_badge(self.badge_http, "HTTP: 运行中", "#2E7D32")
                import socket
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.connect(("8.8.8.8", 80))
                    ip = s.getsockname()[0]
                    s.close()
                except Exception:
                    ip = "<本机IP>"
                self.lbl_lan.config(text="局域网: http://%s:%s" % (ip, DEFAULT_PORT))
            else:
                messagebox.showerror("启动失败", "HTTP 服务器启动失败，查看日志")

    # ----------------- FRP -----------------
    def _pick_frpc(self):
        init = self._custom_frpc_path or find_frpc() or ""
        p = filedialog.askopenfilename(title="选择 frpc.exe",
                                       initialdir=os.path.dirname(init) or os.getcwd(),
                                       filetypes=[("可执行文件", "*.exe"), ("所有文件", "*.*")])
        if p:
            self._custom_frpc_path = p
            self._log_any("[GUI] 已设置 frpc 路径: " + p)

    def _toggle_frpc(self):
        if self.frpc.is_running():
            mode = self.frpc.mode()
            if mode == "external":
                # 外部模式:默认只取消接管,询问是否连外部进程一起杀掉
                pid = self.frpc.current_pid()
                kill = messagebox.askyesno(
                    "停止 FRP (外部启动)",
                    "检测到 frpc 是在程序外部启动的 (PID=%d)。\n\n"
                    "点击「是」：强制结束该外部 frpc 进程\n"
                    "点击「否」：仅取消本程序的接管显示(frpc 继续在外部运行)" % pid,
                )
                self.btn_toggle_frpc.config(text="停止中...", state=tk.DISABLED)
                self._log_any("[FRP] 正在停止(外部模式, kill=%s)..." % ("是" if kill else "否"))
                def _do_stop_ext():
                    self.frpc.stop(kill_external=kill)
                    self.root.after(0, lambda: self._on_frpc_stop_done())
                threading.Thread(target=_do_stop_ext, name="frp-stop-ext", daemon=True).start()
            else:
                self.btn_toggle_frpc.config(text="停止中...", state=tk.DISABLED)
                self._log_any("[FRP] 正在停止...")

                def _do_stop():
                    self.frpc.stop()
                    self.root.after(0, lambda: self._on_frpc_stop_done())

                threading.Thread(target=_do_stop, name="frp-stop", daemon=True).start()
        else:
            self._start_frpc_dialog()

    def _on_frpc_stop_done(self):
        self.btn_toggle_frpc.config(state=tk.NORMAL)
        self.btn_toggle_frpc.config(text="启动 FRP")

    def _start_frpc_dialog(self):
        path = self._custom_frpc_path or find_frpc()
        if not path:
            ret = messagebox.askyesno(
                "未找到 frpc",
                "在当前目录/frp 子目录/PATH 中都没找到 frpc.exe。\n"
                "是否先手动选择 frpc.exe 路径？\n\n"
                "下载地址（Windows amd64）：\n"
                "https://github.com/fatedier/frp/releases"
            )
            if ret:
                self._pick_frpc()
            return
        self.btn_toggle_frpc.config(text="启动中...", state=tk.DISABLED)
        self._log_any("[FRP] 正在后台启动...")

        def _do_start():
            ok = self.frpc.start(path)
            self.root.after(0, lambda: self._on_frpc_start_done(ok))

        threading.Thread(target=_do_start, name="frp-start", daemon=True).start()

    def _on_frpc_start_done(self, ok: bool):
        self.btn_toggle_frpc.config(state=tk.NORMAL)
        if ok:
            self.btn_toggle_frpc.config(text="停止 FRP")
        else:
            self.btn_toggle_frpc.config(text="启动 FRP")
            messagebox.showerror("启动失败", "FRP 未能启动。请查看日志。")

    def _on_frpc_status(self, text):
        def apply():
            if "运行" in text or "外部" in text:
                self._set_badge(self.badge_frp, "FRP: " + text, "#2E7D32")
            elif "未找到" in text or "失败" in text or "退出" in text:
                self._set_badge(self.badge_frp, "FRP: " + text, "#C62828")
            else:
                self._set_badge(self.badge_frp, "FRP: " + text, "#888")
        self.root.after(0, apply)

    # ----------------- 辅助 -----------------
    def _set_badge(self, widget, text, color):
        widget.config(text=text, bg=color)

    def _open_map(self):
        url = "http://127.0.0.1:%s/map" % DEFAULT_PORT
        if self.http_thread and self.http_thread.running:
            webbrowser.open(url)
        else:
            if messagebox.askyesno("HTTP 未启动", "需要先启动 HTTP 服务。是否启动？"):
                self._toggle_http()
                self.root.after(500, lambda: webbrowser.open(url))

    def _apply_threshold(self):
        try:
            v = float(self.var_threshold.get())
            if v < 5:
                raise ValueError
            self.alert_threshold_meters = v
            self._log_any("[GUI] 报警阈值已应用: %.0f 米" % v)
        except Exception:
            messagebox.showerror("参数错误", "阈值必须是不小于 5 的数字")

    def _use_my_ip(self):
        # 简化: 让用户自行输入，或者调用外部 IP 定位 API
        messagebox.showinfo("提示", "请手动在输入框填入报警中心坐标，格式：纬度,经度\n"
                                    "例如：30.2741,120.1551")

    def _clear_all(self):
        n = self.store.count()
        self.store.clear()
        self.alert_states.clear()
        self.client_list.delete("1.0", tk.END)
        self.log_txt.config(state=tk.NORMAL)
        self.log_txt.delete("1.0", tk.END)
        self.log_txt.config(state=tk.DISABLED)
        self.status.config(text="已清空。原客户端数: %d" % n)
        self._refresh_clients_ui()

    # ----------------- 日志队列 -----------------
    def _log_any(self, msg: str):
        self.log_queue.put(msg)

    def _schedule_drain_queue(self):
        try:
            pending = []
            while True:
                pending.append(self.log_queue.get_nowait())
        except queue.Empty:
            pass
        if pending:
            ts = datetime.now().strftime("%H:%M:%S")
            chunk = "".join("[%s] %s\n" % (ts, m) for m in pending)
            self.log_txt.config(state=tk.NORMAL)
            self.log_txt.insert(tk.END, chunk)
            self.log_txt.see(tk.END)
            # 最多保留 5000 行
            cnt = int(self.log_txt.index("end-1c").split(".")[0])
            if cnt > 5000:
                self.log_txt.delete("1.0", "%d.0" % (cnt - 5000))
            self.log_txt.config(state=tk.DISABLED)
        self.root.after(150, self._schedule_drain_queue)

    # ----------------- UI 定时刷新 -----------------
    def _schedule_refresh_ui(self):
        self._refresh_clients_ui()
        self.root.after(1500, self._schedule_refresh_ui)

    def _parse_center(self):
        raw = self.var_center.get().strip()
        if not raw:
            return None
        try:
            parts = [float(x) for x in raw.replace(" ", "").split(",")]
            if len(parts) == 2:
                return parts[0], parts[1]
        except Exception:
            pass
        return None

    def _refresh_clients_ui(self):
        center = self._parse_center()
        items = self.store.get_all()
        items.sort(key=lambda r: r.timestamp, reverse=True)
        self.lbl_count.config(text="客户端数: %d" % len(items))

        lines = []
        self.client_list.delete("1.0", tk.END)
        now = time.time()
        for it in items:
            age = now - it.timestamp if it.timestamp else 0
            age_str = ("%d秒前" % age) if age < 60 else ("%.0f分钟前" % (age / 60)) if age < 3600 else ("%.1f小时前" % (age / 3600))
            dist = -1.0
            if center:
                dist = haversine_meters(center[0], center[1], it.latitude, it.longitude)
            dist_str = format_distance(dist) if dist >= 0 else "未设中心"

            alert_flag = ""
            if dist >= 0 and self.alert_threshold_meters > 0 and dist <= self.alert_threshold_meters:
                prev_alert = self.alert_states.get(it.device_id)
                if not prev_alert:
                    self.alert_states[it.device_id] = True
                    self._log_any("[警报] %s 进入阈值范围 (%.0f 米)" % (it.device_id, dist))
                    self.root.bell()
                alert_flag = " ⚠进范围"
            else:
                self.alert_states[it.device_id] = False

            line = ("%s%s\n  位置: %.6f, %.6f  精度±%.0fm\n  距离: %s   上报时间: %s (%s)\n" %
                    (it.device_id, alert_flag, it.latitude, it.longitude, it.accuracy,
                     dist_str, it.time_str or "-", age_str))
            lines.append(line)

        if not lines:
            self.client_list.insert(tk.END, "暂无客户端数据。\n请在客户端安装「位置服务」APK 并授予位置权限，\n"
                                            "确保客户端上报地址指向  %s/location 。\n" % PUBLIC_BASE)
        else:
            self.client_list.insert(tk.END, "".join(lines))


def main():
    root = tk.Tk()
    try:
        style = ttk.Style()
        if "vista" in style.theme_names():
            style.theme_use("vista")
        elif "clam" in style.theme_names():
            style.theme_use("clam")
    except Exception:
        pass
    app = App(root)
    try:
        root.mainloop()
    finally:
        try:
            if app.http_thread:
                app.http_thread.stop()
        except Exception:
            pass
        try:
            app.frpc.stop()
        except Exception:
            pass


if __name__ == "__main__":
    main()
