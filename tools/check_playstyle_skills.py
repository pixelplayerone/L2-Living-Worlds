"""Validate PhantomPlaystyles.xml against the skill data: every <skill id> must exist, and must be learnable by at
least one class the playstyle covers (class skill trees). Exit code 1 on any failure.
Usage: python3 tools/check_playstyle_skills.py [path to the "dist/game/data" folder]
"""
import glob, os, sys
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "L2J_Mobius_CT_0_Interlude github", "dist", "game", "data")
skills = set()
for f in glob.glob(os.path.join(DATA, "stats", "skills", "*.xml")):
    for s in ET.parse(f).getroot().iter("skill"):
        skills.add(int(s.get("id")))
trees = {}
parent = {}
for c in ET.parse(os.path.join(DATA, "stats", "players", "classList.xml")).getroot().iter("class"):
    if c.get("parentClassId") is not None:
        parent[int(c.get("classId"))] = int(c.get("parentClassId"))
parent[104] = 28   # Elemental Master follows Elemental Summoner (classList.xml lists it under Elven Wizard)
def learnable(c, sid):
    while c is not None:
        if sid in trees.get(c, ()):
            return True
        c = parent.get(c)
    return False
for f in glob.glob(os.path.join(DATA, "stats", "players", "skillTrees", "*Class", "*.xml")):
    for st in ET.parse(f).getroot().iter("skillTree"):
        if st.get("type") == "classSkillTree":
            trees.setdefault(int(st.get("classId")), set()).update(int(s.get("skillId")) for s in st.findall("skill"))
bad = []
for ps in ET.parse(os.path.join(DATA, "PhantomPlaystyles.xml")).getroot().iter("playstyle"):
    ids = [int(x) for x in ps.get("classIds").replace(" ", "").split(",") if x]
    for s in ps.findall("skill"):
        sid = int(s.get("id"))
        if sid not in skills:
            bad.append("%s: skill %d (%s) does not exist" % (ps.get("name"), sid, s.get("name")))
        elif not any(learnable(c, sid) for c in ids):
            bad.append("%s: skill %d (%s) is not in the class skill tree of any covered class" % (ps.get("name"), sid, s.get("name")))
        lo, hi = s.get("minLevel"), s.get("maxLevel")
        if lo and hi and int(lo) > int(hi):
            bad.append("%s: skill %d minLevel %s > maxLevel %s" % (ps.get("name"), sid, lo, hi))
print("\n".join(bad) or "all playstyle skills valid")
sys.exit(1 if bad else 0)
