// ACT4 model macros for BOOM (SpinalHDL port) running in BoomSim.
// The simulated SoC (BoomSim) is the tile, memory at 0x8000_0000 and two
// uncached IO regions the harness turns into interrupt lines (see
// BoomSim.Platform): a SiFive-layout CLINT at 0x0200_0000 (mtime counts
// cycles) and an interrupt generator at 0x0C00_0000 whose words 0 and 1 drive
// meip and seip. The harness watches stores to `tohost` (1 = pass, 3 = fail).
// There is no console.
#ifndef _RVMODEL_MACROS_H
#define _RVMODEL_MACROS_H

#define RVMODEL_DATA_SECTION \
        .pushsection .tohost,"aw",@progbits;                \
        .balign 8; .global tohost; tohost: .dword 0;         \
        .balign 8; .global fromhost; fromhost: .dword 0;     \
        .popsection;

#define STANDARD_SM_SUPPORTED

##### TERMINATION #####

#define RVMODEL_HALT_PASS  \
  li x1, 1                ;\
  la t0, tohost           ;\
  write_tohost_pass:      ;\
    sw x1, 0(t0)          ;\
    sw x0, 4(t0)          ;\
  self_loop_pass:         ;\
    j self_loop_pass      ;\

#define RVMODEL_HALT_FAIL \
  li x1, 3                ;\
  la t0, tohost           ;\
  write_tohost_fail:      ;\
    sw x1, 0(t0)          ;\
    sw x0, 4(t0)          ;\
  self_loop_fail:         ;\
    j self_loop_fail      ;\

##### IO #####

// No console.
#define RVMODEL_IO_INIT(_R1, _R2, _R3)
#define RVMODEL_IO_WRITE_STR(_R1, _R2, _R3, _STR_PTR)

##### Access Fault #####

// Address 0 is not mapped.
#define RVMODEL_ACCESS_FAULT_ADDRESS 0x00000000

##### Interrupt Latency #####

// Iterations of a csrr mip polling loop
#define RVMODEL_INTERRUPT_LATENCY 100

##### Machine Timer #####

// Ticks (cycles) from arming mtimecmp to the interrupt; long enough to reach
// the lower privilege mode first.
#define RVMODEL_TIMER_INT_SOON_DELAY 10000

#define CLINT_BASE_ADDRESS       0x02000000
#define RVMODEL_MTIME_ADDRESS    (CLINT_BASE_ADDRESS + 0xBFF8)
#define RVMODEL_MTIMECMP_ADDRESS (CLINT_BASE_ADDRESS + 0x4000)
#define RVMODEL_MSIP_ADDRESS     (CLINT_BASE_ADDRESS + 0x0)

##### External Interrupts #####

#define INTGEN_BASE_ADDRESS 0x0C000000

#define RVMODEL_SET_MEXT_INT(_R1, _R2) \
  li _R1, 1;                           \
  li _R2, INTGEN_BASE_ADDRESS;         \
  sw _R1, 0(_R2);

#define RVMODEL_CLR_MEXT_INT(_R1, _R2) \
  li _R2, INTGEN_BASE_ADDRESS;         \
  sw zero, 0(_R2);

#define RVMODEL_SET_SEXT_INT(_R1, _R2) \
  li _R1, 1;                           \
  li _R2, INTGEN_BASE_ADDRESS;         \
  sw _R1, 4(_R2);

#define RVMODEL_CLR_SEXT_INT(_R1, _R2) \
  li _R2, INTGEN_BASE_ADDRESS;         \
  sw zero, 4(_R2);

#endif // _RVMODEL_MACROS_H
