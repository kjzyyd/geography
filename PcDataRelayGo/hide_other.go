//go:build !windows

package main

import "syscall"

// hideWindowAttr 非 Windows 平台无需隐藏窗口
func hideWindowAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{}
}
