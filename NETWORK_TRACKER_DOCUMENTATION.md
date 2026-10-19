# SecureIP - Realtime Network Change Tracker & STUN Public IP Resolver

## Overview

This document describes the architectural decision logic, Android OS network callbacks, and STUN protocol mechanics used by **SecureIP** to track network handoffs (both Soft Handoffs and Hard Handoffs) and maintain an up-to-date, cached Public IP in real time.

Designed for high-scale SDK integration (100M+ users), this implementation strictly operates with **zero dangerous runtime permissions** (`ACCESS_NETWORK_STATE` and `INTERNET` only) and supports **Android 10+ (API level 29+)**.

---

## 1. Registered Callbacks & OS Signals

To detect network changes without requesting location permissions (`ACCESS_FINE_LOCATION`), the system registers native `ConnectivityManager` callbacks to evaluate network lifecycle events and link property mutations.

### A. Default Active Network Callback
* **API Used**: `ConnectivityManager.registerDefaultNetworkCallback()`
* **Permission Required**: `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` *(Normal permission, automatically granted)*
* **Min API Level**: Android 7.0+ (API 24+), fully supported on Android 10+ (API 29+)

#### Trigger Events Handled:
1. **`onAvailable(network: Network)`**
   * **What it means**: The primary default active internet transport changed (e.g., Wi-Fi connected/disconnected, or device switched between Cellular $\leftrightarrow$ Wi-Fi).
   * **Decision**: Emits `HardHandoff` or `DefaultNetworkAvailable` event $\rightarrow$ **Triggers Realtime STUN Query**.

2. **`onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties)`**
   * **What it means**: The OS updated the default network's link configuration (e.g., IPv4/IPv6 address rebind, DNS server changes, or default gateway/routing table updates).
   * **State Diff Check**: Calculates a fingerprint:
     $$\text{Fingerprint} = \text{InterfaceName} \mid \text{IP Addresses} \mid \text{Routes}$$
   * **Decision**: If `newFingerprint != lastFingerprint`, emits `SoftHandoff` event $\rightarrow$ **Triggers Realtime STUN Query**.

3. **`onLost(network: Network)`**
   * **What it means**: Device lost internet connectivity completely.
   * **Decision**: Emits `NetworkLost` $\rightarrow$ Marks network state as `Offline` *(Suppresses STUN query until reconnected)*.

---

### B. Cellular Transport Specific Callback
* **API Used**: `ConnectivityManager.registerNetworkCallback(NetworkRequest, NetworkCallback)`
* **Network Request Filter**: `NetworkCapabilities.TRANSPORT_CELLULAR`

#### Trigger Events Handled:
1. **`onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties)`**
   * **What it means**: Monitors cellular data interface rebinds (e.g., when traveling at high speed on a train and the carrier hands off cellular data across regional core gateways / UPF anchors).
   * **State Diff Check**: Compares `LinkProperties` fingerprint against the last known cellular state.
   * **Decision**: If fingerprint changed, emits `SoftHandoff` event $\rightarrow$ **Triggers Realtime STUN Query**.

---

## 2. Decision Tree & Workflow

```
                      ┌─────────────────────────┐
                      │ Android OS Callback     │
                      └────────────┬────────────┘
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         ▼                         ▼                         ▼
  [onAvailable]         [onLinkPropertiesChanged]        [onLost]
         │                         │                         │
  Hard Handoff             Fingerprint Diff Check         Device Offline
  (Transport Switch)               │                         │
         │                ┌────────┴────────┐         Set State to Offline
         │                ▼                 ▼         (No STUN Query)
         │           Fingerprint       Fingerprint
         │             Changed          Unchanged
         │                │                 │
         │                │             Ignore
         └────────┬───────┘            (No-op)
                  │
                  ▼
   ┌──────────────────────────────┐
   │ Realtime Event Emitted       │
   └──────────────┬───────────────┘
                  │
                  ▼
   ┌──────────────────────────────┐
   │ Execute UDP STUN Query       │
   │ (stun.l.google.com:19302)    │
   └──────────────┬───────────────┘
                  │
                  ▼
   ┌──────────────────────────────┐
   │ Calculate Latency (ms) &     │
   │ Cache New Public IP          │
   └──────────────────────────────┘
```

---

## 3. STUN Protocol Mechanics

Instead of sending heavy HTTP REST requests over TCP, the system resolves the device's external Public IP via UDP socket using the **STUN Protocol (RFC 5389 / RFC 3489)** over port `19302` or `3478`.

1. **UDP Packet Request**: Sends a lightweight 20-byte STUN Binding Request packet containing a 4-byte Magic Cookie (`0x2112A442`) and a 12-byte random Transaction ID.
2. **UDP Packet Response**: Parses the STUN Binding Response and extracts the public IP from the `XOR-MAPPED-ADDRESS` (`0x0020`) or `MAPPED-ADDRESS` (`0x0001`) attribute.
3. **Latency Measurement**: Calculates exact round-trip resolution duration in milliseconds ($\text{latencyMs} = \text{endTime} - \text{startTime}$). Typical latency is **sub-20ms**.

---

## 4. Key Technical Guarantees

| Requirement | Implementation & Behavior |
| :--- | :--- |
| **Permissions** | Uses only `ACCESS_NETWORK_STATE` & `INTERNET`. No runtime prompts (`ACCESS_FINE_LOCATION` or `READ_PHONE_STATE` are **not** needed). |
| **Execution Mode** | **Realtime**: Suppressed artificial debounce timers to query STUN immediately upon OS callback receipt. |
| **Efficiency** | Performs state diffing on `LinkProperties` to avoid redundant STUN queries when callbacks fire without actual IP/route changes. |
| **Protocol** | Standard UDP STUN Binding Request (RFC 5389 / RFC 3489) over port `19302`/`3478`, offering sub-20ms resolution latency. |
