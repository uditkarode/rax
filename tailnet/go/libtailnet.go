// Package libtailnet runs a userspace Tailscale node (tsnet) inside the Rax
// Android app. It is bound to Kotlin with gomobile; the Kotlin wrapper in
// de.uditkaro.rax.tailnet is the only caller.
package libtailnet

import (
	"sync"

	_ "golang.org/x/mobile/bind"
	"tailscale.com/net/netmon"
)

// Host is implemented in Kotlin.
type Host interface {
	// Interfaces returns the device's network interfaces as a JSON array
	// (see interfaceJSON).
	Interfaces() string

	// OnStatus receives the node status as a JSON object (see statusJSON)
	// each time it changes.
	OnStatus(status string)
}

var (
	initOnce sync.Once
	host     Host
)

// Init must be called once before any other function in this package.
func Init(h Host) {
	initOnce.Do(func() {
		host = h
		setupLogging()
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			return parseInterfaces(host.Interfaces())
		})
	})
}
