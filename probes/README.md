# probes/

Standalone TornadoVM 4.0.1-dev probes — feasibility gates for proposed
GPU-residency designs. Each probe is a self-contained `main()` class that
exercises one specific TornadoVM mechanic, runs on aorus with the same
build/run incantations as the main sim, and produces a verdict that is
written up in the corresponding design doc.

## Probes

| Probe | Question | Verdict (date, doc) |
|---|---|---|
| `ScatterResidentProbe.java` | Can a FIRST_EXECUTION + UNDER_DEMAND FloatArray be (a) uploaded once, (b) kernel-written each execute, (c) also scatter-written from EVERY_EXECUTION host deltas, and (d) persist across executes, all in one TaskGraph? | **feasible** (2026-06-07; see `RESIDENT_POSE_DELTA_SCATTER.md` § Scatter-into-resident probe) |
| `ScatterResidentProbeSwap.java` | Same shape but with task declaration order swapped — used to confirm that within an execute, declaration order is honored. | (paired with above) |

## Build + run

```
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:probes" \
      probes/ScatterResidentProbe.java

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:probes" \
     ScatterResidentProbe
```
