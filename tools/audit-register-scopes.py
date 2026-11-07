#!/usr/bin/env python3
"""Lex Scala and report register constructors in hardware conditional scopes.

Comments and strings are removed without changing offsets. Balanced delimiters
identify when/elsewhen/otherwise bodies, including the dotted Chisel syntax.
Conditions are evaluated in the parent scope, not in their own body.
This is a lexical inventory, not an enable inference. A declaration and
connection in one Chisel scope can lower to a free-running register.
Function calls that create registers need review.
"""
import argparse, json, re
from pathlib import Path

def sites(path):
    source = path.read_text()
    # Scala block comments can nest.
    clean = list(source)
    i = 0
    while i < len(source):
        start = i
        if source.startswith('//', i):
            i = source.find('\n', i)
            if i < 0: i = len(source)
        elif source.startswith('/*', i):
            i += 2; depth = 1
            while i < len(source) and depth:
                if source.startswith('/*', i): depth += 1; i += 2
                elif source.startswith('*/', i): depth -= 1; i += 2
                else: i += 1
        elif source[i] == "'" and re.match(r"'(?:\\.|[^'\n])'", source[i:]):
            i += len(re.match(r"'(?:\\.|[^'\n])'", source[i:])[0])
        elif source[i] == '"':
            quote = '"""' if source.startswith('"""', i) else '"'
            i += len(quote)
            while i < len(source):
                if source.startswith(quote, i): i += len(quote); break
                if source[i] == '\\': i += 2
                else: i += 1
        else:
            i += 1; continue
        for j in range(start, i):
            if clean[j] != '\n': clean[j] = ' '
    clean = ''.join(clean)
    tokens = list(re.finditer(r'[A-Za-z_$][\w$]*|[^\s]', clean))
    pairs = {}; stack = []
    for k, t in enumerate(tokens):
        if t[0] in ('(', '[', '{'): stack.append(k)
        elif t[0] in (')', ']', '}'):
            if not stack: raise ValueError(f'{path}: unmatched delimiter')
            a = stack.pop()
            if tokens[a][0] + t[0] not in ('()', '[]', '{}'):
                raise ValueError(f'{path}: mismatched delimiters at {t.start()}')
            pairs[a] = k
    if stack: raise ValueError(f'{path}: unclosed delimiter')
    scopes = []
    for k, t in enumerate(tokens):
        if t[0] not in ('when', 'elsewhen', 'otherwise', 'is'): continue
        if k >= 2 and tokens[k-2][0] == 'Option' and tokens[k-1][0] == '.': continue
        n = k + 1
        if n < len(tokens) and tokens[n][0] == '(':
            n = pairs[n] + 1
        if n < len(tokens) and tokens[n][0] != '{':
            raise ValueError(f'{path}: unsupported conditional body at {t.start()}')
        if n < len(tokens) and tokens[n][0] == '{':
            scopes.append((tokens[n].start(), tokens[pairs[n]].start(), source[t.start():tokens[n].start()].strip()))
    # Chisel elsewhen takes its predicate by name. Its registers also run
    # under the false paths of all earlier branches in the chain.
    for k, t in enumerate(tokens):
        if t[0] not in ('when', 'elsewhen'): continue
        if k >= 2 and tokens[k-2][0] == 'Option' and tokens[k-1][0] == '.': continue
        n = k + 1
        if n >= len(tokens) or tokens[n][0] != '(': continue
        body = pairs[n] + 1
        if body >= len(tokens) or tokens[body][0] != '{': continue
        end = pairs[body] + 1
        first = end
        while end < len(tokens):
            branch = end + (tokens[end][0] == '.')
            if branch >= len(tokens) or tokens[branch][0] not in ('elsewhen', 'otherwise'): break
            b = branch + 1
            if tokens[b][0] == '(': b = pairs[b] + 1
            if tokens[b][0] != '{': break
            end = pairs[b] + 1
        if end > first:
            scopes.append((tokens[first].start()-1, tokens[end-1].end(),
                           'false path of ' + source[t.start():tokens[body].start()].strip()))
    result = []
    for k, t in enumerate(tokens[:-1]):
        if t[0] not in ('Reg', 'RegInit', 'RegNext', 'RegEnable', 'RegNextWhen', 'ScopedRegNext', 'ShiftRegister', 'Pipe', 'holdUnless', 'Counter'): continue
        if tokens[k+1][0] not in ('(', '['): continue
        active = [s[2] for s in scopes if s[0] < t.start() < s[1]]
        if active:
            line = source.count('\n', 0, t.start()) + 1
            result.append(dict(file=str(path), line=line, offset=t.start(), kind=t[0], scopes=active,
                               text=source.splitlines()[line-1].strip()))
    return result

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('roots', nargs='+', type=Path)
    args = p.parse_args()
    for root in args.roots:
        if not root.exists(): p.error(f'missing input: {root}')
        for path in sorted(root.rglob('*.scala')) if root.is_dir() else [root]:
            for site in sites(path): print(json.dumps(site))
