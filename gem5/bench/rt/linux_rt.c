// Runtime for the gem5 syscall-emulation (Linux) build of the riscv-tests
// benchmarks. setStats() brackets the region of interest exactly as in the
// bare-metal build: setStats(1) resets gem5's stats, setStats(0) dumps them,
// so the first dump in m5out/stats.txt covers just the ROI.
#include <stdint.h>

// gem5 pseudo-instructions: custom-3 opcode, function in bits 31:25
// (ref/gem5/util/m5/src/abi/riscv/m5op.S, include/gem5/asm/generic/m5ops.h).
#define M5OP_RESET_STATS "0x40"
#define M5OP_DUMP_STATS  "0x41"
#define M5OP(func) do { \
    register uint64_t a0 asm("a0") = 0; /* delay */ \
    register uint64_t a1 asm("a1") = 0; /* period */ \
    asm volatile(".word 0x7b | (" func " << 25)" : "+r"(a0) : "r"(a1) : "memory"); \
  } while (0)

void setStats(int enable)
{
  if (enable)
    M5OP(M5OP_RESET_STATS);
  else
    M5OP(M5OP_DUMP_STATS);
}
