# SecureIP - Realtime Network Change Tracker & STUN Public IP Resolver (POC)

A lightweight, high-performance Proof of Concept (POC) designed for SDK distribution to track network changes (Soft & Hard handoffs) and maintain a cached Public IP in real time.

For full technical documentation on Android OS network callbacks, decision tree, and STUN protocol mechanics, see [NETWORK_TRACKER_DOCUMENTATION.md](./NETWORK_TRACKER_DOCUMENTATION.md).

## Features
- **Zero Runtime Permissions**: Requires only `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` and `<uses-permission android:name="android.permission.INTERNET" />`. No location permissions needed!
- **Android 10+ (API 29+) Compatible**.
- **Realtime OS Callbacks**: Listens to `ConnectivityManager` default network and cellular callbacks without artificial debounce delays.
- **Pure UDP STUN Resolution**: Queries standard public STUN servers (`stun.l.google.com:19302`, `stun.cloudflare.com:3478`) over UDP datagram socket for sub-20ms public IP resolution and latency measurement.
- **Jetpack Compose UI**: Features an expanded, auto-scrolling log window displaying millisecond-timestamped events, transport types, link details, STUN server latency, and simulated tower handoffs.
