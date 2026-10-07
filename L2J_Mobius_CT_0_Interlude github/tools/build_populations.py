#!/usr/bin/env python3
"""Generate PhantomPopulations from the game's own spawn data.

Placing phantoms by hand in the editor is accurate but slow. This tool derives phantom
<population> groups for the real hunting zones straight from dist/game/data/spawns, so every
zone gets phantoms at the level the server actually spawns monsters there, sitting inside
the real hunting area, with no manual authoring.

It reuses the same spawn/NPC reading that tools/build_knowledge.py does, so the level
bands it writes match the 60_zones_generated.txt knowledge facts exactly.

    cd "L2J_Mobius_CT_0_Interlude github"
    python3 tools/build_populations.py            # writes dist/game/data/PhantomPopulations.generated.xml
    python3 tools/build_populations.py --help     # all knobs

SAFE BY DEFAULT: it writes a SEPARATE file (PhantomPopulations.generated.xml) and never
touches your hand-authored PhantomPopulations.xml. Loading is opt-in: set
PhantomAutoHuntingZones = True in config/Custom/FakePlayers.ini and the server parses the
generated file additively, AFTER the authored one, so your hand-authored buddies, regulars,
and crafted friends are always kept - the generated set only adds field hunters. (You can
also test it the old way: back up PhantomPopulations.xml and swap this file in.)

CLUSTERING INTO REAL FIELDS. A spawn FILE is a whole region ("Talking Island" holds every
camp on the island), not one hunting zone, so it is far too coarse to be one population. And
a single field is authored as dozens (sometimes 100+) of small <territory> blocks, so one
population per block would be far too fine and would over-crowd the field. This tool sits in
between: it breaks each region into its individual monster sources (each <territory> block,
each fixed-coordinate monster group) and clusters those by proximity (--cluster-dist) into
field-sized groups, one <population> per cluster.

TERRITORIES ARE PASSED THROUGH AS POLYGONS. About half the datapack's monsters live in
<territory> polygons (the big open fields) rather than at fixed points. A cluster holding any
territory geometry is emitted as a polygon whose shape is the convex hull of every monster
coordinate in the cluster, so phantoms scatter across the real field extent instead of an
eyeballed circle that may sit beside the pack or across a wall. The runtime already supports
polygon populations and geo-snaps each spawn point to the ground, rejecting any that land on
invalid geo, so mild hull overshoot self-corrects. Use --territory-centroid to fall back to
the older behavior (one centroid + radius circle per whole spawn file, no clustering).

Per cluster the tool emits one <population>:
  - A cluster with <territory> geometry -> a polygon: the convex hull of the cluster's
    monster coordinates as <point> children, anchored at the hull center + weighted-mean Z,
    level band from every monster in the cluster, count scaled from the cluster's monster
    total. It also carries a radius = the hull circumradius: the runtime tests only `radius`
    around the center to decide when a real player is near enough to wake the zone (it does
    not test the polygon), so the radius must span the whole field even though placement uses
    the hull.
  - A cluster of fixed-coordinate camps only -> a circle: center on the monster-count-weighted
    average, radius from how far the camp spreads.

Server-side, each spawn point is re-snapped to the ground via geodata, so the z it writes
is only a hint. Pure stdlib. Deterministic (sorted) so regeneration gives stable diffs.
"""

import argparse
import math
import os
import re
import xml.etree.ElementTree as ET
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)  # the "L2J_Mobius_CT_0_Interlude github" dir
DATA = os.path.join(PROJECT, "dist", "game", "data")
DEFAULT_OUT = os.path.join(DATA, "PhantomPopulations.generated.xml")

# Territory folders that are not open-world hunting grounds. Their spawns are event/siege/
# instance or pure map-grid tiles, so we skip them by default (override with --include).
SKIP_TERRITORIES = {"Castles", "SevenSigns", "Others"}

# Readable town per territory folder, only used to annotate the output for the reader.
TERRITORY_TOWN = {
    "TalkingIsland": "Talking Island", "Gludin": "Gludin Village", "Gludio": "Gludio",
    "Dion": "Dion", "Giran": "Giran", "Oren": "Oren", "Aden": "Aden",
    "Hunters": "Hunters Village", "Rune": "Rune", "Goddard": "Goddard",
    "Schuttgart": "Schuttgart", "Innadril": "Heine", "DarkElfTerritory": "the Dark Elf Village",
    "ElvenTerritory": "the Elven Village", "OrcTerritory": "the Orc Village",
    "DwarvenTerritory": "the Dwarven Village",
}

_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_LOWER_WORDS = {"of", "the", "and", "near", "for"}


def readable(stem):
    """Turn a spawn filename stem into a human zone name, or '' if it has no real name.

    'DragonValleyMonsters' -> 'Dragon Valley'; '19_16_PaganAltar' -> 'Pagan Altar'.
    Pure map-grid tiles like '17_20' have no name -> ''. (Same rule as build_knowledge.py.)
    """
    for suf in ("Monsters", "Monster", "Spawns", "Spawn", "NPCs", "NPC"):
        if stem.endswith(suf) and stem != suf:
            stem = stem[: -len(suf)]
    stem = stem.replace("_", " ")
    words = [w for w in _CAMEL.sub(" ", stem).split() if w]
    while words and words[0].isdigit():  # drop leading map-grid coordinates
        words.pop(0)
    if not words or all(w.isdigit() for w in words):
        return ""
    return " ".join(w.lower() if i and w.lower() in _LOWER_WORDS else w
                    for i, w in enumerate(words)).strip()


def parse_root(path):
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"  ! skip {os.path.relpath(path, DATA)}: {e}")
        return None


def load_monster_levels():
    """id -> level, for NPCs typed Monster (the only thing a hunting phantom farms).

    Note: type="Monster" already excludes RaidBoss, GrandBoss, FestivalMonster, RiftInvader,
    Guard, Defender, FeedableBeast and friends - those are their own NPC types, so no separate
    blocklist is needed here.
    """
    levels = {}
    npc_dir = os.path.join(DATA, "stats", "npcs")
    for dp, _, fns in os.walk(npc_dir):
        for fn in sorted(fns):
            if not fn.endswith(".xml"):
                continue
            root = parse_root(os.path.join(dp, fn))
            if root is None:
                continue
            for npc in root.iter("npc"):
                nid = npc.get("id")
                lvl = npc.get("level")
                if nid and lvl and lvl.isdigit() and (npc.get("type") == "Monster"):
                    levels[nid] = int(lvl)
    return levels


def territory_geometry(node):
    """(nodes[list of (x, y)], minZ, maxZ) for a <territory>, or None if it has no nodes."""
    nodes = []
    for n in node.findall("node"):
        try:
            nodes.append((int(n.get("x")), int(n.get("y"))))
        except (TypeError, ValueError):
            continue
    if not nodes:
        return None
    try:
        minz, maxz = int(node.get("minZ")), int(node.get("maxZ"))
    except (TypeError, ValueError):
        minz = maxz = 0
    return nodes, minz, maxz


def collect_zones(levels):
    """Walk spawns/*; per named zone file return a dict of its monster geometry.

    Each entry: {zone, territory, polys, inline_points, inline_levels}
      polys         : list of {nodes, minz, maxz, levels, weight} - one per <territory> block
                      that holds monsters. 'levels' is every monster level in the block
                      (expanded by count); 'weight' is the monster count.
      inline_points : (x, y, z, count, level) for each fixed-coordinate monster in the file.
      inline_levels : every inline monster's level (expanded by count).
    Non-monster NPCs (town folk, gatekeepers) are ignored.
    """
    zones = []
    spawn_root = os.path.join(DATA, "spawns")
    for dp, _, fns in os.walk(spawn_root):
        for fn in sorted(fns):
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dp, fn)
            rel = os.path.relpath(path, spawn_root)
            territory = rel.split(os.sep)[0]
            stem = os.path.splitext(fn)[0]
            if stem.endswith("NPCs"):  # town folk / merchant files, never a hunting ground
                continue
            zone = readable(stem)
            if not zone:  # nameless map-grid tile
                continue
            root = parse_root(path)
            if root is None:
                continue
            polys = []
            inline_points = []
            inline_levels = []
            for spawn in root.iter("spawn"):
                tnode = spawn.find("territory")
                geom = territory_geometry(tnode) if tnode is not None else None
                block_levels = []
                block_weight = 0
                for n in spawn.findall("npc"):
                    nid = n.get("id")
                    if nid not in levels:
                        continue
                    try:
                        c = int(n.get("count") or 1)
                    except ValueError:
                        c = 1
                    if n.get("x") is not None:  # fixed-coordinate monster: its own point
                        try:
                            inline_points.append((int(n.get("x")), int(n.get("y")),
                                                  int(n.get("z") or 0), c, levels[nid]))
                            inline_levels.extend([levels[nid]] * c)
                        except (TypeError, ValueError):
                            pass
                    else:  # monster placed by the territory polygon
                        block_levels.extend([levels[nid]] * c)
                        block_weight += c
                if geom and block_weight:
                    nodes, minz, maxz = geom
                    polys.append({"nodes": nodes, "minz": minz, "maxz": maxz,
                                  "levels": block_levels, "weight": block_weight})
            if polys or (inline_points and inline_levels):
                zones.append({"zone": zone, "territory": territory, "polys": polys,
                              "inline_points": inline_points, "inline_levels": inline_levels})
    return zones


def level_band(mob_levels, args):
    """(minLevel, maxLevel) clamped to the configured window, or None if wholly out of range."""
    lo = int(min(mob_levels))
    hi = int(max(mob_levels))
    if hi < args.level_floor or lo > args.level_ceiling:
        return None
    # never ask a phantom to roll above the player level cap (a few zones seed a stray
    # over-cap mob); clamp the band so every rolled level is a legal character level.
    hi = min(hi, args.level_ceiling)
    lo = min(lo, hi)
    return lo, hi


def scaled_count(weight, args):
    """Phantom count for a monster weight: scaled by density, clamped to [1, max-per-zone]."""
    return max(1, min(args.max_per_zone, round(weight / args.density)))


def convex_hull(points):
    """Convex hull (Andrew's monotone chain) of (x, y) points, as an ordered ring.

    Returns the hull vertices; with fewer than 3 distinct points it returns those points
    unchanged so the caller can fall back to a circle. Pure stdlib, O(n log n).
    """
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def zone_units(z):
    """Break a collected zone into 'units' to be clustered into real hunting fields.

    A spawn FILE is a whole region (e.g. "Talking Island" holds every camp on the island),
    not one hunting zone - so the file is far too coarse to be one population. Each unit is a
    single monster source: one <territory> block, or one fixed-coordinate monster group. Units
    are then clustered by proximity (cluster_units) into field-sized groups.

    Each unit: {cx, cy, z, weight, levels, nodes}. 'nodes' is the territory polygon (empty for
    a point unit); cx/cy is the unit's own centroid used for clustering.
    """
    units = []
    for block in z["polys"]:
        nodes = block["nodes"]
        cx = round(sum(x for x, _ in nodes) / len(nodes))
        cy = round(sum(y for _, y in nodes) / len(nodes))
        units.append({"cx": cx, "cy": cy, "z": (block["minz"] + block["maxz"]) // 2,
                      "weight": block["weight"], "levels": block["levels"], "nodes": nodes})
    for (x, y, zz, c, lvl) in z["inline_points"]:
        units.append({"cx": x, "cy": y, "z": zz, "weight": c,
                      "levels": [lvl] * c, "nodes": []})
    return units


def cluster_units(units, dist):
    """Single-linkage spatial clustering of units: two units join a cluster when their
    centroids are within `dist`. Grid-bucketed union-find, so it stays near-linear even for
    the 100+ block fields, and deterministic (input order preserved)."""
    if not units:
        return []
    cell = max(1, dist)
    grid = defaultdict(list)
    for i, u in enumerate(units):
        grid[(u["cx"] // cell, u["cy"] // cell)].append(i)

    parent = list(range(len(units)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    d2 = dist * dist
    for (gx, gy), idxs in grid.items():
        neigh = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                neigh.extend(grid.get((gx + dx, gy + dy), ()))
        for i in idxs:
            ui = units[i]
            for j in neigh:
                if j <= i:
                    continue
                uj = units[j]
                ddx = ui["cx"] - uj["cx"]
                ddy = ui["cy"] - uj["cy"]
                if (ddx * ddx) + (ddy * ddy) <= d2:
                    union(i, j)

    groups = defaultdict(list)
    for i in range(len(units)):
        groups[find(i)].append(units[i])
    return list(groups.values())


def build_cluster_pop(units, zone, territory, args):
    """One <population> dict for a clustered hunting field, or None if out of level range.

    A cluster holding any <territory> geometry becomes a polygon: the convex hull of every
    monster coordinate in the cluster, so phantoms scatter across the real field shape. A
    cluster of only fixed-coordinate camps becomes a circle. Either way a `radius` is written
    (the hull circumradius, or the camp spread): the runtime decides whether a real player is
    near enough to wake the zone from `radius` around the center - it does not test the
    polygon - so the radius must span the whole field even when placement uses the hull.
    """
    mob_levels = []
    weight = 0
    hull_points = []
    z_samples = []  # (z, weight) for the anchor
    has_territory = False
    circle_points = []  # (x, y, z, weight) fallback if no polygon
    for u in units:
        mob_levels.extend(u["levels"])
        weight += u["weight"]
        z_samples.append((u["z"], u["weight"]))
        circle_points.append((u["cx"], u["cy"], u["z"], u["weight"]))
        if u["nodes"]:
            has_territory = True
            hull_points.extend(u["nodes"])
        else:
            hull_points.append((u["cx"], u["cy"]))

    if not mob_levels:
        return None
    band = level_band(mob_levels, args)
    if band is None:
        return None
    tw = sum(w for _, w in z_samples) or 1
    cz = round(sum(v * w for v, w in z_samples) / tw)
    common = {
        "name": zone.title(), "town": TERRITORY_TOWN.get(territory),
        "count": scaled_count(weight, args), "z": cz,
        "minLevel": band[0], "maxLevel": band[1], "territory": territory,
    }

    hull = convex_hull(hull_points) if has_territory else []
    if len(hull) >= 3:
        cx = round(sum(x for x, _ in hull) / len(hull))
        cy = round(sum(y for _, y in hull) / len(hull))
        radius = max(args.min_radius, round(max(math.hypot(x - cx, y - cy) for x, y in hull)))
        return {**common, "kind": "poly", "x": cx, "y": cy, "radius": radius, "nodes": hull}

    # No usable polygon (point camps, or a degenerate/collinear territory): emit a circle.
    return build_circle_pop(zone, territory, circle_points, mob_levels, args)


def build_circle_pop(zone, territory, points, mob_levels, args):
    """One circular <population> dict for a cloud of fixed-coordinate monsters, or None."""
    band = level_band(mob_levels, args)
    if band is None:
        return None
    total_w = sum(p[3] for p in points) or 1
    cx = round(sum(p[0] * p[3] for p in points) / total_w)
    cy = round(sum(p[1] * p[3] for p in points) / total_w)
    cz = round(sum(p[2] * p[3] for p in points) / total_w)
    # radius: how far the monsters actually spread from the center (mean 2D distance,
    # padded), clamped so tiny camps and sprawling valleys both stay sane.
    dists = [math.hypot(p[0] - cx, p[1] - cy) for p in points]
    spread = round((sum(dists) / len(dists)) * 1.4) if dists else 0
    radius = max(args.min_radius, min(args.max_radius, spread or args.min_radius))
    return {
        "kind": "circle", "name": zone.title(), "town": TERRITORY_TOWN.get(territory),
        "x": cx, "y": cy, "z": cz, "radius": radius, "count": scaled_count(total_w, args),
        "minLevel": band[0], "maxLevel": band[1], "territory": territory,
    }


def populations_for_zone(z, args):
    """Turn one collected zone dict into its <population> dicts (0 or 1)."""
    if args.territory_centroid:
        # Legacy behavior: collapse every territory block to its weighted centroid, merge
        # with the fixed-coordinate monsters, and emit ONE circle for the whole zone file.
        points = list(z["inline_points"])
        levels = list(z["inline_levels"])
        for block in z["polys"]:
            nodes = block["nodes"]
            cx = round(sum(x for x, _ in nodes) / len(nodes))
            cy = round(sum(y for _, y in nodes) / len(nodes))
            cz = (block["minz"] + block["maxz"]) // 2
            points.append((cx, cy, cz, block["weight"]))
            levels.extend(block["levels"])
        if points and levels:
            pop = build_circle_pop(z["zone"], z["territory"], points, levels, args)
            if pop:
                return [pop]
        return []
    # Default: cluster the zone's monster sources into field-sized groups, then emit one
    # population per cluster - a convex-hull polygon for territory fields, a circle for camps.
    pops = []
    for cluster in cluster_units(zone_units(z), args.cluster_dist):
        pop = build_cluster_pop(cluster, z["zone"], z["territory"], args)
        if pop:
            pops.append(pop)
    return pops


def comment_safe(text):
    """XML comments may not contain a double hyphen; collapse any '--' so the file stays
    well-formed no matter what a zone or town name contains."""
    while "--" in text:
        text = text.replace("--", "-")
    return text


def render(pops, args):
    out = []
    out.append('<?xml version="1.0" encoding="UTF-8"?>')
    out.append("<!--")
    out.append("\tAUTO-GENERATED by tools/build_populations.py from dist/game/data/spawns.")
    out.append("\tField-hunter <population> groups: center, level band and count are derived")
    out.append("\tfrom where the server actually spawns monsters. Territory (polygon) fields")
    out.append("\tare emitted as real <point> polygons; fixed-coordinate camps as circles.")
    out.append("")
    out.append("\tLOADING: set PhantomAutoHuntingZones = True in config/Custom/FakePlayers.ini")
    out.append("\tand the server loads this file ADDITIVELY, after your hand-authored")
    out.append("\tPhantomPopulations.xml, so buddies/regulars/friends there are kept and only")
    out.append("\tfield hunters are added. (Or test the old way: back up PhantomPopulations.xml")
    out.append("\tand copy this over it, then //phantom reload.)")
    out.append("")
    out.append("\tSafe to hand-edit or to merge chosen lines into your own file. Tune it with the")
    out.append("\tdensity, max-per-zone, min-radius and max-radius options (run the tool with -h).")
    out.append("-->")
    out.append("<list>")
    last_territory = None
    for p in sorted(pops, key=lambda q: (q["territory"], q["name"], q["x"], q["y"])):
        if p["territory"] != last_territory:
            town = TERRITORY_TOWN.get(p["territory"])
            label = f"{p['territory']}" + (f" ({town})" if town else "")
            out.append(f"\t<!-- ===== {comment_safe(label)} ===== -->")
            last_territory = p["territory"]
        near = f'  near {comment_safe(p["town"])}' if p["town"] else ""
        if p["kind"] == "poly":
            # radius is the activation range (how close a player must be to wake the zone);
            # placement uses the <point> polygon below, so both coexist.
            out.append(
                f'\t<population name="{p["name"]}" x="{p["x"]}" y="{p["y"]}" z="{p["z"]}"'
                f' radius="{p["radius"]}" count="{p["count"]}"'
                f' minLevel="{p["minLevel"]}" maxLevel="{p["maxLevel"]}" respawn="true">'
                f'{" <!--" + near + " -->" if near else ""}'
            )
            for nx, ny in p["nodes"]:
                out.append(f'\t\t<point x="{nx}" y="{ny}" />')
            out.append("\t</population>")
        else:
            out.append(
                f'\t<population name="{p["name"]}" x="{p["x"]}" y="{p["y"]}" z="{p["z"]}"'
                f' radius="{p["radius"]}" count="{p["count"]}"'
                f' minLevel="{p["minLevel"]}" maxLevel="{p["maxLevel"]}" respawn="true" />'
                f'{" <!--" + near + " -->" if near else ""}'
            )
    out.append("</list>")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description="Generate PhantomPopulations from spawn data.")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output path (default: the .generated.xml side file)")
    ap.add_argument("--density", type=float, default=30.0,
                    help="monsters per phantom; lower = denser crowds (default 30)")
    ap.add_argument("--max-per-zone", type=int, default=10, help="cap phantoms in one population (default 10)")
    ap.add_argument("--cluster-dist", type=int, default=3000,
                    help="max gap (units) between monster sources grouped into one hunting field (default 3000)")
    ap.add_argument("--min-radius", type=int, default=400, help="smallest scatter radius (default 400)")
    ap.add_argument("--max-radius", type=int, default=2500, help="largest scatter radius (default 2500)")
    ap.add_argument("--level-floor", type=int, default=1, help="skip zones whose top level is below this")
    ap.add_argument("--level-ceiling", type=int, default=80, help="skip zones whose bottom level is above this")
    ap.add_argument("--territory-centroid", action="store_true",
                    help="legacy mode: collapse each territory to a centroid+radius circle instead of "
                         "passing the polygon through (one circle per zone file)")
    ap.add_argument("--include", action="append", default=[],
                    help="territory folder to include even though it is skipped by default (repeatable)")
    args = ap.parse_args()

    global SKIP_TERRITORIES
    SKIP_TERRITORIES = SKIP_TERRITORIES - set(args.include)

    if not os.path.isdir(DATA):
        raise SystemExit(f"game data not found at {DATA}")

    print("Loading monster levels...")
    levels = load_monster_levels()
    print(f"  {len(levels)} monster templates")
    print("Reading spawns...")
    zones = collect_zones(levels)

    pops = []
    for z in zones:
        if z["territory"] in SKIP_TERRITORIES:
            continue
        pops.extend(populations_for_zone(z, args))

    text = render(pops, args)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(text)

    n_poly = sum(1 for p in pops if p["kind"] == "poly")
    n_circle = len(pops) - n_poly
    print(f"  wrote {len(pops)} populations ({n_poly} polygon, {n_circle} circle) "
          f"to {os.path.relpath(args.out, PROJECT)}")
    if pops:
        lvls = sorted({(p["minLevel"], p["maxLevel"]) for p in pops})
        print(f"  level bands span {min(l for l, _ in lvls)}-{max(h for _, h in lvls)}")
    print("Done. This is a SIDE file. To load it, set PhantomAutoHuntingZones = True in")
    print("config/Custom/FakePlayers.ini (loads additively, keeping your authored file), then")
    print("//phantom reload. Or test the old way: back up PhantomPopulations.xml and swap this in.")


if __name__ == "__main__":
    main()
