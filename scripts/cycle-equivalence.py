#!/usr/bin/env python3
"""Record/check cycle traces. Run from any directory; see docs/CYCLE_EQUIVALENCE.md."""
import concurrent.futures
import contextlib
import difflib
import fcntl
import gzip
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
FILES = ("commit.tsv", "tilelink.tsv", "control.tsv", "konata.log", "result.tsv")
PRESETS = ("small", "medium", "large", "mega", "giga")
# Same suites/environments/exclusions as RiscvTestsSpec at the reference revision.
SUITES = ("rv64ui", "rv64um", "rv64ua", "rv64uf", "rv64ud", "rv64uc", "rv64mi", "rv64si", "rv64uzba", "rv64uzbb", "rv64uzbs")
EXCLUDED = {"rv64ui-p-ma_data", "rv64ui-v-ma_data", "rv64mi-p-instret_overflow"}
QUICK = {
    "rv64ui": ("beq", "bne", "jal", "jalr", "ld", "sd", "ld_st", "st_ld", "fence_i"),
    "rv64um": ("mul", "div"), "rv64ua": ("amoadd_d", "lrsc"),
    "rv64uf": ("fadd", "fdiv"), "rv64ud": ("fmadd", "ldst"),
    "rv64uc": ("rvc",), "rv64mi": ("csr", "illegal", "scall"),
    "rv64si": ("dirty", "wfi"), "rv64uzba": ("sh1add",),
    "rv64uzbb": ("ror",), "rv64uzbs": ("bset",),
}
BENCHES = ("median", "qsort", "rsort", "towers", "vvadd", "memcpy", "multiply", "dhrystone", "spmv")
QUICK_BENCHES = {"median", "qsort", "multiply"}
SCHEMA = 1


@contextlib.contextmanager
def simulator(command):
    # If comparison/compression raises, do not wait on a child whose output pipe
    # can fill while unwinding. Each preset owns its entire compiler/simulator group.
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, start_new_session=True) as proc:
        try:
            yield proc
        finally:
            if proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait()


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def programs(which, expression):
    isa = Path(os.environ.get("RISCV_TESTS", str(ROOT / "../ref/riscv-tests/build/isa"))).resolve()
    vm = ROOT / "tests/vm/build"
    bench = Path(os.environ.get("BENCH_DIR", str(ROOT / "gem5/bench/build/bare"))).resolve()
    selected = []
    if os.environ.get("EQ_ELF"):
        selected = [("elf--" + Path(os.environ["EQ_ELF"]).name, Path(os.environ["EQ_ELF"]).resolve(), 200000)]
    else:
        names = sorted(p.name for p in isa.iterdir() if p.is_file() and not p.name.endswith(".dump")
                       and any(p.name.startswith(f"{s}-{e}-") for s in SUITES for e in ("p", "v"))
                       and p.name not in EXCLUDED)
        if not names:
            raise ValueError(f"no ISA tests in {isa}")
        if which == "quick":
            expected = {f"{s}-{e}-{t}" for s, tests in QUICK.items() for e in ("p", "v") for t in tests
                        if not (s in ("rv64mi", "rv64si") and e == "v")}
            missing = expected - set(names)
            if missing:
                raise ValueError(f"missing quick ISA tests: {sorted(missing)}")
            names = [n for n in names if n in expected]
        selected += [("isa--" + n, isa / n, 200000) for n in names]
        vm_names = sorted(p.stem for p in (ROOT / "tests/vm").glob("*.S"))
        if not vm_names:
            raise ValueError("no VM test sources")
        selected += [("vm--" + n, vm / n, 200000) for n in vm_names]
        bench_names = sorted(p.stem for p in bench.glob("*.riscv")) if which == "full" else sorted(QUICK_BENCHES)
        if which == "full" and not set(BENCHES).issubset(bench_names):
            raise ValueError(f"missing benchmark binaries in {bench}: {sorted(set(BENCHES) - set(bench_names))}")
        selected += [("bench--" + n, bench / (n + ".riscv"), 50000000) for n in bench_names]
    selected = [(n, p, c) for n, p, c in selected if expression.search(n)]
    if not selected:
        raise ValueError("test filter selected zero cases")
    for n, p, _ in selected:
        with p.open("rb") as f:
            if f.read(4) != b"\x7fELF":
                raise ValueError(f"{n}: not an ELF: {p}")
    return selected


@contextlib.contextmanager
def lines(path):
    if path.suffix == ".zst":
        with subprocess.Popen(["zstd", "-qdc", str(path)], stdout=subprocess.PIPE, text=True) as p:
            try:
                yield p.stdout
            finally:
                # Drain after an early difference so zstd can validate the complete frame
                # without reporting a broken pipe instead of the useful cycle mismatch.
                while p.stdout.read(1024 * 1024):
                    pass
                p.stdout.close()
                code = p.wait()
                if code != 0:
                    raise RuntimeError(f"zstd could not read {path}: exit {code}")
    elif path.suffix == ".gz":
        with gzip.open(path, "rt") as f:
            yield f
    else:
        with path.open() as f:
            yield f


def first_difference(expected, actual, konata=False):
    """Streaming comparison, including EOF. Konata's C cursor is relative, C= absolute."""
    cycles = [0, 0]
    with lines(expected) as left, lines(actual) as right:
        for number, pair in enumerate(itertools.zip_longest(left, right), 1):
            for i, line in enumerate(pair):
                if line is None or line.startswith("#"):
                    continue
                fields = line.rstrip("\n").split("\t")
                if konata:
                    if fields[0] == "C=":
                        cycles[i] = int(fields[1])
                    elif fields[0] == "C":
                        cycles[i] += int(fields[1])
                elif fields[0].isdigit():
                    cycles[i] = int(fields[0])
            if pair[0] != pair[1]:
                return (f"line {number}, cycle golden={cycles[0]} actual={cycles[1]}\n"
                        f"  golden: {pair[0].rstrip() if pair[0] is not None else '<EOF>'}\n"
                        f"  actual: {pair[1].rstrip() if pair[1] is not None else '<EOF>'}")
    return None


def compress(path):
    if shutil.which("zstd"):
        subprocess.run(["zstd", "-q", "-T1", "-3", "--rm", str(path)], check=True)
        return path.with_name(path.name + ".zst")
    dest = path.with_name(path.name + ".gz")
    with path.open("rb") as src, dest.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as dst:
            shutil.copyfileobj(src, dst)
    path.unlink()
    return dest


def packed(directory, name):
    found = [directory / (name + suffix) for suffix in (".zst", ".gz") if (directory / (name + suffix)).is_file()]
    if len(found) != 1:
        raise ValueError(f"expected one compressed {directory / name}, found {found}")
    return found[0]


def main():
    started = time.monotonic()
    os.chdir(ROOT)
    mode = os.environ.get("EQ_MODE", "check")
    which = os.environ.get("EQ_SET", "quick")
    presets = os.environ.get("EQ_PRESETS", ",".join(PRESETS)).split(",")
    workers = int(os.environ.get("EQ_PARALLELISM", "2"))
    latency = os.environ.get("EQ_MEM_LATENCY", "suite")
    base_seed = int(os.environ.get("EQ_SEED", "1"))
    assert mode in ("record", "check"), "EQ_MODE must be record or check"
    assert which in ("quick", "full"), "EQ_SET must be quick or full"
    assert workers > 0, "EQ_PARALLELISM must be positive"
    assert presets and len(set(presets)) == len(presets) and all(p in PRESETS for p in presets), "invalid presets"
    assert latency in ("suite", "random") or int(latency) >= 0, "EQ_MEM_LATENCY must be suite, random or a nonnegative cycle count"
    if latency not in ("suite", "random"):
        latency = str(int(latency))
    memory = latency if latency in ("suite", "random") else f"fixed-{latency}"
    expression = re.compile(os.environ.get("EQ_FILTER", "."))
    cases = programs(which, expression)
    root = Path(os.environ.get("EQ_GOLDEN", str(ROOT / "golden"))).resolve() / which / memory
    work = ROOT / "target/equivalence"
    work.mkdir(parents=True, exist_ok=True)
    # A compile/cache and baseline must have a single owner. Preset JVMs below are isolated.
    lock = (work / "lock").open("w")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    run = work / (time.strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}")
    run.mkdir()
    def contract(n, path, limit):
        # Stable across processes, presets, test ordering, and filtered reruns.
        seed = (base_seed + int(hashlib.sha256(n.encode()).hexdigest()[:8], 16)) & 0x7fffffff
        return {"schema": SCHEMA, "elf_sha256": sha256(path), "seed": seed,
                "max_cycles": int(os.environ.get("EQ_MAX_CYCLES", limit)),
                "memory_latency": ("20" if n.startswith("bench--") else "random") if latency == "suite" else latency}
    contracts = {n: contract(n, p, limit) for n, p, limit in cases}
    if mode == "record":
        if root.exists():
            raise ValueError(f"refusing to overwrite golden set {root}; choose a fresh EQ_GOLDEN")
        destination = root.parent / (f".{root.name}-record-{os.getpid()}")
        destination.mkdir(parents=True)
    else:
        manifest = json.loads((root / "manifest.json").read_text())
        assert manifest["schema"] == SCHEMA, "golden schema mismatch"
        expected_cases = {n for n in manifest["cases"] if expression.search(n)}
        if set(contracts) != expected_cases:
            raise ValueError(f"case selection differs from golden manifest: missing={sorted(expected_cases - set(contracts))}, added={sorted(set(contracts) - expected_cases)}")
        for preset in presets:
            for n in contracts:
                expected = json.loads((root / preset / n / "case.json").read_text())
                if expected != contracts[n]:
                    raise ValueError(f"{preset}/{n}: input contract differs\n" + "\n".join(difflib.ndiff(
                        json.dumps(expected, sort_keys=True).splitlines(), json.dumps(contracts[n], sort_keys=True).splitlines())))
                for name in FILES:
                    packed(root / preset / n, name)
        destination = run / "actual"
        destination.mkdir()
    print(f"EQUIV {mode} {which} {memory}: {len(cases)} cases x {len(presets)} presets, parallelism={workers}", flush=True)
    # Compile Scala once, then use separate JVMs with independent elaboration/simulation state.
    build_log = run / "compile.log"
    with build_log.open("w") as log:
        built = subprocess.run(["sbt", "Test/compile", "export Test/fullClasspath"], stdout=log, stderr=subprocess.STDOUT)
    if built.returncode:
        raise RuntimeError(f"Scala compilation failed: {build_log}")
    classpaths = [l.strip() for l in build_log.read_text().splitlines() if l.startswith("/") and "test-classes" in l]
    if not classpaths:
        raise RuntimeError(f"no exported classpath in {build_log}")
    classpath = classpaths[-1]
    cpus = int(os.environ.get("EQ_CPUS_PER_WORKER", str(max(1, (os.cpu_count() or 2) // workers))))

    def worker(preset):
        begin = time.monotonic()
        output = destination / preset
        output.mkdir()
        jobs = run / (preset + ".tsv")
        jobs.write_text("".join(f"{n}\t{p}\t{contracts[n]['seed']}\t{contracts[n]['max_cycles']}\t{contracts[n]['memory_latency']}\n" for n, p, _ in cases))
        failures = []
        completed = set()
        log_path = run / (preset + ".log")
        command = ["java", "-Xmx8G", f"-XX:ActiveProcessorCount={cpus}", "-cp", classpath,
                   "boom.sim.CycleEquivalence", preset, str(jobs), str(output)]
        with log_path.open("w") as log, simulator(command) as proc:
            for line in proc.stdout:
                log.write(line)
                log.flush()
                if line.startswith("EQUIV_DONE\t"):
                    n = line.rstrip().split("\t")[1]
                    if n not in contracts or n in completed:
                        raise ValueError(f"unexpected case completion: {n}")
                    completed.add(n)
                    directory = output / n
                    (directory / "case.json").write_text(json.dumps(contracts[n], indent=2, sort_keys=True) + "\n")
                    for name in FILES:
                        actual = compress(directory / name)
                        if mode == "check":
                            mismatch = first_difference(packed(root / preset / n, name), actual, name == "konata.log")
                            if mismatch:
                                message = f"DIFF {preset}/{n}/{name}: {mismatch}"
                                failures.append(message)
                                print(message, flush=True)
                    print(f"EQUIV {preset}/{n} {'DIFF' if any(f.startswith('DIFF ' + preset + '/' + n + '/') for f in failures) else 'OK'} ({len(completed)}/{len(cases)})", flush=True)
            code = proc.wait()
        if code or completed != set(contracts):
            failures.append(f"{preset}: simulator exit {code}, {len(completed)}/{len(cases)} complete; see {log_path}")
        return {"preset": preset, "seconds": round(time.monotonic() - begin, 3), "completed": len(completed), "failures": failures}

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(worker, presets))
    failures = [f for r in results for f in r["failures"]]
    report = {"mode": mode, "set": which, "memory": memory, "cases_per_preset": len(cases),
              "presets": results, "parallelism": workers, "cpus_per_worker": cpus,
              "seconds": round(time.monotonic() - started, 3), "passed": not failures}
    (run / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    if not failures and mode == "record":
        manifest = {"schema": SCHEMA, "source_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                    "source_diff_sha256": hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD"])).hexdigest(),
                    "harness_sha256": {str(p.relative_to(ROOT)): sha256(p) for p in sorted(
                        list((ROOT / "src/test/scala/boom/sim").glob("*.scala")) +
                        [ROOT / "scripts/cycle-equivalence.py", ROOT / "src/main/scala/boom/system/BoomSimTop.scala"])},
                    "set": which, "memory": memory, "presets": presets, "cases": list(contracts), "record_report": report,
                    "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip(),
                    "verilator": subprocess.check_output(["verilator", "--version"], text=True).strip()}
        (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        destination.rename(root)
    for message in failures:
        if not message.startswith("DIFF "):
            print(message, file=sys.stderr)
    print(f"EQUIV {'PASS' if not failures else 'FAIL'}: {report['seconds']:.1f}s; report {run / 'report.json'}", flush=True)
    return int(bool(failures))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, AssertionError, OSError, RuntimeError, subprocess.SubprocessError) as e:
        print(f"EQUIV ERROR: {e}", file=sys.stderr)
        sys.exit(1)
