"""SP priority rows for zone_combat.tsv: the order a cold bot learns skills, from sp_priority.csv (run sp_priority.py first).

SP  classId  <tier letter><skillId>:<level>,...   one row per class, best first, tiers A, B and C only. A bot saves SP for a tier A skill and never
buys a lower one while it waits; a skill not listed (tier skip) is learned last, cheapest first. Usage: python3 build_sp_priority_rows.py [out.tsv]"""
import csv, collections, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "sp_priority_rows.tsv")
rows = collections.defaultdict(list)
for r in csv.DictReader(open(os.path.join(HERE, "sp_priority.csv"), encoding="utf-8")):
    if r["tier"] in ("A", "B", "C"):
        rows[int(r["class_id"])].append((int(r["priority"]), "%s%s:%s" % (r["tier"], r["skill_id"], r["level"])))
with open(OUT, "w", encoding="utf-8", newline="") as f:
    f.write("#SP\tclassId\ttier+skillId:level, best first   (SP priority; see tools/combat_sim/sp_priority.md)\n")
    for c in sorted(rows):
        f.write("SP\t%d\t%s\n" % (c, ",".join(t for _, t in sorted(rows[c]))))
print(len(rows), "classes ->", OUT)
