#!/usr/bin/env python3
"""
sim_server.py — static file server for the Sim3D Three.js viewer.

Serves files from a configurable ROOT and exposes:
  GET /api/runs         — recursively discover run folders under ROOT (newest-first)
  GET /api/simulations  — backward-compatible alias of /api/runs

A "run folder" is any directory that contains frame_000000.json (the signature
written by ThreeJSWriter for a `-3js` run). One server launched from a
high-level root therefore reaches runs anywhere beneath it — the viewer can
browse and load any of them, and a Refresh rescans without a server restart.

Usage:
  python3 sim_server.py [port] [root]
    port   default 8000
    root   default: the launch directory (cwd). Run from ~/Code and one server
           covers both ~/Code/BoA/ and ~/Code/threejs_output/, with the viewer
           at http://localhost:<port>/sim_viewer_boa.html. May also be set via
           the SIM_ROOT environment variable or an explicit CLI arg (which wins
           over env). Works wherever the script file itself happens to live.

Localhost-only (binds all interfaces on the given port as before; intended for
local use).
"""

import sys
import os
import json
from functools import partial
from pathlib import Path
from datetime import datetime, timezone
from http.server import HTTPServer, SimpleHTTPRequestHandler

# Directories never worth descending into when hunting for run folders.
SKIP_DIRS = {'.git', 'node_modules', '__pycache__', 'libs', '.idea', '.vscode',
             'venv', '.venv', 'RUN_LOGS', 'ParameterFiles', 'removeMe'}
# How deep below root to search. Runs live ~1–2 levels down in practice
# (e.g. ~/Code/threejs_output/run1, ~/Code/BoA/run1.001); 4 is generous.
MAX_DEPTH = 4


def scan_runs(root):
    """Recursively find run folders (dirs with frame_000000.json) under root.

    Returns a list of dicts {path, name, frameCount, modified} sorted
    newest-first. `path` is the POSIX path relative to root (the URL prefix the
    viewer fetches frames from, '.' if root itself is a run); `name` is the leaf
    folder name for display.
    """
    root = Path(root)
    runs = []

    def walk(d, depth):
        # A run folder is a discovery leaf — frame_*.json never live in a
        # subdirectory of one, so don't recurse further into it.
        if (d / 'frame_000000.json').exists():
            frames = list(d.glob('frame_*.json'))
            if frames:
                rel = d.relative_to(root).as_posix()
                runs.append({
                    'path': rel,  # '.' when root itself is the run folder
                    'name': d.name if d != root else (root.name or str(root)),
                    'frameCount': len(frames),
                    'modified': max(f.stat().st_mtime for f in frames),
                })
            return
        if depth >= MAX_DEPTH:
            return
        try:
            entries = sorted(d.iterdir(), key=lambda p: p.name)
        except (PermissionError, OSError):
            return
        for sub in entries:
            if (sub.is_dir() and not sub.name.startswith('.')
                    and sub.name not in SKIP_DIRS):
                walk(sub, depth + 1)

    walk(root, 0)
    runs.sort(key=lambda r: r['modified'], reverse=True)
    return runs


class SimHandler(SimpleHTTPRequestHandler):

    def do_GET(self):
        # Strip any query string before matching the API routes.
        route = self.path.split('?', 1)[0]
        if route in ('/api/runs', '/api/simulations'):
            self._serve_runs()
        else:
            super().do_GET()

    def _serve_runs(self):
        runs = scan_runs(self.directory)
        output = [
            {
                'path': r['path'],
                'name': r['name'],
                'frameCount': r['frameCount'],
                'modified': datetime.fromtimestamp(
                    r['modified'], tz=timezone.utc).isoformat(),
            }
            for r in runs
        ]
        body = json.dumps(output).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass    # suppress per-request noise; startup banner is enough


def resolve_root(argv):
    """CLI arg (argv[2]) > SIM_ROOT env > the launch dir (cwd).

    Defaulting to cwd means "launch the server from the dir you want as root":
    run it from ~/Code and one server reaches both ~/Code/BoA/ and
    ~/Code/threejs_output/, with the viewer at the familiar
    http://localhost:<port>/sim_viewer_boa.html.
    """
    if len(argv) > 2:
        raw = argv[2]
    elif os.environ.get('SIM_ROOT'):
        raw = os.environ['SIM_ROOT']
    else:
        raw = os.getcwd()
    return str(Path(raw).expanduser().resolve())


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    root = resolve_root(sys.argv)

    # Where the viewer HTML lives, expressed relative to root, so the banner can
    # print the exact URL to open.
    script_dir = Path(__file__).resolve().parent
    try:
        viewer_prefix = script_dir.relative_to(root).as_posix()
        viewer_url = ('/' + viewer_prefix + '/sim_viewer_boa.html'
                      if viewer_prefix != '.' else '/sim_viewer_boa.html')
    except ValueError:
        viewer_url = '/sim_viewer_boa.html (note: viewer is not under root)'

    print(f"Sim3D server running at http://localhost:{port}")
    print(f"Serving from root: {root}")
    print(f"Open the viewer:   http://localhost:{port}{viewer_url}")
    print()

    runs = scan_runs(root)
    if runs:
        print(f"Run folders found ({len(runs)}, newest first):")
        for r in runs:
            ts = datetime.fromtimestamp(r['modified']).strftime('%Y-%m-%d %H:%M')
            print(f"  {r['path']:<40}  {r['frameCount']:>6} frames  [{ts}]")
    else:
        print(f"No run folders found under {root} "
              f"(no directory contains frame_000000.json)")
    print()

    handler = partial(SimHandler, directory=root)
    server = HTTPServer(('', port), handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")
