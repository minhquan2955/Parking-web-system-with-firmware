#include "../include/http_response.hpp"
#include "../include/device_protocol.hpp"
#include <cassert>
#include <iostream>

int main() {
 using namespace parking;
 HttpReply response;
 const std::string wire = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}";
 for (size_t n = 0; n < wire.size(); ++n)
   assert(parseHttp(wire.substr(0,n),false,response) == HttpParse::Incomplete);
 assert(parseHttp(wire,false,response) == HttpParse::Complete && response.body == "{}");
 assert(parseHttp(wire.substr(0,wire.size()-1),true,response) == HttpParse::Invalid);
 const std::string chunks = "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n1\r\n{\r\n1;foo=bar\r\n}\r\n0\r\n\r\n";
 for (size_t n = 0; n < chunks.size(); ++n)
   assert(parseHttp(chunks.substr(0,n),false,response) == HttpParse::Incomplete);
 assert(parseHttp(chunks,false,response) == HttpParse::Complete && response.body == "{}");
 assert(parseHttp("HTTP/1.0 503 Error\r\n\r\n{}",true,response) == HttpParse::Complete && response.status == 503);
 assert(parseHttp("HTTP/1.1 200 OK\r\nContent-Length: 9000\r\n\r\n",false,response) == HttpParse::Invalid);
 assert(parseHttp("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n",false,response) == HttpParse::Invalid);
 assert(parseHttp("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",false,response) == HttpParse::Invalid);
 assert(parseHttp("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Test: yes\r\n\r\n",false,response) == HttpParse::Complete);
 assert(parseHttp(std::string(8193,'a'),false,response) == HttpParse::Invalid);

 AccessRequest request;
 std::snprintf(request.eventId,sizeof(request.eventId),"c9df745c-cad1-435a-b859-7eb0c49c1aa6");
 AccessReply reply;
 JsonDocument json;
 json["eventId"]=request.eventId; json["gate"]="IN";
 json["decision"]="ALLOW"; json["command"]="OPEN"; json["reason"]="ENTRY_ALLOWED";
 json["openDurationMs"]=3000; json["sessionId"]="02c06f8b-0df8-4c63-94a4-f0bbbc92e94f";
 json["serverTime"]="2026-10-02T23:59:59.999123Z"; json["validUntil"]="2026-10-03T00:00:02.999123Z";
 auto decode = [&]() { std::string body; serializeJson(json,body); return decodeDecision(body,request,reply); };
 assert(decode() && reply.allow && reply.durationMs == 3000);
 json["gate"]="OUT"; assert(!decode()); json["gate"]="IN";
 json["eventId"]="other"; assert(!decode()); json["eventId"]=request.eventId;
 json["openDurationMs"]="3000"; assert(!decode()); json["openDurationMs"]=3000;
 json["validUntil"]="2026-10-03T00:01:02Z"; assert(!decode());
 json["validUntil"]="2026-10-02T00:00:02Z"; assert(!decode());
 json["decision"]="DENY"; json["command"]="NONE"; json["openDurationMs"]=0;
 json["validUntil"]=nullptr; json["sessionId"]=nullptr; json["reason"]="UNKNOWN_CARD";
 assert(decode() && !reply.allow);
 json["command"]="OPEN"; assert(!decode());
 assert(!decodeDecision("{broken",request,reply));
 assert(instantMs("2024-02-29T00:00:00Z") > 0);
 assert(instantMs("2025-02-29T00:00:00Z") == -1);
 assert(instantMs("2026-10-02T00:00:00.123456789Z") - instantMs("2026-10-02T00:00:00Z") == 123);
 std::cout << "HTTP framing and device decision contract tests passed\n";
}
