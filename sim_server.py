#!/usr/bin/env python3
"""
sim_server.py — static file server for the Sim3D Three.js viewer.
Adds GET /api/simulations to list available simulation output directories.

Usage: python3 sim_server.py [port]    (default port: 8000)
"""

import sys
import os
import json
from pathlib import Path
from datetime import datetime, timezone
from http.server import HTTPServer, SimpleHTTPRequestHandler


def scan_simulations(base='.'):
    """Return list of sim dicts sorted by newest frame file, most recent first."""
    sims = []
    try:
        entries = sorted(Path(base).iterdir(), key=lambda p: p.name)
    except PermissionError:
        return sims

    for subdir in entries:
        if not subdir.is_dir():
            continue
        if not (subdir / 'frame_000000.json').exists():
            continue
        frames = list(subdir.glob('frame_*.json'))
        if not frames:
            continue
        newest_mtime = max(f.stat().st_mtime for f in frames)
        sims.append({
            'name': subdir.name,
            'frameCount': len(frames),
            'modified': newest_mtime,   # float; converted to ISO in JSON output
        })

    sims.sort(key=lambda s: s['modified'], reverse=True)
    return sims


class SimHandler(SimpleHTTPRequestHandler):

    def do_GET(self):
        if self.path == '/api/simulations':
            self._serve_simulations()
        else:
            super().do_GET()

    def _serve_simulations(self):
        sims = scan_simulations('.')
        output = [
            {
                'name': s['name'],
                'frameCount': s['frameCount'],
                'modified': datetime.fromtimestamp(
                    s['modified'], tz=timezone.utc).isoformat(),
            }
            for s in sims
        ]
        body = json.dumps(output).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass    # suppress per-request noise; startup banner is enough


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    serve_dir = os.getcwd()

    print(f"Sim3D server running at http://localhost:{port}")
    print(f"Serving from: {serve_dir}")
    print()

    sims = scan_simulations('.')
    if sims:
        print("Simulations found:")
        for s in sims:
            ts = datetime.fromtimestamp(s['modified']).strftime('%Y-%m-%d %H:%M')
            print(f"  {s['name']:<30}  {s['frameCount']:>6} frames  [{ts}]")
    else:
        print("No simulations found (no subdirectory contains frame_000000.json)")
    print()

    server = HTTPServer(('', port), SimHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")
