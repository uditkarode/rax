package libtailnet

import (
	"net"
	"net/netip"
	"reflect"
	"testing"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestBuildStatus(t *testing.T) {
	peer := func(dns string, online bool, ips ...string) *ipnstate.PeerStatus {
		p := &ipnstate.PeerStatus{DNSName: dns, HostName: "h-" + dns, OS: "linux", Online: online}
		for _, ip := range ips {
			p.TailscaleIPs = append(p.TailscaleIPs, netip.MustParseAddr(ip))
		}
		return p
	}
	mullvad := peer("se-sto-wg-001.mullvad.ts.net.", true, "100.90.0.1")
	mullvad.Location = &tailcfg.Location{Country: "Sweden"}
	st := &ipnstate.Status{
		BackendState:   "Running",
		CurrentTailnet: &ipnstate.TailnetStatus{Name: "user@example.com"},
		Self:           peer("rax-pixel-9.t.ts.net.", true, "100.64.0.1", "fd7a:115c:a1e0::1"),
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): peer("zed.t.ts.net.", false, "100.64.0.3"),
			key.NewNode().Public(): peer("desktop.t.ts.net.", true, "100.64.0.2"),
			key.NewNode().Public(): mullvad,
			key.NewNode().Public(): peer("", true),
		},
	}
	got := buildStatus(st, "", "")
	want := statusJSON{
		State:   "Running",
		Tailnet: "user@example.com",
		Self:    &peerJSON{"rax-pixel-9.t.ts.net", "h-rax-pixel-9.t.ts.net.", "linux", true, []string{"100.64.0.1", "fd7a:115c:a1e0::1"}},
		Peers: []peerJSON{
			{"desktop.t.ts.net", "h-desktop.t.ts.net.", "linux", true, []string{"100.64.0.2"}},
			{"zed.t.ts.net", "h-zed.t.ts.net.", "linux", false, []string{"100.64.0.3"}},
		},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got  %+v\nwant %+v", got, want)
	}
}

func TestBuildStatusNotRunning(t *testing.T) {
	st := &ipnstate.Status{
		BackendState: "NeedsLogin",
		AuthURL:      "https://login.tailscale.com/a/1",
		Self:         &ipnstate.PeerStatus{DNSName: "x."},
	}
	got := buildStatus(st, "boom", "")
	want := statusJSON{State: "NeedsLogin", AuthURL: "https://login.tailscale.com/a/1", Error: "boom"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v, want %+v", got, want)
	}
}

func TestBuildStatusLoggingRequired(t *testing.T) {
	st := &ipnstate.Status{BackendState: "Stopped"}
	got := buildStatus(st, "other", "tailnet requires logging")
	want := statusJSON{State: "Stopped", Error: "tailnet requires logging", LoggingRequired: true}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v, want %+v", got, want)
	}
}

func TestParseInterfaces(t *testing.T) {
	ifs, err := parseInterfaces(`[
		{"name":"wlan0","index":30,"mtu":1500,"up":true,"broadcast":true,"multicast":true,
		 "addrs":["192.168.1.20/24","fe80::1%wlan0/64","bogus","10.0.0.1/99"]},
		{"name":"rmnet0","index":12,"mtu":1400,"up":false,"pointToPoint":true,"addrs":[]}
	]`)
	if err != nil {
		t.Fatal(err)
	}
	if len(ifs) != 2 {
		t.Fatalf("got %d interfaces", len(ifs))
	}
	w := ifs[0]
	if w.Name != "wlan0" || w.Index != 30 || w.MTU != 1500 {
		t.Errorf("wlan0 = %+v", w.Interface)
	}
	if want := net.FlagUp | net.FlagRunning | net.FlagBroadcast | net.FlagMulticast; w.Flags != want {
		t.Errorf("wlan0 flags = %v, want %v", w.Flags, want)
	}
	addrs, _ := w.Addrs()
	var got []string
	for _, a := range addrs {
		got = append(got, a.String())
	}
	if want := []string{"192.168.1.20/24", "fe80::1/64"}; !reflect.DeepEqual(got, want) {
		t.Errorf("wlan0 addrs = %v, want %v", got, want)
	}
	r := ifs[1]
	if r.IsUp() || r.Flags&net.FlagPointToPoint == 0 {
		t.Errorf("rmnet0 flags = %v", r.Flags)
	}
	if addrs, err := r.Addrs(); err != nil || len(addrs) != 0 {
		t.Errorf("rmnet0 addrs = %v, %v", addrs, err)
	}
}
