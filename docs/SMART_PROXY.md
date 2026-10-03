# Smart Proxy (experimental)

Branch: `feature/smart-proxy-routing`

## Goal

Proxy TikTok API/control traffic through a selected upstream proxy while keeping video, photo and
other non-control traffic direct.

This is implemented inside TikTok's TTNet/Cronet path. The patch uses TikTok's own
`com.bytedance.ttnet.TTNetInit.setProxy(...)` entry point and points it at a loopback HTTP CONNECT
router embedded in the ReVanced extension.

The loopback router does **not** decrypt TLS.

## Routing

Default proxied host families:

- API/search/verification/IM API hosts on `*.tiktokv.com`
- API/search/verification/IM API hosts on `*.musical.ly`
- `*.tiktokapi.com`
- `*.snssdk.com`
- `*.isnssdk.com`
- `*.zijieapi.com`
- selected API/security/config hosts on `*.byteoversea.com`

Everything else is direct by default. This intentionally excludes ordinary media/CDN hosts.

Additional API host suffixes can be supplied in ReVanced settings.

## Upstream types

- HTTP CONNECT
- SOCKS5
- optional username/password authentication

## Failure behavior

`Direct fallback` is enabled by default. If the upstream proxy cannot be reached, an API request is
retried directly instead of taking the entire app offline.

## Important limitation

This controls the proxy configured inside TikTok TTNet. It cannot bypass a device-wide Android VPN
owned by another app. Android VPN split tunneling must be configured in the VPN application itself.

## Status

Experimental until tested on-device for:

1. login/account requests,
2. For You feed,
3. regular video playback,
4. image posts,
5. search,
6. downloads,
7. proxy failure/fallback.
