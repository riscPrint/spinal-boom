#!/usr/bin/env python3
"""Check scope extraction on the syntax that changes register enables."""
import runpy
import tempfile
import unittest
from pathlib import Path
sites = runpy.run_path(str(Path(__file__).with_name('audit-register-scopes.py')))['sites']

class ScopeTests(unittest.TestCase):
    def scan(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'case.scala'; path.write_text(text)
            return sites(path)

    def test_comments_and_strings(self):
        self.assertEqual(self.scan('''// when(c) { RegNext(x) }
          /* nested /* when(e) { */ Reg(x) */
          val text = "when(c) { RegInit(x) }"
          val plain = RegNext(x)'''), [])

    def test_scala_option_is_not_hardware_condition(self):
        self.assertEqual(self.scan('val r = Option.when(feature) { RegNext(x) }'), [])

    def test_nested_constructor_and_condition(self):
        rows=self.scan('when(a) { when(RegNext(b)) { x := RegNext(RegNext(d)) } }')
        self.assertEqual([r['kind'] for r in rows], ['RegNext']*3)
        self.assertEqual([len(r['scopes']) for r in rows], [1,2,2])

    def test_priority_predicate_false_paths(self):
        rows=self.scan('when(a) { } .elsewhen(RegNext(b)) { x := RegEnable(d,e) } .otherwise { val r=RegInit(0) }')
        self.assertEqual([r['kind'] for r in rows], ['RegNext','RegEnable','RegInit'])
        self.assertTrue(any('false path of when(a)' == c for c in rows[0]['scopes']))
        self.assertEqual(len(rows[2]['scopes']),3)

    def test_elsewhen_in_parent_when(self):
        rows=self.scan('when(a) { when(b) { } .elsewhen(RegNext(c)) { val r=Reg(UInt()) ; r := d } }')
        self.assertEqual([r['kind'] for r in rows], ['RegNext','Reg'])
        self.assertEqual(len(rows[0]['scopes']),2)

if __name__ == '__main__': unittest.main()
