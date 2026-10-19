#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "game/Random.h"
#include "cpp_rng_range_guard.h"

int main(int argc, char **argv) {
    if (argc != 5) return 2;
    const std::int32_t range = static_cast<std::int32_t>(std::strtol(argv[4], nullptr, 10));
    if (!inclusiveRandomRangeIsSafe(range)) return 3;
    sts::Random rng;
    rng.seed0 = std::stoull(argv[1], nullptr, 16);
    rng.seed1 = std::stoull(argv[2], nullptr, 16);
    rng.counter = static_cast<std::int32_t>(std::strtol(argv[3], nullptr, 10));
    const std::int32_t selectedIndex = rng.random(range);
    std::printf("%d\t%016llx\t%016llx\t%d\n", selectedIndex,
            static_cast<unsigned long long>(rng.seed0),
            static_cast<unsigned long long>(rng.seed1), rng.counter);
    return 0;
}
