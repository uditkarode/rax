package libtailnet

import (
	"testing"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"tailscale.com/types/empty"
)

func newTestNode(state ipn.State) *node {
	return &node{state: state, loginRequested: true}
}

func stateMsg(s ipn.State) *ipn.Notify { return &ipn.Notify{State: &s} }

func errMsg(s string) *ipn.Notify { return &ipn.Notify{ErrMessage: &s} }

func TestErrorLastsUntilTheBackendMovesOn(t *testing.T) {
	url := "https://login.tailscale.com/a/1"
	for name, next := range map[string]*ipn.Notify{
		"new state":       stateMsg(ipn.Starting),
		"login URL":       {BrowseToURL: &url},
		"login finished":  {LoginFinished: &empty.Message{}},
		"state and error": {State: new(ipn.Running), ErrMessage: new("again")},
	} {
		n := newTestNode(ipn.NeedsLogin)
		n.apply(errMsg("node registration rate limited"))
		if n.errMsg == "" {
			t.Fatalf("%s: error not recorded", name)
		}
		n.apply(next)
		want := ""
		if next.ErrMessage != nil {
			want = *next.ErrMessage
		}
		if n.errMsg != want {
			t.Errorf("%s: errMsg = %q, want %q", name, n.errMsg, want)
		}
	}
}

func TestErrorStaysWhileNothingChanges(t *testing.T) {
	n := newTestNode(ipn.NeedsLogin)
	n.apply(errMsg("boom"))
	n.apply(stateMsg(ipn.NeedsLogin))
	n.apply(&ipn.Notify{PeersRemoved: []tailcfg.NodeID{1}})
	if n.errMsg != "boom" {
		t.Errorf("errMsg = %q", n.errMsg)
	}
}

func TestBusFellBehindIsNotAnError(t *testing.T) {
	n := newTestNode(ipn.Starting)
	n.apply(errMsg("IPN bus consumer fell behind; closing watch"))
	if n.errMsg != "" || n.loggingErr != "" {
		t.Errorf("errMsg = %q, loggingErr = %q", n.errMsg, n.loggingErr)
	}
}

func TestLoggingRequiredOutlastsStateChanges(t *testing.T) {
	const msg = "tailnet requires logging to be enabled. Remove --no-logs-no-support from tailscaled command line."
	n := newTestNode(ipn.Starting)
	n.apply(errMsg(msg))
	n.apply(stateMsg(ipn.Stopped))
	n.apply(stateMsg(ipn.NeedsLogin))
	if n.loggingErr != msg || n.errMsg != "" {
		t.Errorf("loggingErr = %q, errMsg = %q", n.loggingErr, n.errMsg)
	}
	n.apply(stateMsg(ipn.Running))
	if n.loggingErr != "" {
		t.Errorf("loggingErr = %q after Running", n.loggingErr)
	}
}

func TestLoginRequestedOnceForNeedsLogin(t *testing.T) {
	n := newTestNode(ipn.Running)
	n.loginRequested = false
	if _, start := n.apply(stateMsg(ipn.NeedsLogin)); !start {
		t.Error("no login started for NeedsLogin")
	}
	if _, start := n.apply(stateMsg(ipn.NeedsLogin)); start {
		t.Error("a second login started")
	}
	n.apply(stateMsg(ipn.Running))
	if n.loginRequested {
		t.Error("loginRequested stays after Running")
	}
}

func TestAffectsStatus(t *testing.T) {
	online := true
	url := "https://x"
	for _, m := range []*ipn.Notify{
		stateMsg(ipn.Running),
		errMsg("boom"),
		{BrowseToURL: &url},
		{LoginFinished: &empty.Message{}},
		{SelfChange: &tailcfg.Node{}},
		{PeersChanged: []*tailcfg.Node{{}}},
		{PeersRemoved: []tailcfg.NodeID{1}},
		{PeerChangedPatch: []*tailcfg.PeerChange{{NodeID: 1}, {NodeID: 2, Online: &online}}},
	} {
		if !affectsStatus(m) {
			t.Errorf("affectsStatus(%+v) = false", m)
		}
	}
	for _, m := range []*ipn.Notify{
		{},
		{PeerChangedPatch: []*tailcfg.PeerChange{{NodeID: 1, DERPRegion: 3}, {NodeID: 2, LastSeen: new(time.Now())}}},
	} {
		if affectsStatus(m) {
			t.Errorf("affectsStatus(%+v) = true", m)
		}
	}
}
