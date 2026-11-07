# Per-instruction PMP at a four-byte boundary, packet edge, and page edge.
# Every case enters S-mode directly. The M-mode trap checks cause and mepc.
  .section .text.init
  .globl _start
_start:
  csrw mie, zero
  csrw medeleg, zero
  la t0, trap
  csrw mtvec, t0
  li s2, 2
restart:
  la s0, cases
  li s1, 8
next:
  ld a0, 0(s0)                 # instruction address
  ld a1, 8(s0)                 # first PMP region address
  ld a2, 16(s0)                # PMP config
  ld a4, 32(s0)                # expected mtval for access faults
  ld a3, 24(s0)                # expected trap cause
  srli t0, a1, 2
  csrw pmpaddr0, t0
  li t0, -1
  csrw pmpaddr1, t0
  csrw pmpcfg0, a2
  fence.i
  li t0, 3 << 11
  csrc mstatus, t0
  li t0, 1 << 11
  csrs mstatus, t0
  csrw mepc, a0
  mret
trap:
  csrr t0, mcause
  bne t0, a3, fail
  csrr t0, mepc
  bne t0, a0, fail
  li t0, 1
  bne a3, t0, checked
  csrr t0, mtval
  bne t0, a4, fail
checked:
  addi s0, s0, 40
  addi s1, s1, -1
  bnez s1, next
  addi s2, s2, -1
  beqz s2, pass
  # Repeat through Sv39, with a 1 GiB identity leaf. The page-edge
  # instruction now needs two separate I-TLB page translations.
  la t0, root_pt
  li t1, (0x80000 << 10) | 0xcf
  sd t1, 16(t0)
  srli t0, t0, 12
  li t1, 8 << 60
  or t0, t0, t1
  csrw satp, t0
  sfence.vma
  j restart
pass:
  li a0, 1
  j halt
fail:
  slli a0, s1, 1
  ori a0, a0, 1
halt:
  la t0, tohost
  sd a0, 0(t0)
1: j 1b

  .balign 8
legal32:
  ecall                        # fits in NA4, rest of packet denies execute
legal16:
  .2byte 0x0001                # c.nop
legal16_end:
  .2byte 0x9002                # c.ebreak at last parcel of NA4
  .balign 8
  .2byte 0x0001
straddle:
  .4byte 0x00000073            # ecall, second parcel outside NA4
  .2byte 0x0001
  .balign 8
  .space 6
packet_edge:
  .4byte 0x00000073
  .balign 4096
  .space 4094
page_edge:
  .4byte 0x00000073

  .section .data
  .balign 8
cases:
  # NA4 RWX, fallback NAPOT RW (no execute).
  .dword legal32, legal32, 0x1b17, 9, 0
  .dword legal16_end, legal16, 0x1b17, 3, 0
  .dword straddle, straddle-2, 0x1b17, 1, straddle+2
  # NA4 deny, fallback NAPOT RWX. Deny second parcel, then first parcel.
  .dword packet_edge, packet_edge+2, 0x1f10, 1, packet_edge+2
  .dword packet_edge, packet_edge-2, 0x1f10, 1, packet_edge
  .dword page_edge, page_edge+2, 0x1f10, 1, page_edge+2
  .dword page_edge, page_edge-2, 0x1f10, 1, page_edge
  # Both parcels are allowed across a packet edge.
  .dword packet_edge, legal32, 0x1f17, 9, 0
  .section .tohost,"aw",@progbits
  .balign 64
  .globl tohost
tohost: .dword 0

  .section .bss
  .balign 4096
root_pt: .space 4096
