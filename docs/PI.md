# Raspberry Pi 3 (lite-cloud) — memory spike

Status: **Wave 0 spike**. Final setup guide (systemd, install steps) is W1d's deliverable; real-hardware numbers in Wave 4.

## Constraints
- Raspberry Pi 3, 1 GB RAM, **64-bit OS required** (Raspberry Pi OS Lite 64-bit). 32-bit OS is unsupported: no current
  Temurin 25 build for armhf.
- No local AI; providers use OpenAI (explicit opt-in, see PRIVACY.md). Embedded H2 file DB; in-JVM vector search.
- Budget: OS + services ≈ 150–250 MB ⇒ **≤ 750 MB RSS for both JVMs together**.

## Method (2026-11-23)
Pre-v1 jars (backend Quarkus 3.29, frontend Vaadin 24.9 production build) in `eclipse-temurin:25-jre` containers with
`--memory=1g`, backend against PostgreSQL in a separate container (not counted). One browser session opened on the UI
and reloaded 3×, then `VmRSS`/`VmHWM` of PID 1 read from `/proc`. JVM flags:

```
backend:  -Xmx128m -Xms32m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=96m  -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m
frontend: -Xmx160m -Xms32m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m
```

## Results
| Run | Backend RSS (peak) | Frontend RSS (peak) | Total |
|---|---|---|---|
| x86_64 native | 160 MB | 172 MB | **332 MB** |
| linux/arm64 under QEMU emulation (upper bound, includes emulator overhead) | 314 MB | 347 MB | **661 MB** |

Startup under emulation: backend 28 s, frontend 42 s (real Pi 3 expected in the same range or slower).
Also verified: the Wave 0 lite-cloud backend build (H2) starts, migrates and serves entries; it refuses to start
without consent/API key.

## Conclusion
The two-JVM layout fits the budget with margin on measured numbers (D-008). Not yet measured: H2 in-process cost,
OpenAI client libraries, Vaadin with the full v1 UI, and real ARM hardware. Re-measure after Wave 1 (W1d) and on a
real Pi in Wave 4. Fallback if > 750 MB: single-process lite packaging (record as a new decision).

Further levers if needed: AppCDS archive (`-XX:SharedArchiveFile`), `-XX:+UseCompressedOops` (default), lower
`-Xmx` for the backend (H2 cache via `CACHE_SIZE`), Vaadin `vaadin.closeIdleSessions=true` and short session timeout.
