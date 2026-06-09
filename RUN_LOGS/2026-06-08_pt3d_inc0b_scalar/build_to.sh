#!/usr/bin/env bash
# Compile current source tree to a target dir (preserves uncommitted edits in working tree).
set -eu
DEST="$1"
mkdir -p "$DEST"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
cd /home/jba/Code/BoA
javac -g --release 21 --enable-preview -encoding ISO-8859-1 -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      -d "$DEST" \
      boxOfActin/*.java *.java ec/util/*.java edu/cornell/lassp/houle/RngPack/*.java infoCCD/*.java
