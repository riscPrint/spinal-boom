# FP loads must mark FS Dirty when they commit (Priv. 1.12, 3.1.6.6).
# Check both Initial and Clean states. Run without compressed instructions.
  .section .text.init
  .globl _start
_start:
  la t0, fail
  csrw mtvec, t0
  csrw mie, zero
  li t1, 3 << 13
  li t2, 1 << 13
  la a0, value
  csrc mstatus, t1
  csrs mstatus, t2
  flw f0, 0(a0)
  csrr t0, mstatus
  and t0, t0, t1
  bne t0, t1, fail
  li t2, 2 << 13
  csrc mstatus, t1
  csrs mstatus, t2
  fld f1, 0(a0)
  csrr t0, mstatus
  and t0, t0, t1
  bne t0, t1, fail
  li a0, 1
  j halt
fail:
  li a0, 3
halt:
  la t0, tohost
  sd a0, 0(t0)
1: j 1b
  .section .data
  .balign 8
value: .quad 0x3ff0000000000000
  .section .tohost,"aw",@progbits
  .balign 64
  .globl tohost
tohost: .dword 0
