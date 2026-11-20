// Minimal Art-Net test for the Electroconcept USB Node.
// Two RGBW pars: par 1 at DMX channel 1, par 2 at DMX channel 5.
// Build: clang++ -std=c++17 -O2 artnet_test.cpp -o artnet_test
// Run:   ./artnet_test [node_ip] [universe]   (defaults: 2.0.0.1, 0)

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>

struct RGBW { uint8_t r, g, b, w; };

class ArtNet {
public:
    ArtNet(const char* ip, uint16_t universe) : universe_(universe) {
        sock_ = socket(AF_INET, SOCK_DGRAM, 0);
        int yes = 1;
        setsockopt(sock_, SOL_SOCKET, SO_BROADCAST, &yes, sizeof yes);
        std::memset(&dst_, 0, sizeof dst_);
        dst_.sin_family = AF_INET;
        dst_.sin_port = htons(6454);
        inet_pton(AF_INET, ip, &dst_.sin_addr);
        dmx_.fill(0);
    }
    ~ArtNet() { close(sock_); }

    void setPar(int startChannel, RGBW c) {  // startChannel is 1-based
        int i = startChannel - 1;
        dmx_[i] = c.r; dmx_[i + 1] = c.g; dmx_[i + 2] = c.b; dmx_[i + 3] = c.w;
    }

    bool send() {
        uint8_t pkt[18 + 512];
        std::memcpy(pkt, "Art-Net\0", 8);
        pkt[8] = 0x00; pkt[9] = 0x50;            // OpDmx (little endian 0x5000)
        pkt[10] = 0; pkt[11] = 14;               // protocol version 14
        pkt[12] = seq_ = (seq_ % 255) + 1;       // sequence 1..255
        pkt[13] = 0;                             // physical port
        pkt[14] = universe_ & 0xFF;              // SubUni
        pkt[15] = (universe_ >> 8) & 0x7F;       // Net
        pkt[16] = 0x02; pkt[17] = 0x00;          // length 512, big endian
        std::memcpy(pkt + 18, dmx_.data(), 512);
        return sendto(sock_, pkt, sizeof pkt, 0,
                      reinterpret_cast<sockaddr*>(&dst_), sizeof dst_) == (ssize_t)sizeof pkt;
    }

private:
    int sock_;
    sockaddr_in dst_;
    uint16_t universe_;
    uint8_t seq_ = 0;
    std::array<uint8_t, 512> dmx_;
};

// Hold a frame for `seconds`, resending at ~40 Hz (nodes may time out without refresh).
static void hold(ArtNet& a, double seconds) {
    auto end = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
    while (std::chrono::steady_clock::now() < end) {
        if (!a.send()) std::perror("sendto");
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
    }
}

int main(int argc, char** argv) {
    const char* ip = argc > 1 ? argv[1] : "2.0.0.1";
    uint16_t universe = argc > 2 ? (uint16_t)std::atoi(argv[2]) : 0;
    ArtNet a(ip, universe);
    std::printf("Sending Art-Net to %s universe %u\n", ip, universe);

    struct Step { const char* name; RGBW p1, p2; };
    const Step steps[] = {
        {"par1 RED   / par2 off",   {255, 0, 0, 0}, {0, 0, 0, 0}},
        {"par1 off   / par2 RED",   {0, 0, 0, 0},   {255, 0, 0, 0}},
        {"both GREEN",              {0, 255, 0, 0}, {0, 255, 0, 0}},
        {"both BLUE",               {0, 0, 255, 0}, {0, 0, 255, 0}},
        {"both WHITE (W channel)",  {0, 0, 0, 255}, {0, 0, 0, 255}},
    };
    for (const auto& s : steps) {
        std::printf("  %s\n", s.name);
        a.setPar(1, s.p1);
        a.setPar(5, s.p2);
        hold(a, 2.0);
    }

    std::printf("  rainbow fade (6 s)\n");
    for (int f = 0; f < 240; ++f) {
        double t = f / 40.0;
        auto ch = [](double x) { return (uint8_t)(127.5 + 127.5 * std::sin(x)); };
        a.setPar(1, {ch(t), ch(t + 2.094), ch(t + 4.189), 0});
        a.setPar(5, {ch(t + 3.14), ch(t + 5.23), ch(t + 7.33), 0});
        hold(a, 0.025);
    }

    std::printf("  blackout\n");
    a.setPar(1, {0, 0, 0, 0});
    a.setPar(5, {0, 0, 0, 0});
    hold(a, 0.5);
    return 0;
}
