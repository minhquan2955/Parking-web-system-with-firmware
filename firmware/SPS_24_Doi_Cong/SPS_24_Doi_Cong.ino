/*
 * SPS_24 - ESP32 WROOM, 2 RC522, 2 SG90, 2 IR, 2 active-low buzzers,
 *          3 HC-SR04, LCD 16x2 I2C, Wi-Fi dashboard and OTA.
 * Libraries: MFRC522, ESP32Servo, LiquidCrystal I2C, ElegantOTA.
 * ElegantOTA must use its DEFAULT synchronous WebServer mode (async=0).
 * WebServer, WiFi, SPI and Wire are supplied with the ESP32 board package.
 * Serial Monitor: 115200. Dashboard: http://<IP>/ ; OTA: http://<IP>/update
 * Use an OTA-capable partition scheme. Perform OTA with the model stopped;
 * firmware updates restart the device and startup commands both gates closed.
 *
 * PIN MAP (GPIO numbers, not physical pin positions):
 * RC522 IN (RIGHT): SS5/RST16; OUT (LEFT): SS17/RST33; shared SCK18/MISO19/MOSI23.
 * Both RC522: 3V3 and common GND; IRQ unconnected.
 * IR IN (RIGHT) OUT=34, IR OUT (LEFT) OUT=35. INPUT only; GPIO34/35 have no pull-ups.
 * Servo IN (RIGHT)=4, OUT (LEFT)=32; 5V supply with adequate current and common GND.
 * Buzzer IN (RIGHT) I/O=15, OUT (LEFT) I/O=14; LOW sounds, HIGH silences.
 * HC-SR04 S1 TRIG27/ECHO36; S2 TRIG26/ECHO39; S3 TRIG25/ECHO13.
 * HC-SR04: 5V. EACH ECHO -> 10k -> GPIO junction -> 10k -> GND.
 * LCD SDA21/SCL22, address 0x27. Level-shift I2C if pulled up to 5V.
 * No external gate indicator lights are used; GPIO13 is ECHO input only.
 *
 * Backend validates every UID (4/7/10 bytes) and records access/session logs.
 * No local whitelist or offline authorization. Pin map is unchanged.
 * IR alone cannot open a closed gate. It holds an open gate and reopens a
 * closing gate. Servo position is estimated; SG90 provides no feedback.
 * Occupied: 2 <= distance <= 15 cm. Other valid readings: free.
 * Invalid/timeout: UNKNOWN, excluded from the available-slot count.
 *
 * One hardware task owns RFID, sensors, gates and LCD. A separate backend
 * task exchanges bounded queues; HTTP never runs inside the hardware task.
 * pulseIn has a 30ms timeout; the two gates are serviced before and after
 * each measurement. This is a tabletop model, not a real safety barrier.
 */

#include <Arduino.h>
#include <cmath>
#include <SPI.h>
#include <Wire.h>
#include <MFRC522.h>
#include <ESP32Servo.h>
#include <LiquidCrystal_I2C.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ElegantOTA.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#include <atomic>
#include <esp_system.h>
#include <esp_timer.h>
#include "include/device_protocol.hpp"

#if ELEGANTOTA_USE_ASYNC_WEBSERVER
#error "Set ELEGANTOTA_USE_ASYNC_WEBSERVER to 0 in ElegantOTA library configuration."
#endif

#if __has_include("parking_config.h")
#include "parking_config.h"
#else
#include "parking_config.example.h"
#endif
#include "include/backend_http.hpp"

constexpr const char* FIRMWARE_VERSION = "sps24-backend-1.0.0";
char bootId[37] = {};
QueueHandle_t scanRequests = nullptr, scanReplies = nullptr;
std::atomic<bool> backendReady{false}, transportBusy{false}, otaRunning{false};
std::atomic<uint32_t> heartbeatAt{0};
enum class BackendState { Starting, Ready, Offline, Unauthorized, Disabled, RateLimited, StaleBoot, BadResponse, ConfigError };
std::atomic<BackendState> backendState{BackendState::Starting};

constexpr uint8_t LCD_ADDR = 0x27;
constexpr uint8_t TRIG_PINS[3] = {27, 26, 25};
constexpr uint8_t ECHO_PINS[3] = {36, 39, 13};
constexpr uint8_t ENTRY_IR_ACTIVE = LOW;
constexpr uint8_t EXIT_IR_ACTIVE = LOW;
constexpr uint8_t ENTRY_BUZZER_ACTIVE = LOW;
constexpr uint8_t EXIT_BUZZER_ACTIVE = LOW;
constexpr int ENTRY_CLOSED_ANGLE = 0, ENTRY_OPEN_ANGLE = 90;
constexpr int EXIT_CLOSED_ANGLE = 0, EXIT_OPEN_ANGLE = 90;
constexpr uint32_t MIN_OPEN_MS = 3000, CLEAR_MS = 1500, CLOSING_MS = 1000;
constexpr uint32_t BEEP_MS = 200, RFID_POLL_MS = 50, SONAR_GAP_MS = 70;
constexpr uint32_t ECHO_TIMEOUT_US = 30000, WIFI_RETRY_MS = 30000;
constexpr float CAR_MIN_CM = 2.0f, CAR_MAX_CM = 15.0f;
constexpr uint8_t STABLE_READINGS = 3;
constexpr int UNKNOWN = -1, FREE = 0, OCCUPIED = 1;

struct Snapshot {
  int slots[3];
  float distanceCm[3];
  uint8_t gates[2]; // 0=CLOSED, 1=OPEN, 2=CLOSING (estimated).
  bool obstacles[2];
  char lastUid[21]; // Full UID, including leading zeros, up to 10 bytes.
  char lastGate[4];
  uint32_t lastScanMs;
  uint32_t updatedAtMs;
  char lastResult[48] = "BOOTING";
  char lastEvent[37] = {};
};

// Explicit declarations keep Arduino's automatic prototype generation safe.
void recordCard(const char* gate, const byte* uid, byte size);
void cardAbsent(const char* gate, bool uncertain);
void processAccess();
void backendTask(void* parameter);
void makeUuid(char* output);
void showResult(const char* result);
const char* backendLabel();
int classifyDistance(float cm);
void updateSlot(uint8_t index, int reading);
void sampleSlot(uint8_t index);
void publishSnapshot();
Snapshot readSnapshot();
int freeCount(const Snapshot& state);
char slotSymbol(int state);
const char* gateName(uint8_t state);
void printRow(uint8_t row, const char* text);
void refreshLcd();
void hardwareTask(void* parameter);
void maintainWifi();
String occupancyJson(const Snapshot& state);
void setup();
void loop();

class Gate {
 public:
  Gate(const char* name, uint8_t ss, uint8_t rst, uint8_t servoPin,
       uint8_t irPin, uint8_t buzzerPin, uint8_t irActive,
       uint8_t buzzerActive, int closedAngle, int openAngle)
      : name_(name), reader_(ss, rst), servoPin_(servoPin), irPin_(irPin),
        buzzerPin_(buzzerPin), irActive_(irActive), buzzerActive_(buzzerActive),
        closedAngle_(closedAngle), openAngle_(openAngle) {}

  void begin() {
    pinMode(irPin_, INPUT);
    pinMode(buzzerPin_, OUTPUT);
    digitalWrite(buzzerPin_, !buzzerActive_);
    servo_.setPeriodHertz(50);
    servo_.attach(servoPin_, 500, 2400);
    servo_.write(closedAngle_);
    reader_.PCD_Init();
    Serial.printf("[%s] RC522:\n", name_);
    reader_.PCD_DumpVersionToSerial();
    if (!servo_.attached()) Serial.printf("[%s] ERROR: servo attach failed.\n", name_);
  }

  bool obstacle() const { return digitalRead(irPin_) == irActive_; }
  uint8_t state() const { return state_; }

  void scan() {
    // WUPA includes HALTed cards; REQA/IsNewCardPresent alone would mistake a
    // held card for removal immediately after PICC_HaltA().
    byte atqa[2], atqaSize = sizeof(atqa);
    const auto presence = reader_.PICC_WakeupA(atqa, &atqaSize);
    if (presence == MFRC522::STATUS_TIMEOUT) { cardAbsent(name_, false); return; }
    if (presence != MFRC522::STATUS_OK || !reader_.PICC_ReadCardSerial()) {
      cardAbsent(name_, true); return;
    }
    const byte size = reader_.uid.size;
    const bool valid = size == 4 || size == 7 || size == 10;
    if (valid && servo_.attached()) {
      recordCard(name_, reader_.uid.uidByte, size);
    } else {
      Serial.printf("[%s] Invalid UID length or servo not attached.\n", name_);
    }
    reader_.PICC_HaltA();
    reader_.PCD_StopCrypto1();
  }

  void authorize(uint32_t durationMs) {
    minimumOpenMs_ = durationMs;
    const uint32_t now = millis();
    open(now);
    digitalWrite(buzzerPin_, buzzerActive_);
    beeping_ = true; beepAt_ = now;
  }

  void update() {
    const uint32_t now = millis();
    if (beeping_ && uint32_t(now - beepAt_) >= BEEP_MS) {
      digitalWrite(buzzerPin_, !buzzerActive_);
      beeping_ = false;
    }
    const bool blocked = obstacle();
    if (state_ == 1) {
      if (blocked) clearTiming_ = false;
      else {
        if (!clearTiming_) { clearTiming_ = true; clearAt_ = now; }
        if (uint32_t(now - openedAt_) >= minimumOpenMs_ &&
            uint32_t(now - clearAt_) >= CLEAR_MS) {
          servo_.write(closedAngle_);
          state_ = 2;
          closingAt_ = now;
          Serial.printf("[%s] DANG DONG\n", name_);
        }
      }
    } else if (state_ == 2) {
      if (blocked) {
        Serial.printf("[%s] VAT CAN -> MO LAI\n", name_);
        open(now); // Reopening for an obstacle does not sound the buzzer.
      } else if (uint32_t(now - closingAt_) >= CLOSING_MS) {
        state_ = 0;
        Serial.printf("[%s] DONG (uoc luong)\n", name_);
      }
    }
  }

 private:
  void open(uint32_t now) {
    servo_.write(openAngle_);
    state_ = 1;
    openedAt_ = now;
    clearTiming_ = false;
    Serial.printf("[%s] MO %d do\n", name_, openAngle_);
  }
  const char* name_;
  MFRC522 reader_;
  Servo servo_;
  uint8_t servoPin_, irPin_, buzzerPin_, irActive_, buzzerActive_;
  int closedAngle_, openAngle_;
  uint8_t state_ = 0;
  bool clearTiming_ = false, beeping_ = false;
  uint32_t openedAt_ = 0, clearAt_ = 0, closingAt_ = 0, beepAt_ = 0;
  uint32_t minimumOpenMs_ = MIN_OPEN_MS;
};

Gate entry("VAO", 5, 16, 4, 34, 15, ENTRY_IR_ACTIVE, ENTRY_BUZZER_ACTIVE,
           ENTRY_CLOSED_ANGLE, ENTRY_OPEN_ANGLE);
Gate exitGate("RA", 17, 33, 32, 35, 14, EXIT_IR_ACTIVE, EXIT_BUZZER_ACTIVE,
              EXIT_CLOSED_ANGLE, EXIT_OPEN_ANGLE);
LiquidCrystal_I2C lcd(LCD_ADDR, 16, 2);
WebServer server(80);
bool lcdReady = false;
Snapshot hardware = {{UNKNOWN, UNKNOWN, UNKNOWN}, {-1, -1, -1},
                     {0, 0}, {false, false}, "", "", 0, 0};
Snapshot sharedState = hardware;
portMUX_TYPE snapshotMux = portMUX_INITIALIZER_UNLOCKED;
int candidates[3] = {UNKNOWN, UNKNOWN, UNKNOWN};
uint8_t streaks[3] = {0, 0, 0};

class GateDriver : public parking::HardwareDriver {
 public:
  void open(parking::Gate gate, uint32_t durationMs) override {
    (gate == parking::Gate::In ? entry : exitGate).authorize(durationMs);
  }
  void tick(uint32_t) override { entry.update(); exitGate.update(); }
  void rest() override {} // Gate::begin sets startup position, only at boot.
};
GateDriver gateDriver;
parking::AccessController accessControl(gateDriver);
parking::Presentations presentations;

const char INDEX_HTML[] PROGMEM = R"html(
<!doctype html><html lang="vi"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Smart Parking System</title><style>
body{font:16px system-ui;background:#edf2f7;color:#172435;max-width:900px;margin:32px auto;padding:0 16px}
h1{font-size:28px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px}
.card{background:white;padding:20px;border-radius:12px}.free{color:#176c3d}.occupied{color:#b42318}.unknown{color:#946000}
code{overflow-wrap:anywhere}a{color:#165db5}#connection{font-weight:600}
</style><h1>Smart Parking System</h1>
<p id="connection">Đang kết nối...</p><h2 id="count">Chỗ trống: --/3</h2>
<div class="grid"><div class="card"><h3>Ô 1</h3><p id="s1">--</p><small id="d1"></small></div>
<div class="card"><h3>Ô 2</h3><p id="s2">--</p><small id="d2"></small></div>
<div class="card"><h3>Ô 3</h3><p id="s3">--</p><small id="d3"></small></div></div>
<p id="gates"></p><p>Lần quét gần nhất: <strong id="gate">--</strong> · UID: <code id="uid">--</code></p>
<p id="time"></p><p>Backend: <strong id="backend">--</strong></p>
<p>Kết quả xác thực: <strong id="result">--</strong><br>Mã sự kiện: <code id="event">--</code></p>
<p>Thẻ được xác thực và lưu lịch sử trên web trung tâm. Mất kết nối backend thì không mở cổng mới.</p>
<p>X = có xe (2–15 cm); T = trống; ? = chưa xác định, không tính là chỗ trống.</p>
<p><a href="/update">Cập nhật firmware OTA</a> — thực hiện khi mô hình đã dừng hoạt động.</p>
<script>
const $=id=>document.getElementById(id);
const gateText={CLOSED:'Đóng (ước lượng)',OPEN:'Mở',CLOSING:'Đang đóng'};
async function refresh(){
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),3000);
 try{
  const response=await fetch('/occupancy',{cache:'no-store',signal:controller.signal});
  if(!response.ok)throw new Error('HTTP '+response.status);
  const data=await response.json();
  if(data.sampleAgeMs>2000)throw new Error('Dữ liệu cảm biến đã cũ');
  $('connection').textContent='Đã kết nối';$('count').textContent='Chỗ trống: '+data.free+'/3';
  for(let i=1;i<=3;i++){
   const state=data['slot'+i],node=$('s'+i);
   node.textContent=state===null?'? Chưa xác định':state?'X Có xe':'T Trống';
   node.className=state===null?'unknown':state?'occupied':'free';
   $('d'+i).textContent=data.distanceCm[i-1]===null?'Không có số đo hợp lệ':data.distanceCm[i-1].toFixed(1)+' cm';
  }
  $('gates').textContent='Cổng vào (phải): '+gateText[data.entryGate]+' · Cổng ra (trái): '+gateText[data.exitGate];
  $('gate').textContent=data.lastGate||'--';$('uid').textContent=data.lastUid||'--';
  $('backend').textContent=data.backendStatus; $('result').textContent=data.lastResult; $('event').textContent=data.lastEventId||'--';
  $('time').textContent='Nhận dữ liệu lúc '+new Date().toLocaleTimeString();
 }catch(error){
  $('connection').textContent='Mất kết nối hoặc dữ liệu đã cũ';$('count').textContent='Chỗ trống: --/3';
  for(let i=1;i<=3;i++){ $('s'+i).textContent='? Chưa xác định';$('s'+i).className='unknown';$('d'+i).textContent=''; }
  $('gates').textContent='Trạng thái cổng: chưa xác định'; $('backend').textContent='Chưa xác định';
 }finally{clearTimeout(timer);setTimeout(refresh,1000);}
}refresh();
</script></html>)html";

void recordCard(const char* gate, const byte* uid, byte size) {
  if (size != 4 && size != 7 && size != 10) return;
  const auto direction = strcmp(gate, "VAO") == 0 ? parking::Gate::In : parking::Gate::Out;
  char uidText[21] = {};
  for (byte i = 0; i < size; ++i)
    snprintf(uidText + 2 * i, sizeof(uidText) - 2 * i,
             "%02X", static_cast<unsigned>(uid[i]));
  if (!presentations.observe(direction, uidText, millis())) return;
  // Do not overwrite the displayed pending event when the other reader is busy.
  if (accessControl.busy() || transportBusy.load()) {
    Serial.printf("[%s] BUSY: remove card before trying again.\n", gate);
    return;
  }
  snprintf(hardware.lastUid, sizeof(hardware.lastUid), "%s", uidText);
  snprintf(hardware.lastGate, sizeof(hardware.lastGate), "%s", gate);
  hardware.lastScanMs = millis();
  hardware.lastEvent[0] = '\0';
  Serial.printf("[%s] UID: %s\n", gate, hardware.lastUid);
  if (otaRunning.load()) { showResult("OTA_IN_PROGRESS"); return; }
  if (!backendReady.load() || WiFi.status() != WL_CONNECTED) { showResult("BACKEND_UNAVAILABLE"); return; }
  if ((direction == parking::Gate::In ? entry : exitGate).state() != 0) { showResult("GATE_BUSY"); return; }
  parking::AccessRequest request;
  makeUuid(request.eventId);
  snprintf(request.cardUid, sizeof(request.cardUid), "%s", uidText);
  request.gate = direction; request.started = millis();
  accessControl.setRegistered(true);
  if (!accessControl.begin(direction, request.eventId, request.started)) { showResult("BUSY"); return; }
  transportBusy.store(true);
  if (xQueueSend(scanRequests, &request, 0) != pdTRUE) {
    transportBusy.store(false); accessControl.cancel(); showResult("BUSY"); return;
  }
  snprintf(hardware.lastEvent, sizeof(hardware.lastEvent), "%s", request.eventId);
  showResult("VALIDATING");
}

void makeUuid(char* output) {
  uint8_t bytes[16]; esp_fill_random(bytes, sizeof(bytes));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  snprintf(output, 37, "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
      bytes[0],bytes[1],bytes[2],bytes[3],bytes[4],bytes[5],bytes[6],bytes[7],
      bytes[8],bytes[9],bytes[10],bytes[11],bytes[12],bytes[13],bytes[14],bytes[15]);
}

void cardAbsent(const char* gate, bool uncertain) {
  const auto direction = strcmp(gate, "VAO") == 0 ? parking::Gate::In : parking::Gate::Out;
  if (uncertain) presentations.uncertain(direction);
  else presentations.absent(direction, millis());
}

void showResult(const char* result) {
  snprintf(hardware.lastResult, sizeof(hardware.lastResult), "%s", result);
  hardware.lastScanMs = millis();
  Serial.printf("[%s] %s | event=%s\n", hardware.lastGate, result, hardware.lastEvent);
}

void processAccess() {
  if (accessControl.tick(millis())) showResult("NEEDS_REVIEW");
  if (otaRunning.load() && accessControl.busy()) { accessControl.cancel(); showResult("NEEDS_REVIEW"); }
  parking::AccessReply reply;
  if (xQueueReceive(scanReplies, &reply, 0) != pdTRUE || !accessControl.matches(reply.eventId, reply.gate)) return;
  if (reply.allow && (WiFi.status() != WL_CONNECTED || otaRunning.load())) {
    accessControl.cancel(); showResult("NEEDS_REVIEW"); return;
  }
  const bool opened = accessControl.accept({reply.eventId, reply.gate, reply.allow, reply.allow, reply.durationMs}, millis());
  showResult(reply.allow && !opened ? "NEEDS_REVIEW" : reply.reason);
}

const char* backendLabel() {
  switch (backendState.load()) {
    case BackendState::Ready: return "ONLINE";
    case BackendState::Unauthorized: return "DEVICE_UNAUTHORIZED";
    case BackendState::Disabled: return "DEVICE_DISABLED";
    case BackendState::RateLimited: return "DEVICE_RATE_LIMITED";
    case BackendState::StaleBoot: return "DEVICE_BOOT_NOT_REGISTERED";
    case BackendState::BadResponse: return "INVALID_RESPONSE";
    case BackendState::ConfigError: return "CONFIG_REQUIRED";
    case BackendState::Starting: return "CONNECTING";
    default: return "OFFLINE";
  }
}

void backendTask(void*) {
  const bool configured = strlen(PARKING_DEVICE_KEY) >= 24 &&
      strcmp(PARKING_DEVICE_KEY, "COPY_DEVICE_KEY_FROM_PROJECT_ENV") &&
      strcmp(PARKING_WIFI_SSID, "YOUR_WIFI");
  uint64_t seq = 0;
  uint32_t lastHeartbeat = 0, changedAt = millis();
  bool first = true;
  int observed[3] = {UNKNOWN, UNKNOWN, UNKNOWN}, reported[3] = {UNKNOWN, UNKNOWN, UNKNOWN};
  auto markFailure = [](int status) {
    backendReady.store(false);
    backendState.store(status == 401 ? BackendState::Unauthorized : status == 403 ? BackendState::Disabled :
        status == 429 ? BackendState::RateLimited : status == 409 ? BackendState::StaleBoot :
        status == 200 ? BackendState::BadResponse : BackendState::Offline);
  };
  for (;;) {
    if (!configured) {
      backendState.store(BackendState::ConfigError); vTaskDelay(pdMS_TO_TICKS(1000)); continue;
    }
    if (WiFi.status() != WL_CONNECTED) markFailure(0);
    if (backendReady.load() && uint32_t(millis()-heartbeatAt.load()) >= 15000) markFailure(0);

    parking::AccessRequest request;
    if (xQueueReceive(scanRequests, &request, 0) == pdTRUE) {
      parking::AccessReply result;
      snprintf(result.eventId, sizeof(result.eventId), "%s", request.eventId);
      result.gate = request.gate;
      snprintf(result.reason, sizeof(result.reason), "BACKEND_UNAVAILABLE");
      JsonDocument json;
      json["bootId"] = bootId; json["eventId"] = request.eventId;
      json["cardUid"] = request.cardUid; json["gate"] = parking::gateCode(request.gate);
      std::string body; serializeJson(json, body);
      bool mayHaveSent = false, decided = false;
      for (unsigned attempt = 0; attempt < 3; ++attempt) {
        const uint32_t elapsed = millis()-request.started;
        if (elapsed >= parking::AccessDeadlineMs || otaRunning.load() || WiFi.status() != WL_CONNECTED) break;
        const uint32_t budget = std::min<uint32_t>(1000, parking::AccessDeadlineMs-elapsed);
        const auto response = parking::postJson("/api/v1/device/access-events", body, budget, mayHaveSent);
        if (uint32_t(millis()-request.started) >= parking::AccessDeadlineMs) break;
        if (response.status == 200 && parking::decodeDecision(response.body, request, result)) {
          decided = true; break;
        }
        if (response.status >= 400 && response.status < 500) {
          markFailure(response.status);
          const char* reason = response.status == 401 ? "DEVICE_UNAUTHORIZED" : response.status == 403 ? "DEVICE_DISABLED" :
              response.status == 429 ? "DEVICE_RATE_LIMITED" : response.status == 409 ? "DEVICE_CONFLICT" : "INVALID_REQUEST";
          snprintf(result.reason, sizeof(result.reason), "%s", mayHaveSent && attempt > 0 ? "NEEDS_REVIEW" : reason);
          decided = true; break; // Never retry a 4xx.
        }
        if (attempt < 2 && uint32_t(millis()-request.started) + 75 < parking::AccessDeadlineMs)
          vTaskDelay(pdMS_TO_TICKS(75));
      }
      if (!decided) {
        result.allow = false; result.durationMs = 0;
        snprintf(result.reason, sizeof(result.reason), "%s", mayHaveSent ? "NEEDS_REVIEW" : "BACKEND_UNAVAILABLE");
        markFailure(0);
      }
      xQueueOverwrite(scanReplies, &result);
      transportBusy.store(false);
    }

    Snapshot snapshot = readSnapshot();
    if (uint32_t(millis()-snapshot.updatedAtMs) >= 2000)
      for (int& slot : snapshot.slots) slot = UNKNOWN;
    bool changed = false;
    for (int i = 0; i < 3; ++i) {
      if (observed[i] != snapshot.slots[i]) { observed[i] = snapshot.slots[i]; changedAt = millis(); }
      if (reported[i] != observed[i]) changed = true;
    }
    const uint32_t now = millis();
    const bool due = first || uint32_t(now-lastHeartbeat) >= 5000 ||
        (backendReady.load() && changed && uint32_t(now-changedAt) >= 300 && uint32_t(now-lastHeartbeat) >= 1000);
    if (due && WiFi.status() == WL_CONNECTED && !otaRunning.load()) {
      first = false; lastHeartbeat = now;
      JsonDocument json;
      json["bootId"] = bootId; json["seq"] = seq++;
      json["uptimeMs"] = static_cast<uint64_t>(esp_timer_get_time()/1000);
      json["firmwareVersion"] = FIRMWARE_VERSION;
      auto slots = json["slots"].to<JsonArray>();
      for (int i = 0; i < 3; ++i) {
        auto slot = slots.add<JsonObject>();
        slot["slotId"] = String("S") + String(i+1);
        slot["state"] = snapshot.slots[i] == FREE ? "FREE" : snapshot.slots[i] == OCCUPIED ? "OCCUPIED" : "UNKNOWN";
      }
      std::string body; serializeJson(json, body);
      bool sent = false;
      const auto response = parking::postJson("/api/v1/device/heartbeats", body, 1000, sent);
      JsonDocument answer;
      if (response.status == 200 && !deserializeJson(answer, response.body) && answer["accepted"].is<bool>() && answer["accepted"].as<bool>()) {
        heartbeatAt.store(millis()); backendState.store(BackendState::Ready); backendReady.store(true);
        for (int i = 0; i < 3; ++i) reported[i] = snapshot.slots[i];
      } else markFailure(response.status);
    }
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

int classifyDistance(float cm) {
  if (!std::isfinite(cm) || cm < 2.0f || cm > 400.0f) return UNKNOWN;
  return cm >= CAR_MIN_CM && cm <= CAR_MAX_CM ? OCCUPIED : FREE;
}

void updateSlot(uint8_t index, int reading) {
  if (reading == UNKNOWN) {
    hardware.slots[index] = UNKNOWN;
    candidates[index] = UNKNOWN;
    streaks[index] = 0;
    return;
  }
  if (reading != candidates[index]) {
    candidates[index] = reading;
    streaks[index] = 1;
  } else if (streaks[index] < STABLE_READINGS) ++streaks[index];
  if (streaks[index] >= STABLE_READINGS) hardware.slots[index] = reading;
}

void sampleSlot(uint8_t index) {
  digitalWrite(TRIG_PINS[index], LOW);
  delayMicroseconds(3);
  digitalWrite(TRIG_PINS[index], HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PINS[index], LOW);
  const unsigned long duration = pulseIn(ECHO_PINS[index], HIGH, ECHO_TIMEOUT_US);
  hardware.distanceCm[index] = duration ? duration / 58.0f : -1.0f;
  updateSlot(index, classifyDistance(hardware.distanceCm[index]));
  hardware.updatedAtMs = millis();
}

void publishSnapshot() {
  hardware.gates[0] = entry.state(); hardware.gates[1] = exitGate.state();
  hardware.obstacles[0] = entry.obstacle(); hardware.obstacles[1] = exitGate.obstacle();
  portENTER_CRITICAL(&snapshotMux);
  sharedState = hardware;
  portEXIT_CRITICAL(&snapshotMux);
}

Snapshot readSnapshot() {
  portENTER_CRITICAL(&snapshotMux);
  Snapshot result = sharedState;
  portEXIT_CRITICAL(&snapshotMux);
  return result;
}

int freeCount(const Snapshot& state) {
  int count = 0;
  for (int i = 0; i < 3; ++i) if (state.slots[i] == FREE) ++count;
  return count;
}

char slotSymbol(int state) { return state == FREE ? 'T' : state == OCCUPIED ? 'X' : '?'; }
const char* gateName(uint8_t state) { return state == 1 ? "OPEN" : state == 2 ? "CLOSING" : "CLOSED"; }

void printRow(uint8_t row, const char* text) {
  if (!lcdReady) return;
  // Only write changed rows, preventing flicker and unnecessary I2C traffic.
  static char previous[2][17] = {};
  char padded[17];
  memset(padded, ' ', 16); padded[16] = '\0';
  const size_t length = strlen(text) < 16 ? strlen(text) : 16;
  memcpy(padded, text, length);
  if (strcmp(previous[row], padded) == 0) return;
  lcd.setCursor(0, row); lcd.print(padded);
  memcpy(previous[row], padded, sizeof(padded));
}

void refreshLcd() {
  char row[17];
  const bool recent = hardware.lastUid[0] && uint32_t(millis()-hardware.lastScanMs) < 5000;
  const char* result = hardware.lastResult;
  if (otaRunning.load()) snprintf(row, sizeof(row), "Dang cap nhat OTA");
  else if (recent && !strcmp(result, "VALIDATING")) snprintf(row, sizeof(row), "Dang xac thuc...");
  else if (recent && (!strcmp(result, "ENTRY_ALLOWED") || !strcmp(result, "EXIT_ALLOWED"))) snprintf(row, sizeof(row), "Cho phep %s", hardware.lastGate);
  else if (recent && !strcmp(result, "NEEDS_REVIEW")) snprintf(row, sizeof(row), "Can doi soat web");
  else if (!backendReady.load()) snprintf(row, sizeof(row), "Backend offline");
  else if (recent) snprintf(row, sizeof(row), "Tu choi %s", hardware.lastGate);
  else snprintf(row, sizeof(row), "Cho trong: %d/3", freeCount(hardware));
  printRow(0, row);
  const uint32_t age = millis() - hardware.lastScanMs;
  if (hardware.lastUid[0] && age < 5000) {
    // 12 hex characters per page; no UID bytes are discarded.
    const size_t length = strlen(hardware.lastUid);
    const uint8_t pages = (length + 11) / 12;
    const uint8_t page = (age / 1500) % pages;
    snprintf(row, sizeof(row), "%s %.12s", hardware.lastGate, hardware.lastUid + page * 12);
  } else {
    snprintf(row, sizeof(row), "1:%c 2:%c 3:%c", slotSymbol(hardware.slots[0]),
             slotSymbol(hardware.slots[1]), slotSymbol(hardware.slots[2]));
  }
  printRow(1, row);
}

void hardwareTask(void* parameter) {
  (void)parameter;
  uint32_t lastPoll = millis(), sonarFinished = millis(), lastDisplay = 0, lastLog = 0;
  uint8_t sensor = 0;
  bool entryTurn = true;
  for (;;) {
    processAccess();
    if (uint32_t(millis() - lastPoll) >= RFID_POLL_MS) {
      lastPoll = millis();
      if (entryTurn) entry.scan(); else exitGate.scan();
      entryTurn = !entryTurn;
      entry.update(); exitGate.update();
    }
    if (uint32_t(millis() - sonarFinished) >= SONAR_GAP_MS) {
      sampleSlot(sensor);
      sonarFinished = millis(); // Quiet gap starts AFTER the previous measurement.
      sensor = (sensor + 1) % 3;
      entry.update(); exitGate.update();
    }
    publishSnapshot();
    if (uint32_t(millis() - lastDisplay) >= 250) {
      lastDisplay = millis(); refreshLcd();
    }
    if (uint32_t(millis() - lastLog) >= 2000) {
      lastLog = millis();
      Serial.printf("Slots %c/%c/%c | %.1f/%.1f/%.1f cm | free=%d\n",
        slotSymbol(hardware.slots[0]), slotSymbol(hardware.slots[1]), slotSymbol(hardware.slots[2]),
        hardware.distanceCm[0], hardware.distanceCm[1], hardware.distanceCm[2], freeCount(hardware));
    }
    vTaskDelay(1); // One FreeRTOS tick, always reachable.
  }
}

String occupancyJson(const Snapshot& state) {
  String json; json.reserve(420);
  json = "{";
  for (int i = 0; i < 3; ++i) {
    if (i) json += ',';
    json += "\"slot"; json += String(i + 1); json += "\":";
    json += state.slots[i] == UNKNOWN ? "null" : state.slots[i] == OCCUPIED ? "true" : "false";
  }
  json += ",\"free\":"; json += String(freeCount(state));
  json += ",\"distanceCm\":[";
  for (int i = 0; i < 3; ++i) {
    if (i) json += ',';
    json += classifyDistance(state.distanceCm[i]) == UNKNOWN ? String("null") : String(state.distanceCm[i], 1);
  }
  json += "],\"entryGate\":\""; json += gateName(state.gates[0]);
  json += "\",\"exitGate\":\""; json += gateName(state.gates[1]);
  json += "\",\"lastUid\":\""; json += state.lastUid;
  json += "\",\"lastGate\":\""; json += state.lastGate;
  json += "\",\"lastScanMs\":"; json += String(state.lastScanMs);
  json += ",\"sampleAgeMs\":"; json += String(uint32_t(millis() - state.updatedAtMs));
  json += ",\"backendStatus\":\""; json += backendLabel();
  json += "\",\"lastResult\":\""; json += state.lastResult;
  json += "\",\"lastEventId\":\""; json += state.lastEvent;
  json += "\",\"otaInProgress\":"; json += otaRunning.load() ? "true" : "false";
  json += '}';
  return json;
}

void maintainWifi() {
  static bool connectedBefore = false;
  static uint32_t lastAttempt = millis();
  const bool connected = WiFi.status() == WL_CONNECTED;
  if (connected && !connectedBefore) {
    Serial.printf("Wi-Fi OK. Web: http://%s/ | OTA: http://%s/update\n",
                  WiFi.localIP().toString().c_str(), WiFi.localIP().toString().c_str());
  } else if (!connected && connectedBefore) {
    backendReady.store(false); backendState.store(BackendState::Offline);
    Serial.println("Wi-Fi lost; no new gate authorization. Existing motion continues safely.");
  }
  connectedBefore = connected;
  if (!connected && uint32_t(millis() - lastAttempt) >= WIFI_RETRY_MS) {
    lastAttempt = millis(); WiFi.reconnect();
    Serial.println("Retrying Wi-Fi; new access requires backend authorization.");
  }
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(true);
  WiFi.begin(PARKING_WIFI_SSID, PARKING_WIFI_PASSWORD);
  makeUuid(bootId);
  scanRequests = xQueueCreate(1, sizeof(parking::AccessRequest));
  scanReplies = xQueueCreate(1, sizeof(parking::AccessReply));
  if (!scanRequests || !scanReplies) {
    Serial.println("FATAL: queue allocation failed.");
    for (;;) delay(1000);
  }
  pinMode(14, OUTPUT); digitalWrite(14, !EXIT_BUZZER_ACTIVE);
  pinMode(15, OUTPUT); digitalWrite(15, !ENTRY_BUZZER_ACTIVE);
  for (int i = 0; i < 3; ++i) {
    pinMode(TRIG_PINS[i], OUTPUT); digitalWrite(TRIG_PINS[i], LOW);
    pinMode(ECHO_PINS[i], INPUT);
  }
  Wire.begin(21, 22); Wire.setClock(100000); Wire.setTimeOut(20);
  Wire.beginTransmission(LCD_ADDR); lcdReady = Wire.endTransmission() == 0;
  if (lcdReady) { lcd.init(); lcd.backlight(); refreshLcd(); }
  else Serial.println("LCD not found: check SDA21/SCL22 and LCD_ADDR (0x27 or actual address).");

  pinMode(17, OUTPUT); pinMode(5, OUTPUT);
  digitalWrite(17, HIGH); digitalWrite(5, HIGH);
  SPI.begin(18, 19, 23);
  entry.begin(); exitGate.begin();
  accessControl.boot();
  publishSnapshot();
  const BaseType_t created = xTaskCreatePinnedToCore(
      hardwareTask, "ParkingHardware", 8192, nullptr, 1, nullptr, ARDUINO_RUNNING_CORE);
  if (created != pdPASS) {
    Serial.println("FATAL: hardware task creation failed. Reboot after checking memory.");
    for (;;) delay(1000);
  }

  const BaseType_t networkCreated = xTaskCreatePinnedToCore(
      backendTask, "ParkingBackend", 12288, nullptr, 1, nullptr, 0);
  if (networkCreated != pdPASS) {
    backendReady.store(false);
    Serial.println("FATAL: backend task creation failed; new access disabled.");
  }
  server.on("/", HTTP_GET, []() { server.send_P(200, "text/html; charset=utf-8", INDEX_HTML); });
  server.on("/occupancy", HTTP_GET, []() {
    const Snapshot snapshot = readSnapshot();
    server.sendHeader("Cache-Control", "no-store");
    server.send(200, "application/json", occupancyJson(snapshot));
  });
  ElegantOTA.onStart([]() { otaRunning.store(true); backendReady.store(false); });
  ElegantOTA.onEnd([](bool success) { if (!success) otaRunning.store(false); });
  ElegantOTA.begin(&server, PARKING_OTA_USER, PARKING_OTA_PASSWORD);
  server.begin(); // Available when Wi-Fi connects, including a late connection.
  Serial.printf("READY: backend authorization required. Firmware=%s | boot=%s\n", FIRMWARE_VERSION, bootId);
}

void loop() {
  maintainWifi();
  if (WiFi.status() == WL_CONNECTED) server.handleClient();
  ElegantOTA.loop();
  delay(1);
}
