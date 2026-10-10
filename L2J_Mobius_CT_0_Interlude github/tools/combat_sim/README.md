# Combat simulator and the Living Population zone data

Generates `dist/game/modules/living-population/data/zone_combat.tsv` (the cold bots' kill and death model) from the datapack. Pure Python 3, no dependencies. The project folder is found from this file's location; set `L2_PROJECT` to point elsewhere.

## Regenerating after a datapack change (monsters, spawns, drops, exp)
Run from this folder:

    python3 build_zone_monsters.py       # zone averages, respawns, aggression, exp, undead share   (reads the module's data/zones.xml + the NPC data)
    python3 build_zone_combat.py ../../dist/game/modules/living-population/data/zone_combat.tsv

This is the quick path and takes seconds. Running it on an unchanged datapack reproduces the committed `zone_combat.tsv` exactly.

## Regenerating after a change to skills, classes, items or gear
The rotation, heal, rest and buff rows come from the simulator and need the longer pipeline, in this order:

1. `build_curves.py` - gear curves per grade (reads `gear_*.csv`, `*_tiers.csv`).
2. `rotations.py "<Class Line>"` per class line (hours for the big ones; `python3 rotations.py Titan`) - writes `rotations_<line>.json`. These JSON files are about 110 MB in total and are NOT committed; the rows below that were derived from them are.
3. `build_rotation_table.py` -> `rotation_rows.tsv` (ROT / ROTSELF / SERV / ROTU rows), `build_heal_rows.py` -> `heal_rows.tsv`, `build_rest_table.py` -> `rest_rows.tsv` (via `rest_estimate.py`), `build_buff_factors.py`, `build_party_buffs.py`, `build_newbie_buffs.py`, `build_buffer_extras.py` -> the buff `.tsv` files.
4. `build_zone_combat.py` - concatenates all of the above into `zone_combat.tsv`.

`build_zone_combat.py` only reads the `.tsv` and `.csv` files committed here, so step 4 works without redoing steps 2-3 when only monsters changed.

## Other tools
- `compare_playstyles.py`, `playstyle_gain.py`, `propose_playstyle_updates.py` - compare the sim's best rotations with `PhantomPlaystyles.xml` and estimate the gain.
- `*.md` - the generated reports (`curves.md`, `zone_monsters.md`, `rest_estimate.md`, tier tables).
