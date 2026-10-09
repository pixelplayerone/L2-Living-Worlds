"""Checks the builder's monster stats against what the in-game NPC window shows. Add a row for every monster you inspect in game.
Usage: python3 check_monster_stats.py   (exits 1 when a stat is off by more than 1.5% plus one unit)"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = [sys.argv[0]]
src = open(os.path.join(HERE, "build_zone_monsters.py"), encoding="utf-8").read().split("root = ET.parse(zones_file)")[0]
exec(compile(src, "build_zone_monsters", "exec"))
# npc id: (name, hp, patk, pdef, matk, mdef, atk speed) as the NPC window shows them
WINDOW = {
    20592: ("Satyr", 6275, 411, 287, 192, 212, 278),
    20534: ("Red Keltir", 75, 9, 41, 3, 30, 278),
    20034: ("Prowler", 446, 47, 75, 20, 55, 278),
    20210: ("Ol Mahum Sgt", 1812, 110, 149, 44, 109, 278),
    20804: ("Crokian Lad", 3186, 291, 208, 140, 152, 278),
    20833: ("Zaken Archer", 1799, 269, 260, 99, 225, 278),
    20669: ("Taik Supply", 2643, 737, 292, 436, 215, 278),
    18119: ("Deadman Corpse", 3302, 986, 398, 559, 266, 278),
    21352: ("Graz Antelope", 9027, 1415, 559, 728, 588, 278),
    21356: ("Graz Nepenthes", 9398, 1489, 674, 776, 602, 278),
}
bad = 0
for nid, (name, hp, patk, pdef, matk, mdef, aspd) in WINDOW.items():
    n = npcs[nid]
    for label, want, got in (("HP", hp, n["hp"]), ("P.Atk", patk, n["patk"]), ("P.Def", pdef, n["pdef"]), ("M.Atk", matk, n["matk"]), ("M.Def", mdef, n["mdef"]), ("Atk.Speed", aspd, n["aspd"])):
        off = max(0.0, abs(got - want) - 1.0) / want      # the window shows whole numbers (cut, not rounded): allow one unit
        print("%-10s %-10s window %7.0f  builder %7.0f  %s" % (name, label, want, got, "ok" if off <= 0.015 else "OFF %.1f%%" % (100 * off)))
        bad += off > 0.015
sys.exit(1 if bad else 0)
