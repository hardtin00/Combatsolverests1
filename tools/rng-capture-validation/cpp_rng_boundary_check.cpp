#include <cstdint>
#include <cstdio>
#include <cstdio>
#include <limits>
#include "cpp_rng_range_guard.h"

int main() {
    const std::int32_t max = (std::numeric_limits<std::int32_t>::max)();
    if (inclusiveRandomRangeIsSafe(max)) {
        std::fprintf(stderr, "INT_MAX must be rejected before range + 1 is evaluated\n");
        return 1;
    }
    if (inclusiveRandomRangeIsSafe(-1)
            || inclusiveRandomRangeIsSafe((std::numeric_limits<std::int32_t>::min)())) {
        std::fprintf(stderr, "negative inclusive ranges must be rejected\n");
        return 1;
    }
    if (!inclusiveRandomRangeIsSafe(max - 1) || !inclusiveRandomRangeIsSafe(0)) {
        std::fprintf(stderr, "valid boundary rejected\n");
        return 1;
    }
    std::printf("RNG_RANGE_BOUNDARY_OK rejected=INT_MIN,-1,INT_MAX accepted=0,INT_MAX-1\n");
    return 0;
}
