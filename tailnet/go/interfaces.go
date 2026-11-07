package libtailnet

import (
	"encoding/json"
	"net"
	"net/netip"
	"strconv"
	"strings"

	"tailscale.com/net/netmon"
)

// interfaceJSON is one network interface as reported by Kotlin. Addrs are
// "ip/prefixlen"; IPv6 addresses may carry a "%zone".
type interfaceJSON struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Broadcast    bool     `json:"broadcast"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Multicast    bool     `json:"multicast"`
	Addrs        []string `json:"addrs"`
}

// parseInterfaces converts the Kotlin interface list for netmon. Go's
// net.Interfaces fails on Android because apps may not dump netlink routes.
func parseInterfaces(s string) ([]netmon.Interface, error) {
	var in []interfaceJSON
	if err := json.Unmarshal([]byte(s), &in); err != nil {
		return nil, err
	}
	out := make([]netmon.Interface, 0, len(in))
	for _, ji := range in {
		ni := &net.Interface{Index: ji.Index, MTU: ji.MTU, Name: ji.Name}
		for _, f := range []struct {
			set  bool
			flag net.Flags
		}{
			{ji.Up, net.FlagUp | net.FlagRunning},
			{ji.Broadcast, net.FlagBroadcast},
			{ji.Loopback, net.FlagLoopback},
			{ji.PointToPoint, net.FlagPointToPoint},
			{ji.Multicast, net.FlagMulticast},
		} {
			if f.set {
				ni.Flags |= f.flag
			}
		}
		// AltAddrs must be non-nil: netmon would otherwise ask the kernel.
		addrs := []net.Addr{}
		for _, a := range ji.Addrs {
			if ipn, ok := parseIfaceAddr(a); ok {
				addrs = append(addrs, ipn)
			}
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	return out, nil
}

func parseIfaceAddr(s string) (*net.IPNet, bool) {
	addrStr, bitsStr, ok := strings.Cut(s, "/")
	if !ok {
		return nil, false
	}
	ip, err := netip.ParseAddr(addrStr)
	if err != nil {
		return nil, false
	}
	bits, err := strconv.Atoi(bitsStr)
	if err != nil || bits < 0 || bits > ip.BitLen() {
		return nil, false
	}
	ip = ip.WithZone("")
	return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(bits, ip.BitLen())}, true
}
