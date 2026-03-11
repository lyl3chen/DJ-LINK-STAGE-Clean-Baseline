#include <ableton/Link.hpp>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <csignal>
#include <cstring>
#include <cerrno>
#include <iostream>
#include <regex>
#include <sstream>
#include <string>

namespace {
volatile std::sig_atomic_t gRunning = 1;

void onSignal(int) { gRunning = 0; }

struct SyncPayload {
  double tempo = 120.0;
  double beatPosition = 0.0;
  bool playing = false;
  long long timestamp = 0;
  int sourcePlayer = -1;
  std::string sourceState = "OFFLINE";
};

std::string extractString(const std::string& s, const std::string& key, const std::string& def = "") {
  std::regex re("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
  std::smatch m;
  if (std::regex_search(s, m, re) && m.size() > 1) return m[1].str();
  return def;
}

double extractDouble(const std::string& s, const std::string& key, double def = 0.0) {
  std::regex re("\\\"" + key + "\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
  std::smatch m;
  if (std::regex_search(s, m, re) && m.size() > 1) {
    try { return std::stod(m[1].str()); } catch (...) {}
  }
  return def;
}

long long extractLong(const std::string& s, const std::string& key, long long def = 0) {
  std::regex re("\\\"" + key + "\\\"\\s*:\\s*(-?[0-9]+)");
  std::smatch m;
  if (std::regex_search(s, m, re) && m.size() > 1) {
    try { return std::stoll(m[1].str()); } catch (...) {}
  }
  return def;
}

bool extractBool(const std::string& s, const std::string& key, bool def = false) {
  std::regex re("\\\"" + key + "\\\"\\s*:\\s*(true|false)");
  std::smatch m;
  if (std::regex_search(s, m, re) && m.size() > 1) return m[1].str() == "true";
  return def;
}

bool parsePayload(const std::string& s, SyncPayload& out, std::string& err) {
  if (s.find("{") == std::string::npos) {
    err = "invalid json: missing object";
    return false;
  }
  out.tempo = extractDouble(s, "tempo", out.tempo);
  out.beatPosition = extractDouble(s, "beatPosition", out.beatPosition);
  out.playing = extractBool(s, "playing", out.playing);
  out.timestamp = extractLong(s, "timestamp", out.timestamp);
  out.sourcePlayer = static_cast<int>(extractLong(s, "sourcePlayer", out.sourcePlayer));
  out.sourceState = extractString(s, "sourceState", out.sourceState);
  err.clear();
  return true;
}

std::string jsonEscape(const std::string& in) {
  std::string out;
  out.reserve(in.size() + 16);
  for (char c : in) {
    switch (c) {
      case '\\': out += "\\\\"; break;
      case '"': out += "\\\""; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default: out += c; break;
    }
  }
  return out;
}

long long nowMs() {
  using namespace std::chrono;
  return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

} // namespace

int main() {
  std::signal(SIGINT, onSignal);
  std::signal(SIGTERM, onSignal);

  const char* hostEnv = std::getenv("LINK_BRIDGE_HOST");
  const char* listenEnv = std::getenv("LINK_BRIDGE_LISTEN_PORT");
  const char* ackHostEnv = std::getenv("LINK_BRIDGE_ACK_HOST");
  const char* ackPortEnv = std::getenv("LINK_BRIDGE_ACK_PORT");

  std::string host = hostEnv ? hostEnv : "127.0.0.1";
  int listenPort = listenEnv ? std::atoi(listenEnv) : 19110;
  std::string ackHost = ackHostEnv ? ackHostEnv : "127.0.0.1";
  int ackPort = ackPortEnv ? std::atoi(ackPortEnv) : 19111;

  std::string backendMode = "abletonlink-active";
  bool backendLoaded = true;
  std::string backendInitError;
  std::string backendVersion = "ableton-link-cpp";
  std::string peerDetectionWorking = "true";

  std::cerr << "[cpp-link-bridge] abletonlink loaded" << std::endl;

  ableton::Link link(120.0);
  link.enable(true);
  link.enableStartStopSync(true);
  std::cerr << "[cpp-link-bridge] backend init success mode=abletonlink-active" << std::endl;

  int recvSock = ::socket(AF_INET, SOCK_DGRAM, 0);
  int sendSock = ::socket(AF_INET, SOCK_DGRAM, 0);
  if (recvSock < 0 || sendSock < 0) {
    std::cerr << "[cpp-link-bridge] socket create failed" << std::endl;
    return 2;
  }

  sockaddr_in recvAddr{};
  recvAddr.sin_family = AF_INET;
  recvAddr.sin_port = htons(static_cast<uint16_t>(listenPort));
  recvAddr.sin_addr.s_addr = inet_addr(host.c_str());

  if (::bind(recvSock, reinterpret_cast<sockaddr*>(&recvAddr), sizeof(recvAddr)) < 0) {
    std::cerr << "[cpp-link-bridge] bind failed host=" << host << " port=" << listenPort << std::endl;
    return 3;
  }

  // 关键：给 recvfrom 设置超时，保证收到 SIGTERM 后主循环能尽快退出，避免被管理器强杀(137)。
  timeval tv{};
  tv.tv_sec = 0;
  tv.tv_usec = 200 * 1000; // 200ms
  setsockopt(recvSock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

  sockaddr_in ackAddr{};
  ackAddr.sin_family = AF_INET;
  ackAddr.sin_port = htons(static_cast<uint16_t>(ackPort));
  ackAddr.sin_addr.s_addr = inet_addr(ackHost.c_str());

  std::cerr << "[cpp-link-bridge] listening udp://" << host << ":" << listenPort
            << ", ack=>udp://" << ackHost << ":" << ackPort << std::endl;

  char buf[4096];
  SyncPayload payload;

  while (gRunning) {
    sockaddr_in srcAddr{};
    socklen_t srcLen = sizeof(srcAddr);
    const ssize_t n = recvfrom(recvSock, buf, sizeof(buf) - 1, 0,
                               reinterpret_cast<sockaddr*>(&srcAddr), &srcLen);
    if (n <= 0) {
      if (!gRunning) break;
      if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
      continue;
    }

    buf[n] = '\0';
    std::string msg(buf, static_cast<size_t>(n));

    std::string parseErr;
    bool ok = parsePayload(msg, payload, parseErr);
    std::string error;

    if (!ok) {
      error = parseErr;
    } else {
      const auto micros = std::chrono::microseconds(payload.timestamp * 1000);
      auto state = link.captureAppSessionState();
      if (std::isfinite(payload.tempo) && payload.tempo > 0.0) {
        state.setTempo(payload.tempo, micros);
      }
      if (std::isfinite(payload.beatPosition)) {
        state.requestBeatAtTime(payload.beatPosition, micros, 4.0);
      }
      state.setIsPlaying(payload.playing, micros);
      link.commitAppSessionState(state);

      std::cerr << "[cpp-link-bridge] rx tempo=" << payload.tempo
                << " beat=" << payload.beatPosition
                << " playing=" << (payload.playing ? "true" : "false")
                << " ts=" << payload.timestamp << std::endl;
    }

    const std::size_t peers = link.numPeers();
    const long long ts = nowMs();

    std::ostringstream oss;
    oss << "{"
        << "\"type\":\"ack\"," 
        << "\"running\":true,"
        << "\"numPeers\":" << peers << ","
        << "\"lastAckTs\":" << ts << ","
        << "\"error\":\"" << jsonEscape(error) << "\"," 
        << "\"backendMode\":\"" << backendMode << "\"," 
        << "\"backendLoaded\":" << (backendLoaded ? "true" : "false") << ","
        << "\"backendVersion\":\"" << jsonEscape(backendVersion) << "\"," 
        << "\"peerDetectionWorking\":\"" << peerDetectionWorking << "\"," 
        << "\"backendInitError\":\"" << jsonEscape(backendInitError) << "\""
        << "}";

    const auto ack = oss.str();
    sendto(sendSock, ack.c_str(), ack.size(), 0,
           reinterpret_cast<sockaddr*>(&ackAddr), sizeof(ackAddr));
  }

  ::close(recvSock);
  ::close(sendSock);
  std::cerr << "[cpp-link-bridge] stopping" << std::endl;
  return 0;
}
