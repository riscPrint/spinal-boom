# Builds the riscv-tests benchmarks twice from the same sources and flags:
#   build/bare/<b>.riscv   bare metal, for the RTL (BenchmarksSpec)
#   build/linux/<b>.riscv  static Linux binaries, for gem5 SE mode
#
#   gem5/docker.sh make -C gem5/bench      # both, in the container
#
# Build both in the container: it has GCC 13 for both targets, so the two
# builds get the same code (a different GCC gives different instruction
# counts, which breaks the comparison).
#
# Both builds measure the same region of interest: the code between
# setStats(1) and setStats(0) in each benchmark.

SRC        ?= ../../../ref/riscv-tests/benchmarks
BARE_CC    ?= riscv64-unknown-elf-gcc --specs=picolibc.specs
LINUX_CC   ?= riscv64-linux-gnu-gcc

# Single-threaded benchmarks that bracket their kernel with setStats() (mm reads
# mcycle directly, which traps in gem5 SE mode; the mt-*, vec-*
# and pmp benchmarks need multiple harts, V, or M-mode setup).
BENCHES ?= median qsort rsort towers vvadd memcpy multiply dhrystone spmv

# Must match the port: RV64GC + Zba/Zbb/Zbs (act4/boom-rv64gc/boom-rv64gc.yaml).
MARCH  ?= rv64gc_zba_zbb_zbs
CFLAGS ?= -march=$(MARCH) -mabi=lp64d -mcmodel=medany -O2 -std=gnu99 -ffast-math -fno-common \
          -fno-builtin-printf -fno-tree-loop-distribute-patterns \
          -Wno-implicit-int -Wno-implicit-function-declaration -U_FORTIFY_SOURCE \
          -DPREALLOCATE=1

BARE_FLAGS  = -static -nostdlib -nostartfiles -T $(SRC)/common/test.ld
LINUX_FLAGS = -static
# dhrystone's Start/Stop_Timer read mcycle, which traps in gem5's user mode.
# MSC_CLOCK makes it use clock() (one emulated syscall at each end of the ROI).
# CLK_TCK=1 makes its minimum run time 2 clock() ticks (2 us), so it does one
# pass, like the bare-metal build. (0 would compile a divide-by-zero trap.)
LINUX_FLAGS_dhrystone = -DMSC_CLOCK -DCLK_TCK=1

incs = -I$(SRC)/../env -I$(SRC)/common -I$(SRC)/$*

.PHONY: all bare linux clean
all: bare linux
bare:  $(BENCHES:%=build/bare/%.riscv)
linux: $(BENCHES:%=build/linux/%.riscv)

# riscv-tests' bare-metal runtime, with two changes (see patch_syscalls.sh):
# no HTIF console traffic, and the ROI counters are stored to `bench_stats`
# where the RTL harness sees them.
build/bare/syscalls.c: $(SRC)/common/syscalls.c rt/patch_syscalls.sh
	@mkdir -p $(@D)
	sh rt/patch_syscalls.sh $< > $@

build/bare/%.riscv: build/bare/syscalls.c $(wildcard $(SRC)/common/*) FORCE
	@mkdir -p $(@D)
	$(BARE_CC) $(incs) $(CFLAGS) $(BARE_FLAGS) -o $@ \
	  $(wildcard $(SRC)/$*/*.c) $(wildcard $(SRC)/$*/*.S) \
	  build/bare/syscalls.c $(SRC)/common/crt.S -lm -lgcc

build/linux/%.riscv: rt/linux_rt.c FORCE
	@mkdir -p $(@D)
	$(LINUX_CC) $(incs) $(CFLAGS) $(LINUX_FLAGS) $(LINUX_FLAGS_$*) -o $@ \
	  $(wildcard $(SRC)/$*/*.c) $(wildcard $(SRC)/$*/*.S) rt/linux_rt.c -lm

FORCE:
clean:
	rm -rf build
