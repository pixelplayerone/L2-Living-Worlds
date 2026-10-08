#!/usr/bin/env python3
"""Build the Living Population travel catalog (towns and hunting zones) from the game's own data.

Living bots travel like players: they hunt in a named zone that fits their level, use a Scroll of Escape (or walk)
to a town to shop, and pay a gatekeeper to reach their next zone. This tool writes the catalog they use:

    dist/game/modules/living-population/data/zones.xml

It combines a HAND-PICKED list of well-known leveling zones (ZONES below, name and level range chosen by the
maintainer) with the server's own data, so every coordinate is real:

- the gatekeeper teleport point and fee for each zone comes from data/teleporters/town/*.xml,
- the hunting spots inside a zone are real monster spawn points (data/spawns) whose level fits the zone, and the
  zone lists those monsters with their spawn counts (cold bots earn from their real drop lists),
- each town's Scroll of Escape arrival point comes from data/mapregion/*.xml,
- each town's gatekeeper and grocer positions come from data/spawns (the grocer is the merchant whose buy list
  sells Scrolls of Escape),
- each town's class masters come from the village master scripts (data/scripts/village_master/*Change*), one per
  script, placed from data/spawns. They change a bot's class and teach its skills.

Edit ZONES to change which zones bots use or their level ranges, then re-run:

    cd "L2J_Mobius_CT_0_Interlude github"
    python3 tools/build_living_zones.py
"""

import glob
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "dist", "game", "data")
OUT = os.path.join(ROOT, "dist", "game", "modules", "living-population", "data", "zones.xml")

SCROLL_OF_ESCAPE = 736
SPOT_RADIUS = 6000  # how far from the teleport point a monster spawn may be to count as part of the zone
STARTER_RADIUS = 16000
MAX_SPOTS = 8
LEVEL_SLACK = 2  # a spawn counts when its level is within the zone range widened by this

# Hand-picked leveling zones: (gatekeeper destination name, min level, max level). The name must match a
# gatekeeper destination in data/teleporters/town exactly. Level ranges follow the monsters actually spawned
# around each teleport point, rounded to the ranges players know.
ZONES = [
    # Early levels, near the starting villages
    ("Obelisk of Victory", 6, 12),
    ("Talking Island, Western Territory (Northern Area)", 11, 15),
    ("Singing Waterfall", 11, 16),
    ("Elven Forest", 10, 15),
    ("Elven Fortress", 10, 14),
    ("Swampland", 12, 16),
    ("The Immortal Plateau", 11, 15),
    ("Frozen Waterfall", 13, 17),
    ("Abandoned Coal Mines", 12, 17),
    ("Western Mining Zone (Central Shore)", 12, 16),
    ("Fellmere Harvesting Grounds", 14, 18),
    # 15 to 25
    ("Eastern Mining Zone (Northeastern Shore)", 17, 21),
    ("Windmill Hill", 17, 21),
    ("Abandoned Camp", 17, 23),
    ("Ruins of Agony", 17, 22),
    ("Ruins of Despair", 18, 23),
    ("Spider Nest", 17, 20),
    ("Langk Lizardman Dwellings", 19, 24),
    ("The Immortal Plateau, Southern Region", 19, 24),
    ("Mithril Mines", 20, 25),
    ("Windawood Manor", 21, 25),
    # 24 to 35
    ("Plains of Dion", 23, 27),
    ("Orc Barracks", 24, 30),
    ("Forgotten Temple", 26, 30),
    ("Fortress of Resistance", 26, 31),
    ("Windy Hill", 27, 31),
    ("Crypts of Disgrace", 28, 33),
    ("The Ant Nest", 28, 35),
    ("Breka's Stronghold", 29, 35),
    ("Plunderous Plains", 30, 36),
    # 35 to 45
    ("Cruma Tower", 35, 45),
    ("Bee Hive", 35, 39),
    ("Plains of the Lizardmen", 35, 40),
    ("Field of Whispers", 35, 39),
    ("Field of Silence", 36, 40),
    ("Alligator Island", 39, 43),
    ("Sea of Spores", 40, 44),
    ("Ivory Tower", 41, 46),
    ("Devil's Isle", 43, 48),
    ("Den of Evil", 44, 50),
    # 45 to 60
    ("Tanor Canyon", 46, 51),
    ("Enchanted Valley, Southern Region", 47, 52),
    ("Pavel Ruins", 48, 52),
    ("Forest of Mirrors", 50, 60),
    ("Outlaw Forest", 50, 56),
    ("Hardin's Private Academy", 52, 56),
    ("Forsaken Plains", 54, 59),
    ("Silent Valley", 55, 61),
    ("Skyshadow Meadow", 56, 61),
    ("Seal of Shilen", 55, 63),
    # 58 to 80
    ("Fields of Massacre", 58, 70),
    ("Valley of Saints", 60, 64),
    ("Forest of the Dead", 62, 68),
    ("Tower of Insolence", 64, 73),
    ("Swamp of Screams", 66, 70),
    ("Wall of Argos", 67, 71),
    ("Blazing Swamp", 70, 75),
    ("Hot Springs", 72, 76),
    ("Ketra Orc Outpost", 72, 80),
    ("Varka Silenos Stronghold", 76, 80),
    ("Monastery of Silence", 78, 80),
]

# Newbie grounds around each starting village: (zone name, race, gatekeeper destination used as the center,
# min level, max level). A new bot hunts here first, like a new player.
STARTER_ZONES = [
    ("Talking Island newbie grounds", "Human", "Talking Island Village", 1, 10),
    ("Elven Village newbie grounds", "Elf", "Elven Village", 1, 10),
    ("Dark Elf Village newbie grounds", "DarkElf", "Dark Elf Village", 1, 10),
    ("Orc Village newbie grounds", "Orc", "Orc Village", 1, 10),
    ("Dwarven Village newbie grounds", "Dwarf", "Dwarven Village", 1, 10),
]


def load_npcs():
    """npc id -> (level, type, name)"""
    npcs = {}
    for path in glob.glob(os.path.join(DATA, "stats", "npcs", "*.xml")):
        for match in re.finditer(r'<npc id="(\d+)" level="(\d+)" type="(\w+)" name="([^"]*)"', open(path, encoding="utf-8").read()):
            npcs[int(match.group(1))] = (int(match.group(2)), match.group(3), match.group(4))
    return npcs


def load_spawns(npcs):
    """Every spawn as (npc id, x, y, z, count). Territory spawns use the territory centroid."""
    out = []
    for path in glob.glob(os.path.join(DATA, "spawns", "**", "*.xml"), recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for spawn in root.iter("spawn"):
            # A territory carries its height band (minZ/maxZ) on the territory element, not on its nodes; the middle of
            # the band is a ground height the server can snap to the right layer.
            nodes = []
            heights = []
            for territory in spawn.iter("territory"):
                low, high = territory.get("minZ"), territory.get("maxZ")
                if low is not None and high is not None:
                    heights.append((int(low) + int(high)) // 2)
                nodes.extend((int(n.get("x")), int(n.get("y"))) for n in territory.iter("node"))
            centroid = None
            if nodes:
                z = (sum(heights) // len(heights)) if heights else 0
                centroid = (sum(n[0] for n in nodes) // len(nodes), sum(n[1] for n in nodes) // len(nodes), z)
            for npc in spawn.iter("npc"):
                npc_id = int(npc.get("id"))
                if npc_id not in npcs:
                    continue
                count = int(npc.get("count") or 1)
                if npc.get("x") is not None:
                    out.append((npc_id, int(npc.get("x")), int(npc.get("y")), int(npc.get("z")), count))
                elif centroid:
                    out.append((npc_id, centroid[0], centroid[1], centroid[2], count))
    return out


def load_gatekeepers():
    """gatekeeper npc id -> {destination name: (x, y, z, fee)} for the NORMAL (adena) lists."""
    out = {}
    for path in glob.glob(os.path.join(DATA, "teleporters", "town", "*.xml")):
        root = ET.parse(path).getroot()
        for npc in root.iter("npc"):
            routes = out.setdefault(int(npc.get("id")), {})
            for teleport in npc.iter("teleport"):
                if teleport.get("type") != "NORMAL":
                    continue
                for loc in teleport.iter("location"):
                    if loc.get("feeId") not in (None, "57"):
                        continue
                    routes[loc.get("name")] = (int(loc.get("x")), int(loc.get("y")), int(loc.get("z")), int(loc.get("feeCount") or 0))
    return out


# Map region files that are real towns (others such as DMZ, colosseum or the GM regions reuse town respawn points).
TOWN_REGION_FILES = ("_town.xml", "giran_habor.xml", "town_of_schuttgart.xml")

# Friendlier names for the region "town" labels the data uses.
TOWN_NAMES = {
    "Darkelven Town": "Dark Elf Village",
    "Elven Town": "Elven Village",
    "Dwarven Town": "Dwarven Village",
    "Orc Town": "Orc Village",
    "Talking Island Town": "Talking Island Village",
    "Giran Habor": "Giran Harbor",
    "Gludin Castle Town": "Gludin Village",
    "Gludio Castle Town": "Gludio",
    "Dion Castle Town": "Dion",
    "Giran Castle Town": "Giran",
    "Oren Castle Town": "Oren",
    "Aden Castle Town": "Aden",
    "Heine Town": "Heine",
    "Goddard Town": "Goddard",
    "Rune Town": "Rune",
    "Town of Schuttgart": "Schuttgart",
}


def load_regions():
    """Town regions as (town name, first non-chaotic respawn point)."""
    out = []
    for path in glob.glob(os.path.join(DATA, "mapregion", "*.xml")):
        if not path.endswith(TOWN_REGION_FILES):
            continue
        root = ET.parse(path).getroot()
        for region in root.iter("region"):
            town = region.get("town")
            points = [p for p in region.iter("respawnPoint") if p.get("isChaotic") != "true"]
            if town and points:
                p = points[0]
                out.append((TOWN_NAMES.get(town, town), (int(p.get("X")), int(p.get("Y")), int(p.get("Z")))))
    return out


def grocer_ids():
    """Merchants whose buy list sells Scrolls of Escape (buy list files are named <npc id><list number>)."""
    ids = set()
    for path in glob.glob(os.path.join(DATA, "buylists", "*.xml")):
        text = open(path, encoding="utf-8").read()
        if re.search(r'<item id="%d"' % SCROLL_OF_ESCAPE, text):
            npc = re.search(r'<npcs>\s*<npc>(\d+)</npc>', text)
            if npc:
                ids.add(int(npc.group(1)))
            else:
                ids.add(int(os.path.basename(path)[:5]))
    return ids


def class_masters():
    """npc id -> class-change script name, from the village master scripts (the masters also teach their skills)."""
    masters = {}
    for path in sorted(glob.glob(os.path.join(DATA, "scripts", "village_master", "*Change*", "*.java"))):
        script = os.path.splitext(os.path.basename(path))[0]
        block = re.search(r"int\[\] NPCS =\s*\{(.*?)\}", open(path, encoding="utf-8").read(), re.S)
        if block:
            for npc_id in re.findall(r"\b(\d{5})\b", block.group(1)):
                masters.setdefault(int(npc_id), script)
    return masters


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def pick_spots(center, spawns, npcs, lo, hi, radius):
    """Up to MAX_SPOTS monster spawn points near center whose level fits, spread out (farthest-point sampling)."""
    candidates = []
    for npc_id, x, y, z, _count in spawns:
        level, kind, _name = npcs[npc_id]
        if kind != "Monster":
            continue
        if (lo - LEVEL_SLACK) <= level <= (hi + LEVEL_SLACK) and dist((x, y), center) <= radius:
            candidates.append((x, y, z))
    if not candidates:
        return []
    candidates.sort(key=lambda p: dist(p, center))
    chosen = [candidates[0]]
    while len(chosen) < MAX_SPOTS:
        best = max(candidates, key=lambda p: min(dist(p, c) for c in chosen))
        if min(dist(best, c) for c in chosen) < 400:
            break
        chosen.append(best)
    return chosen


def zone_monsters(center, spawns, npcs, lo, hi, radius):
    """The monsters a bot hunting there meets, as {npc id: spawn count}, so cold bots earn from their real drop lists."""
    counts = {}
    for npc_id, x, y, _z, count in spawns:
        level, kind, _name = npcs[npc_id]
        if kind != "Monster":
            continue
        if (lo - LEVEL_SLACK) <= level <= (hi + LEVEL_SLACK) and dist((x, y), center) <= radius:
            counts[npc_id] = counts.get(npc_id, 0) + count
    return counts


def monster_lines(counts):
    return ['\t\t<monster npcId="%d" count="%d" />' % (npc_id, counts[npc_id]) for npc_id in sorted(counts)]


def main():
    npcs = load_npcs()
    spawns = load_spawns(npcs)
    gatekeepers = load_gatekeepers()
    regions = load_regions()
    grocers = grocer_ids()
    masters = class_masters()

    def nearest_region(point):
        return min(regions, key=lambda r: dist(r[1], point))

    positions = {}
    for npc_id, x, y, z, _count in spawns:
        positions.setdefault(npc_id, (x, y, z))

    # Towns: one per gatekeeper that has a spawn, named after the town region it stands in.
    towns = {}
    for npc_id in sorted(gatekeepers):
        if npc_id not in positions:
            continue
        pos = positions[npc_id]
        town, arrival = nearest_region(pos)
        if dist(arrival, pos) > 6000:
            continue
        entry = towns.setdefault(town, {"arrival": arrival, "gatekeepers": [], "grocers": [], "masters": {}})
        entry["gatekeepers"].append((npc_id, pos))
    for npc_id in sorted(grocers):
        if npc_id not in positions:
            continue
        pos = positions[npc_id]
        town, arrival = nearest_region(pos)
        if town in towns and dist(arrival, pos) <= 6000:
            towns[town]["grocers"].append((npc_id, pos))
    for npc_id in sorted(masters):
        if npc_id not in positions:
            continue
        pos = positions[npc_id]
        town, arrival = nearest_region(pos)
        if town in towns and dist(arrival, pos) <= 6000:
            towns[town]["masters"].setdefault(masters[npc_id], (npc_id, pos))

    def routes_to(destination):
        found = []
        for town, entry in towns.items():
            for npc_id, _pos in entry["gatekeepers"]:
                route = gatekeepers[npc_id].get(destination)
                if route:
                    found.append((town, route))
        return found

    problems = []
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             "<!-- GENERATED by tools/build_living_zones.py. Edit the ZONES list in that tool and re-run it. -->",
             "<zones>"]
    for town in sorted(towns):
        entry = towns[town]
        ax, ay, az = entry["arrival"]
        lines.append('\t<town name="%s" x="%d" y="%d" z="%d">' % (town, ax, ay, az))
        for npc_id, (x, y, z) in entry["gatekeepers"]:
            lines.append('\t\t<gatekeeper npcId="%d" x="%d" y="%d" z="%d" />' % (npc_id, x, y, z))
        for npc_id, (x, y, z) in entry["grocers"]:
            lines.append('\t\t<grocer npcId="%d" x="%d" y="%d" z="%d" />' % (npc_id, x, y, z))
        for script in sorted(entry["masters"]):
            npc_id, (x, y, z) = entry["masters"][script]
            lines.append('\t\t<master script="%s" npcId="%d" x="%d" y="%d" z="%d" />' % (script, npc_id, x, y, z))
        # Gatekeeper routes to other towns (a destination counts as a town when it lands near that town's arrival).
        hops = {}
        for npc_id, _pos in entry["gatekeepers"]:
            for (hx, hy, _hz, fee) in gatekeepers[npc_id].values():
                other = min(towns, key=lambda t: dist(towns[t]["arrival"], (hx, hy)))
                if other != town and dist(towns[other]["arrival"], (hx, hy)) <= 6000:
                    hops[other] = min(fee, hops.get(other, fee))
        for other in sorted(hops):
            lines.append('\t\t<route town="%s" fee="%d" />' % (other, hops[other]))
        lines.append("\t</town>")

    for name, race, center_dest, lo, hi in STARTER_ZONES:
        found = routes_to(center_dest)
        if not found:
            problems.append("starter zone %s: no gatekeeper route to %s" % (name, center_dest))
            continue
        cx, cy, cz, _fee = found[0][1]
        spots = pick_spots((cx, cy), spawns, npcs, lo, hi, STARTER_RADIUS)
        if not spots:
            problems.append("starter zone %s: no monster spawns found" % name)
            continue
        lines.append('\t<zone name="%s" minLevel="%d" maxLevel="%d" starterRace="%s">' % (name, lo, hi, race))
        for x, y, z in spots:
            lines.append('\t\t<spot x="%d" y="%d" z="%d" />' % (x, y, z))
        lines.extend(monster_lines(zone_monsters((cx, cy), spawns, npcs, lo, hi, STARTER_RADIUS)))
        lines.append("\t</zone>")

    for name, lo, hi in ZONES:
        found = routes_to(name)
        if not found:
            problems.append("zone %s: no gatekeeper route" % name)
            continue
        tx, ty, tz, _fee = found[0][1]
        spots = pick_spots((tx, ty), spawns, npcs, lo, hi, SPOT_RADIUS)
        if not spots:
            problems.append("zone %s: no monster spawns in its level range" % name)
            continue
        lines.append('\t<zone name="%s" minLevel="%d" maxLevel="%d">' % (name.replace("'", "&apos;"), lo, hi))
        for town, (x, y, z, fee) in sorted(found, key=lambda f: f[1][3]):
            lines.append('\t\t<teleport town="%s" x="%d" y="%d" z="%d" fee="%d" />' % (town, x, y, z, fee))
        for x, y, z in spots:
            lines.append('\t\t<spot x="%d" y="%d" z="%d" />' % (x, y, z))
        lines.extend(monster_lines(zone_monsters((tx, ty), spawns, npcs, lo, hi, SPOT_RADIUS)))
        lines.append("\t</zone>")
    lines.append("</zones>")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    print("Wrote %s: %d towns, %d zones." % (os.path.relpath(OUT, ROOT), len(towns), sum(1 for l in lines if l.startswith("\t<zone "))))
    for problem in problems:
        print("  skipped: " + problem)
    return 0


if __name__ == "__main__":
    sys.exit(main())
