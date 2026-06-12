# Source me. Defines GPU_RUN / CPU_RUN run-command prefixes for the dense v4 benchmark.
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
export TORNADOVM_HOME TDIR

# GPU: full tornado argfile + maxbytecodesize. $XMX defaults 8G.
gpu_run() {   # args after fn = BoxOfActin args
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx${XMX:-8G} \
       -Dtornado.tvm.maxbytecodesize=16384 $TPROF \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu "$@"
}
# CPU: tornado-api on cp only for class loading; no argfile, no -gpu.
cpu_run() {
  java --enable-preview -Xmx${XMX:-8G} \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r "$@"
}
