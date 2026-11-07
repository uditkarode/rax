package libtailnet

import "tailscale.com/net/netmon"

// NetworkChanged tells the node that the default network changed. ifName
// is the default network's interface name, or "" if there is no network.
// gateway is its IPv4 default gateway, or "".
func NetworkChanged(ifName, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	netmon.UpdateLastKnownDefaultGateway(gateway)
	if n := current(); n != nil {
		if nm, ok := n.srv.Sys().NetMon.GetOK(); ok {
			nm.InjectEvent()
		}
	}
}
