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

	/**
	 * A zone's average monster, and what the zone can supply.
	 * @param respawnPerMinute monsters the whole zone can supply per minute (each spawn returns after its respawn delay); 0 = unknown
	 * @param spots how many hunting spots the zone has
	 * @param aggressivePercent the share of its spawns that attack on sight, in percent
	 * @param expPerKill the average experience a kill gives, before the server's XP rate; 0 = unknown
	 */
	public record ZoneStats(String name, int minLevel, int maxLevel, double mobLevel, double hp, double pDef, double mDef, double pAtk, double mAtk, double respawnPerMinute, int spots, double aggressivePercent, double expPerKill)
	{
		int midLevel()
		{
			return (minLevel + maxLevel) / 2;
		}
	}

	/**
	 * What a bot's worn gear gives: the weapon's attack (P.Atk, or M.Atk for a mage), total P.Def of its armor and shield, total M.Def of its jewelry.
	 * @param attack the weapon's P.Atk or M.Atk
	 * @param pDef P.Def of armor and shield (empty slots at their naked values)
	 * @param mDef M.Def of the jewelry (empty slots at their naked values)
	 */
	public record Stats(double attack, double pDef, double mDef)
	{
	}

	private static final int GRADES = 6;
	private static final int MAX_BUFF_LEVEL = 90;

	private final Params _params;
	private final Map<String, double[]> _curves;
	private final Map<String, Integer> _zoneIndex = new HashMap<>();
	private final List<ZoneStats> _zones;
	private final double[] _killScale = new double[Role.values().length]; // seconds of fighting per unit of raw time-to-kill
	private final double[] _threatMedian = new double[Role.values().length];
	private final Map<Role, double[][]> _buffs = new java.util.EnumMap<>(Role.class); // role -> {damage, pDef, mDef} multipliers by level, full buffer party
	private volatile double _aggroRisk; // extra death multiple in a zone where every monster attacks on sight (0 = off)
	private volatile double _soulshotDamage = 2.0; // damage with soulshots over without (auto-attack: P.Atk x2)
	private volatile double _spiritshotDamage = Math.sqrt(2.0); // damage with spiritshots over without (M.Atk x2, damage grows with its square root)
	private volatile double[] _buffShare = new double[Role.values().length]; // per role: 0 no buffs, 1 the full party
	private final Map<Long, Double> _killCache = new ConcurrentHashMap<>();
	private final Map<Long, Double> _deathCache = new ConcurrentHashMap<>();

	private ZoneCombat(Params params, Map<String, double[]> curves, List<ZoneStats> zones, Map<Role, double[][]> buffs)
	{
		_params = params;
		_buffs.putAll(buffs);
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
		return new ZoneCombat(new Params(false, 12.0, 0.5, 0.5, 3.0, 24.0, 0.25, 4.0, 10), Map.of(), List.of(), Map.of());
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
		final Map<Role, double[][]> buffs = new java.util.EnumMap<>(Role.class);
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
					else if (f[0].equals("BUFF") && (f.length >= 6))
					{
						final Role role = Role.valueOf(f[1].toUpperCase(java.util.Locale.ROOT));
						final int level = Integer.parseInt(f[2]);
						final double[][] table = buffs.computeIfAbsent(role, r -> new double[3][MAX_BUFF_LEVEL + 1]);
						if ((level >= 0) && (level <= MAX_BUFF_LEVEL))
						{
							table[0][level] = Double.parseDouble(f[3]);
							table[1][level] = Double.parseDouble(f[4]);
							table[2][level] = Double.parseDouble(f[5]);
						}
					}
					else if (f[0].equals("ZONE") && (f.length >= 10))
					{
						final boolean more = f.length >= 14; // older data files stop at M.Atk, or before the experience
						zones.add(new ZoneStats(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9]), more ? Double.parseDouble(f[10]) : 0.0, more ? Integer.parseInt(f[11]) : 0, more ? Double.parseDouble(f[12]) : 0.0, more ? Double.parseDouble(f[13]) : 0.0));
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
		return new ZoneCombat(use, curves, zones, buffs);
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

	/**
	 * Emulates buffers keeping the bots buffed while they level. Each role has a share from 0 (no buffs) to 1 (the full
	 * buffer party of its level: Might, Haste, the songs and dances, Shield ...); a share blends the full multipliers in
	 * proportionally. Needs the BUFF rows of the data file; without them the shares do nothing. Calibration stays unbuffed, so
	 * buffs lift a bot above the baseline.
	 * @param shares per {@link Role} ordinal (TANK, MELEE, BOW, MAGE), each clamped to 0..1
	 */
	public void setBuffShares(double[] shares)
	{
		final double[] use = new double[Role.values().length];
		for (int i = 0; (i < use.length) && (shares != null) && (i < shares.length); i++)
		{
			use[i] = Math.max(0.0, Math.min(1.0, shares[i]));
		}
		_buffShare = use;
		_killCache.clear();
		_deathCache.clear();
	}

	/**
	 * Sets how much the shots add. The calibration assumes shots, so a bot without them does that much less damage.
	 * @param soulshot damage with soulshots over without, for physical roles (at least 1)
	 * @param spiritshot damage with spiritshots over without, for mages (at least 1)
	 */
	public void setShotDamage(double soulshot, double spiritshot)
	{
		_soulshotDamage = Math.max(1.0, soulshot);
		_spiritshotDamage = Math.max(1.0, spiritshot);
		_killCache.clear();
	}

	/**
	 * Things go wrong where the monsters attack on sight: they come in packs and pull while the bot fights. The death rate
	 * gets {@code 1 + risk x aggressive share} on top of the gear model.
	 * @param risk the extra death multiple in a zone where every monster is aggressive (0 = off)
	 */
	public void setAggroRisk(double risk)
	{
		_aggroRisk = Math.max(0.0, risk);
		_deathCache.clear();
	}

	/**
	 * The most kills per minute a bot can get from the zone's respawns. The zone supplies a fixed number of monsters per
	 * minute and its bots share them.
	 * @param zone the zone
	 * @param occupants bots in the zone, this one included
	 * @param usableShare the share of the zone's spawns a bot can practically reach (0 to 1)
	 * @return kills per minute, or infinity when the zone's respawns are unknown
	 */
	public double respawnCap(String zone, int occupants, double usableShare)
	{
		final Integer zi = (zone == null) ? null : _zoneIndex.get(zone);
		if (zi == null)
		{
			return Double.POSITIVE_INFINITY;
		}
		final ZoneStats z = _zones.get(zi);
		if (z.respawnPerMinute() <= 0.0)
		{
			return Double.POSITIVE_INFINITY;
		}
		return (z.respawnPerMinute() * Math.max(0.0, Math.min(1.0, usableShare))) / Math.max(1, occupants);
	}

	/** @return levels per gear tier */
	public int tierStep()
	{
		return _params.gearTierLevelStep();
	}

	/**
	 * @param zone a zone name
	 * @return the average experience a kill gives there before the XP rate, or -1 when unknown
	 */
	public double expPerKill(String zone)
	{
		final Integer zi = (zone == null) ? null : _zoneIndex.get(zone);
		return ((zi == null) || (_zones.get(zi).expPerKill() <= 0.0)) ? -1.0 : _zones.get(zi).expPerKill();
	}

	/**
	 * @param zone a zone name
	 * @return the average level of the monsters there, or -1 when the zone is not in the data
	 */
	public double mobLevel(String zone)
	{
		final Integer zi = (zone == null) ? null : _zoneIndex.get(zone);
		return (zi == null) ? -1.0 : _zones.get(zi).mobLevel();
	}

	/**
	 * The server's own rule for experience from a kill (Attackable.calculateExpAndSp): none when the killer and the monster
	 * are {@code maxDifference} or more levels apart, either way (Rates.ini MonsterExpMaxLevelDifference).
	 * @param level the bot's level
	 * @param mobLevel the average level of the monsters in its zone (0 or less = unknown)
	 * @param maxDifference the server's limit (0 or less = no limit)
	 * @return whether hunting there pays no experience
	 */
	public static boolean outleveled(int level, double mobLevel, int maxDifference)
	{
		if ((maxDifference <= 0) || (mobLevel <= 0))
		{
			return false;
		}
		final double gap = level - mobLevel;
		return (gap >= maxDifference) || (gap <= -maxDifference);
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
		return killsPerMinute(zone, classId, level, weaponGrade, armorGrade, skillFraction, 1.0);
	}

	/**
	 * Like {@link #killsPerMinute(String, int, int, int, int, double)} with the share of the time the bot has its shots.
	 * @param shotFraction the share of the span it fires soulshots (spiritshots for a mage), 0 to 1; 1 is what the calibration assumes
	 * @return kills per minute
	 */
	public double killsPerMinute(String zone, int classId, int level, int weaponGrade, int armorGrade, double skillFraction, double shotFraction)
	{
		return killsPerMinute(zone, classId, level, curveStats(roleOf(classId), weaponGrade, armorGrade), skillFraction, shotFraction);
	}

	/**
	 * Like the grade version, from the stats of the gear the bot actually wears.
	 * @param stats its weapon attack and defence from worn items
	 * @return kills per minute
	 */
	public double killsPerMinute(String zone, int classId, int level, Stats stats, double skillFraction, double shotFraction)
	{
		final Integer zi = knows(zone) ? _zoneIndex.get(zone) : null;
		if (zi == null)
		{
			return _params.baseKillsPerMinute();
		}
		final Role role = roleOf(classId);
		final int skills = (int) Math.round(Math.max(0.0, Math.min(1.0, skillFraction)) * 10.0);
		final int shots = (int) Math.round(Math.max(0.0, Math.min(1.0, shotFraction)) * 10.0);
		final long attack = Math.max(0, Math.min(8191, Math.round(stats.attack())));
		final long key = (((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8192L + attack) * 16L * 11L + (skills * 11L) + shots;
		return _killCache.computeIfAbsent(key, k ->
		{
			final double fightSeconds = _killScale[role.ordinal()] * rawTimeToKill(_zones.get(zi), role, level, attack, skills / 10.0) / buff(role, 0, level) / shotDamage(role, shots / 10.0);
			final double overhead = (60.0 / _params.baseKillsPerMinute()) * (1.0 - _params.fightShare());
			return Math.max(_params.minKillsPerMinute(), Math.min(_params.maxKillsPerMinute(), 60.0 / (overhead + fightSeconds)));
		});
	}

	/** @return the zones the model knows */
	public List<ZoneStats> zones()
	{
		return java.util.Collections.unmodifiableList(_zones);
	}

	/** @return the stats of the best gear of these grades for a role (the curves): what a fitted bot of the grade has */
	public Stats curveStats(Role role, int weaponGrade, int armorGrade)
	{
		return new Stats(curve(role == Role.MAGE ? "matk_mage" : (role == Role.BOW ? "patk_bow" : "patk_melee"), weaponGrade), curve(pDefCurve(role), armorGrade), curve(mDefCurve(role), armorGrade));
	}

	/**
	 * @param zone the zone it hunts in
	 * @param classId its class
	 * @param level its level (for its buffs)
	 * @param armorGrade the grade of its armor
	 * @return the multiple of the base death rate for this zone and armor (1.0 when the model is off or does not know the zone)
	 */
	public double deathFactor(String zone, int classId, int level, int armorGrade)
	{
		return deathFactor(zone, classId, level, curveStats(roleOf(classId), 0, armorGrade));
	}

	/**
	 * Like the grade version, from the defence of the gear the bot actually wears.
	 * @param stats its worn gear (only the defence is used)
	 * @return the multiple of the base death rate
	 */
	public double deathFactor(String zone, int classId, int level, Stats stats)
	{
		final Integer zi = knows(zone) ? _zoneIndex.get(zone) : null;
		if (zi == null)
		{
			return 1.0;
		}
		final Role role = roleOf(classId);
		final long pDef = Math.max(0, Math.min(8191, Math.round(stats.pDef())));
		final long mDef = Math.max(0, Math.min(8191, Math.round(stats.mDef())));
		final long key = ((((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8192L) + pDef) * 8192L + mDef;
		return _deathCache.computeIfAbsent(key, k ->
		{
			final double mean = _threatMedian[role.ordinal()];
			final ZoneStats z = _zones.get(zi);
			final double gear = (mean <= 0) ? 1.0 : Math.max(_params.minDeathFactor(), Math.min(_params.maxDeathFactor(), threat(z, pDef, mDef, buff(role, 1, level), buff(role, 2, level)) / mean));
			return gear * (1.0 + (_aggroRisk * z.aggressivePercent() / 100.0));
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
				raw[i] = rawTimeToKill(zone, role, level, curveStats(role, grade, grade).attack(), 1.0);
				threats[i] = threat(zone, curveStats(role, grade, grade).pDef(), curveStats(role, grade, grade).mDef(), 1.0, 1.0);
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
	private double rawTimeToKill(ZoneStats zone, Role role, int level, double weaponAttack, double skillFraction)
	{
		final double levelMod = (level + 89.0) / 100.0;
		final double skill = _params.skillFloor() + ((1.0 - _params.skillFloor()) * skillFraction);
		final double rate;
		if (role == Role.MAGE)
		{
			rate = Math.sqrt(Math.max(1.0, weaponAttack * levelMod)) / Math.max(1.0, zone.mDef());
		}
		else
		{
			rate = (weaponAttack * levelMod) / Math.max(1.0, zone.pDef());
		}
		return zone.hp() / Math.max(1e-9, rate * skill);
	}

	private static String pDefCurve(Role role)
	{
		return (role == Role.TANK) ? "pdef_tank" : (role == Role.MELEE) ? "pdef_melee" : (role == Role.BOW) ? "pdef_light" : "pdef_robe";
	}

	private static String mDefCurve(Role role)
	{
		return (role == Role.MAGE) ? "mdef_robe" : (role == Role.BOW) ? "mdef_light" : "mdef_heavy";
	}

	/** How hard the zone's monsters hit a bot with this P.Def and M.Def: their attack over its defence. */
	private double threat(ZoneStats zone, double pDef, double mDef, double pDefBuff, double mDefBuff)
	{
		final double physical = zone.pAtk() / Math.max(1.0, pDef * pDefBuff);
		final double magical = zone.mAtk() / Math.max(1.0, mDef * mDefBuff);
		return Math.max(physical, magical);
	}

	/** @return damage relative to a fully shot bot: 1 with shots all the time, down to 1 / (the shots' bonus) with none */
	private double shotDamage(Role role, double fraction)
	{
		final double bonus = (role == Role.MAGE) ? _spiritshotDamage : _soulshotDamage;
		return fraction + ((1.0 - fraction) / bonus);
	}

	/** @return the blended buff multiplier (kind 0 damage, 1 P.Def, 2 M.Def) for a role at a level: 1 with no share or no data */
	private double buff(Role role, int kind, int level)
	{
		final double share = _buffShare[role.ordinal()];
		final double[][] table = _buffs.get(role);
		if ((share <= 0.0) || (table == null))
		{
			return 1.0;
		}
		final double full = table[kind][Math.max(0, Math.min(MAX_BUFF_LEVEL, level))];
		return (full <= 0.0) ? 1.0 : (1.0 + (share * (full - 1.0)));
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
