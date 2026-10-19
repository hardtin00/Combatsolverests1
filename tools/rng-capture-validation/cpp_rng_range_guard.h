#ifndef CS1_RNG_VALIDATION_CPP_RNG_RANGE_GUARD_H
#define CS1_RNG_VALIDATION_CPP_RNG_RANGE_GUARD_H

#include <cstdint>
#include <limits>

// sts::Random::random(range) evaluates range + 1 as int32_t. Keep this guard
// inside the isolated validation harness so invalid inputs never reach it.
inline bool inclusiveRandomRangeIsSafe(std::int32_t range) {
    return range >= 0 && range < (std::numeric_limits<std::int32_t>::max)();
}

#endif
