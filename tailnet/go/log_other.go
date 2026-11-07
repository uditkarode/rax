//go:build !android

package libtailnet

import "log"

func setupLogging() {}

func logVerbosef(format string, args ...any) {}
func logDebugf(format string, args ...any)   {}
func logInfof(format string, args ...any)    { log.Printf(format, args...) }
func logWarnf(format string, args ...any)    { log.Printf(format, args...) }
func logErrorf(format string, args ...any)   { log.Printf(format, args...) }
