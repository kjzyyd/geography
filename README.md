# 地理位置监控系统

一个基于 Android 手机 + 电脑「中转站」+「服务端」的轻量地理位置上报与管理项目。

## 项目组成

| 组件 | 说明 |
|------|------|
| `AndroidLocationClient` | Android 手机端,后台持续上报 GPS 位置 |
| `AndroidLocationServer` | Android 服务端(报警中心),接收 & 展示各客户端到本机的距离 |
| `PcDataRelay` | 电脑端中转站,把手机上报的数据转给服务端,并支持删除客户端、查看实时列表 |

## 客户端(手机)工作原理

- **省电 + 不阻止休眠**:全程不持有 WakeLock,静止时不做连续定位,系统可正常进入休眠。
- **加速度计门控**:用极低功耗的加速度计判断是否在移动。
  - **静止**:每 **2 分钟** 做一次单次定位并上报,结束立即释放 GPS。
  - **移动**:切到连续定位,每 **5 秒** 上报一次。
  - **位移 ≥ 20 米**:立即上报。
  - 停止移动超过 1 分钟自动回到静止省电模式。
- 主动上报:数据由手机通过 HTTP POST 推送到中转站/服务端,无需服务端轮询。
- 开机自启:设备重启后自动恢复后台运行。
- 无需界面:不弹窗、不显示消息,通知为最低优先级且无声音。

## 构建

### 客户端 APK
```bash
./build_client_manual.sh
# 输出: ./LocationClient-debug.apk
```

### 服务端 APK
```bash
./build_server_manual.sh
```

## 安装手机端

普通安装(后续可在系统设置里手动关闭电池优化):

```bash
adb install -r LocationClient-debug.apk
```

首次打开图标会弹出位置权限申请,授予后即开始后台上报,之后无任何其它界面与提示。

### 变成系统应用(推荐,可彻底避免被系统清理/省电限制)
需要**已 root** 的手机,连电脑并开启 USB 调试后:

```bash
# 将 APK 与本脚本放同一目录
adb push install_as_system_app.sh /data/local/tmp/
adb shell "sh /data/local/tmp/install_as_system_app.sh"
```

脚本会:卸载旧版本 → 把 APK 推到 `/system/app/` → 重启设备 → 加入后台白名单。
成为系统应用后,系统不会对它做电池优化、强制停止或内存优先回收,最大化后台存活率。

## 配置

手机端服务端地址可在安装目录的配置文件里修改:

- 外部存储 `Download/location_client_config.txt` 或
- 应用内部 `client_config.txt`

格式(每行一个键值对):

```
server_url=http://192.168.1.100:8080/location
device_id=client_001
```

## 电脑端中转站

```bash
python3 main.py
```

功能:
- 实时客户端列表(带刷新,5 秒更新)
- 服务端与中转站数据实时同步
- 在电脑上删除特定客户端
- 转发手机数据到服务端

## 服务端(Android)

- 显示每个客户端到「服务端本机」的距离
- 每个客户端独立报警开关 + 总报警开关
- 显示客户端备注
- 阈值可保存,报警可单独或全部开关