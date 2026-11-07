package libtailnet

import (
	"cmp"
	"slices"
	"strings"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
)

// statusJSON is the status sent to Kotlin. State is an ipn.State name.
// Self and Peers are set only in state Running.
type statusJSON struct {
	State   string     `json:"state"`
	AuthURL string     `json:"authURL,omitempty"`
	Error   string     `json:"error,omitempty"`
	Tailnet string     `json:"tailnet,omitempty"`
	Self    *peerJSON  `json:"self,omitempty"`
	Peers   []peerJSON `json:"peers,omitempty"`
}

type peerJSON struct {
	DNSName  string   `json:"dnsName"`
	HostName string   `json:"hostName"`
	OS       string   `json:"os,omitempty"`
	Online   bool     `json:"online"`
	IPs      []string `json:"ips"`
}

func buildStatus(st *ipnstate.Status, errMsg string) statusJSON {
	s := statusJSON{
		State:   st.BackendState,
		AuthURL: st.AuthURL,
		Error:   errMsg,
	}
	if st.CurrentTailnet != nil {
		s.Tailnet = st.CurrentTailnet.Name
	}
	if st.BackendState != ipn.Running.String() {
		return s
	}
	if st.Self != nil {
		self := toPeerJSON(st.Self)
		s.Self = &self
	}
	for _, p := range st.Peer {
		// Location is set only on Mullvad exit nodes, which are not
		// computers the app can connect to.
		if p.DNSName == "" || p.Location != nil {
			continue
		}
		s.Peers = append(s.Peers, toPeerJSON(p))
	}
	slices.SortFunc(s.Peers, func(a, b peerJSON) int { return cmp.Compare(a.DNSName, b.DNSName) })
	return s
}

func toPeerJSON(p *ipnstate.PeerStatus) peerJSON {
	ips := make([]string, 0, len(p.TailscaleIPs))
	for _, ip := range p.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	return peerJSON{
		DNSName:  strings.TrimSuffix(p.DNSName, "."),
		HostName: p.HostName,
		OS:       p.OS,
		Online:   p.Online,
		IPs:      ips,
	}
}
