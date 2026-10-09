#!/usr/bin/env python3
"""
Build a demo scenario for whichever camera set is loaded.

scenarios/demo.json names Chandigarh cameras, so it does nothing against
another set -- and the two set-pieces it exists for, the cloned plate and
the identity recovered through an unreadable plate, are the strongest
things in the demo. This reads the live road graph and writes an
equivalent scenario over real routes.

    python3 scripts/generate_scenario.py --out scenarios/tricity.json
"""

import argparse
import json
import subprocess
import sys
from collections import defaultdict


def psql(sql):
    out = subprocess.run(
        ["docker", "exec", "anpr-postgres", "psql", "-U", "anpr", "-d", "anpr", "-tAc", sql],
        capture_output=True, text=True, check=True).stdout
    return [line for line in out.strip().splitlines() if line]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    edges = defaultdict(list)
    for row in psql("""SELECT e.from_camera_id||'|'||e.to_camera_id
                         FROM camera_edges e
                         JOIN cameras a ON a.camera_id=e.from_camera_id AND a.is_active
                         JOIN cameras b ON b.camera_id=e.to_camera_id   AND b.is_active"""):
        a, b = row.split("|")
        edges[a].append(b)

    if not edges:
        sys.exit("no edges among active cameras -- run graphgen first")

    def walk(start, length):
        """Longest simple path we can find from start, up to length."""
        route, seen = [start], {start}
        while len(route) < length:
            nxt = [c for c in edges.get(route[-1], []) if c not in seen]
            if not nxt:
                break
            # Prefer continuing somewhere that itself has an exit.
            nxt.sort(key=lambda c: -len(edges.get(c, [])))
            route.append(nxt[0])
            seen.add(nxt[0])
        return route

    # The longest route we can find carries the blacklist and null-plate stories.
    best = max((walk(s, 6) for s in edges), key=len)
    if len(best) < 4:
        sys.exit(f"longest route is only {len(best)} cameras; graph is too sparse")

    # A clone pair needs two cameras with NO edge between them, far enough
    # apart that being seen at both within seconds is physically impossible.
    far = psql("""
        SELECT a.camera_id||'|'||b.camera_id
          FROM cameras a JOIN cameras b ON a.camera_id < b.camera_id
         WHERE a.is_active AND b.is_active
           AND NOT EXISTS (SELECT 1 FROM camera_edges e
                            WHERE (e.from_camera_id=a.camera_id AND e.to_camera_id=b.camera_id)
                               OR (e.from_camera_id=b.camera_id AND e.to_camera_id=a.camera_id))
         ORDER BY ST_Distance(a.geom::geography, b.geom::geography) DESC
         LIMIT 40""")
    far_all = far
    clone_a, clone_b = far[0].split("|")

    # A scenario vehicle needs at least two cameras, so extend each clone
    # one hop along its own side of the city. They stay far apart, which
    # is what makes being seen at both within seconds impossible.
    def extend(camera):
        onward = edges.get(camera, [])
        return [camera, onward[0]] if onward else None

    clone_route_a = extend(clone_a)
    clone_route_b = extend(clone_b)

    # Fall back to any pair of distant cameras that both have an exit.
    if not clone_route_a or not clone_route_b:
        for row in far_all:
            a, b = row.split("|")
            ra, rb = extend(a), extend(b)
            if ra and rb:
                clone_a, clone_b, clone_route_a, clone_route_b = a, b, ra, rb
                break
    if not clone_route_a or not clone_route_b:
        sys.exit("could not find two distant cameras that each have an onward edge")

    vehicles = [
        # Blacklisted lorry: crosses several cameras, collapses into one
        # alert row with a repeat count rather than one row per camera.
        {"plate": "CH01AB1234", "type": "truck", "colour": "white",
         "route": best[:5], "startDelaySeconds": 5, "embeddingSeed": "blacklist-lorry"},

        # Identity recovered without a plate: read cleanly, then unreadable
        # in the middle, then read again -- and still one vehicle.
        {"plate": "CH01ZZ9999", "type": "car", "colour": "silver",
         "route": best[:4], "startDelaySeconds": 20,
         "forceNullPlateAt": [best[2]], "embeddingSeed": "null-plate-car"},

        # The clone pair: same registration, two vehicles that do not look
        # alike, seen too far apart too quickly to be the same car.
        {"plate": "CH04CL0NE", "type": "car", "colour": "black",
         "route": clone_route_a, "startDelaySeconds": 40, "embeddingSeed": "clone-black"},
        {"plate": "CH04CL0NE", "type": "car", "colour": "white",
         "route": clone_route_b, "startDelaySeconds": 55, "embeddingSeed": "clone-white"},

        # A heavy vehicle through a restricted zone.
        {"plate": "PB65AB1234", "type": "truck", "colour": "blue",
         "route": best[:3], "startDelaySeconds": 70, "embeddingSeed": "zone-truck"},
    ]

    with open(args.out, "w") as f:
        json.dump({"vehicles": vehicles}, f, indent=2)
        f.write("\n")

    print(f"wrote {args.out}")
    print(f"  main route ({len(best)} cameras): {' -> '.join(best[:5])}")
    print(f"  clone pair (no edge between them): {clone_a} / {clone_b}")


if __name__ == "__main__":
    main()
