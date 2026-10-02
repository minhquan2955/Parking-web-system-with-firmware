#pragma once
#include <Arduino.h>
#include <WiFi.h>
#include <lwip/sockets.h>
#include <fcntl.h>
#include <cerrno>
#include "http_response.hpp"

namespace parking {
// No DNS or blocking Stream reads. One monotonic budget covers connect, write,
// headers and body, including a server that sends one byte at a time.
inline HttpReply postJson(const char* path, const std::string& body, uint32_t budgetMs, bool& mayHaveSent) {
    HttpReply reply;
    if (WiFi.status() != WL_CONNECTED || !budgetMs) return reply;
    const uint32_t started = millis();
    sockaddr_in target{};
    target.sin_family = AF_INET;
    target.sin_port = htons(PARKING_BACKEND_PORT);
    if (inet_pton(AF_INET, PARKING_BACKEND_IP, &target.sin_addr) != 1) return reply;
    const int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) return reply;
    struct SocketGuard { int fd; ~SocketGuard() { close(fd); } } guard{fd};
    if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0) return reply;
    auto ready = [&](bool writing) {
        const uint32_t elapsed = millis() - started;
        if (elapsed >= budgetMs) return false;
        const uint32_t remaining = budgetMs - elapsed;
        timeval timeout{static_cast<long>(remaining/1000), static_cast<long>((remaining%1000)*1000)};
        fd_set set; FD_ZERO(&set); FD_SET(fd, &set);
        return select(fd+1, writing ? nullptr : &set, writing ? &set : nullptr, nullptr, &timeout) > 0;
    };
    if (connect(fd, reinterpret_cast<sockaddr*>(&target), sizeof(target)) != 0) {
        if (errno != EINPROGRESS || !ready(true)) return reply;
        int error = 0; socklen_t size = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0 || error) return reply;
    }
    std::string request = std::string("POST ") + path + " HTTP/1.1\r\nHost: " + PARKING_BACKEND_IP +
        ":" + std::to_string(PARKING_BACKEND_PORT) + "\r\nContent-Type: application/json\r\nAccept: application/json\r\n"
        "Connection: close\r\nX-Device-Id: " + PARKING_DEVICE_ID + "\r\nX-Device-Key: " + PARKING_DEVICE_KEY +
        "\r\nContent-Length: " + std::to_string(body.size()) + "\r\n\r\n" + body;
    size_t offset = 0;
    while (offset < request.size()) {
        if (!ready(true)) return {};
        const int count = send(fd, request.data()+offset, request.size()-offset, 0);
        if (count < 0 && (errno == EWOULDBLOCK || errno == EAGAIN)) continue;
        if (count <= 0) return {};
        mayHaveSent = true; offset += count;
    }
    std::string wire; wire.reserve(2048);
    while (uint32_t(millis()-started) < budgetMs) {
        if (!ready(false)) return {};
        char buffer[512];
        const int count = recv(fd, buffer, sizeof(buffer), 0);
        if (count < 0 && (errno == EWOULDBLOCK || errno == EAGAIN)) continue;
        if (count < 0) return {};
        if (count) wire.append(buffer, count);
        const auto state = parseHttp(wire, count == 0, reply);
        if (state == HttpParse::Complete && uint32_t(millis()-started) < budgetMs) return reply;
        if (state == HttpParse::Invalid || count == 0) return {};
    }
    return {};
}
}
