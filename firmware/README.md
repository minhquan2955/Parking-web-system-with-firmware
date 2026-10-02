# ESP32: backend validation and parking sessions

`SPS_24_Doi_Cong.ino` is the hardware firmware. It preserves the uploaded sketch's
pins, right-hand IN / left-hand OUT, RC522 readers, SG90 motion, IR anti-pinch
cycle, three sonars, LCD, local diagnostic dashboard and synchronous ElegantOTA.
The backend is the only authority for opening a gate after a card scan.

## Configure and run

1. Use `parking_config.h` for local settings (ignored by Git). On this workstation
   it was created from the sketch's existing Wi-Fi settings and `.env` device key.
   For a new checkout, copy `parking_config.example.h` and replace placeholders.
2. `PARKING_BACKEND_IP` is the computer's **LAN IPv4**, currently configured as
   `192.168.1.117`; `PARKING_BACKEND_PORT` is `3000`. Update the IP if DHCP changes.
   Do not enter a URL, hostname, `localhost`, or the ESP32's own IP in this field.
3. `PARKING_DEVICE_ID=ESP32-01`; `PARKING_DEVICE_KEY` must match the provisioned
   backend device. `.env` supplies its initial key. Editing `.env` alone does not
   rotate a key already stored in the database.
4. The project's running Docker stack already exposes web/API on LAN port 3000;
   keep using it. As an alternative without Docker, stop the Docker stack first
   (preserving its volume), then run `./scripts/start-local.ps1 -SkipBuild -Lan`.
   Omit `-SkipBuild` on a fresh checkout. Docker uses database `parking`; the
   local launcher uses a separate persistent database, so changing launch modes
   does not transfer cards or sessions. Allow TCP 3000 in Windows Firewall for
   the trusted LAN if needed. Keep ESP32 and computer on the same LAN without
   AP/client isolation.
5. Build and upload using the pinned PlatformIO environment below. Watch Serial
   at 115200 for UID, gate, event ID, and validation result. Assign the physical
   UID to a USER and activate/renew its card through the central website.

The transport deliberately supports **HTTP over IPv4 on the demo LAN**. It uses
nonblocking sockets with one total timeout covering connect, send and receive;
it does not resolve DNS or send device keys to redirects. A deployment outside
this LAN requires a separate HTTPS transport with certificate verification.

## Build / upload

Pinned target: ESP32-WROOM / `esp32dev`, 4 MB flash, Arduino-ESP32 2.0.17 via
PlatformIO espressif32 6.12.0. `min_spiffs.csv` provides two OTA app partitions.
The original GPIO/voltage assumptions are documented at the top of the sketch;
actual wiring and power still need to be checked on the model.

From `D:\IoT_WebSystem` with the locally installed build tools:

```powershell
$env:PLATFORMIO_CORE_DIR = "$PWD/.tools/platformio"
.\.tools\pio-venv\Scripts\python.exe -m platformio run -d firmware
# Only with the correct board connected; replace COM5 with its actual port:
.\.tools\pio-venv\Scripts\python.exe -m platformio run -d firmware -t upload --upload-port COM5
```

On another workstation install PlatformIO Core 6.1.18, then use
`pio run -d firmware` / `pio run -d firmware -t upload --upload-port COM5`.
The supported, verified build path is PlatformIO; Arduino IDE's installed
ESP32 core 3.x is not the pinned environment for ESP32Servo 1.1.2. Keep the
`include/` headers and configuration with the sketch, not just the `.ino` file.

Build output: `.pio/build/esp32dev/firmware.bin` for OTA; PlatformIO handles
bootloader/partition offsets for a USB upload. The binary contains local Wi-Fi
and device credentials, so keep it private. First use USB to install the selected
partition layout if the currently flashed layout is unknown.

Local dashboard: `http://<ESP32-IP>/`; JSON diagnostics: `/occupancy`; OTA:
`/update`. OTA now requires `PARKING_OTA_USER` and `PARKING_OTA_PASSWORD` from
the ignored config. These are independent of website credentials. A random OTA
password was generated locally. Update only while the model is stopped; no new
access is authorized during OTA, and reboot commands the gates closed.

## Runtime behavior

- A new boot UUID is registered by heartbeat before scans. Heartbeats send all
  S1/S2/S3 states every 5 s; changes debounce 300 ms and send at most once per s.
  A stale hardware snapshot sends UNKNOWN instead of reusing old free spaces.
- RFID UIDs retain leading zeros and all 4/7/10 bytes. WUPA detects held HALTed
  cards ([MFRC522 WUPA implementation](https://github.com/miguelbalboa/rfid/blob/1.4.12/src/MFRC522.cpp#L545)). Same-card presentations need >=1 s removal and >=2 s cooldown. BUSY or
  offline presentations are consumed; holding a card does not queue a later open.
- One pending access request across both readers. At most three HTTP attempts,
  <=1 s each, within the original 3 s monotonic deadline (including queue time).
  Retries use the same boot/event/UID/gate. No retry for 4xx.
- Matching ALLOW + OPEN + valid original interval opens once, with a 3 s minimum.
  IR may hold/reopen the barrier during its existing local safety cycle. That
  mechanical reopening does not create another scan or session.
- A disconnect prevents new authorizations but allows existing motion to finish.
  No cached whitelist, offline decisions, persisted requests or replay on reboot.
- `NEEDS_REVIEW` means the backend may have committed while the response was lost
  or became too late. Look up the event ID on `/admin/access-events`, inspect
  `/parking-history`, and use the existing audited session correction if needed.
  The firmware never automatically creates a replacement event to recover an open.
- The backend records logical entry/exit decisions, not proof that a vehicle
  physically passed. Unknown/blocked/expired cards and admission restrictions
  use existing backend rules. Exit with an OPEN session remains permitted even
  after card expiry/blocking. No database migration or API shape change is required.
  The backend replay serializer was corrected to keep ISO timestamps identical
  to the first HTTP response; it does not extend the original validity window.

Do not run the simulator and the board simultaneously using `ESP32-01`: each
boot registers itself, superseding the other. Stop the simulator, then reboot
the ESP32 when switching back to hardware.

## Software checks

After PlatformIO has installed the pinned libraries, from the project root:

```powershell
g++ -std=c++17 -Wall -Wextra -pedantic firmware/test/access_controller_test.cpp -o .runtime/firmware-test.exe
.\.runtime\firmware-test.exe
g++ -std=c++17 -Wall -Wextra -pedantic -I firmware/.pio/libdeps/esp32dev/ArduinoJson/src firmware/test/protocol_test.cpp -o .runtime/firmware-protocol-test.exe
.\.runtime\firmware-protocol-test.exe
```

The hardware sketch uses these tested controller and response-parsing headers.
Backend `firmwareHttpContractPersistsEntryExitAndWebHistory` additionally tests
heartbeat, entry, replay without extending expiry, exit, rejected second exit,
stored event/session counts and the user's web history against PostgreSQL.

## Physical acceptance (still required)

Verify right IN / left OUT, each sonar S1-S3, LCD and both buzzers; present and
hold a card, remove/re-present, then try both readers at once. Verify unknown,
expired, blocked and valid cards and a full lot. Disconnect Wi-Fi/backend during
validation and during an already-open gate; check no late opening and continued
IR closure protection. Test an authorized OTA update while stopped. Software
tests and successful compilation do not establish these physical results.

## Simulator

`simulator/simulator.py` implements the full REST device contract using a simulated actuator. Python 3.10+; no third-party dependencies. Set `DEVICE_KEY` from the local `.env`, then run:

```powershell
python firmware/simulator/simulator.py --url http://localhost:3000
```

Set all three slots explicitly (`slot S1 FREE`, etc.), then `scan IN <UID>`. A CLI scan represents presenting and removing a card; repeated presentations have a 2s cooldown. Sensor changes debounce 300ms, heartbeats serialize, snapshots send at most once per second on changes and every 5s otherwise. `offline on` injects a network outage. `reboot` creates a new boot ID without replaying old events.

The simulator is independent of the real actuator. It remains useful for backend
demonstrations without hardware; stop it before using the physical ESP32.

```powershell
python -m unittest discover -s firmware/simulator -p 'test_*.py' -v
```
