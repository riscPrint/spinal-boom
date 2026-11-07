# ECALL and EBREAK do not retire (Priv. 1.12, 3.3.1).
  .section .text.init
  .globl _start
_start:
  csrw mie, zero
  csrw mcountinhibit, zero
  la t0, trap
  csrw mtvec, t0
  csrr a0, minstret
  ecall
  csrr a1, minstret
  sub a1, a1, a0
  li t1, 5 # first CSR read plus four handler instructions
  bne a1, t1, fail
  csrr a0, minstret
  ebreak
  csrr a1, minstret
  sub a1, a1, a0
  bne a1, t1, fail
  li a0, 1
  j halt
trap:
  csrr t0, mepc
  addi t0, t0, 4
  csrw mepc, t0
  mret
fail:
  li a0, 3
halt:
  la t0, tohost
  sd a0, 0(t0)
1: j 1b
  .section .tohost,"aw",@progbits
  .balign 64
  .globl tohost
tohost: .dword 0
