# A load or store whose page-table walk cannot read a PTE must raise an access
# fault (cause 5 / 7), not a page fault (13 / 15). Two ways a PTE read fails:
#   - the PTE address is outside physical memory (a pointer PTE with a huge PPN);
#   - PMP denies S-mode reads of the page-table page.
# Runs in S-mode under Sv39; every trap goes to M-mode.
# tohost: 1 = pass, (n << 1) | 1 = fail at check n.

#define CHECK_FAULT(n, cause, va, ...) \
  li t6, n;           \
  li t5, cause;       \
  li a0, va;          \
  __VA_ARGS__;        \
  bnez t5, fail       /* the trap handler clears t5 */

  .section .text.init
  .globl _start
_start:
  la t0, mtrap
  csrw mtvec, t0
  csrw medeleg, zero
  csrw mideleg, zero
  csrw mie, zero        # mie has no reset value
  li t6, 0

  # PMP entry 0: the denied page-table page, no permissions (NAPOT, 4 KiB).
  # PMP entry 1: everything else, RWX (NAPOT, all of memory).
  la t0, denied_pt
  srli t0, t0, 2
  ori t0, t0, 0x1ff
  csrw pmpaddr0, t0
  li t0, -1
  csrw pmpaddr1, t0
  li t0, 0x1f18
  csrw pmpcfg0, t0

  # Sv39 root table:
  #   [0] VA 0x0000_0000 -> pointer to PPN 1 << 30, outside physical memory
  #   [1] VA 0x4000_0000 -> pointer to denied_pt (PMP denies reading it)
  #   [2] VA 0x8000_0000 -> 1 GiB identity leaf, RWX, A/D set (code and data)
  la a1, root_pt
  li t0, ((1 << 30) << 10) | 1
  sd t0, 0(a1)
  la t0, denied_pt
  srli t0, t0, 12
  slli t0, t0, 10
  ori t0, t0, 1
  sd t0, 8(a1)
  li t0, (0x80000 << 10) | 0xcf
  sd t0, 16(a1)

  srli t0, a1, 12
  li t1, 8 << 60
  or t0, t0, t1
  csrw satp, t0
  sfence.vma

  # Drop to S-mode.
  li t0, 3 << 11
  csrc mstatus, t0
  li t0, 1 << 11
  csrs mstatus, t0
  la t0, s_code
  csrw mepc, t0
  mret

s_code:
  CHECK_FAULT(1, 5, 0x1000, ld a1, 0(a0))       # PTE outside memory, load
  CHECK_FAULT(2, 7, 0x1000, sd a1, 0(a0))       # PTE outside memory, store
  CHECK_FAULT(3, 5, 0x40001000, ld a1, 0(a0))   # PTE read denied by PMP, load
  CHECK_FAULT(4, 7, 0x40001000, sd a1, 0(a0))   # PTE read denied by PMP, store
  CHECK_FAULT(5, 5, 0x40002000, lw a1, 0(a0))   # again, after the fault entry is cached
  li t6, 6
  la a0, scratch                               # a mapped page still works
  sd t6, 0(a0)
  ld a1, 0(a0)
  bne a1, t6, fail
  ecall                                        # done

mtrap:
  csrr t0, mcause
  li t1, 9                                     # ecall from S: pass
  beq t0, t1, pass
  bne t0, t5, fail
  csrr t0, mtval
  bne t0, a0, fail
  li t5, 0
  csrr t0, mepc
  addi t0, t0, 4
  csrw mepc, t0
  mret

pass:
  li t0, 1
  j report
fail:
  slli t0, t6, 1
  ori t0, t0, 1
report:
  la t1, tohost
  sd t0, 0(t1)
1:j 1b

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0

  .data
  .align 12
root_pt:   .zero 4096
denied_pt: .zero 4096
scratch:   .zero 4096
