#!/usr/bin/env python3
"""
Generate a camera-set JSON from an OpenStreetMap extract.

Cameras are placed on real road junctions: nodes shared by three or more
major ways. Candidates are then thinned so the deployment spreads across
the city instead of clumping on one interchange, because the road graph
only forms an edge between cameras within MAX_EDGE_M of each other --
a clumped set produces a dense island and a lot of orphans.

Reads the same .osm.pbf the local OSRM container is built from, so the
setup has no dependency on Overpass (which rate-limits) and is
reproducible offline.

    python3 scripts/generate_camera_set.py \
        --pbf osrm-data/region.osm.pbf \
        --bbox 30.62,76.65,30.80,76.90 \
        --count 50 --set tricity --city "Chandigarh Tricity" --prefix TRI

Writes src/main/resources/camera-sets/<set>.json.
"""

import argparse
import json
import math
import pathlib
import sys
from collections import Counter, defaultdict

import osmium

# Ways a traffic camera would plausibly watch. Residential and service
# roads are excluded: they would pull the placement into housing estates.
MAJOR = ("trunk", "primary", "secondary", "tertiary")

# Rank for choosing which road names a junction, and for the heading.
CLASS_RANK = {"trunk": 4, "primary": 3, "secondary": 2, "tertiary": 1}

# Zones become the origins and destinations of the OD matrix. Bigger
# places first: one zone per camera would make every flow a singleton
# and the matrix unreadable.
PLACE_RANK = {"city": 5, "town": 4, "suburb": 3, "quarter": 2, "neighbourhood": 1}

# Must match RouteGeneratorRunner.MAX_DISTANCE_M. Two cameras further
# apart than this never form an edge, so a camera with no neighbour
# inside it can never continue a trajectory.
MAX_EDGE_M = 3000.0

# Must match RouteGeneratorRunner.MAX_HEADING_DEVIATION_DEG. An edge is
# kept only if both cameras point within this of the direction of travel.
MAX_HEADING_DEVIATION = 90.0

# The generator applies MAX_EDGE_M to the *road* distance as well as the
# straight line, and roads in a grid city run about 1.4x the crow-flight
# distance. Planning against the raw 3 km over-counts which pairs can
# actually become edges -- measured: 562 pairs looked in range, only 247
# survived routing.
ROAD_DETOUR_FACTOR = 1.4
EFFECTIVE_RANGE_M = MAX_EDGE_M / ROAD_DETOUR_FACTOR

EARTH_R = 6371000.0


def haversine(a, b):
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dlat, dlon = lat2 - lat1, lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def bearing(a, b):
    """Initial compass bearing from a to b, degrees."""
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlon = math.radians(b[1] - a[1])
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


class WayPass(osmium.SimpleHandler):
    """Counts node degree over major ways and remembers each way's shape."""

    def __init__(self):
        super().__init__()
        self.degree = Counter()
        self.node_ways = defaultdict(list)   # node id -> [(class, name, [node ids])]
        self.wanted = set()

    def way(self, w):
        highway = w.tags.get("highway")
        if highway not in MAJOR:
            return
        name = w.tags.get("name") or w.tags.get("ref") or ""
        refs = [n.ref for n in w.nodes]
        if len(refs) < 2:
            return
        for ref in refs:
            self.degree[ref] += 1
            self.node_ways[ref].append((highway, name, refs))
            self.wanted.add(ref)


class NodePass(osmium.SimpleHandler):
    """Second pass: coordinates for the nodes we kept, plus place labels."""

    def __init__(self, wanted, bbox):
        super().__init__()
        self.wanted = wanted
        self.bbox = bbox
        self.coords = {}
        self.places = []       # (lat, lon, name)

    def node(self, n):
        if not n.location.valid():
            return
        lat, lon = n.location.lat, n.location.lon

        if n.id in self.wanted:
            self.coords[n.id] = (lat, lon)

        place = n.tags.get("place")
        name = n.tags.get("name")
        if place in PLACE_RANK and name:
            s, w, nn, e = self.bbox
            if s <= lat <= nn and w <= lon <= e:
                self.places.append((lat, lon, name, PLACE_RANK[place]))


def thin(candidates, count, min_spacing_m, max_spacing_m):
    """
    Grow a connected deployment outward from the busiest junction.

    Pure farthest-point selection spreads well but happily places a
    camera 4 km from anything else, and the road graph only forms an
    edge under MAX_EDGE_M -- such a camera can never continue a
    trajectory. So each pick is the candidate furthest from the chosen
    set *that is still within max_spacing_m of something already
    chosen*. That maximises coverage subject to staying connected,
    rather than maximising coverage and discovering the breakage later.

    max_spacing_m is deliberately below MAX_EDGE_M: the edge test is on
    road distance, which runs longer than the straight line between the
    same two points.
    """
    if not candidates:
        return []

    chosen = [candidates[0]]
    taken = {candidates[0]["node_id"]}
    # Distance from each candidate to the nearest chosen camera.
    best = [haversine((c["lat"], c["lon"]),
                      (chosen[0]["lat"], chosen[0]["lon"]))
            for c in candidates]

    while len(chosen) < count:
        pick, pick_d = -1, -1.0
        for i, c in enumerate(candidates):
            if c["node_id"] in taken:
                continue
            d = best[i]
            # Too close adds nothing; too far orphans the camera.
            if d < min_spacing_m or d > max_spacing_m:
                continue
            # Spread is the driver, but among candidates at a similar
            # distance prefer the busier, named, higher-class junction —
            # that is where a camera would actually go, and it keeps the
            # set out of unnamed rural lanes.
            importance = (1.0
                          + (0.35 if c["crossing"] else 0.0)
                          + 0.08 * min(c["degree"] - 3, 4)
                          + 0.10 * CLASS_RANK.get(c["road_class"], 0))
            score = d * importance
            if score > pick_d:
                pick, pick_d = i, score

        if pick < 0:
            break

        chosen.append(candidates[pick])
        taken.add(candidates[pick]["node_id"])
        p = (candidates[pick]["lat"], candidates[pick]["lon"])
        for i, c in enumerate(candidates):
            d = haversine((c["lat"], c["lon"]), p)
            if d < best[i]:
                best[i] = d

    return chosen


def pick_zone_centres(places, count):
    """
    Choose a handful of well-separated place labels to act as zones.

    Every camera then snaps to its nearest. Taking one label per camera
    instead would give an origin-destination matrix in which almost
    every cell is a single vehicle.
    """
    if not places:
        return []

    # Prominence first, then spread: seed with the biggest place and keep
    # taking the one furthest from everything already taken.
    ranked = sorted(places, key=lambda p: -p[3])
    chosen = [ranked[0]]

    while len(chosen) < count and len(chosen) < len(ranked):
        pick, pick_d = None, -1.0
        for p in ranked:
            if p in chosen:
                continue
            d = min(haversine((p[0], p[1]), (c[0], c[1])) for c in chosen)
            # Weight by prominence so a big town beats a hamlet at equal distance.
            score = d * (1 + 0.25 * p[3])
            if score > pick_d:
                pick, pick_d = p, score
        if pick is None:
            break
        chosen.append(pick)

    return chosen


def assign_headings(cameras, steps=24):
    """
    Choose headings jointly, to maximise the number of survivable edges.

    The generator keeps an edge A->B only if *both* cameras point within
    MAX_HEADING_DEVIATION of the direction of travel. Optimising each
    camera on its own does not work: two adjacent cameras each pick the
    half-plane covering most of their own neighbours, and those are
    often opposite, so the pair is rejected at one end. Measured that
    way, 50 cameras yielded 42 edges against Chandigarh's 73 across 14.

    So hill-climb the joint assignment instead: seed each camera with
    its own best half-plane, then repeatedly re-point whichever single
    camera gains the most pairs, until nothing improves. Every camera
    still watches exactly one approach, which is what a real
    installation does -- the directions are just chosen to agree along
    the corridors, the way a deployment planner would lay them out.
    """
    n = len(cameras)
    pos = [(c["lat"], c["lon"]) for c in cameras]

    # Pairs close enough to become an edge at all. Straight-line
    # distance stands in for the road distance the generator measures.
    pairs = []
    for i in range(n):
        for j in range(n):
            if i != j and haversine(pos[i], pos[j]) <= EFFECTIVE_RANGE_M:
                pairs.append((i, j, bearing(pos[i], pos[j])))

    if not pairs:
        return [0] * n

    touching = [[] for _ in range(n)]
    for idx, (i, j, _) in enumerate(pairs):
        touching[i].append(idx)
        touching[j].append(idx)

    candidates = [round(k * 360 / steps) % 360 for k in range(steps)]

    def agrees(h, b):
        return abs((b - h + 180) % 360 - 180) <= MAX_HEADING_DEVIATION

    heading = []
    for i in range(n):
        best, best_score = 0, -1
        for cand in candidates:
            score = sum(1 for idx in touching[i] if agrees(cand, pairs[idx][2]))
            if score > best_score:
                best, best_score = cand, score
        heading.append(best)

    def kept(h):
        """
        Surviving pairs, plus a bonus for cameras that end up with both
        an inbound and an outbound edge.

        Maximising raw pair count alone leaves a fifth of the cameras as
        pure sources or sinks: a vehicle seen there can never be linked
        onward, so the camera reports sightings but never appears in a
        journey. A camera on the edge of the covered area genuinely is a
        source or a sink, but one in the middle should not be, so
        coverage is worth trading a few pairs for.
        """
        total = 0
        outbound = [False] * n
        inbound = [False] * n
        for i, j, b in pairs:
            if agrees(h[i], b) and agrees(h[j], b):
                total += 1
                outbound[i] = True
                inbound[j] = True
        connected = sum(1 for i in range(n) if outbound[i] and inbound[i])
        return total + 3 * connected

    current = kept(heading)
    for _ in range(300):                      # converges long before this
        gain, move = 0, None
        for i in range(n):
            original = heading[i]
            for cand in candidates:
                if cand == original:
                    continue
                heading[i] = cand
                delta = kept(heading) - current
                if delta > gain:
                    gain, move = delta, (i, cand)
            heading[i] = original
        if move is None:
            break
        heading[move[0]] = move[1]
        current += gain

    print(f"  headings: {current} of {len(pairs)} in-range ordered pairs "
          f"agree at both ends", flush=True)
    return heading


def build_candidates(ways, nodes, bbox):
    """Junctions inside the bbox: nodes shared by three or more major ways."""
    south, west, north, east = bbox
    out = []
    for node_id, deg in ways.degree.items():
        if deg < 3:
            continue
        pos = nodes.coords.get(node_id)
        if pos is None:
            continue
        lat, lon = pos
        if not (south <= lat <= north and west <= lon <= east):
            continue

        # Prefer a named way: an unnamed trunk road names the junction
        # "Unnamed Road", which tells an operator nothing. Class is the
        # tiebreak among named ways.
        best = max(ways.node_ways[node_id],
                   key=lambda w: (1 if w[1] else 0, CLASS_RANK.get(w[0], 0), len(w[1])))
        names = sorted({w[1] for w in ways.node_ways[node_id] if w[1]})

        out.append({
            "node_id": node_id,
            "degree": deg,
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "road": best[1] or "Unnamed Road",
            "road_class": best[0],
            "refs": best[2],
            "crossing": names[:2],
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--bbox", required=True, help="south,west,north,east")
    ap.add_argument("--count", type=int, default=50)
    ap.add_argument("--set", required=True, help="camera-set file name")
    ap.add_argument("--city", required=True)
    ap.add_argument("--prefix", default="CAM")
    ap.add_argument("--min-spacing", type=float, default=800.0)
    # Below MAX_EDGE_M on purpose: the graph tests road distance, which
    # is longer than the straight line this measures.
    ap.add_argument("--max-spacing", type=float, default=2200.0)
    ap.add_argument("--cache", default="",
                    help="cache parsed junctions here; reuse on re-runs "
                         "so tuning the selection does not re-read the pbf")
    ap.add_argument("--zones", type=int, default=12,
                    help="how many zones to bucket cameras into for OD analytics")
    ap.add_argument("--out-dir", default="src/main/resources/camera-sets")
    args = ap.parse_args()

    south, west, north, east = (float(x) for x in args.bbox.split(","))
    bbox = (south, west, north, east)

    cache_path = pathlib.Path(args.cache) if args.cache else None

    if cache_path and cache_path.exists():
        print(f"reusing cached junctions from {cache_path}", flush=True)
        cached = json.loads(cache_path.read_text())
        candidates = cached["candidates"]
        places = [tuple(p) for p in cached["places"]]
        coords = {int(k): tuple(v) for k, v in cached["coords"].items()}
    else:
        print(f"reading {args.pbf} …", flush=True)
        ways = WayPass()
        ways.apply_file(args.pbf)
        print(f"  major ways touched {len(ways.wanted):,} nodes", flush=True)

        nodes = NodePass(ways.wanted, bbox)
        nodes.apply_file(args.pbf)
        print(f"  resolved {len(nodes.coords):,} node positions, "
              f"{len(nodes.places)} place labels in bbox", flush=True)

        candidates = build_candidates(ways, nodes, bbox)
        places = nodes.places
        # Only the nodes the candidates actually reference are worth
        # keeping; the full 3M-node table is not.
        keep = {r for c in candidates for r in c["refs"]}
        coords = {k: v for k, v in nodes.coords.items() if k in keep}

        if cache_path:
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(json.dumps({
                "candidates": candidates,
                "places": places,
                "coords": {str(k): list(v) for k, v in coords.items()},
            }))
            print(f"  cached to {cache_path}", flush=True)

    candidates.sort(key=lambda c: -c["degree"])
    print(f"  {len(candidates):,} junction candidates in bbox", flush=True)

    if len(candidates) < args.count:
        print(f"!! only {len(candidates)} candidates for a target of {args.count}",
              file=sys.stderr)

    chosen = thin(candidates, args.count, args.min_spacing, args.max_spacing)
    print(f"  selected {len(chosen)} cameras "
          f"(spacing {args.min_spacing:.0f}-{args.max_spacing:.0f} m)", flush=True)

    zone_centres = pick_zone_centres(places, args.zones)
    print(f"  {len(zone_centres)} zones for the OD matrix", flush=True)

    ordered = sorted(chosen, key=lambda c: (-c["lat"], c["lon"]))
    headings = assign_headings(ordered)

    cameras = []
    for i, c in enumerate(ordered, start=1):
        heading = headings[i - 1]

        zone = "Zone_00"
        if zone_centres:
            near = min(zone_centres,
                       key=lambda p: haversine((c["lat"], c["lon"]), (p[0], p[1])))
            zone = near[2].replace(" ", "_")

        junction = " x ".join(c["crossing"]) if c["crossing"] else f"{zone} local roads"
        label = (c["road"] if c["road"] != "Unnamed Road" else zone)
        cameras.append({
            "camera_id": f"{args.prefix}_CAM_{i:02d}",
            "name": f"{label.replace(' ', '_')}_Jn_{i:02d}",
            "lat": c["lat"],
            "lon": c["lon"],
            "heading_degrees": heading,
            "zone": zone,
            "junction": junction,
            "watches": f"{compass(heading)}-bound traffic",
        })

    # Connectivity check. A camera with no neighbour inside MAX_EDGE_M can
    # never form an edge, so it can never continue a trajectory -- that is
    # a broken deployment, not a cosmetic issue.
    spacings, orphans = [], []
    for a in cameras:
        others = [haversine((a["lat"], a["lon"]), (b["lat"], b["lon"]))
                  for b in cameras if b is not a]
        nearest = min(others) if others else float("inf")
        spacings.append(nearest)
        if nearest > EFFECTIVE_RANGE_M:
            orphans.append(a["camera_id"])

    out = pathlib.Path(args.out_dir) / f"{args.set}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "city": args.city,
        "_readme": (f"Generated by scripts/generate_camera_set.py from {args.pbf} "
                    f"over bbox {args.bbox}. Regenerate rather than editing by hand."),
        "cameras": cameras,
    }, indent=2) + "\n")

    spacings.sort()
    print()
    print(f"wrote {out} — {len(cameras)} cameras")
    print(f"  nearest-neighbour spacing: "
          f"min {spacings[0]:.0f} m · median {spacings[len(spacings)//2]:.0f} m · "
          f"max {spacings[-1]:.0f} m")
    print(f"  cameras with no neighbour inside {EFFECTIVE_RANGE_M:.0f} m "
          f"(road-adjusted from {MAX_EDGE_M:.0f} m): "
          f"{len(orphans)}{' ' + ', '.join(orphans) if orphans else ''}")
    if orphans:
        print("  !! those cameras cannot form graph edges — reduce the bbox "
              "or raise --count", file=sys.stderr)
        return 1
    return 0


def compass(deg):
    return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int(((deg % 360) + 22.5) // 45) % 8]


if __name__ == "__main__":
    sys.exit(main())
