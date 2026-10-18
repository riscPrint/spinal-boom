#!/usr/bin/env python3
"""vcdq: print value changes of VCD signals matching a regex.

usage: vcdq.py wave.vcd REGEX [--from T] [--to T] [--list]
"""
import re, sys, argparse

ap = argparse.ArgumentParser()
ap.add_argument("vcd"); ap.add_argument("regex")
ap.add_argument("--from", dest="t0", type=int, default=0)
ap.add_argument("--to", dest="t1", type=int, default=1 << 62)
ap.add_argument("--list", action="store_true", help="only list matching signals")
a = ap.parse_args()
rx = re.compile(a.regex)

scope, ids = [], {}
f = open(a.vcd)
for line in f:
    t = line.split()
    if not t: continue
    if t[0] == "$scope": scope.append(t[2])
    elif t[0] == "$upscope": scope.pop()
    elif t[0] == "$var":
        name = ".".join(scope[1:] + [t[4]])
        if rx.search(name): ids.setdefault(t[3], []).append((name, int(t[2])))
    elif t[0] == "$enddefinitions": break
if a.list:
    for v in ids.values():
        for n, w in v: print(n, w)
    sys.exit()
now = 0
for line in f:
    c = line[0]
    if c == "#":
        now = int(line[1:]); continue
    if now < a.t0 or now > a.t1: continue
    if c in "01xz":
        val, code = c, line[1:].strip()
    elif c == "b":
        val, code = line[1:].split()
        val = hex(int(val, 2)) if set(val) <= {"0", "1"} else val
    else: continue
    for n, w in ids.get(code, []):
        print(f"{now:8d} {n} = {val}")
