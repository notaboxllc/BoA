#!/bin/bash
# CPU run helper. args passed through.
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
java -Xmx4G --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." BoxOfActin "$@"
