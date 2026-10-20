# Simulation scripts

`cycle-equivalence.py` records and checks deterministic commit, TileLink, Konata
and per-cycle control traces. Run it after `source env.sh`; see
[usage, inputs, workloads and coverage](../docs/CYCLE_EQUIVALENCE.md).

The recorder and workload adapter are BOOM-specific. The Python streaming text
comparison and compression functions have no BOOM dependencies; the optional
Konata cursor reader understands Kanata's cycle records.

Run comparator regressions with:

```sh
python3 -m unittest discover -s scripts -p 'test_cycle_equivalence.py'
```
