#pragma once
#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <string>

namespace parking {
enum class HttpParse { Incomplete, Complete, Invalid };
struct HttpReply { int status = 0; std::string body; };

// Bounded HTTP framing shared by firmware and host tests. Accept both the
// Spring server's Content-Length and a reverse proxy's chunked response.
inline HttpParse parseHttp(const std::string& wire, bool eof, HttpReply& reply) {
    if (wire.size() > 8192) return HttpParse::Invalid;
    const auto split = wire.find("\r\n\r\n");
    if (split == std::string::npos) return eof ? HttpParse::Invalid : HttpParse::Incomplete;
    if (split > 4096 || (wire.compare(0, 9, "HTTP/1.1 ") && wire.compare(0, 9, "HTTP/1.0 ")) ||
        wire.size() < 12 || !std::isdigit(wire[9]) || !std::isdigit(wire[10]) || !std::isdigit(wire[11]))
        return HttpParse::Invalid;
    reply.status = (wire[9]-'0')*100 + (wire[10]-'0')*10 + wire[11]-'0';
    if (reply.status < 200) return HttpParse::Invalid;
    bool chunked = false;
    long length = -1;
    auto pos = wire.find("\r\n") + 2;
    while (pos < split) {
        const auto end = wire.find("\r\n", pos);
        auto line = wire.substr(pos, end-pos);
        std::transform(line.begin(), line.end(), line.begin(), [](unsigned char c){ return std::tolower(c); });
        auto colon = line.find(':');
        if (colon == std::string::npos) return HttpParse::Invalid;
        const auto name = line.substr(0, colon);
        auto value = line.substr(colon+1);
        value.erase(0, value.find_first_not_of(" \t"));
        if (name == "content-length") {
            if (length >= 0 || value.empty() || value.find_first_not_of("0123456789") != std::string::npos)
                return HttpParse::Invalid;
            length = std::strtol(value.c_str(), nullptr, 10);
            if (length < 0 || length > 4096) return HttpParse::Invalid;
        } else if (name == "transfer-encoding") {
            if (value != "chunked") return HttpParse::Invalid;
            chunked = true;
        }
        pos = end + 2;
    }
    if (chunked && length >= 0) return HttpParse::Invalid;
    const auto body = wire.substr(split+4);
    if (!chunked) {
        if (body.size() > 4096) return HttpParse::Invalid;
        if (length >= 0 && body.size() < static_cast<size_t>(length))
            return eof ? HttpParse::Invalid : HttpParse::Incomplete;
        if (length < 0 && !eof) return HttpParse::Incomplete;
        reply.body = length < 0 ? body : body.substr(0, length);
        return HttpParse::Complete;
    }
    reply.body.clear(); pos = 0;
    for (;;) {
        const auto end = body.find("\r\n", pos);
        if (end == std::string::npos) return eof ? HttpParse::Invalid : HttpParse::Incomplete;
        auto size = body.substr(pos, end-pos);
        const auto extension = size.find(';');
        if (extension != std::string::npos) size.resize(extension);
        if (size.empty() || size.size() > 4 || size.find_first_not_of("0123456789abcdefABCDEF") != std::string::npos)
            return HttpParse::Invalid;
        const auto count = std::strtoul(size.c_str(), nullptr, 16);
        if (count > 4096 || reply.body.size() + count > 4096) return HttpParse::Invalid;
        pos = end + 2;
        if (count == 0) {
            if (body.size() < pos+2) return eof ? HttpParse::Invalid : HttpParse::Incomplete;
            if (body.compare(pos, 2, "\r\n") == 0 || body.find("\r\n\r\n", pos) != std::string::npos)
                return HttpParse::Complete;
            return eof ? HttpParse::Invalid : HttpParse::Incomplete;
        }
        if (body.size() < pos+count+2) return eof ? HttpParse::Invalid : HttpParse::Incomplete;
        if (body.compare(pos+count, 2, "\r\n")) return HttpParse::Invalid;
        reply.body.append(body, pos, count);
        pos += count+2;
    }
}
}
