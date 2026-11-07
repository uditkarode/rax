package libtailnet

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>
#include <android/log.h>
*/
import "C"

import (
	"fmt"
	"log"
	"strings"
	"unsafe"
)

// logTag is the logcat tag. Default level is INFO; to see the node's
// debug output run: adb shell setprop log.tag.RaxTailnet VERBOSE
var logTag = C.CString("RaxTailnet")

func setupLogging() {
	log.SetFlags(0)
	log.SetOutput(logWriter{})
}

// logWriter takes output of Go's log package, which some Tailscale
// packages use directly.
type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	if loggable(C.ANDROID_LOG_DEBUG) {
		write(C.ANDROID_LOG_DEBUG, strings.TrimSuffix(string(p), "\n"))
	}
	return len(p), nil
}

func loggable(prio C.int) bool {
	return C.__android_log_is_loggable(prio, logTag, C.ANDROID_LOG_INFO) != 0
}

func write(prio C.int, msg string) {
	cs := C.CString(msg)
	C.__android_log_write(prio, logTag, cs)
	C.free(unsafe.Pointer(cs))
}

func logf(prio C.int, format string, args ...any) {
	if loggable(prio) {
		write(prio, fmt.Sprintf(format, args...))
	}
}

// logVerbosef takes tsnet's backend log, which is very chatty.
func logVerbosef(format string, args ...any) { logf(C.ANDROID_LOG_VERBOSE, format, args...) }
func logDebugf(format string, args ...any)   { logf(C.ANDROID_LOG_DEBUG, format, args...) }
func logInfof(format string, args ...any)    { logf(C.ANDROID_LOG_INFO, format, args...) }
func logWarnf(format string, args ...any)    { logf(C.ANDROID_LOG_WARN, format, args...) }
func logErrorf(format string, args ...any)   { logf(C.ANDROID_LOG_ERROR, format, args...) }
