#pragma once
#include <ArduinoJson.h>
#include <cstdio>
#include <cstring>
#include "access_controller.hpp"

namespace parking {
struct AccessRequest {
    char eventId[37]{};
    char cardUid[21]{};
    Gate gate = Gate::In;
    uint32_t started = 0;
};
struct AccessReply {
    char eventId[37]{};
    Gate gate = Gate::In;
    bool allow = false;
    uint32_t durationMs = 0;
    char reason[48]{};
};
inline const char* gateCode(Gate gate) { return gate == Gate::In ? "IN" : "OUT"; }

// Spring Instant serializes UTC with optional fractional seconds. Validate
// the original 3s validity interval without depending on an ESP32 wall clock.
inline int64_t instantMs(const char* value) {
    if (!value) return -1;
    const size_t size = std::strlen(value);
    if (size < 20 || size > 30 || value[size-1] != 'Z') return -1;
    int year, month, day, hour, minute, second, consumed = 0;
    if (std::sscanf(value, "%4d-%2d-%2dT%2d:%2d:%2d%n", &year, &month, &day, &hour, &minute, &second, &consumed) != 6 ||
        consumed != 19 || year < 1970 || year > 2199 || month < 1 || month > 12 || day < 1 ||
        hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) return -1;
    const auto leap = [](int y) { return y%4 == 0 && (y%100 != 0 || y%400 == 0); };
    const int months[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (day > months[month-1] + (month == 2 && leap(year))) return -1;
    int64_t days = day-1;
    for (int y = 1970; y < year; ++y) days += leap(y) ? 366 : 365;
    for (int m = 1; m < month; ++m) days += months[m-1] + (m == 2 && leap(year));
    int fraction = 0;
    if (size > 20) {
        if (value[19] != '.' || size < 22) return -1;
        int multiplier = 100;
        for (size_t i = 20; i < size-1; ++i) {
            if (value[i] < '0' || value[i] > '9') return -1;
            fraction += (value[i]-'0') * multiplier;
            multiplier /= 10;
        }
    }
    return ((days*24+hour)*60*60 + minute*60 + second)*1000 + fraction;
}

inline bool decodeDecision(const std::string& body, const AccessRequest& request, AccessReply& reply) {
    JsonDocument json;
    if (deserializeJson(json, body) || !json.is<JsonObject>()) return false;
    if (!json["eventId"].is<const char*>() || !json["gate"].is<const char*>() ||
        std::strcmp(json["eventId"], request.eventId) || std::strcmp(json["gate"], gateCode(request.gate)) ||
        !json["decision"].is<const char*>() || !json["command"].is<const char*>() ||
        !json["reason"].is<const char*>() || !json["openDurationMs"].is<uint32_t>()) return false;
    const char* decision = json["decision"];
    const char* command = json["command"];
    const char* reason = json["reason"];
    const bool allow = std::strcmp(decision, "ALLOW") == 0;
    if (!allow && std::strcmp(decision, "DENY")) return false;
    if (!*reason || std::strlen(reason) >= sizeof(reply.reason)) return false;
    for (const char* p = reason; *p; ++p) if ((*p < 'A' || *p > 'Z') && *p != '_') return false;
    const uint32_t duration = json["openDurationMs"];
    const int64_t serverTime = instantMs(json["serverTime"] | static_cast<const char*>(nullptr));
    if (serverTime < 0) return false;
    if (allow) {
        const int64_t validUntil = instantMs(json["validUntil"] | static_cast<const char*>(nullptr));
        if (std::strcmp(command, "OPEN") || duration != 3000 || validUntil-serverTime != 3000 ||
            !json["sessionId"].is<const char*>() || std::strlen(json["sessionId"]) != 36) return false;
    } else if (std::strcmp(command, "NONE") || duration || !json["validUntil"].isNull() || !json["sessionId"].isNull()) {
        return false;
    }
    std::snprintf(reply.eventId, sizeof(reply.eventId), "%s", request.eventId);
    reply.gate = request.gate; reply.allow = allow; reply.durationMs = duration;
    std::snprintf(reply.reason, sizeof(reply.reason), "%s", reason);
    return true;
}
}
