/*
 * Copyright (c) 2013 L2jMobius
 *
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingpop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zone combat for cold bots: how fast a bot kills in the zone it hunts, and how dangerous that zone is for it, from the
 * bot's own offence and defence against the average monster of the zone, instead of one flat rate for everybody.
 * <p>
 * Offence is the best weapon curve of its grade (P.Atk, or M.Atk for mages) times the level modifier, over the zone's
 * average defence; a mage's damage grows with the square root of M.Atk, like the game's magic formula. A kill takes
 * zone HP over that damage rate, plus a fixed overhead for everything else (finding the next mob, looting, sitting).
 * Defence is the best armor curve of the bot's grade and type against the zone's average P.Atk and M.Atk.
 * <p>
 * The model is calibrated, not absolute: for each role (tank, melee, archer, mage) a well-fitted reference bot (level in the
 * middle of the zone, gear of its level's grade, every skill) averages the configured kills per minute in the median zone and
 * the configured deaths per hour there, so the flat rates stay the baseline. What changes is how far above or below it a bot
 * sits: weaker gear, a missing grade, missing skills or a zone with tougher monsters for its level all move it. HP, HP
 * regeneration and resting are not modeled (resting is already handled by {@link ColdRisk}); calibration absorbs them.
 * Pure, so it is tested without a server.
 */
public final class ZoneCombat
{
	/** Roles with their own gear curves and calibration. */
	public enum Role
	{
		TANK, MELEE, BOW, MAGE
	}

	/**
	 * Tuning.
	 * @param enabled whether bots use the model (false = the flat rates)
	 * @param baseKillsPerMinute the flat rate, and the average of a fitted bot over the zones
	 * @param fightShare the share of one kill cycle (60 / base seconds) a fitted bot spends fighting; the rest is overhead
	 * @param skillFloor damage multiplier of a bot with none of the skills its level allows (1.0 with all of them)
	 * @param minKillsPerMinute lowest kill rate the model gives
	 * @param maxKillsPerMinute highest kill rate the model gives
	 * @param minDeathFactor lowest multiple of the base death rate
	 * @param maxDeathFactor highest multiple of the base death rate
	 * @param gearTierLevelStep levels per gear tier (a tier's grade is the grade of level tier x step), for bots that buy whole tiers
	 */
	public record Params(boolean enabled, double baseKillsPerMinute, double fightShare, double skillFloor, double minKillsPerMinute, double maxKillsPerMinute, double minDeathFactor, double maxDeathFactor, int gearTierLevelStep)
	{
		public static Params defaults()
		{
			return new Params(true, 12.0, 0.5, 0.5, 3.0, 24.0, 0.25, 4.0, 10);
		}
	}

	/** A zone's average monster. */
	public record ZoneStats(String name, int minLevel, int maxLevel, double mobLevel, double hp, double pDef, double mDef, double pAtk, double mAtk)
	{
		int midLevel()
		{
			return (minLevel + maxLevel) / 2;
		}
	}

	private static final int GRADES = 6;

	private final Params _params;
	private final Map<String, double[]> _curves;
	private final Map<String, Integer> _zoneIndex = new HashMap<>();
	private final List<ZoneStats> _zones;
	private final double[] _killScale = new double[Role.values().length]; // seconds of fighting per unit of raw time-to-kill
	private final double[] _threatMedian = new double[Role.values().length];
	private final Map<Long, Double> _killCache = new ConcurrentHashMap<>();
	private final Map<Long, Double> _deathCache = new ConcurrentHashMap<>();

	private ZoneCombat(Params params, Map<String, double[]> curves, List<ZoneStats> zones)
	{
		_params = params;
		_curves = curves;
		_zones = zones;
		for (int i = 0; i < zones.size(); i++)
		{
			_zoneIndex.put(zones.get(i).name(), i);
		}
		calibrate();
	}

	/** @return a model that does nothing: every lookup returns the flat rates */
	public static ZoneCombat off()
	{
		return new ZoneCombat(new Params(false, 12.0, 0.5, 0.5, 3.0, 24.0, 0.25, 4.0, 10), Map.of(), List.of());
	}

	/**
	 * @param reader the data file (the generator's {@code zone_combat.tsv}: CURVE and ZONE rows)
	 * @param params tuning
	 * @return the model; without usable data it is switched off
	 * @throws IOException on a read error
	 */
	public static ZoneCombat parse(Reader reader, Params params) throws IOException
	{
		final Map<String, double[]> curves = new HashMap<>();
		final List<ZoneStats> zones = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(reader))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				if (line.isEmpty() || (line.charAt(0) == '#'))
				{
					continue;
				}
				final String[] f = line.split("\t", -1);
				try
				{
					if (f[0].equals("CURVE") && (f.length >= (2 + GRADES)))
					{
						final double[] values = new double[GRADES];
						for (int g = 0; g < GRADES; g++)
						{
							values[g] = Double.parseDouble(f[2 + g]);
						}
						curves.put(f[1], values);
					}
					else if (f[0].equals("ZONE") && (f.length >= 10))
					{
						zones.add(new ZoneStats(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9])));
					}
				}
				catch (RuntimeException e)
				{
					// skip a bad line
				}
			}
		}
		final boolean usable = !zones.isEmpty() && curves.keySet().containsAll(List.of("patk_melee", "patk_bow", "matk_mage", "pdef_tank", "pdef_melee", "pdef_light", "pdef_robe", "mdef_heavy", "mdef_light", "mdef_robe"));
		final Params use = usable ? params : new Params(false, params.baseKillsPerMinute(), params.fightShare(), params.skillFloor(), params.minKillsPerMinute(), params.maxKillsPerMinute(), params.minDeathFactor(), params.maxDeathFactor(), params.gearTierLevelStep());
		return new ZoneCombat(use, curves, zones);
	}

	/**
	 * @param classId a class id
	 * @return its role
	 */
	public static Role roleOf(int classId)
	{
		if (LivingSupplies.isTank(classId))
		{
			return Role.TANK;
		}
		if (LivingSupplies.isLight(classId))
		{
			return isArcher(classId) ? Role.BOW : Role.MAGE;
		}
		return Role.MELEE;
	}

	private static boolean isArcher(int classId)
	{
		return (classId == 9) || (classId == 24) || (classId == 37) || (classId == 92) || (classId == 102) || (classId == 109);
	}

	/**
	 * @param tier a whole-tier gear tier
	 * @param step levels per tier
	 * @return its grade (0 to 5)
	 */
	public static int gradeOfTier(int tier, int step)
	{
		return (step <= 0) ? 0 : LivingSupplies.gradeFor(Math.max(0, tier) * step);
	}

	/**
	 * @param tree a class's skill tree
	 * @param known the skills the bot knows (skill id to level), or null when its skills are not tracked
	 * @param level its level
	 * @return the share of the skill levels its level allows (and a shop or the game can give) that it has learned
	 */
	public static double skillFraction(List<SkillPlanner.Entry> tree, Map<Integer, Integer> known, int level)
	{
		if ((known == null) || (tree == null) || tree.isEmpty())
		{
			return 1.0;
		}
		int allowed = 0;
		int have = 0;
		for (SkillPlanner.Entry entry : tree)
		{
			if ((entry.minLevel() > level) || entry.unsold())
			{
				continue;
			}
			allowed++;
			final Integer got = known.get(entry.skillId());
			if ((got != null) && (got >= entry.level()))
			{
				have++;
			}
		}
		return (allowed == 0) ? 1.0 : ((double) have / allowed);
	}

	/** @return levels per gear tier */
	public int tierStep()
	{
		return _params.gearTierLevelStep();
	}

	/** @return whether the model is active (on and with data) */
	public boolean enabled()
	{
		return _params.enabled();
	}

	/** @return whether the model knows this zone */
	public boolean knows(String zone)
	{
		return _params.enabled() && (zone != null) && _zoneIndex.containsKey(zone);
	}

	/** @return the number of zones */
	public int zoneCount()
	{
		return _zones.size();
	}

	/**
	 * @param zone the zone it hunts in
	 * @param classId its class
	 * @param level its level
	 * @param weaponGrade the grade of its weapon (0 no grade, 1 D, 2 C, 3 B, 4 A, 5 S)
	 * @param armorGrade the grade of its armor
	 * @param skillFraction the share of the skills its level allows that it has learned (0 to 1)
	 * @return kills per minute: the flat rate when the model is off or does not know the zone
	 */
	public double killsPerMinute(String zone, int classId, int level, int weaponGrade, int armorGrade, double skillFraction)
	{
		final Integer zi = knows(zone) ? _zoneIndex.get(zone) : null;
		if (zi == null)
		{
			return _params.baseKillsPerMinute();
		}
		final Role role = roleOf(classId);
		final int skills = (int) Math.round(Math.max(0.0, Math.min(1.0, skillFraction)) * 10.0);
		final long key = ((((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8L + clamp(weaponGrade)) * 8L + clamp(armorGrade)) * 16L + skills;
		return _killCache.computeIfAbsent(key, k ->
		{
			final double fightSeconds = _killScale[role.ordinal()] * rawTimeToKill(_zones.get(zi), role, level, clamp(weaponGrade), skills / 10.0);
			final double overhead = (60.0 / _params.baseKillsPerMinute()) * (1.0 - _params.fightShare());
			return Math.max(_params.minKillsPerMinute(), Math.min(_params.maxKillsPerMinute(), 60.0 / (overhead + fightSeconds)));
		});
	}

	/**
	 * @param zone the zone it hunts in
	 * @param classId its class
	 * @param armorGrade the grade of its armor
	 * @return the multiple of the base death rate for this zone and armor (1.0 when the model is off or does not know the zone)
	 */
	public double deathFactor(String zone, int classId, int armorGrade)
	{
		final Integer zi = knows(zone) ? _zoneIndex.get(zone) : null;
		if (zi == null)
		{
			return 1.0;
		}
		final Role role = roleOf(classId);
		final long key = ((zi * 4L) + role.ordinal()) * 8L + clamp(armorGrade);
		return _deathCache.computeIfAbsent(key, k ->
		{
			final double mean = _threatMedian[role.ordinal()];
			return (mean <= 0) ? 1.0 : Math.max(_params.minDeathFactor(), Math.min(_params.maxDeathFactor(), threat(_zones.get(zi), role, clamp(armorGrade)) / mean));
		});
	}

	/** Per role: scale the fitted reference bot's fight time in the median zone to the target share, and take its threat there as one. */
	private void calibrate()
	{
		if (!_params.enabled() || _zones.isEmpty())
		{
			return;
		}
		final double targetSeconds = (60.0 / _params.baseKillsPerMinute()) * _params.fightShare();
		for (Role role : Role.values())
		{
			final double[] raw = new double[_zones.size()];
			final double[] threats = new double[_zones.size()];
			for (int i = 0; i < _zones.size(); i++)
			{
				final ZoneStats zone = _zones.get(i);
				final int level = Math.max(1, zone.midLevel());
				final int grade = LivingSupplies.gradeFor(level);
				raw[i] = rawTimeToKill(zone, role, level, grade, 1.0);
				threats[i] = threat(zone, role, grade);
			}
			// The median zone is the anchor, so a few extreme zones (newbie grounds, the highest levels) do not pull the baseline.
			final double medianRaw = median(raw);
			_killScale[role.ordinal()] = (medianRaw <= 0) ? 1.0 : (targetSeconds / medianRaw);
			_threatMedian[role.ordinal()] = median(threats);
		}
	}

	private static double median(double[] values)
	{
		final double[] sorted = values.clone();
		java.util.Arrays.sort(sorted);
		final int n = sorted.length;
		return (n == 0) ? 0.0 : ((n % 2 == 1) ? sorted[n / 2] : ((sorted[(n / 2) - 1] + sorted[n / 2]) / 2.0));
	}

	/** Zone HP over the bot's damage rate against the zone's defence, in arbitrary units (calibration turns them into seconds). */
	private double rawTimeToKill(ZoneStats zone, Role role, int level, int weaponGrade, double skillFraction)
	{
		final double levelMod = (level + 89.0) / 100.0;
		final double skill = _params.skillFloor() + ((1.0 - _params.skillFloor()) * skillFraction);
		final double rate;
		if (role == Role.MAGE)
		{
			rate = Math.sqrt(Math.max(1.0, curve("matk_mage", weaponGrade) * levelMod)) / Math.max(1.0, zone.mDef());
		}
		else
		{
			final double attack = curve(role == Role.BOW ? "patk_bow" : "patk_melee", weaponGrade) * levelMod;
			rate = attack / Math.max(1.0, zone.pDef());
		}
		return zone.hp() / Math.max(1e-9, rate * skill);
	}

	/** How hard the zone's monsters hit a bot of this role in armor of this grade: their attack over its defence. */
	private double threat(ZoneStats zone, Role role, int armorGrade)
	{
		final String pdef = (role == Role.TANK) ? "pdef_tank" : (role == Role.MELEE) ? "pdef_melee" : (role == Role.BOW) ? "pdef_light" : "pdef_robe";
		final String mdef = (role == Role.MAGE) ? "mdef_robe" : (role == Role.BOW) ? "mdef_light" : "mdef_heavy";
		final double physical = zone.pAtk() / Math.max(1.0, curve(pdef, armorGrade));
		final double magical = zone.mAtk() / Math.max(1.0, curve(mdef, armorGrade));
		return Math.max(physical, magical);
	}

	private double curve(String name, int grade)
	{
		final double[] values = _curves.get(name);
		return (values == null) ? 1.0 : values[clamp(grade)];
	}

	private static int clamp(int grade)
	{
		return Math.max(0, Math.min(GRADES - 1, grade));
	}
}
