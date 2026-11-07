# Per-instruction PMP at a four-byte boundary, packet edge, and page edge.
# Every case enters S-mode directly. The M-mode trap checks cause and mepc.
  .section .text.init
  .globl _start
_start:
  csrw mie, zero
  csrw medeleg, zero
  la t0, trap
  csrw mtvec, t0
  li s4, 0
  li s2, 2
restart:
  la s0, cases
  li s1, 13
next:
  li s3, 0
  ld a5, 40(s0)                # expected trap PC
  ld a6, 48(s0)                # expected register write
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
  bne t0, a5, fail
  li t0, 1
  bne a3, t0, checked
  csrr t0, mtval
  bne t0, a4, fail
checked:
  bne s3, a6, fail
  addi s0, s0, 56
  addi s1, s1, -1
  bnez s1, next
  bnez s4, pass
  addi s2, s2, -1
  beqz s2, priority_setup
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
priority_setup:
  # Replace the identity leaf with 4 KiB leaves. The second instruction
  # parcel has no mapping. Its stale/unused physical PMP result must not
  # replace the page fault. Denying the first parcel must fault before PTW.
  li s4, 1
  la t0, root_pt
  la t1, level1_pt
  srli t1, t1, 2
  ori t1, t1, 1
  sd t1, 16(t0)
  la t0, level1_pt
  la t1, level0_pt
  srli t1, t1, 2
  ori t1, t1, 1
  sd t1, 0(t0)
  la t0, level0_pt
  li t1, (0x80000 << 10) | 0xcf
  li t2, 512
2:
  sd t1, 0(t0)
  addi t0, t0, 8
  addi t1, t1, 1024
  addi t2, t2, -1
  bnez t2, 2b
  la t0, page_edge+2
  srli t0, t0, 12
  andi t0, t0, 511
  slli t0, t0, 3
  la t1, level0_pt
  add t0, t0, t1
  sd zero, 0(t0)
  sfence.vma
  la s0, priority_cases
  li s1, 2
  j next
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

  .balign 8
prefix_write:
  addi s3, zero, 42
prefix_fault:
  .4byte 0x00000073
  .balign 8
redirect_half:
  j redirect_target
  .2byte 0x0001
  .2byte 0x0073                # saved half on the discarded fall-through
  .balign 64
redirect_target:
  ecall
  .balign 4096
  .globl forbidden_fetch_start, forbidden_fetch_end
forbidden_fetch_start:
  .4byte 0x00000073            # both parcels denied, cold cache line
forbidden_rvc:
  .2byte 0x9002
  .space 58
forbidden_fetch_end:

  .section .data
  .balign 8
cases:
  # NA4 RWX, fallback NAPOT RW (no execute).
  .dword legal32, legal32, 0x1b17, 9, 0, legal32, 0
  .dword legal16_end, legal16, 0x1b17, 3, 0, legal16_end, 0
  .dword straddle, straddle-2, 0x1b17, 1, straddle+2, straddle, 0
  # NA4 deny, fallback NAPOT RWX. Deny second parcel, then first parcel.
  .dword packet_edge, packet_edge+2, 0x1f10, 1, packet_edge+2, packet_edge, 0
  .dword packet_edge, packet_edge-2, 0x1f10, 1, packet_edge, packet_edge, 0
  .dword page_edge, page_edge+2, 0x1f10, 1, page_edge+2, page_edge, 0
  .dword page_edge, page_edge-2, 0x1f10, 1, page_edge, page_edge, 0
  # Both parcels are allowed across a packet edge.
  .dword packet_edge, legal32, 0x1f17, 9, 0, packet_edge, 0
  .dword prefix_write, prefix_write, 0x1b17, 1, prefix_fault, prefix_fault, 42
  .dword forbidden_fetch_start, forbidden_fetch_start+31, 0x1f18, 1, forbidden_fetch_start, forbidden_fetch_start, 0
  .dword forbidden_rvc, forbidden_fetch_start+31, 0x1f18, 1, forbidden_rvc, forbidden_rvc, 0
  .dword redirect_half, legal32, 0x1f17, 9, 0, redirect_target, 0
  .dword prefix_write, prefix_write+4, 0x1b0f, 1, prefix_fault, prefix_fault, 42
priority_cases:
  .dword page_edge, page_edge+2, 0x1f10, 12, 0, page_edge, 0
  .dword page_edge, page_edge-2, 0x1f10, 1, page_edge, page_edge, 0
  .section .tohost,"aw",@progbits
  .balign 64
  .globl tohost
tohost: .dword 0

  .section .bss
  .balign 4096
root_pt: .space 4096
level1_pt: .space 4096
level0_pt: .space 4096
