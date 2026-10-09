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
	private static final int TANK = 6;       // Temple Knight line (a tank id in LivingSupplies)
	private static final int MELEE = 2;      // Gladiator line
	private static final int BOW = 9;
	private static final int MAGE = 10;

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
		final String[][] zones = { { "Talking Island newbie grounds", "5" }, { "Cruma Tower", "45" }, { "Blazing Swamp", "72" } };
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
}
