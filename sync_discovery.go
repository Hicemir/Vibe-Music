package main

import (
	"context"
	"encoding/json"
	"net"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/ipv4"
)

type syncDiscovery struct {
	mgr    *SyncManager
	mu     sync.Mutex
	conn   net.PacketConn
	cancel context.CancelFunc
	peers  map[string]SyncPeer
}

func newSyncDiscovery(mgr *SyncManager) *syncDiscovery {
	return &syncDiscovery{
		mgr:   mgr,
		peers: map[string]SyncPeer{},
	}
}

func localIPv4sAndBroadcasts() (ips []string, bcasts []net.IP) {
	seenIP := map[string]bool{}
	seenBC := map[string]bool{}
	addBC := func(ip net.IP) {
		if ip == nil {
			return
		}
		v4 := ip.To4()
		if v4 == nil {
			return
		}
		s := v4.String()
		if !seenBC[s] {
			seenBC[s] = true
			bcasts = append(bcasts, v4)
		}
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if (iface.Flags&net.FlagUp) == 0 || (iface.Flags&net.FlagLoopback) != 0 {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, addr := range addrs {
				ipNet, ok := addr.(*net.IPNet)
				if !ok {
					continue
				}
				v4 := ipNet.IP.To4()
				if v4 == nil || v4.IsLoopback() || v4.IsLinkLocalUnicast() {
					continue
				}
				s := v4.String()
				if !seenIP[s] {
					seenIP[s] = true
					ips = append(ips, s)
				}
				if len(ipNet.Mask) == 4 && (iface.Flags&net.FlagBroadcast) != 0 {
					bc := make(net.IP, 4)
					for i := 0; i < 4; i++ {
						bc[i] = v4[i] | ^ipNet.Mask[i]
					}
					addBC(bc)
				}
			}
		}
	}
	addBC(net.IPv4bcast)
	addBC(net.ParseIP(syncMulticastAddr))
	sort.Strings(ips)
	return ips, bcasts
}

func (d *syncDiscovery) Start() error {
	d.mu.Lock()
	if d.cancel != nil {
		d.mu.Unlock()
		return nil
	}
	conn, err := net.ListenPacket("udp4", ":53318")
	if err != nil {
		// Fall back to ephemeral UDP port if 53318 is occupied; still able to send & receive unicast replies.
		conn, err = net.ListenPacket("udp4", ":0")
		if err != nil {
			d.mu.Unlock()
			return err
		}
	}
	// Best-effort join multicast group across interfaces
	if uc, ok := conn.(*net.UDPConn); ok {
		group := &net.UDPAddr{IP: net.ParseIP(syncMulticastAddr)}
		if ifaces, err := net.Interfaces(); err == nil {
			for i := range ifaces {
				if (ifaces[i].Flags&net.FlagUp) != 0 && (ifaces[i].Flags&net.FlagMulticast) != 0 && (ifaces[i].Flags&net.FlagLoopback) == 0 {
					_ = joinMulticastOnIface(uc, &ifaces[i], group)
				}
			}
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	d.conn = conn
	d.cancel = cancel
	d.peers = map[string]SyncPeer{}
	d.mu.Unlock()

	go d.readLoop(ctx, conn)
	go d.announceLoop(ctx)
	return nil
}

func (d *syncDiscovery) Stop() {
	d.mu.Lock()
	cancel := d.cancel
	conn := d.conn
	d.cancel = nil
	d.conn = nil
	d.peers = map[string]SyncPeer{}
	d.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if conn != nil {
		_ = conn.Close()
	}
	d.mgr.emitPeers()
}

func (d *syncDiscovery) Peers() []SyncPeer {
	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now().Unix()
	out := make([]SyncPeer, 0, len(d.peers))
	for id, p := range d.peers {
		if now-p.LastSeen > 10 {
			delete(d.peers, id)
			continue
		}
		_, trusted := d.mgr.app.store.GetTrustedSyncPeer(p.DeviceID)
		p.Trusted = trusted
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Trusted != out[j].Trusted {
			return out[i].Trusted
		}
		if out[i].DeviceName != out[j].DeviceName {
			return out[i].DeviceName < out[j].DeviceName
		}
		return out[i].DeviceID < out[j].DeviceID
	})
	return out
}

func (d *syncDiscovery) UpsertManualPeer(p SyncPeer) {
	d.mu.Lock()
	p.LastSeen = time.Now().Unix()
	d.peers[p.DeviceID] = p
	d.mu.Unlock()
	d.mgr.emitPeers()
}

func (d *syncDiscovery) buildPacket(pktType string) ([]byte, error) {
	info := d.mgr.selfInfo()
	id := d.mgr.identitySnapshot()
	pkt := syncDiscoveryPacket{
		Magic:         syncDiscoveryMagic,
		Type:          pktType,
		DeviceID:      id.DeviceID,
		DeviceName:    info.DeviceName,
		Platform:      runtime.GOOS,
		Port:          info.Port,
		TrackCount:    info.TrackCount,
		PlaylistCount: info.PlaylistCount,
		PubKeySpkiB64: id.PubSpkiB64,
	}
	return json.Marshal(pkt)
}

func (d *syncDiscovery) SendReverseRequest(peerIP, targetDeviceID string, syncDeletions bool) {
	d.mu.Lock()
	conn := d.conn
	d.mu.Unlock()
	if conn == nil {
		return
	}
	info := d.mgr.selfInfo()
	id := d.mgr.identitySnapshot()
	pkt := syncDiscoveryPacket{
		Magic:          syncDiscoveryMagic,
		Type:           "reverse_request",
		DeviceID:       id.DeviceID,
		DeviceName:     info.DeviceName,
		Platform:       runtime.GOOS,
		Port:           info.Port,
		TrackCount:     info.TrackCount,
		PlaylistCount:  info.PlaylistCount,
		PubKeySpkiB64:  id.PubSpkiB64,
		TargetDeviceID: targetDeviceID,
		SyncDeletions:  syncDeletions,
	}
	b, err := json.Marshal(pkt)
	if err != nil {
		return
	}
	ip := net.ParseIP(peerIP)
	if ip == nil {
		return
	}
	_, _ = conn.WriteTo(b, &net.UDPAddr{IP: ip, Port: syncDefaultPort})
}

func (d *syncDiscovery) announceOnce() {
	d.mu.Lock()
	conn := d.conn
	d.mu.Unlock()
	if conn == nil {
		return
	}
	b, err := d.buildPacket("announce")
	if err != nil {
		return
	}
	_, bcasts := localIPv4sAndBroadcasts()
	for _, dst := range bcasts {
		_, _ = conn.WriteTo(b, &net.UDPAddr{IP: dst, Port: syncDefaultPort})
	}
}

func (d *syncDiscovery) announceLoop(ctx context.Context) {
	d.announceOnce()
	tk := time.NewTicker(2500 * time.Millisecond)
	defer tk.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tk.C:
			d.announceOnce()
			// Prune expired peers periodically
			d.mu.Lock()
			now := time.Now().Unix()
			pruned := false
			for id, p := range d.peers {
				if now-p.LastSeen > 10 {
					delete(d.peers, id)
					pruned = true
				}
			}
			d.mu.Unlock()
			if pruned {
				d.mgr.emitPeers()
			}
		}
	}
}

func (d *syncDiscovery) readLoop(ctx context.Context, conn net.PacketConn) {
	buf := make([]byte, 2048)
	selfID := d.mgr.identitySnapshot().DeviceID
	for {
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, addr, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			return
		}
		if n <= 0 || n > 1536 {
			continue
		}
		var pkt syncDiscoveryPacket
		if json.Unmarshal(buf[:n], &pkt) != nil {
			continue
		}
		if pkt.Magic != syncDiscoveryMagic || pkt.DeviceID == "" || pkt.DeviceID == selfID || pkt.Port <= 0 || pkt.Port > 65535 {
			continue
		}
		if _, err := verifyDeviceIDAndKey(pkt.DeviceID, pkt.PubKeySpkiB64); err != nil {
			continue
		}
		ua, ok := addr.(*net.UDPAddr)
		if !ok || ua.IP == nil {
			continue
		}
		ipStr := ua.IP.To4()
		if ipStr == nil {
			continue
		}
		remoteIP := ipStr.String()

		if pkt.Type == "reverse_request" {
			if pkt.TargetDeviceID == selfID {
				go d.mgr.handleReverseRequest(remoteIP, pkt)
			}
			continue
		}

		if pkt.Type != "announce" && pkt.Type != "reply" {
			continue
		}

		_, trusted := d.mgr.app.store.GetTrustedSyncPeer(pkt.DeviceID)
		peer := SyncPeer{
			DeviceID:      pkt.DeviceID,
			DeviceName:    strings.TrimSpace(pkt.DeviceName),
			Platform:      pkt.Platform,
			IP:            remoteIP,
			Port:          pkt.Port,
			TrackCount:    pkt.TrackCount,
			PlaylistCount: pkt.PlaylistCount,
			PubKeySpkiB64: pkt.PubKeySpkiB64,
			Trusted:       trusted,
			LastSeen:      time.Now().Unix(),
		}

		d.mu.Lock()
		prev, existed := d.peers[pkt.DeviceID]
		d.peers[pkt.DeviceID] = peer
		d.mu.Unlock()

		if !existed || prev.IP != peer.IP || prev.Port != peer.Port || prev.DeviceName != peer.DeviceName ||
			prev.TrackCount != peer.TrackCount || prev.PlaylistCount != peer.PlaylistCount || prev.Trusted != peer.Trusted {
			d.mgr.emitPeers()
		}

		if pkt.Type == "announce" {
			if rep, err := d.buildPacket("reply"); err == nil {
				_, _ = conn.WriteTo(rep, &net.UDPAddr{IP: ipStr, Port: syncDefaultPort})
				if ua.Port > 0 && ua.Port != syncDefaultPort {
					_, _ = conn.WriteTo(rep, ua)
				}
			}
		}
	}
}

func joinMulticastOnIface(uc *net.UDPConn, iface *net.Interface, group *net.UDPAddr) error {
	p := ipv4.NewPacketConn(uc)
	return p.JoinGroup(iface, group)
}
