//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var (
	kernel32DLL         = syscall.NewLazyDLL("kernel32.dll")
	procGetDiskFreeExW  = kernel32DLL.NewProc("GetDiskFreeSpaceExW")
)

func freeDiskBytes(dir string) (uint64, bool) {
	p, err := syscall.UTF16PtrFromString(dir)
	if err != nil {
		return 0, false
	}
	var avail uint64
	r1, _, _ := procGetDiskFreeExW.Call(
		uintptr(unsafe.Pointer(p)),
		uintptr(unsafe.Pointer(&avail)),
		0,
		0,
	)
	return avail, r1 != 0
}
