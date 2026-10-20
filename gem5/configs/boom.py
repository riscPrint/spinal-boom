# gem5 model of the BOOM v4 presets in this repo, for architecture exploration.
#
# Runs one RISC-V binary on an O3 CPU set up from the BOOM preset
# (src/main/scala/boom/common/config-mixins.scala). The memory system mirrors
# the RTL simulation SoC: private L1 I$/D$ straight to a fixed-latency memory,
# no L2 and no prefetcher.
#
#   gem5.opt --outdir=OUT gem5/configs/boom.py --config large BINARY
#   ... --set cpu.fetchToDecodeDelay=4 --set dcache.mshrs=8     # overrides
#
# Two modes, picked from the binary:
#  * A static Linux binary runs in syscall-emulation (SE) mode.
#  * A bare-metal riscv-tests ELF (one that defines `tohost`, e.g.
#    gem5/bench/build/bare/*.riscv, the same bytes the RTL runs) runs in
#    full-system mode, M-mode from its entry point, see run_bare().
#
# Read docs/GEM5.md first: this is an approximation of BOOM, calibrated
# against the RTL (gem5/calibrate.sh), and only relative results are meaningful.

import argparse
import json
import math
import mmap
import os
import struct

import m5
from m5.objects import *

# --------------------------------------------------------------------------
# BOOM presets. Field names follow BoomCoreParams / DCacheParams /
# ICacheParams. Issue queues are (issueWidth, numEntries).
# --------------------------------------------------------------------------
PRESETS = {
    "small": dict(
        fetchWidth=4, decodeWidth=1, numRobEntries=32,
        mem_iq=(2, 8), unq_iq=(1, 8), alu_iq=(1, 8), fp_iq=(1, 8),
        numIntPhysRegisters=52, numFpPhysRegisters=48,
        numLdqEntries=8, numStqEntries=8, maxBrCount=8,
        numFetchBufferEntries=8, ftqEntries=16, lsuWidth=1,
        dcache=dict(rowBits=64, nSets=64, nWays=4, nMSHRs=2, nTLBWays=8),
        icache=dict(rowBits=64, nSets=64, nWays=4, fetchBytes=8),
    ),
    "medium": dict(
        fetchWidth=4, decodeWidth=2, numRobEntries=64,
        mem_iq=(2, 12), unq_iq=(1, 12), alu_iq=(2, 20), fp_iq=(1, 12),
        numIntPhysRegisters=80, numFpPhysRegisters=64,
        numLdqEntries=16, numStqEntries=16, maxBrCount=12,
        numFetchBufferEntries=16, ftqEntries=32, lsuWidth=1,
        dcache=dict(rowBits=64, nSets=64, nWays=4, nMSHRs=2, nTLBWays=8),
        icache=dict(rowBits=64, nSets=64, nWays=4, fetchBytes=8),
    ),
    "large": dict(
        fetchWidth=8, decodeWidth=3, numRobEntries=96,
        mem_iq=(2, 16), unq_iq=(1, 16), alu_iq=(3, 16), fp_iq=(1, 24),
        numIntPhysRegisters=100, numFpPhysRegisters=96,
        numLdqEntries=24, numStqEntries=24, maxBrCount=16,
        numFetchBufferEntries=24, ftqEntries=32, lsuWidth=1,
        dcache=dict(rowBits=128, nSets=64, nWays=8, nMSHRs=4, nTLBWays=16),
        icache=dict(rowBits=128, nSets=64, nWays=8, fetchBytes=16),
    ),
    "mega": dict(
        fetchWidth=8, decodeWidth=4, numRobEntries=128,
        mem_iq=(3, 32), unq_iq=(1, 20), alu_iq=(4, 40), fp_iq=(2, 32),
        numIntPhysRegisters=144, numFpPhysRegisters=128,
        numLdqEntries=32, numStqEntries=32, maxBrCount=20,
        numFetchBufferEntries=32, ftqEntries=40, lsuWidth=2,
        dcache=dict(rowBits=128, nSets=64, nWays=8, nMSHRs=8, nTLBWays=32),
        icache=dict(rowBits=128, nSets=64, nWays=8, fetchBytes=16),
    ),
}

# Shared by every preset (parameters.scala, HellaCache.scala, FPU params).
COMMON = dict(
    blockBytes=64,
    icacheLatency=2,          # ICacheParams.latency
    imulLatency=3,            # BoomCoreParams.imulLatency (megaTapeout: 4)
    intToFpLatency=2,         # BoomCoreParams.intToFpLatency
    fpuLatency=4,             # dfmaLatency; every FPU op is padded to it (fpu.scala:37)
    # Iterative units: these depend on the operands in BOOM. Averages, to calibrate.
    intDivLatency=34,         # rocket MulDiv, unroll 1, early out
    fpDivLatency=30,          # hardfloat DivSqrtRecFN_small
    fpSqrtLatency=30,
    numRasEntries=32,
    itlbEntries=32,           # ICacheParams.nTLBWays default
)

# Branch predictor (withTAGELBPD, config-mixins.scala). BOOM's tables hold one
# entry per fetch-packet slot, so entries = sets * fetchWidth.
TAGE_TABLES = [(128, 7), (128, 7), (256, 8), (256, 8), (128, 9), (128, 9)]  # (sets, tag bits)
TAGE_HIST = (2, 64)          # history lengths 2,4,8,...,64 (geometric in gem5 too)
BIM_SETS = 2048
BTB_SETS, BTB_WAYS = 128, 2


def log2(x):
    return int(math.log2(x))


def fu(ops, count, lat=1, pipelined=True):
    return FUDesc(
        opList=[OpDesc(opClass=o, opLat=lat, pipelined=pipelined) for o in ops],
        count=count,
    )


def issue_queues(p):
    """BOOM's four issue queues as gem5 IQUnits with disjoint FU pools.

    gem5 puts an instruction in the first queue whose pool can execute it, so
    keeping the pools disjoint makes placement follow BOOM's decode
    (decode.scala:480). Differences: gem5 classes CSR accesses as IntAlu
    (ALU queue; BOOM: UNQ), and int->fp and fp->int conversions share
    FloatCvt (FP queue; BOOM sends int->fp to UNQ).
    """
    c = COMMON
    mem_w, mem_n = p["mem_iq"]
    unq_w, unq_n = p["unq_iq"]
    alu_w, alu_n = p["alu_iq"]
    fp_w, fp_n = p["fp_iq"]
    mem = [
        fu(["MemRead", "MemWrite", "FloatMemRead", "FloatMemWrite", "InstPrefetch"], mem_w),
    ]
    unq = [
        fu(["IntMult"], 1, c["imulLatency"]),
        fu(["IntDiv"], 1, c["intDivLatency"], pipelined=False),
        fu(["System"], 1),
    ]
    alu = [fu(["IntAlu"], alu_w)]
    fp = [
        fu(["FloatAdd", "FloatCmp", "FloatCvt", "FloatMult", "FloatMultAcc",
            "FloatMisc", "Bf16Cvt"], fp_w, c["fpuLatency"]),
        FUDesc(opList=[
            OpDesc(opClass="FloatDiv", opLat=c["fpDivLatency"], pipelined=False),
            OpDesc(opClass="FloatSqrt", opLat=c["fpSqrtLatency"], pipelined=False),
        ], count=1),
    ]
    # Leftover classes (vector/SIMD/matrix) are never used by RV64GC code, but
    # gem5 asserts if an instruction fits no queue, so park them in UNQ.
    used = {o.opClass for pool in (mem, unq, alu, fp) for d in pool for o in d.opList}
    unq.append(fu([o for o in OpClass.vals if o not in used and o != "No_OpClass"], 1))
    return [
        IQUnit(numEntries=mem_n, fuPool=FUPool(FUList=mem)),
        IQUnit(numEntries=unq_n, fuPool=FUPool(FUList=unq)),
        IQUnit(numEntries=alu_n, fuPool=FUPool(FUList=alu)),
        IQUnit(numEntries=fp_n, fuPool=FUPool(FUList=fp)),
    ]


def branch_predictor(p):
    fw = p["fetchWidth"]
    tage = LTAGE_TAGE(
        nHistoryTables=len(TAGE_TABLES),
        minHist=TAGE_HIST[0],
        maxHist=TAGE_HIST[1],
        tagTableTagWidths=[0] + [t for _, t in TAGE_TABLES],
        logTagTableSizes=[log2(BIM_SETS * fw)] + [log2(s * fw) for s, _ in TAGE_TABLES],
        logUResetPeriod=11,  # BOOM uBitPeriod = 2048
    )
    # BOOM loop predictor: 16 sets x 4 ways per column.
    loop = LoopPredictor(logSizeLoopPred=log2(16 * 4), logLoopTableAssoc=2)
    return BranchPredictor(
        conditionalBranchPred=LTAGE(tage=tage, loop_predictor=loop),
        btb=SimpleBTB(numEntries=BTB_SETS * BTB_WAYS * fw, associativity=BTB_WAYS),
        ras=ReturnAddrStack(numEntries=COMMON["numRasEntries"]),
        indirectBranchPred=SimpleIndirectPredictor(),
    )


def make_cpu(p):
    cpu = RiscvO3CPU()
    w = p["decodeWidth"]
    issue = sum(q[0] for q in (p["mem_iq"], p["unq_iq"], p["alu_iq"], p["fp_iq"]))

    # Front end. fetchWidth counts instructions in gem5, 16-bit slots in BOOM.
    cpu.fetchWidth = p["fetchWidth"]
    cpu.fetchBufferSize = p["icache"]["fetchBytes"]
    cpu.fetchQueueSize = p["numFetchBufferEntries"]
    # gem5's decoupled front end (FTQ) is new in 25.x and not yet checked on
    # RISC-V; enable with --set cpu.decoupledFrontEnd=True to try it.
    cpu.numFTQEntries = p["ftqEntries"]

    # Core width: BOOM uses one width for decode, rename, dispatch and commit.
    cpu.decodeWidth = cpu.renameWidth = cpu.dispatchWidth = cpu.commitWidth = w
    cpu.squashWidth = w
    cpu.issueWidth = issue
    cpu.wbWidth = issue

    # Stage delays: first guesses from BOOM's pipeline (F0-F3 + fetch buffer,
    # decode, rename 1-2, dispatch, issue, register read, execute). These set
    # the branch mispredict penalty; calibrate them (docs/GEM5.md section 6).
    cpu.fetchToDecodeDelay = 3
    cpu.decodeToRenameDelay = 1
    cpu.renameToIEWDelay = 2
    cpu.issueToExecuteDelay = 2
    cpu.iewToCommitDelay = 1
    cpu.iewToFetchDelay = 2
    # Keep at 1: in gem5 25.1, commitToFetchDelay > 1 deadlocks the O3 CPU or
    # panics ("Dependency graph ... not empty", inst_queue.cc).
    cpu.commitToFetchDelay = 1

    cpu.numROBEntries = p["numRobEntries"]
    cpu.numPhysIntRegs = p["numIntPhysRegisters"]   # both include the 32 arch regs
    cpu.numPhysFloatRegs = p["numFpPhysRegisters"]
    cpu.LQEntries = p["numLdqEntries"]
    cpu.SQEntries = p["numStqEntries"]
    cpu.cacheLoadPorts = p["lsuWidth"]
    cpu.cacheStorePorts = p["lsuWidth"]
    cpu.instQueues = issue_queues(p)
    cpu.branchPred = branch_predictor(p)

    cpu.mmu.itb.size = COMMON["itlbEntries"]
    cpu.mmu.dtb.size = p["dcache"]["nTLBWays"]
    return cpu


def cache_size(c):
    return c["nSets"] * c["nWays"] * COMMON["blockBytes"]


def apply_overrides(objs, sets):
    """--set path=value, e.g. cpu.numROBEntries=64, dcache.mshrs=8."""
    for s in sets:
        path, value = s.split("=", 1)
        names = path.split(".")
        obj = objs[names[0]]
        for n in names[1:-1]:
            obj = getattr(obj, n)
        # Values are Python expressions; gem5 SimObject classes are in scope.
        setattr(obj, names[-1], eval(value, vars(m5.objects)))


# --------------------------------------------------------------------------
# Bare metal: the riscv-tests ELF the RTL runs (benchmarks/common/crt.S and
# test.ld: entry and RAM at 0x80000000, M-mode, exit by writing
# (code << 1) | 1 to `tohost`). gem5 has no HTIF device, and a PIO device over
# the .tohost page would also receive the ELF loader's 64-byte functional
# writes, so instead:
#  * PcCountTracker probes stop the simulation when chosen PCs commit:
#    `setStats` (1st call: ROI start, 2nd: ROI end; its first instruction is
#    the `csrr mcycle` the program measures with) and `tohost_exit`.
#  * Guest memory is a shared-memory backing store. After `tohost_exit`, the
#    caches are written back and `tohost` (exit code) and `bench_stats` (the
#    program's own mcycle/minstret ROI deltas, gem5/bench/rt/patch_syscalls.sh)
#    are read from it.
# --------------------------------------------------------------------------
BARE_RAM_BASE = 0x80000000


def elf_symbols(path):
    """{name: (value, size)} from an ELF64 little-endian symbol table."""
    with open(path, "rb") as f:
        elf = f.read()
    if elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        return {}
    shoff, = struct.unpack_from("<Q", elf, 0x28)
    shentsize, shnum = struct.unpack_from("<HH", elf, 0x3A)
    secs = [struct.unpack_from("<IIQQQQIIQQ", elf, shoff + i * shentsize) for i in range(shnum)]
    syms = {}
    for _, typ, _, _, off, size, link, _, _, entsize in secs:
        if typ != 2:  # SHT_SYMTAB
            continue
        stroff = secs[link][4]
        for o in range(off, off + size, entsize):
            name, _, _, _, value, sz = struct.unpack_from("<IBBHQQ", elf, o)
            end = elf.index(b"\0", stroff + name)
            syms[elf[stroff + name:end].decode()] = (value, sz)
    return syms


def read_guest(shm_name, addr, n):
    """n bytes of guest RAM at addr, from the shared backing store."""
    try:
        import _posixshmem
        fd = _posixshmem.shm_open(shm_name, os.O_RDONLY, 0)
    except ImportError:
        fd = os.open("/dev/shm" + shm_name, os.O_RDONLY)
    try:
        off = addr - BARE_RAM_BASE
        start = off - off % mmap.ALLOCATIONGRANULARITY
        with mmap.mmap(fd, off - start + n, mmap.MAP_SHARED, mmap.PROT_READ, offset=start) as m:
            return bytes(m[off - start:off - start + n])
    finally:
        os.close(fd)


def setup_bare(system, cpu, binary, syms):
    system.workload = RiscvBareMetal(bootloader=binary)   # reset vector = ELF entry
    # With no translation in M-mode, wrong-path loads can reach unmapped
    # addresses (towers: 0). Answer them with an error, as a real bus does,
    # instead of gem5's "Unable to find destination" fatal.
    system.membus.badaddr_responder = BadAddr()
    system.membus.default = system.membus.badaddr_responder.pio
    # Short name: macOS limits shm names to 31 characters.
    system.shared_backstore = f"/gem5boom{os.getpid()}"
    system.auto_unlink_shared_backstore = True
    pcs = {"tohost_exit": syms["tohost_exit"][0]}
    targets = [PcCountPair(pcs["tohost_exit"], 1)]
    if "setStats" in syms:
        pcs["setStats"] = syms["setStats"][0]
        targets += [PcCountPair(pcs["setStats"], 1), PcCountPair(pcs["setStats"], 2)]
    system.pc_tracker = PcCountTrackerManager(targets=targets)
    cpu.probeListener = PcCountTracker(targets=targets, core=cpu, ptmanager=system.pc_tracker)
    return pcs


def run_bare(system, syms, pcs):
    """Simulate to the tohost write. Returns the program's exit code."""
    roi = []                                  # ticks of setStats(1), setStats(0)
    while True:
        ev = m5.simulate()
        if ev.getCause() != "simpoint starting point found":
            print(f"Exiting @ tick {m5.curTick()}: {ev.getCause()} before tohost_exit")
            return None
        pc = system.pc_tracker.getCurrentPcCountPair().get_pc()
        if pc == pcs.get("setStats"):
            roi.append(m5.curTick())
            # Start: reset; end: dump. The first dump in stats.txt is the ROI.
            if len(roi) == 1:
                m5.stats.reset()
            else:
                m5.stats.dump()
        elif pc == pcs["tohost_exit"]:
            break
    if len(roi) < 2:
        m5.stats.dump()                       # no ROI markers: the dump is the whole run
    # The store to tohost is a few instructions after tohost_exit's entry.
    # Run on until it is visible in memory (tohost_exit then spins forever).
    root = Root.getInstance()
    tohost_addr = syms["tohost"][0]
    for _ in range(100):
        m5.drain()
        m5.memWriteback(root)
        tohost, = struct.unpack("<Q", read_guest(system.shared_backstore, tohost_addr, 8))
        if tohost:
            break
        m5.simulate(1000 * 1000)              # 1000 cycles (ticks are ps, clock is 1 GHz)
    code = tohost >> 1 if tohost & 1 else None
    result = {"tohost": tohost, "exit_code": code,
              "roi": "setStats" if len(roi) >= 2 else "whole run"}
    if "bench_stats" in syms:
        cyc, ins = struct.unpack("<QQ", read_guest(system.shared_backstore, syms["bench_stats"][0], 16))
        result["bench_stats"] = {"cycles": cyc, "instret": ins}
    with open(os.path.join(m5.options.outdir, "bare.json"), "w") as f:
        json.dump(result, f, indent=1)
    print(f"Exiting @ tick {m5.curTick()}: tohost = {tohost:#x} (code {code})"
          + (f", bench_stats {result['bench_stats']}" if "bench_stats" in result else ""))
    return code


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("binary")
    ap.add_argument("--config", default="large", choices=sorted(PRESETS))
    ap.add_argument("--mem-latency", type=int, default=20,
                    help="memory latency in cycles; match BENCH_MEM_LATENCY of the RTL run")
    ap.add_argument("--set", action="append", default=[], metavar="PATH=VALUE",
                    help="override a parameter: cpu.X, icache.X, dcache.X, mem.X, membus.X")
    ap.add_argument("--max-insts", type=int, default=0)
    args, rest = ap.parse_known_args()
    p = PRESETS[args.config]
    syms = elf_symbols(args.binary)
    bare = "tohost" in syms and "tohost_exit" in syms

    system = System()
    system.clk_domain = SrcClockDomain(clock="1GHz", voltage_domain=VoltageDomain())
    system.mem_mode = "timing"
    # Bare metal: RAM where riscv-tests' test.ld links the program.
    system.mem_ranges = [AddrRange(BARE_RAM_BASE if bare else 0, size="512MiB")]
    system.cache_line_size = COMMON["blockBytes"]

    system.cpu = cpu = make_cpu(p)
    if args.max_insts:
        cpu.max_insts_any_thread = args.max_insts

    ic, dc = p["icache"], p["dcache"]
    icache = Cache(
        size=f"{cache_size(ic)}B", assoc=ic["nWays"],
        tag_latency=1, data_latency=COMMON["icacheLatency"] - 1, response_latency=1,
        mshrs=1, tgts_per_mshr=1, write_buffers=1,   # BOOM's I$ has one refill in flight
        is_read_only=True, writeback_clean=False,
    )
    dcache = Cache(
        size=f"{cache_size(dc)}B", assoc=dc["nWays"],
        tag_latency=1, data_latency=1, response_latency=1,
        mshrs=dc["nMSHRs"], tgts_per_mshr=4, write_buffers=1,
        writeback_clean=False,
    )
    cpu.addPrivateSplitL1Caches(icache, dcache)

    # The RTL bus is as wide as a cache row; one beat per cycle.
    bus_bytes = dc["rowBits"] // 8
    system.membus = SystemXBar(
        width=bus_bytes, frontend_latency=0, forward_latency=0,
        response_latency=0, snoop_response_latency=0,
    )
    cpu.createInterruptController()
    cpu.connectAllPorts(
        system.membus.cpu_side_ports,
        system.membus.cpu_side_ports,
        system.membus.mem_side_ports,
    )
    system.system_port = system.membus.cpu_side_ports
    system.mem = SimpleMemory(
        range=system.mem_ranges[0],
        latency=f"{args.mem_latency}ns",            # 1 cycle = 1 ns at 1 GHz
        latency_var="0ns",
        bandwidth=f"{bus_bytes}GiB/s",  # ~1 row per cycle at 1 GHz
    )
    system.mem.port = system.membus.mem_side_ports

    apply_overrides(
        {"cpu": cpu, "icache": icache, "dcache": dcache,
         "mem": system.mem, "membus": system.membus, "system": system},
        args.set,
    )

    if bare:
        pcs = setup_bare(system, cpu, args.binary, syms)
    else:
        system.workload = SEWorkload.init_compatible(args.binary)
        cpu.workload = Process(cmd=[args.binary] + rest)
    cpu.createThreads()

    root = Root(full_system=bare, system=system)
    m5.instantiate()
    if bare:
        code = run_bare(system, syms, pcs)
        if code != 0:
            raise SystemExit(1 if code is None else code)
        return
    ev = m5.simulate()
    print(f"Exiting @ tick {m5.curTick()}: {ev.getCause()} (code {ev.getCode()})")
    if ev.getCode() != 0:
        raise SystemExit(ev.getCode())


if __name__ == "__m5_main__":
    main()
