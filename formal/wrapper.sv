// riscv-formal wrapper for BOOM (Spinal port), NRET = 1 (small BOOM).
// The I$/D$ TileLink responders inside BoomFormalTop take their data and
// stalls from free inputs, as do the interrupt lines.
module rvfi_wrapper (
	input         clock,
	input         reset,
	`RVFI_OUTPUTS
);
	(* keep *) `rvformal_rand_reg [63:0] imem_rand_data;
	(* keep *) `rvformal_rand_reg        imem_rand_stall_a;
	(* keep *) `rvformal_rand_reg        imem_rand_stall_d;
	(* keep *) `rvformal_rand_reg [63:0] dmem_rand_data;
	(* keep *) `rvformal_rand_reg        dmem_rand_stall_a;
	(* keep *) `rvformal_rand_reg        dmem_rand_stall_d;
`ifdef BOOM_FORMAL_IRQ
	(* keep *) `rvformal_rand_reg [4:0]  irq;
`else
	wire [4:0] irq = 5'b0;
`endif

	BoomFormalTop uut (
		.clk                     (clock),
		.reset                   (reset),
		.io_interrupts_debug     (irq[0]),
		.io_interrupts_mtip      (irq[1]),
		.io_interrupts_msip      (irq[2]),
		.io_interrupts_meip      (irq[3]),
		.io_interrupts_seip      (irq[4]),
		.io_imem_rand_data       (imem_rand_data),
		.io_imem_rand_stall_a    (imem_rand_stall_a),
		.io_imem_rand_stall_d    (imem_rand_stall_d),
		.io_dmem_rand_data       (dmem_rand_data),
		.io_dmem_rand_stall_a    (dmem_rand_stall_a),
		.io_dmem_rand_stall_d    (dmem_rand_stall_d),
		.io_rvfi_0_valid         (rvfi_valid),
		.io_rvfi_0_order         (rvfi_order),
		.io_rvfi_0_insn          (rvfi_insn),
		.io_rvfi_0_trap          (rvfi_trap),
		.io_rvfi_0_halt          (rvfi_halt),
		.io_rvfi_0_intr          (rvfi_intr),
		.io_rvfi_0_mode          (rvfi_mode),
		.io_rvfi_0_ixl           (rvfi_ixl),
		.io_rvfi_0_rs1_addr      (rvfi_rs1_addr),
		.io_rvfi_0_rs2_addr      (rvfi_rs2_addr),
		.io_rvfi_0_rs1_rdata     (rvfi_rs1_rdata),
		.io_rvfi_0_rs2_rdata     (rvfi_rs2_rdata),
		.io_rvfi_0_rd_addr       (rvfi_rd_addr),
		.io_rvfi_0_rd_wdata      (rvfi_rd_wdata),
		.io_rvfi_0_pc_rdata      (rvfi_pc_rdata),
		.io_rvfi_0_pc_wdata      (rvfi_pc_wdata),
		.io_rvfi_0_mem_addr      (rvfi_mem_addr),
		.io_rvfi_0_mem_rmask     (rvfi_mem_rmask),
		.io_rvfi_0_mem_wmask     (rvfi_mem_wmask),
		.io_rvfi_0_mem_rdata     (rvfi_mem_rdata),
		.io_rvfi_0_mem_wdata     (rvfi_mem_wdata)
	);
endmodule
