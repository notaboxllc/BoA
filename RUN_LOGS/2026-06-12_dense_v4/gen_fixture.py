#!/usr/bin/env python3
"""Generate dense-fixture variants from boa10-64Seg-dyn-dense by overriding params.

Usage: gen_fixture.py <out_path> key=val [key=val ...]
Each key is a param label; value is the numeric value (isActive forced true).
Lines for that label are rewritten in place (preserving trailing comment).
"""
import sys, re

BASE = "ParameterFiles/boa10-64Seg-dyn-dense"

def main():
    out = sys.argv[1]
    overrides = {}
    for a in sys.argv[2:]:
        k, v = a.split("=", 1)
        overrides[k] = v
    with open(BASE) as f:
        lines = f.readlines()
    seen = set()
    res = []
    for ln in lines:
        m = re.match(r"^([A-Za-z0-9_]+):(true|false):([^;]*);(.*)$", ln)
        if m and m.group(1) in overrides:
            k = m.group(1)
            comment = m.group(4)
            res.append(f"{k}:true:{overrides[k]};{comment}\n")
            seen.add(k)
        else:
            res.append(ln)
    # append any overrides not present in base (e.g. crossLinkGrabDist)
    extra = [k for k in overrides if k not in seen]
    if extra:
        res.append("// --- v4 generated overrides ---\n")
        for k in extra:
            res.append(f"{k}:true:{overrides[k]};\n")
    with open(out, "w") as f:
        f.writelines(res)
    print(f"wrote {out}  overrides={overrides}  appended={extra}")

if __name__ == "__main__":
    main()
