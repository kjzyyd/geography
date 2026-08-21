#!/bin/bash
# 将客户端 APK 作为【系统应用】安装(需要设备已 root)。
# 用法: 将本脚本和 LocationClient-debug.apk 放到同一目录,
#       设备连接电脑并开启 USB 调试(root 授权)后执行:
#       ./install_as_system_app.sh
#
# 原理: 把 APK 推到 /system/app/ 并赋予系统级权限。成为系统应用后,
#       Android 系统不会把它当普通后台应用进行电池优化/强制停止,
#       内存不足时也不会优先杀掉,从而最大限度避免被系统检测和清理。
set -e

APK=LocationClient-debug.apk
PACKAGE=com.example.locationclient
[ -f "$APK" ] || { echo "未找到 $APK,请先构建"; exit 1; }

echo "=== 1. 确认 adb / root ==="
command -v adb >/dev/null || { echo "未安装 adb"; exit 1; }
adb wait-for-device
ADBROOT=$(adb shell "su -c 'echo ok'" 2>/dev/null || echo "fail1")
if [ "$ADBROOT" != "ok" ]; then
  # 部分设备用二进制的 su 需要普通调用
  ADBROOT=$(adb shell "echo ok")
fi
echo "adb 连接正常"

echo "=== 2. 卸载旧普通版本(若有) ==="
adb uninstall $PACKAGE >/dev/null 2>&1 || true
adb shell "su -c 'pm uninstall $PACKAGE'" >/dev/null 2>&1 || true

echo "=== 3. 认读 system 分区 ==="
adb shell "su -c 'mount -o rw,remount /system'" >/dev/null 2>&1 \
  || adb shell "su -c 'mount -o rw,remount /'" >/dev/null 2>&1 \
  || { echo "无法 remount system 分区,需 root"; exit 1; }

echo "=== 4. 推送 APK 到系统目录 ==="
SYSDIR=/system/app/LocationClient
adb shell "su -c 'mkdir -p $SYSDIR'"
adb push $APK /data/local/tmp/LocationClient.apk
adb shell "su -c 'cp /data/local/tmp/LocationClient.apk $SYSDIR/$APK && chmod 0644 $SYSDIR/$APK && chown root:root $SYSDIR/$APK && rm /data/local/tmp/LocationClient.apk'"

echo "=== 5. 重启设备完成安装 ==="
adb reboot
echo "系统应用安装完成,等待设备重启..."
adb wait-for-device
adb shell "su -c 'pm background-whitelist $PACKAGE'" >/dev/null 2>&1 || true
echo "完成!该应用已成为系统应用,耗电与清理限制将大幅降低。"