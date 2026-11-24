# Raspberry Pi 3 (profile C, `lite-cloud`)

Profile C runs the whole app (one JVM: REST API and web pages) on a Raspberry Pi 3 (1 GB RAM) with **no local AI**: chat, embeddings, transcription and
speech use the OpenAI API. **Your entry text and audio leave the device** (docs/PRIVACY.md). Explicit opt-in:
`lite-cloud` build + `OPENAI_API_KEY` + `JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`.

## Requirements
- Raspberry Pi 3 (B or B+, 1 GB RAM), microSD 16 GB or more (a USB SSD is more durable), network.
- **Raspberry Pi OS Lite, 64-bit** (Bookworm or newer). The Pi 3 CPU is 64-bit capable.
- **32-bit OS is unsupported.** There is no current Temurin 25 build for 32-bit ARM (armhf), and a 32-bit JVM caps the
  heap addressing and wastes the little memory there is.
- Java 25 (Eclipse Temurin JRE, aarch64).

## Memory budget
OS and services use about 150 to 250 MB, leaving **750 MB for the JVM** (SPEC section 13). Since D-033 there is a single JVM:
the former Vaadin frontend (a second 160 MB heap plus its framework) is gone, the Qute pages are rendered by the backend.
The JVM flags in the systemd unit (and `scripts/run-lite.*`) are tuned for that:

```
-Xmx128m -Xms32m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m
```

`-Xmx` stays at 128m: `scripts/smoke-lite` renders every page (home, journal, entry, coach, memory, gym, weekly, settings) and runs
the AI flows against the mock with this limit without heap pressure; templates are small and rendered per request. Raise
it to 160m (`LITE_BACKEND_JVM_OPTS` for the script, the `ExecStart` line for the unit) if you see `OutOfMemoryError` in
`journalctl` with a very large journal; `MemoryMax=350M` leaves room for that.

Swap: enable **zram** (compressed RAM swap) as a safety net, and keep a small disk swap (256 to 512 MB) as a last resort.
On Raspberry Pi OS Bookworm: `sudo apt install zram-tools`, set `ALGO=zstd` and `PERCENT=50` in `/etc/default/zramswap`,
`sudo systemctl restart zramswap`. Avoid large swap on the SD card (wear). `MemoryMax` in the units kills a runaway JVM
before it takes the whole Pi down; systemd restarts it.

## Build on a PC, copy the jar directory
The Pi is too small to build the app (Maven, npm). On a PC with JDK 25:

```
scripts\build-lite.ps1        (Windows)      sh scripts/build-lite.sh     (Linux/macOS)
```

Result: `backend/target/lite-app/` (a directory, copy it whole; the web assets are inside the jar). It is the same build as for
the other profiles (D-032); `lite-cloud` is selected at runtime by `QUARKUS_PROFILE=lite-cloud` (set by the unit). The only
database is the embedded H2 file in the backend JVM: no database process, nothing else to install.

## Install on the Pi
```
# 1. Java 25 (Temurin, aarch64). Adoptium apt repo:
sudo apt update && sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-25-jre
java -version        # must say 25 and aarch64 (uname -m: aarch64)

# 2. Dedicated user and directories
sudo useradd --system --home /var/lib/ai-diary --shell /usr/sbin/nologin ai-diary
sudo install -d -o ai-diary -g ai-diary -m 0700 /var/lib/ai-diary /var/lib/ai-diary/audio
sudo install -d -m 0755 /opt/ai-diary /etc/ai-diary

# 3. Copy the artifacts from the PC (adjust host)
scp -r backend/target/lite-app pi@raspberrypi:/tmp/backend
sudo rm -rf /opt/ai-diary/backend && sudo mv /tmp/backend /opt/ai-diary/backend

# 4. Secrets: root-owned, mode 0600, read by the unit's `EnvironmentFile`
scp deploy/backend.env.example pi@raspberrypi:/tmp/backend.env
sudo install -m 0600 -o root -g root /tmp/backend.env /etc/ai-diary/backend.env && rm /tmp/backend.env
sudoedit /etc/ai-diary/backend.env      # OPENAI_API_KEY and JOURNAL_CLOUD_CONSENT

# 5. Units
scp deploy/systemd/*.service pi@raspberrypi:/tmp/
sudo install -m 0644 /tmp/ai-diary-*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ai-diary-backend
journalctl -u ai-diary-backend -f      # no journal content in the logs
```
The first start takes 30 to 120 seconds on a Pi 3. The unit binds `127.0.0.1:8080` by default (no authentication in v1).
Reach the UI with an SSH tunnel: `ssh -L 8080:127.0.0.1:8080 pi@raspberrypi`, then open `http://localhost:8080`.

### Opening the UI to your home network (LAN opt-in)
Off by default. Anyone on that network can then read your journal (there is no login in v1); never expose the port to
the internet. Two settings are needed, both in `/etc/ai-diary/backend.env` (see `deploy/backend.env.example`), then
`sudo systemctl restart ai-diary-backend`:

```
JOURNAL_HTTP_HOST=0.0.0.0
JOURNAL_ALLOWED_HOSTS=raspberrypi,raspberrypi.local,192.168.1.50     # the name(s) or IP you type into the browser
```

Without `JOURNAL_ALLOWED_HOSTS` the Host check (DNS rebinding protection) answers `421` for every name except
`localhost`, `127.0.0.1` and `[::1]`. Then open `http://<that name>:8080`.

## Update and backup
- Update: build on the PC, copy as in step 3, `sudo systemctl restart ai-diary-backend` (Flyway migrates
  the H2 file on start; additive migrations only).
- Backup: `sudo systemctl stop ai-diary-backend && sudo tar czf ai-diary-backup.tgz -C /var/lib ai-diary && sudo systemctl start ai-diary-backend`
  (the H2 file must not be copied while the backend runs). Or use the in-app export (Settings). Keep backups private.
- Delete everything: Settings -> Delete all, or remove `/var/lib/ai-diary` (docs/PRIVACY.md).

## Manual verification checklist
- [ ] `uname -m` says `aarch64`; `java -version` says 25.
- [ ] `ls -l /etc/ai-diary/backend.env` shows `-rw------- root root`.
- [ ] Without `OPENAI_API_KEY` or the consent value the backend unit fails with a clear message in `journalctl`.
- [ ] With both set: the unit is `active (running)`; `curl -s http://127.0.0.1:8080/api/system/info` (writes need `-H "X-AI-Diary: 1"`) shows profile `lite-cloud`.
- [ ] `http://127.0.0.1:8080/` (through the SSH tunnel) shows the cloud banner on every page.
- [ ] A text entry saves; a voice entry is transcribed (OpenAI); the coach asks questions.
- [ ] `ps -o rss,cmd -C java` : the RSS is at most 750 MB after browsing every page. `free -m` shows no sustained swap use.
- [ ] `journalctl -u ai-diary-backend | grep -i <a word you wrote>` finds nothing.
- [ ] Export (Settings) downloads a zip with `entries.json`; Delete all empties the journal.
- [ ] `sudo systemctl kill -s KILL ai-diary-backend` : systemd restarts it (`Restart=on-failure`).
- [ ] Reboot: the unit comes back.

## Smoke test on a PC
`scripts\smoke-lite.ps1` (`sh scripts/smoke-lite.sh`) builds the lite artifacts, starts the single JVM with the flags above against a
mock OpenAI server (`scripts/mock-openai/server.mjs`, a dependency-free Node script that logs only method and path) in a
throwaway data directory, and checks the refusal without consent/key, the HTML pages, the cloud banner, the AI features, audio,
export and delete-all. It prints the memory of the JVM; on Windows that is the working set, not the Linux RSS the 750 MB budget refers to.
Use `-SkipBuild` / `--skip-build` to reuse an existing build. It does not replace the checklist above on real hardware.

## Measurements

Historical measurements (two JVMs: backend plus the Vaadin frontend that D-033 removed). Re-measure on a Pi with the single
JVM; the budget is expected to be far below the old 644 MB. On Windows, `scripts\smoke-lite.ps1` printed a working set of
221 MB for the single JVM after rendering every page (2026-11-24, indicative only).

### Wave 0 spike (2026-11-23)
Note: these numbers were measured WITH a PostgreSQL container on another host, before D-032 (embedded H2 only, no database
process). They should be re-measured; the memory budget is expected to get easier.

Pre-v1 jars (backend Quarkus 3.29, frontend Vaadin 24.9 production build) in `eclipse-temurin:25-jre` containers with
`--memory=1g`, backend against PostgreSQL in a separate container (not counted). One browser session opened on the UI and
reloaded 3x, then `VmRSS`/`VmHWM` of the JVM read from `/proc`, flags as above.

| Run | Backend RSS (peak) | Frontend RSS (peak) | Total |
|---|---|---|---|
| x86_64 native | 160 MB | 172 MB | **332 MB** |
| linux/arm64 under QEMU emulation (upper bound, includes emulator overhead) | 314 MB | 347 MB | **661 MB** |

Startup under emulation: backend 28 s, frontend 42 s.

### Re-measurement with the current lite build (W1d, 2026-11-23)
Current `scripts/build-lite` output (H2 file database, Flyway at start, Vaadin production frontend) in one
`eclipse-temurin:25-jre` **linux/arm64** container (QEMU emulation, `--memory=1g`), both JVMs with the flags above,
throwaway H2 directory, dummy API key and consent, **fake providers** via env (so OpenAI client cost is not included).
Frontend requested 3 times, one entry created via the API; `VmRSS`/`VmHWM` read from `/proc`.

| | RSS |
|---|---|
| Backend | 329 MB |
| Frontend | 315 MB |
| **Total** | **644 MB** (limit 750 MB) |

Startup under emulation was slow (backend 91 s, frontend 108 s while both started at once; real Pi 3 expected in the same
range). Emulation overhead inflates these numbers, so real hardware should be lower. Still to measure on real hardware
(Wave 4): the real OpenAI providers, a long Vaadin session with all views, and the SD-card startup time. Fallback if the
total ever exceeds 750 MB: single-process packaging (D-008).

Further levers if needed: AppCDS archive (`-XX:SharedArchiveFile`), lower `-Xmx` (H2 cache via `CACHE_SIZE`).
