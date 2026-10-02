#pragma once
#include <array>
#include <cstdint>
#include <string>

namespace parking {
enum class Gate { In, Out };
constexpr uint32_t AccessDeadlineMs = 3000;
struct Decision {
    std::string eventId;
    Gate gate;
    bool allow;
    bool commandOpen;
    uint32_t durationMs;
};
struct HardwareDriver {
    virtual ~HardwareDriver() = default;
    virtual void open(Gate gate, uint32_t durationMs) = 0;
    virtual void tick(uint32_t nowMs) = 0; // Never block heartbeat while a gate is open.
    virtual void rest() = 0;
};
// Observe even while offline/BUSY: a rejected presentation must never turn
// into a queued opening when connectivity returns. Only hardware task owns this.
class Presentations {
    struct Reader {
        std::string uid;
        uint32_t lastScan = 0, absentSince = 0;
        bool absent = false, seen = false;
    };
    std::array<Reader, 2> readers{};
    Reader& reader(Gate g) { return readers[g == Gate::In ? 0 : 1]; }
public:
    void absent(Gate g, uint32_t now) {
        auto& r = reader(g);
        if (!r.absent) { r.absent = true; r.absentSince = now; }
    }
    void uncertain(Gate g) { reader(g).absent = false; }
    bool observe(Gate g, const std::string& uid, uint32_t now) {
        auto& r = reader(g);
        const bool eligible = !r.seen || r.uid != uid ||
            (r.absent && uint32_t(now - r.absentSince) >= 1000 &&
             uint32_t(now - r.lastScan) >= 2000);
        r.absent = false; r.uid = uid; r.seen = true;
        if (eligible) r.lastScan = now;
        return eligible;
    }
};

// The sketch and host tests use this same policy.
class AccessController {
    HardwareDriver& hardware;
    bool registered = false, pending = false, executed = false;
    uint32_t started = 0;
    Gate gate = Gate::In;
    std::string event;
public:
    explicit AccessController(HardwareDriver& driver) : hardware(driver) {}
    void boot() { registered = pending = executed = false; event.clear(); hardware.rest(); }
    void setRegistered(bool value) { registered = value; }
    bool busy() const { return pending; }
    bool matches(const std::string& id, Gate g) const { return pending && id == event && g == gate; }
    bool begin(Gate g, const std::string& eventId, uint32_t now) {
        if (!registered || pending) return false;
        pending = true; executed = false; started = now; gate = g; event = eventId;
        return true;
    }
    bool accept(const Decision& d, uint32_t now) {
        if (!matches(d.eventId, d.gate) || executed || uint32_t(now - started) >= AccessDeadlineMs)
            return false;
        pending = false;
        if (!d.allow || !d.commandOpen || d.durationMs != 3000) return false;
        executed = true; // Consume BEFORE touching the servo.
        hardware.open(gate, d.durationMs);
        return true;
    }
    void cancel() { pending = false; }
    bool retryAllowed(unsigned attempts, uint32_t now) const {
        return pending && attempts < 3 && uint32_t(now - started) < AccessDeadlineMs;
    }
    bool tick(uint32_t now) {
        hardware.tick(now);
        if (pending && uint32_t(now - started) >= AccessDeadlineMs) { pending = false; return true; }
        return false;
    }
};
}
