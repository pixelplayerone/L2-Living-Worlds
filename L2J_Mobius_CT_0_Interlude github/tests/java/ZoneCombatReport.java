import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.l2jmobius.gameserver.livingpop.ColdRisk;
import org.l2jmobius.gameserver.livingpop.LivingSupplies;
import org.l2jmobius.gameserver.livingpop.ZoneCombat;

/**
 * Prints the cold-state model against the flat one for sample bots: kills per minute, experience per hour (per 1x rate, at
 * 13 exp per mob level) and deaths per hour. Usage: java ZoneCombatReport path/to/zone_combat.tsv
 */
public class ZoneCombatReport
{
	// class ids (Interlude): 6 a tank, 2 Gladiator (melee), 9 Hawkeye-line archer, 10 Human Mystic (mage)
	private static final int TANK = 90;
	private static final int MELEE = 88;
	private static final int BOW = 92;
	private static final int MAGE = 94;

	public static void main(String[] args) throws Exception
	{
		final ZoneCombat.Params params = ZoneCombat.Params.defaults();
		final ZoneCombat model;
		try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8))
		{
			model = ZoneCombat.parse(reader, params);
		}
		final ColdRisk.Params risk = new ColdRisk.Params(0.3, 90_000L, 600_000L, 60_000L, 3_600_000L);
		model.setAggroRisk(1.0);
		model.setRotationTtk(true);
		model.setRest(true);
		model.setEvasion(true);
		model.setStartingBuffs(true);
		final String[][] zones = { { "Talking Island newbie grounds", "5" }, { "Cruma Tower", "45" }, { "Blazing Swamp", "72" } };
		levelAverages(model, risk, args[1], args[2]);
		System.out.println();
		classTable(model, args[1], args[2]);
		System.out.println();
		rotationCompare(model);
		model.setRotationTtk(true);
		System.out.println();
		levelAveragesBack(model, risk);
		System.out.println();
		System.out.println("| zone | role | bot | kills/min old>new | deaths/hr old>new | exp/hr old (L*13) | exp/hr new | exp/hr new+ZoneExp |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		for (String[] z : zones)
		{
			final int level = Integer.parseInt(z[1]);
			final int grade = LivingSupplies.gradeFor(level);
			for (int c : new int[] { TANK, MELEE, BOW, MAGE })
			{
				final String name = ZoneCombat.roleOf(c).name().toLowerCase();
				row(model, risk, z[0], name, "fitted", c, level, grade, 1.0, 0);
				row(model, risk, z[0], name, "1 grade behind", c, level, Math.max(0, grade - 1), 1.0, 1);
				row(model, risk, z[0], name, "no skills", c, level, grade, 0.0, 0);
			}
		}
	}

	private static void row(ZoneCombat model, ColdRisk.Params risk, String zone, String role, String bot, int classId, int level, int grade, double skills, int behind)
	{
		final double flatKills = 12.0;
		double kills = model.killsPerMinute(zone, classId, level, grade, grade, skills, 1.0);
		kills = Math.max(0.3, Math.min(kills, model.respawnCap(zone, 4, 0.5)));   // 4 bots sharing the zone
		final double flatDeaths = ColdRisk.danger(risk, level, level - 3, level + 3, 20, 5 - behind, 5, classId).deathsPerHour();
		final double deaths = ColdRisk.danger(risk, level, level - 3, level + 3, 20, 5 - behind, 5, classId, model.deathFactor(zone, classId, level, grade)).deathsPerHour();
		System.out.printf("| %s | %s | %s | %.1f > %.1f | %.2f > %.2f | %.0f | %.0f | %.0f |%n", zone, role, bot, flatKills, kills, flatDeaths, deaths, level * 13.0 * flatKills * 60, level * 13.0 * kills * 60, model.expPerKill(zone) * kills * 60);
	}

	private static java.util.Map<Integer, Double> readTable(String path, String attr) throws Exception
	{
		final java.util.Map<Integer, Double> out = new java.util.HashMap<>();
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("level=\"(\\d+)\"[^>]*?" + attr + "=\"([\\d.]+)\"").matcher(Files.readString(Path.of(path)));
		while (m.find())
		{
			out.put(Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2)));
		}
		return out;
	}

	/** Averages over the zones whose level range holds the level and over the four roles, a fitted bot (curve gear of its grade, all skills, shots, potions), 4 bots per zone. */
	private static void levelAverages(ZoneCombat model, ColdRisk.Params risk, String lossXml, String expXml) throws Exception
	{
		final java.util.Map<Integer, Double> loss = readTable(lossXml, "val");
		final java.util.Map<Integer, Double> total = readTable(expXml, "tolevel");
		System.out.println("| level | zones | kills/min old > new | deaths/hr old > new | exp/hr old (L*13) | exp/hr new | exp/hr new + ZoneExp | net after death loss |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		final StringBuilder hours = new StringBuilder("| level | exp needed | flat | ZoneExp | ZoneExp net of deaths |\n|---|---|---|---|---|\n");
		for (int level : new int[] { 1, 20, 40, 52, 61, 76, 80 })
		{
			final int grade = LivingSupplies.gradeFor(level);
			double kills = 0, deaths = 0, zoneExp = 0, flatDeaths = 0;
			int n = 0, zonesUsed = 0;
			for (ZoneCombat.ZoneStats z : model.zones())
			{
				if ((level < z.minLevel()) || (level > z.maxLevel()))
				{
					continue;
				}
				zonesUsed++;
				for (int c : new int[] { TANK, MELEE, BOW, MAGE })
				{
					final ZoneCombat.Stats st = model.curveStats(ZoneCombat.roleOf(c), grade, grade);
					final double k = Math.max(0.3, Math.min(model.killsPerMinute(z.name(), c, level, st, 1.0, 1.0, false, 4.0), model.respawnCap(z.name(), 4, 0.5)));
					kills += k;
					zoneExp += k * z.expPerKill();
					deaths += ColdRisk.danger(risk, level, level - 3, level + 3, 20, 5, 5, c, model.deathFactor(z.name(), c, level, st)).deathsPerHour();
					flatDeaths += ColdRisk.danger(risk, level, level - 3, level + 3, 20, 5, 5, c).deathsPerHour();
					n++;
				}
			}
			if (n == 0)
			{
				System.out.println("| " + level + " | 0 | no zone covers this level | | | | | |");
				continue;
			}
			final double span = (level >= 80) ? 0 : (total.get(level + 1) - total.get(level));
			final double zoneHr = (zoneExp / n) * 60;
			final double lost = (deaths / n) * (loss.get(level) / 100.0) * span;
			final double net = zoneHr - lost;
			System.out.printf("| %d | %d | 12.0 > %.1f | %.2f > %.2f | %.0f | %.0f | %.0f | %.0f |%n", level, zonesUsed, kills / n, flatDeaths / n, deaths / n, level * 13.0 * 12 * 60, level * 13.0 * (kills / n) * 60, zoneHr, net);
			if (span > 0)
			{
				hours.append(String.format("| %d | %.0f | %.1f h | %.1f h | %s |%n", level, span, span / (level * 13.0 * 12 * 60), span / zoneHr, (net <= 0) ? "never" : String.format("%.1f h", span / net)));
			}
		}
		System.out.println();
		System.out.print(hours);
	}

	/** Solo against the virtual party for classes the four-role averages leave out: dagger, summoner, healer, and a melee and a tank for reference (fitted gear, ZoneExp, death exp loss). */
	private static void classTable(ZoneCombat model, String lossXml, String expXml) throws Exception
	{
		final java.util.Map<Integer, Double> loss = readTable(lossXml, "val");
		final java.util.Map<Integer, Double> total = readTable(expXml, "tolevel");
		final Object[][] classes = { { "Dagger (Adventurer)", 93 }, { "Summoner (Arcana Lord)", 96 }, { "Healer (Cardinal)", 97 }, { "Melee (Duelist)", 88 }, { "Tank (Phoenix Knight)", 90 } };
		System.out.println("| level | class | solo kills/min | solo deaths/hr | solo net exp/hr | party kills/min | party deaths/hr | party net exp/hr (a quarter) |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		for (int level : new int[] { 20, 40, 61, 76 })
		{
			final int grade = LivingSupplies.gradeFor(level);
			final double span = total.get(level + 1) - total.get(level);
			final double lossPerDeath = (loss.get(level) / 100.0) * span;
			for (Object[] c : classes)
			{
				final int id = (Integer) c[1];
				double sk = 0, sd = 0, sn = 0, pk = 0, pd = 0, pn = 0;
				int n = 0;
				for (ZoneCombat.ZoneStats z : model.zones())
				{
					if ((level < z.minLevel()) || (level > z.maxLevel()))
					{
						continue;
					}
					final ZoneCombat.Stats st = model.curveStats(ZoneCombat.roleOf(id), grade, grade);
					final double k = Math.max(0.3, Math.min(model.killsPerMinute(z.name(), id, level, st, 1.0, 1.0, false, 4.0), model.respawnCap(z.name(), 4, 0.5)));
					final double d = 0.3 * model.deathFactor(z.name(), id, level, st);
					double ppk = 0, ppd = 0, ppn = 0;
					for (int v = 0; v < 12; v++)
					{
						final ZoneCombat.PartyOutcome o = model.party(z.name(), id, level, st, 1.0, 1.0, false, v * 7);
						if (o == null)
						{
							continue;
						}
						ppk += o.killsPerMinute() / 12;
						ppd += 0.3 * o.deathFactor() / 12;
						ppn += ((o.killsPerMinute() * 60.0 * z.expPerKill() * o.expShare()) - (0.3 * o.deathFactor() * lossPerDeath)) / 12;
					}
					sk += k;
					sd += d;
					sn += k * 60.0 * z.expPerKill() - d * lossPerDeath;
					pk += ppk;
					pd += ppd;
					pn += ppn;
					n++;
				}
				if (n > 0)
				{
					System.out.printf("| %d | %s | %.1f | %.2f | %.0f | %.1f | %.2f | %.0f |%n", level, c[0], sk / n, sd / n, sn / n, pk / n, pd / n, pn / n);
				}
			}
		}
	}

	/** Same averages with gear a grade back: starter gear at 1, then top of the grade below the level's (S at 80). */
	static void levelAveragesBack(ZoneCombat model, ColdRisk.Params risk)
	{
		System.out.println("| level | gear | zones | kills/min old > new | deaths/hr old > new | exp/hr old (L*13) | exp/hr new | exp/hr new + ZoneExp |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		final int[] levels = { 1, 20, 40, 52, 61, 76, 80 };
		final int[] grades = { -1, 0, 1, 2, 3, 4, 5 };
		final String[] names = { "starter", "top NG", "top D", "top C", "top B", "top A", "top S" };
		for (int li = 0; li < levels.length; li++)
		{
			final int level = levels[li];
			final int behind = (li == 0 || li == 6) ? 0 : 1;
			double kills = 0, deaths = 0, zoneExp = 0, flatDeaths = 0;
			int n = 0, zonesUsed = 0;
			for (ZoneCombat.ZoneStats z : model.zones())
			{
				if ((level < z.minLevel()) || (level > z.maxLevel()))
				{
					continue;
				}
				zonesUsed++;
				for (int c : new int[] { TANK, MELEE, BOW, MAGE })
				{
					final ZoneCombat.Role role = ZoneCombat.roleOf(c);
					final ZoneCombat.Stats st = (grades[li] >= 0) ? model.curveStats(role, grades[li], grades[li]) : starter(role);
					final double k = Math.max(0.3, Math.min(model.killsPerMinute(z.name(), c, level, st, 1.0, 1.0), model.respawnCap(z.name(), 4, 0.5)));
					kills += k;
					zoneExp += k * z.expPerKill();
					deaths += ColdRisk.danger(risk, level, level - 3, level + 3, 20, 5 - behind, 5, c, model.deathFactor(z.name(), c, level, st)).deathsPerHour();
					flatDeaths += ColdRisk.danger(risk, level, level - 3, level + 3, 20, 5 - behind, 5, c).deathsPerHour();
					n++;
				}
			}
			System.out.printf("| %d | %s | %d | 12.0 > %.1f | %.2f > %.2f | %.0f | %.0f | %.0f |%n", level, names[li], zonesUsed, kills / n, flatDeaths / n, deaths / n, level * 13.0 * 12 * 60, level * 13.0 * (kills / n) * 60, (zoneExp / n) * 60);
		}
	}

	/** Starting gear: Short Sword P.Atk 8 / Short Bow 16 / Apprentice's Rod M.Atk 8, naked armor values (fighter 84 with the starter shirt and pants, mystic 54), naked jewelry 41. */
	private static ZoneCombat.Stats starter(ZoneCombat.Role role)
	{
		return switch (role)
		{
			case MAGE -> new ZoneCombat.Stats(8, 54, 41);
			case BOW -> new ZoneCombat.Stats(16, 84, 41);
			default -> new ZoneCombat.Stats(8, 84, 41);
		};
	}

	/** Kills/min of the relative model (calibrated to 12) against the rotation model (real seconds), fitted third-class bots: Phoenix Knight 90, Duelist 88, Sagittarius 92, Archmage 94. */
	static void rotationCompare(ZoneCombat model)
	{
		System.out.println("| level | zones | tank rel > rot | melee rel > rot | bow rel > rot | mage rel > rot | avg rel > rot |");
		System.out.println("|---|---|---|---|---|---|---|");
		final int[] classes = { 90, 88, 92, 94 };
		for (int level : new int[] { 1, 20, 40, 52, 61, 76, 80 })
		{
			final int grade = LivingSupplies.gradeFor(level);
			final double[] rel = new double[4], rot = new double[4];
			int zones = 0;
			for (ZoneCombat.ZoneStats z : model.zones())
			{
				if ((level < z.minLevel()) || (level > z.maxLevel()))
				{
					continue;
				}
				zones++;
				for (int r = 0; r < 4; r++)
				{
					final ZoneCombat.Stats st = model.curveStats(ZoneCombat.roleOf(classes[r]), grade, grade);
					model.setRotationTtk(false);
					rel[r] += Math.max(0.3, Math.min(model.killsPerMinute(z.name(), classes[r], level, st, 1.0, 1.0), model.respawnCap(z.name(), 4, 0.5)));
					model.setRotationTtk(true);
					rot[r] += Math.max(0.3, Math.min(model.killsPerMinute(z.name(), classes[r], level, st, 1.0, 1.0), model.respawnCap(z.name(), 4, 0.5)));
				}
			}
			model.setRotationTtk(false);
			double ra = 0, oa = 0;
			final StringBuilder sb = new StringBuilder("| " + level + " | " + zones + " |");
			for (int r = 0; r < 4; r++)
			{
				sb.append(String.format(" %.1f > %.1f |", rel[r] / zones, rot[r] / zones));
				ra += rel[r] / zones / 4;
				oa += rot[r] / zones / 4;
			}
			System.out.println(sb + String.format(" %.1f > %.1f |", ra, oa));
		}
	}
}
