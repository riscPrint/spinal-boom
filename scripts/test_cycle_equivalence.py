"""Comparator regressions: cursor handling, EOF, exact beats and compressed inputs."""
import gzip
import importlib.util
from pathlib import Path
import tempfile
import shutil
import subprocess
import sys
import unittest

spec = importlib.util.spec_from_file_location("equivalence", Path(__file__).with_name("cycle-equivalence.py"))
eq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eq)


class ComparisonTest(unittest.TestCase):
    def test_comparison_error_stops_owned_simulator(self):
        child = None
        with self.assertRaisesRegex(RuntimeError, "comparison failed"):
            # Keep producing output: waiting before terminating can deadlock on the pipe.
            with eq.simulator([sys.executable, "-u", "-c",
                               "print('ready', flush=True)\nwhile True: print('x' * 4096, flush=True)"]) as child:
                self.assertEqual(child.stdout.readline(), "ready\n")
                raise RuntimeError("comparison failed")
        self.assertIsNotNone(child.returncode)

    def compare(self, left, right, konata=False, compressed=False):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            if compressed:
                a = a.with_suffix(".gz")
                with gzip.open(a, "wt") as f:
                    f.write(left)
            else:
                a.write_text(left)
            b.write_text(right)
            return eq.first_difference(a, b, konata)

    def test_identical_and_empty(self):
        self.assertIsNone(self.compare("", ""))
        self.assertIsNone(self.compare("# header\n4\tA\tdata=ff\n", "# header\n4\tA\tdata=ff\n", compressed=True))

    def test_one_cycle_shift(self):
        message = self.compare("# header\n12\tA\tdata=ff\n", "# header\n13\tA\tdata=ff\n")
        self.assertIn("line 2, cycle golden=12 actual=13", message)

    def test_beat_payload_and_eof(self):
        self.assertIn("data=fe", self.compare("2\tD\tdata=ff\n", "2\tD\tdata=fe\n"))
        self.assertIn("actual: <EOF>", self.compare("2\tE\tsink=1\n", ""))
        self.assertIn("golden: <EOF>", self.compare("", "2\tE\tsink=1\n"))

    def test_konata_relative_and_absolute_cycles(self):
        prefix = "Kanata\t0004\nC=\t7\nC\t5\n"
        self.assertIn("cycle golden=12 actual=12", self.compare(prefix + "S\t1\t0\tEx\n", prefix + "S\t1\t0\tWb\n", True))
        self.assertIn("cycle golden=12 actual=13", self.compare(prefix, prefix.replace("C\t5", "C\t6"), True))

    @unittest.skipUnless(shutil.which("zstd"), "zstd unavailable")
    def test_zstd_early_mismatch_preserves_diagnostic(self):
        # Larger than the pipe buffer: an early return must not turn into SIGPIPE.
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            a.write_text("7\tD\tdata=ff\n" + "8\tD\tdata=00\n" * 200000)
            b.write_text("7\tD\tdata=fe\n")
            subprocess.run(["zstd", "-q", str(a)], check=True)
            self.assertIn("cycle golden=7 actual=7", eq.first_difference(a.with_suffix(".zst"), b))


if __name__ == "__main__":
    unittest.main()
