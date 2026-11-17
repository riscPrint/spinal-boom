"""Wrapper contract tests. All build commands are fake and run in temp trees."""
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class WrapperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='boom deps ')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for directory in ('scripts', 'act4', 'formal'):
            shutil.copytree(ROOT / directory, self.root / directory)
        shutil.copy(ROOT / 'env.sh', self.root / 'env.sh')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.source = self.root / 'shared source'
        self.source.mkdir()
        (self.source / 'sentinel').write_text('unchanged')
        self.log = self.root / 'calls'
        self.env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                        RVW=str(self.bin / 'rvw'), SOURCE=str(self.source),
                        CALLS=str(self.log), JAVA_HOME='/nonexistent')
        for key in ('ACT4_ELFS', 'RVW_SAIL', 'RISCV_TESTS'):
            self.env.pop(key, None)
        self.tool('rvw', '''printf '%s\\n' "$*" >> "$CALLS"
if [ "$1" = doctor ]; then
  if [ "${FAIL_RESOLVE:-}" = 1 ]; then exit 7; fi
  if [ "$3" = sail ]; then echo "${RVW_SAIL:-$SOURCE/sail_riscv_sim}"; else echo "$SOURCE"; fi
fi
''')
        self.tool('uname', 'echo Linux\n')
        self.tool('sail_riscv_sim', 'exit 0\n')
        self.tool('make', '''printf 'make %s\\n' "$*" >> "$CALLS"
[ "$2" != "$SOURCE" ] || exit 9
printf built > "$2/sentinel"
for arg in "$@"; do
  case "$arg" in WORKDIR=*)
    work="${arg#WORKDIR=}"
    mkdir -p "$work/boom-rv64gc/elfs"
    printf elf > "$work/boom-rv64gc/elfs/test.elf";;
  esac
done
''')

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/bash\nset -eu\n' + body)
        path.chmod(0o755)

    def run_script(self, name, *args, check=True):
        return subprocess.run(['bash', str(self.root / name), *args], cwd=self.root,
                              env=self.env, text=True, capture_output=True, check=check)

    def test_isa_output_and_options(self):
        self.env['RISCV_TESTS'] = str(self.root / 'custom isa')
        self.run_script('scripts/build-riscv-tests.sh', '--envs', 'p')
        self.assertEqual(self.log.read_text().strip(),
                         f'regress riscv-tests-build --out {self.root}/target/verification/riscv-tests --elf-dir {self.env["RISCV_TESTS"]} --xlen 64 --envs p')

    def test_isa_default_output(self):
        self.run_script('scripts/build-riscv-tests.sh')
        self.assertEqual(self.log.read_text().strip(),
                         f'regress riscv-tests-build --out {self.root}/target/verification/riscv-tests --elf-dir {self.root}/target/verification/riscv-tests/isa --xlen 64')

    def test_sail_override_requires_resolver(self):
        self.env['RVW_SAIL'] = str(self.source / 'sail_riscv_sim')
        self.run_script('act4/build.sh')
        self.assertIn('doctor --resolve sail', self.log.read_text())

    def test_arbitrary_sail_executable_name(self):
        chosen = self.bin / 'chosen-sail'
        self.tool('chosen-sail', 'printf selected > "$CALLS.selected"\n')
        self.env['RVW_SAIL'] = str(chosen)
        self.tool('make', 'sail_riscv_sim --version\n')
        (self.bin / 'sail_riscv_sim').unlink()
        self.run_script('act4/build.sh')
        self.assertEqual(Path(str(self.log) + '.selected').read_text(), 'selected')

    def test_act4_private_source_and_selection(self):
        self.run_script('act4/build.sh', 'EXTENSIONS=I,M')
        self.assertEqual((self.source / 'sentinel').read_text(), 'unchanged')
        calls = self.log.read_text()
        self.assertIn('doctor --resolve act4', calls)
        self.assertNotIn('--acquire', calls)
        self.assertIn('EXTENSIONS=I,M', calls)
        self.assertIn(f'CONFIG_FILES={self.root}/act4/boom-rv64gc/test_config.yaml', calls)
        self.assertTrue((self.root / 'target/verification/act4/work/boom-rv64gc/elfs/test.elf').is_file())
        self.assertEqual(list((self.root / 'target/verification/act4').glob('source.*')), [])

    def test_explicit_source_and_output_override(self):
        self.env['ACT4_ELFS'] = str(self.root / 'custom elfs')
        self.env['FAIL_RESOLVE'] = '1'
        self.run_script('act4/build.sh', str(self.source), 'EXTENSIONS=I')
        self.assertTrue((Path(self.env['ACT4_ELFS']) / 'test.elf').is_file())
        self.assertNotIn('doctor', self.log.read_text())
        self.assertEqual((self.source / 'sentinel').read_text(), 'unchanged')

    def test_resolver_failure_stops_before_make(self):
        self.env['FAIL_RESOLVE'] = '1'
        result = self.run_script('act4/build.sh', check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('make ', self.log.read_text())

    def test_formal_private_copy_keeps_checks(self):
        (self.source / 'checks').mkdir()
        (self.source / 'checks/genchecks.py').write_text(
            'from pathlib import Path\nPath("checks").mkdir()\n')
        (self.root / 'scripts/run-main.sh').write_text('#!/bin/bash\nexit 0\n')
        self.run_script('formal/run.sh', 'cover_ch0')
        private, = (self.root / 'target/verification/formal').glob('run.*')
        checks = private / 'source/cores/boom/checks.cfg'
        self.assertEqual(checks.read_bytes(), (ROOT / 'formal/checks.cfg').read_bytes())
        self.assertFalse((self.source / 'cores').exists())
        self.assertIn('cover_ch0', self.log.read_text())

    def test_monitor_generator_uses_private_source(self):
        monitor = self.source / 'monitor'
        monitor.mkdir()
        (monitor / 'generate.py').write_text(
            'from pathlib import Path\n'
            'Path("generated").write_text("private")\n'
            'print("$signed(a) / $signed(b)")\n')
        self.run_script('formal/gen_monitors.sh')
        self.assertFalse((monitor / 'generated').exists())
        for n in (1, 2, 3):
            self.assertIn('$unsigned($signed(a) / $signed(b))',
                          (self.root / f'formal/rvfimon_c{n}.v').read_text())
        self.assertEqual(list((self.root / 'target/verification/formal').glob('monitors.*')), [])

    def test_env_resolves_without_acquire(self):
        result = self.run_script('env.sh')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.log.read_text().strip(), 'doctor --resolve sail')


class CycleInputTest(unittest.TestCase):
    def test_missing_default_and_explicit_elf(self):
        spec = importlib.util.spec_from_file_location('eq', ROOT / 'scripts/cycle-equivalence.py')
        eq = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(eq)
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True):
            eq.ROOT = Path(tmp)
            with self.assertRaisesRegex(ValueError, 'missing ISA directory'):
                eq.programs('full', re.compile('.*'))
            elf = Path(tmp) / 'one.elf'
            elf.write_bytes(b'\x7fELF')
            os.environ['EQ_ELF'] = str(elf)
            self.assertEqual(len(eq.programs('full', re.compile('.*'))), 1)


if __name__ == '__main__':
    unittest.main()
