#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <string>

#include "game/Random.h"
#include "cpp_rng_range_guard.h"

static std::int32_t randomInclusive(sts::Random &rng, std::int32_t range) {
    if (!inclusiveRandomRangeIsSafe(range)) {
        throw std::invalid_argument("inclusive random range outside safe int32 domain");
    }
    return rng.random(range);
}

static std::uint64_t parseHex(const char *text) {
    return static_cast<std::uint64_t>(std::stoull(text, nullptr, 16));
}

static std::string hex64(std::uint64_t value) {
    char out[17];
    std::snprintf(out, sizeof(out), "%016llx", static_cast<unsigned long long>(value));
    return std::string(out);
}

static void row(const char *name, const std::string &value, const sts::Random &rng) {
    const std::string seed0 = hex64(rng.seed0);
    const std::string seed1 = hex64(rng.seed1);
    std::printf("%s\t%s\t%s\t%s\t%d\n", name, value.c_str(), seed0.c_str(), seed1.c_str(), rng.counter);
}

static std::string floatBits(float value) {
    union { float f; std::uint32_t u; } bits;
    bits.f = value;
    char out[9];
    std::snprintf(out, sizeof(out), "%x", bits.u);
    return std::string(out);
}

static std::string doubleBits(double value) {
    union { double d; std::uint64_t u; } bits;
    bits.d = value;
    return hex64(bits.u);
}

int main(int argc, char **argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: cpp_rng_trace <seed0-hex> <seed1-hex> <counter>\n");
        return 2;
    }
    sts::Random rng;
    rng.seed0 = parseHex(argv[1]);
    rng.seed1 = parseHex(argv[2]);
    rng.counter = static_cast<std::int32_t>(std::strtol(argv[3], nullptr, 10));

    row("nextLong", hex64(rng.nextLong()), rng);
    row("nextInt-rejection-bound-1073741825", std::to_string(rng.nextInt(1073741825)), rng);
    row("nextFloat-bits", floatBits(rng.nextFloat()), rng);
    row("nextDouble-bits", doubleBits(rng.nextDouble()), rng);
    row("random-int-inclusive-0", std::to_string(randomInclusive(rng, 0)), rng);
    row("random-int-inclusive-1", std::to_string(randomInclusive(rng, 1)), rng);
    row("random-int-inclusive-2", std::to_string(randomInclusive(rng, 2)), rng);
    row("random-int-inclusive-10", std::to_string(randomInclusive(rng, 10)), rng);
    row("random-int-inclusive-large", std::to_string(randomInclusive(rng, 1073741824)), rng);
    row("random-int-inclusive-max-safe", std::to_string(randomInclusive(
            rng, (std::numeric_limits<std::int32_t>::max)() - 1)), rng);
    row("random-int-signed-range", std::to_string(rng.random(-100, 100)), rng);
    row("random-float-bits", floatBits(rng.random()), rng);
    row("random-float-scaled-bits", floatBits(rng.random(17.25f)), rng);
    row("random-float-range-bits", floatBits(rng.random(-1.25f, 5.75f)), rng);
    row("random-long", hex64(static_cast<std::uint64_t>(rng.randomLong())), rng);
    row("random-long-scaled", hex64(static_cast<std::uint64_t>(rng.random(4294967296LL))), rng);
    row("random-boolean", rng.randomBoolean() ? "1" : "0", rng);
    row("random-boolean-half", rng.randomBoolean(0.5f) ? "1" : "0", rng);
    return 0;
}
