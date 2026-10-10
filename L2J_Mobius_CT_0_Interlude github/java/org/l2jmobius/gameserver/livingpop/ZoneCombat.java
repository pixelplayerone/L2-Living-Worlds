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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.function.DoubleBinaryOperator;

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
	 * @param maxKillsPerMinute highest kill rate the model gives
	 * @param minDeathFactor lowest multiple of the base death rate
	 * @param maxDeathFactor highest multiple of the base death rate
	 * @param gearTierLevelStep levels per gear tier (a tier's grade is the grade of level tier x step), for bots that buy whole tiers
	 */
	public record Params(boolean enabled, double baseKillsPerMinute, double fightShare, double skillFloor, double maxKillsPerMinute, double minDeathFactor, double maxDeathFactor, int gearTierLevelStep)
	{
		public static Params defaults()
		{
			return new Params(true, 12.0, 0.5, 0.5, 24.0, 0.25, 4.0, 10);
		}
	}

	/**
	 * A zone's average monster, and what the zone can supply.
	 * @param respawnPerMinute monsters the whole zone can supply per minute (each spawn returns after its respawn delay); 0 = unknown
	 * @param spots how many hunting spots the zone has
	 * @param aggressivePercent the share of its spawns that attack on sight, in percent
	 * @param expPerKill the average experience a kill gives, before the server's XP rate; 0 = unknown
	 * @param atkSpeed the monsters' attack speed stat (one attack every 500000 / this milliseconds); 0 = unknown
	 * @param critPercent the share of the monsters' hits that are critical, in percent (a crit doubles the hit)
	 * @param shotShare the share of their hits that carry a soulshot (P.Atk doubled)
	 */
	public record ZoneStats(String name, int minLevel, int maxLevel, double mobLevel, double hp, double pDef, double mDef, double pAtk, double mAtk, double respawnPerMinute, int spots, double aggressivePercent, double expPerKill, double accuracy, double atkSpeed, double critPercent, double shotShare)
	{
		/** @return the monsters' attacks per second (the old flat 0.5 when the speed is unknown) */
		double hitsPerSecond()
		{
			return (atkSpeed > 0) ? (atkSpeed / 500.0) : MOB_HITS_PER_SECOND;
		}

		/** @return the average multiple of a plain hit that shots and crits give: (1 + shot share) x (1 + crit share) */
		double hitMultiple()
		{
			return (1.0 + shotShare) * (1.0 + (critPercent / 100.0));
		}

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
	 * @param selfBuffs the share (0 to 1) of the class's damage and defence self buffs the bot has learned, or below 0 when unknown (its skill share is used)
	 */
	public record Stats(double attack, double pDef, double mDef, double selfBuffs)
	{
		/** Stats whose self buffs follow the bot's skill share (it is not tracked which self buffs it has bought). */
		public Stats(double attack, double pDef, double mDef)
		{
			this(attack, pDef, mDef, -1.0);
		}
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
	private volatile double _blessedDamage = 2.0; // damage with blessed spiritshots over without (M.Atk x4, damage grows with its square root)
	private volatile double _spiritshotDamage = Math.sqrt(2.0); // damage with spiritshots over without (M.Atk x2, damage grows with its square root)
	private volatile double[] _buffShare = new double[Role.values().length]; // per role: 0 no buffs, 1 the full party
	private final Map<String, java.util.TreeMap<Integer, double[]>> _rotationsUndead = new HashMap<>(); // the same against undead monsters (healer lines with Turn Undead style skills, Phoenix Knight)
	private final Map<String, Double> _undeadShare = new HashMap<>(); // zone -> share (0 to 1) of its monsters that are undead
	private final Map<String, java.util.TreeMap<Integer, Double>> _rotationMp = new HashMap<>(); // line -> level -> MP per second the 60 s rotation spends
	private final Map<String, java.util.TreeMap<Integer, double[]>> _rotations = new HashMap<>(); // line -> level -> {auto dps, dps over 5, 15, 30, 45, 60, 90, 120 s} against the sim's dummy
	private final Map<Integer, String> _rotationLine = new HashMap<>(); // class id -> its rotation line
	private final Map<String, java.util.TreeMap<Integer, double[]>> _rotSelf = new HashMap<>(); // line -> level -> {dps ratio over 5 .. 120 s with the self buffs, P.Def mul, M.Def mul}
	private final Map<String, java.util.TreeMap<Integer, int[]>> _rotSelfIds = new HashMap<>(); // line -> level -> the self buff skill ids those ratios assume
	private final Map<Role, double[][]> _newbie = new java.util.EnumMap<>(Role.class); // Newbie Helper buffs: role -> {damage, pDef, mDef} by level (8-25)
	private final Map<String, java.util.TreeMap<Integer, double[]>> _bufferExtras = new HashMap<>(); // buffer -> level -> {run speed added, HP regen mul, absorb share, HP mul}
	private volatile boolean _startingBuffs;
	private volatile java.util.function.IntToLongFunction _expSpan; // experience span of a level (for weighing the heals against the deaths they save)
	private volatile double _expRate = 1.0;
	private final Map<List<Object>, Boolean> _healCache = new ConcurrentHashMap<>();
	private volatile boolean _selfHealOn; // mystic-line bots heal themselves with the heals they have learned, and summoners heal the servitor (HEAL rows)
	private final Map<String, java.util.TreeMap<Integer, List<double[]>>> _heals = new HashMap<>(); // line -> level -> heals {skill id, power, mana, cast s, reuse s}
	private volatile boolean _hpDeaths; // deaths from the HP model (fight damage against the HP a bot starts a fight with) instead of the flat rate times the gear threat
	private volatile double _fightRisk = 0.002; // the chance of dying in one fight a bot accepts: it sits below the HP at which a fight is riskier than this, so a more dangerous zone makes it rest sooner
	private volatile double _deathBase = 0.3; // the cold death rate per hour the factor is a multiple of
	private volatile int _rotationWindow = -1; // index of the fixed rotation window (-1 = nearest the fight's length)
	private volatile boolean _shotsFromHits; // shots a kill uses from its hits (fight time over the attack interval, times the weapon's shots per attack) instead of a flat count
	private volatile double[] _hitInterval = { 1.4, 2.4, 2.2 }; // seconds between a bot's attacks: melee (tanks too), bow, mage (one cast)
	private volatile double _rangedWalk = 1.0; // share of the walk between monsters that archers and casters keep
	private final Map<String, java.util.TreeMap<Integer, double[]>> _serv = new HashMap<>(); // summoner line -> level -> {servitor dps, HP, P.Def}
	private static final int[] ROTATION_WINDOWS = { 5, 15, 30, 45, 60, 90, 120 };
	private static final double SIM_PDEF = 400.0;
	private static final int NEWBIE_FROM_LEVEL = 8; // the Newbie Helper's support magic (SupportMagic.java)
	private static final int NEWBIE_TO_LEVEL = 25;
	private static final double MOB_HITS_PER_SECOND = 0.5; // a monster's attacks on its target, when its zone has no attack speed
	private static final double MOB_DAMAGE = 76.0; // the server's physical damage constant: 76 x P.Atk x shot x proximity / P.Def
	private static final double SERVITOR_RESUMMON_SECONDS = 20.0;
	private static final double SERVITOR_MAX_DEAD_SHARE = 0.6;
	private static final double SERVITOR_BASE_EXPOSURE = 0.1; // monsters that still go for the summoner (area attacks, ranged, aggro on the master)
	private static final double SIM_MDEF = 300.0;
	private final Map<String, Map<Role, java.util.TreeMap<Integer, double[]>>> _rest = new HashMap<>(); // zone -> role -> level -> {cycle s, HP deficit per kill, HP sit regen per s, MP sit s per kill}
	private volatile boolean _restOn; // sitting to refill HP and MP lowers the kill rate (the rest estimate)
	private volatile boolean _evasionOn; // monsters miss a bot with evasion: fewer hits taken
	private static final double POTION_HEAL_LESSER = 120.0; // Lesser Healing Potion: 8 HP a second for 15 s
	private static final double POTION_HEAL = 360.0; // Healing Potion: 24 HP a second for 15 s
	private volatile boolean _rotationTtk; // time to kill from the sim's rotations in real seconds, not the calibrated relative model
	private final Map<Long, Double> _killCache = new ConcurrentHashMap<>();
	private final Map<Long, Double> _deathCache = new ConcurrentHashMap<>();
	private final Map<Long, Double> _rateCache = new ConcurrentHashMap<>(); // deaths an hour from the HP model (-1 where it has no data)
	private volatile double[] _extraMonsters = { 0.15, 0.075, 0.04, 0.02, 0.01 }; // the chance a 2nd, 3rd ... 6th monster joins a fight (before the zone's aggressive share raises them)

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
		return new ZoneCombat(new Params(false, 12.0, 0.5, 0.5, 24.0, 0.25, 4.0, 10), Map.of(), List.of(), Map.of());
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
		final Map<String, java.util.TreeMap<Integer, Double>> rotationMp = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, double[]>> rotations = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, double[]>> rotationsUndead = new HashMap<>();
		final Map<String, Double> undeadShare = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, List<double[]>>> heals = new HashMap<>();
		final Map<Integer, String> rotationLine = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, double[]>> rotSelf = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, int[]>> rotSelfIds = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, double[]>> serv = new HashMap<>();
		final Map<Role, double[][]> newbie = new java.util.EnumMap<>(Role.class);
		final Map<String, java.util.TreeMap<Integer, double[]>> bufferExtras = new HashMap<>();
		final Map<String, Map<Role, java.util.TreeMap<Integer, double[]>>> rest = new HashMap<>();
		final Map<String, Map<Role, double[][]>> partyBuffs = new HashMap<>();
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
					else if (f[0].equals("ROT") && (f.length >= 11))
					{
						final double[] values = new double[1 + ROTATION_WINDOWS.length];
						for (int i = 0; i < values.length; i++)
						{
							values[i] = Double.parseDouble(f[3 + i]);
						}
						rotations.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[2]), values);
					}
					else if (f[0].equals("ROTMP") && (f.length >= 4))
					{
						rotationMp.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[2]), Double.parseDouble(f[3]));
					}
					else if (f[0].equals("ROTU") && (f.length >= 11))
					{
						final double[] values = new double[1 + ROTATION_WINDOWS.length];
						for (int i = 0; i < values.length; i++)
						{
							values[i] = Double.parseDouble(f[3 + i]);
						}
						rotationsUndead.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[2]), values);
					}
					else if (f[0].equals("HEAL") && (f.length >= 8))
					{
						heals.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).computeIfAbsent(Integer.parseInt(f[2]), k -> new ArrayList<>()).add(new double[] { Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]) });
					}
					else if (f[0].equals("ZUNDEAD") && (f.length >= 3))
					{
						undeadShare.put(f[1], Double.parseDouble(f[2]));
					}
					else if (f[0].equals("ROTSELF") && (f.length >= 12))
					{
						final double[] values = new double[ROTATION_WINDOWS.length + 2];
						for (int i = 0; i < values.length; i++)
						{
							values[i] = Double.parseDouble(f[3 + i]);
						}
						final int level = Integer.parseInt(f[2]);
						rotSelf.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(level, values);
						final String idText = (f.length > 3 + values.length) ? f[3 + values.length] : "-";
						rotSelfIds.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(level, idText.equals("-") ? new int[0] : java.util.Arrays.stream(idText.split(",")).mapToInt(Integer::parseInt).toArray());
					}
					else if (f[0].equals("BBUFF") && (f.length >= 7))
					{
						bufferExtras.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[2]), new double[] { Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]) });
					}
					else if (f[0].equals("NBUFF") && (f.length >= 6))
					{
						final int level = Integer.parseInt(f[2]);
						if ((level >= 0) && (level <= MAX_BUFF_LEVEL))
						{
							final double[][] table = newbie.computeIfAbsent(Role.valueOf(f[1].toUpperCase(java.util.Locale.ROOT)), r -> new double[6][MAX_BUFF_LEVEL + 1]);
							table[0][level] = Double.parseDouble(f[3]);
							table[1][level] = Double.parseDouble(f[4]);
							table[2][level] = Double.parseDouble(f[5]);
							table[3][level] = (f.length >= 8) ? Double.parseDouble(f[6]) : 1.0; // sit regen multiplier (Regeneration)
							table[4][level] = (f.length >= 8) ? Double.parseDouble(f[7]) : 0.0; // share of damage dealt that comes back as HP (Vampiric Rage)
							table[5][level] = (f.length >= 9) ? Double.parseDouble(f[8]) : 1.0; // run speed multiplier (Wind Walk for Beginners)
						}
					}
					else if (f[0].equals("SERV") && (f.length >= 6))
					{
						serv.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[2]), (f.length >= 8) ? new double[] { Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]) } : new double[] { Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]) });
					}
					else if (f[0].equals("PBUFF") && (f.length >= 7))
					{
						final int level = Integer.parseInt(f[3]);
						if ((level >= 0) && (level <= MAX_BUFF_LEVEL))
						{
							final double[][] table = partyBuffs.computeIfAbsent(f[1], k -> new java.util.EnumMap<>(Role.class)).computeIfAbsent(Role.valueOf(f[2].toUpperCase(java.util.Locale.ROOT)), k -> new double[3][MAX_BUFF_LEVEL + 1]);
							table[0][level] = Double.parseDouble(f[4]);
							table[1][level] = Double.parseDouble(f[5]);
							table[2][level] = Double.parseDouble(f[6]);
						}
					}
					else if (f[0].equals("REST") && (f.length >= 8))
					{
						rest.computeIfAbsent(f[1], k -> new java.util.EnumMap<>(Role.class)).computeIfAbsent(Role.valueOf(f[2].toUpperCase(java.util.Locale.ROOT)), k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[3]), new double[] { Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), (f.length >= 9) ? Double.parseDouble(f[8]) : 0.0, (f.length >= 10) ? Double.parseDouble(f[9]) : 0.0 });
					}
					else if (f[0].equals("ROTCLASS") && (f.length >= 3))
					{
						rotationLine.put(Integer.parseInt(f[1]), f[2]);
					}
					else if (f[0].equals("ZONE") && (f.length >= 10))
					{
						final boolean more = f.length >= 14; // older data files stop at M.Atk, or before the experience
						zones.add(new ZoneStats(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9]), more ? Double.parseDouble(f[10]) : 0.0, more ? Integer.parseInt(f[11]) : 0, more ? Double.parseDouble(f[12]) : 0.0, more ? Double.parseDouble(f[13]) : 0.0, (f.length >= 15) ? Double.parseDouble(f[14]) : 0.0, (f.length >= 18) ? Double.parseDouble(f[15]) : 0.0, (f.length >= 18) ? Double.parseDouble(f[16]) : 0.0, (f.length >= 18) ? Double.parseDouble(f[17]) : 0.0));
					}
				}
				catch (RuntimeException e)
				{
					// skip a bad line
				}
			}
		}
		final boolean usable = !zones.isEmpty() && curves.keySet().containsAll(List.of("patk_melee", "patk_bow", "matk_mage", "pdef_tank", "pdef_melee", "pdef_light", "pdef_robe", "mdef_heavy", "mdef_light", "mdef_robe"));
		final Params use = usable ? params : new Params(false, params.baseKillsPerMinute(), params.fightShare(), params.skillFloor(), params.maxKillsPerMinute(), params.minDeathFactor(), params.maxDeathFactor(), params.gearTierLevelStep());
		final ZoneCombat model = new ZoneCombat(use, curves, zones, buffs);
		model._rotations.putAll(rotations);
		model._rotationMp.putAll(rotationMp);
		model._rotationsUndead.putAll(rotationsUndead);
		model._undeadShare.putAll(undeadShare);
		model._heals.putAll(heals);
		model._rotationLine.putAll(rotationLine);
		model._rotSelf.putAll(rotSelf);
		model._rotSelfIds.putAll(rotSelfIds);
		model._serv.putAll(serv);
		model._newbie.putAll(newbie);
		model._bufferExtras.putAll(bufferExtras);
		model._rest.putAll(rest);
		model._partyBuffs.putAll(partyBuffs);
		return model;
	}

	/**
	 * Time to kill from the sim's best rotation per class line and level, in real seconds, instead of the calibrated relative model.
	 * A class without a rotation line keeps the relative model.
	 * @param on whether to use the rotations
	 */
	public void setRotationTtk(boolean on)
	{
		_rotationTtk = on;
		_killCache.clear();
	}

	/**
	 * @param on whether sitting to refill HP and MP lowers the kill rate (the rest estimate; potions cover part of the HP)
	 */
	public void setRest(boolean on)
	{
		_restOn = on;
		_killCache.clear();
	}

	/**
	 * @param zone a zone
	 * @return whether sitting is already modeled for that zone (the rest estimate is on and has rows for it), so the fixed rests are not taken on top
	 */
	public boolean restModeled(String zone)
	{
		return _restOn && _rest.containsKey(zone);
	}

	/**
	 * @param on whether monsters miss a bot with evasion, so it takes fewer hits (a lower death factor for the nimble: archers, then fighters, then mages)
	 */
	public void setEvasion(boolean on)
	{
		_evasionOn = on;
		_deathCache.clear();
		_rateCache.clear();
		calibrate();
	}

	/** @return a monster's chance to land a hit on a bot of this role and level, 0.2 to 0.98 (1 without accuracy data or with evasion off) */
	private double hitChance(ZoneStats zone, Role role, int level)
	{
		if (!_evasionOn || (zone.accuracy() <= 0))
		{
			return 1.0;
		}
		final double dex = (role == Role.BOW) ? 40.0 : (role == Role.MELEE) ? 33.0 : (role == Role.TANK) ? 30.0 : 25.0;
		final double monsterAccuracy = (6.0 * Math.sqrt(30.0)) + zone.mobLevel() + zone.accuracy(); // base accuracy of a monster: level + 6 x sqrt(DEX 30) + its template bonus
		final double evasion = level + (6.0 * Math.sqrt(dex));
		return Math.max(0.2, Math.min(0.98, (80.0 + (2.0 * (monsterAccuracy - evasion))) / 100.0));
	}

	/** Spoil (skill 254) mana by skill level; a spoiler learns the levels at these character levels. */
	private static final int[] SPOIL_LEVEL = { 10, 20, 28, 36, 43, 49, 55, 60, 64, 68, 72 };
	private static final int[] SPOIL_MP = { 12, 19, 25, 31, 38, 44, 50, 55, 59, 63, 67 };

	/** @return the mana one Spoil cast takes at a character level (the best level learned by then; 0 below 10) */
	public static int spoilMana(int level)
	{
		int mp = 0;
		for (int i = 0; i < SPOIL_LEVEL.length; i++)
		{
			if (level >= SPOIL_LEVEL[i])
			{
				mp = SPOIL_MP[i];
			}
		}
		return mp;
	}

	/** @return the seconds of sitting one Spoil cast adds per kill (0 for a non-spoiler or without MP regen data) */
	private static double spoilSitSeconds(double[] rest, int level, boolean spoiler)
	{
		return (!spoiler || (rest.length < 5) || (rest[4] <= 0.0)) ? 0.0 : (spoilMana(level) / rest[4]);
	}

	/**
	 * Kills factor from sitting: the cycle over the cycle plus the sit. The HP the monsters take in a fight this long (with the extra monsters that join) is repaid at the sitting regen, the MP the rotation spent
	 * beyond the standing regen likewise, and both refill in the same sit; potions heal part of the HP deficit so it sits less. 1 without rest data.
	 * @param fight the seconds of one fight
	 * @param cycle the fight and the walk before the next one
	 * @param mpUsed the MP one kill costs, or -1 for the rest table's own figure
	 */
	private double restFactor(String zone, Role role, int level, double fight, double cycle, double mpUsed, double potionsPerHour, double killsPerMinute, boolean spoiler, double healCover, double healMana)
	{
		final Map<Role, java.util.TreeMap<Integer, double[]>> byRole = _rest.get(zone);
		final java.util.TreeMap<Integer, double[]> table = (byRole == null) ? null : byRole.get(role);
		if ((table == null) || table.isEmpty())
		{
			return 1.0;
		}
		final java.util.Map.Entry<Integer, double[]> entry = (table.floorEntry(level) != null) ? table.floorEntry(level) : table.firstEntry();
		final double[] r = entry.getValue(); // cycle, HP deficit per kill, HP sit regen per s, MP sit s (the rest estimate's own fight, used here only for the MP when the rotation has none), MP sit regen, HP pool
		double healPerKill = (potionsPerHour <= 0 || killsPerMinute <= 0) ? 0.0 : ((potionsPerHour * ((level < 20) ? POTION_HEAL_LESSER : POTION_HEAL)) / (killsPerMinute * 60.0));
		double sitRegen = r[2];
		if (_startingBuffs && (level >= NEWBIE_FROM_LEVEL) && (level <= NEWBIE_TO_LEVEL) && (_newbie.get(role) != null) && knows(zone))
		{
			// Newbie Helper fighters: Vampiric Rage returns a share of the damage dealt (about the monster's HP) and Regeneration speeds the sitting regen.
			final double[][] nb = _newbie.get(role);
			healPerKill += nb[4][level] * _zones.get(_zoneIndex.get(zone)).hp();
			sitRegen *= (nb[3][level] <= 0.0) ? 1.0 : nb[3][level];
		}
		else if (_startingBuffs && (level > NEWBIE_TO_LEVEL) && knows(zone) && (bufferExtras(level) != null))
		{
			// A Hierophant's Regeneration and a Doom Cryer's Chant of Vampire, the same way.
			final double[] extras = bufferExtras(level);
			healPerKill += extras[2] * _zones.get(_zoneIndex.get(zone)).hp();
			sitRegen *= extras[1];
		}
		// Mystic-line bots heal themselves (healCover is the share of the HP they lose that the heals they have learned cover) and summoners repair the servitor; the mana (healMana per kill) comes out of the pool they sit to refill.
		final double lost = damageTaken(_zones.get(_zoneIndex.get(zone)), role, level, fight);
		final double deficit = Math.max(0.0, lost - (r[2] * 1.1 / 1.5 * cycle) - healPerKill);
		final double healMp = (r.length >= 5 && r[4] > 0.0) ? (healMana / r[4]) : 0.0;
		final double sitHp = (deficit * (1.0 - healCover)) / Math.max(1e-9, sitRegen);
		final double sitMpBase = (mpUsed >= 0.0 && r.length >= 5 && r[4] > 0.0) ? (Math.max(0.0, mpUsed - (r[4] * 1.1 / 1.5 * cycle)) / r[4]) : r[3];
		// A spoiler bot casts Spoil on every monster: that mana is refilled by sitting too (a spoiler in the party that is not the bot costs it nothing).
		final double sit = Math.max(sitHp, sitMpBase + healMp + spoilSitSeconds(r, level, spoiler));
		return cycle / (cycle + sit);
	}

	/**
	 * The rough party model: a virtual party of 4 to 9 members (tank, healer, buffer and damage dealer, then members drawn at random from a second buffer, a minor buffer and up to four more damage dealers), of which the bot is one (by its class). The server's party bonus by size sets the exp share.
	 * @param enabled whether bots may party
	 * @param healCoverage the share of the tank's HP loss the healer heals (so the tank sits less)
	 * @param chainChance when a member dies, the chance the next in line (tank, damage dealer, buffer, healer) dies before the mob does
	 * @param resetSeconds how long the party takes to resurrect and get going after a death
	 * @param healMpPerHp mana the healer spends per HP it heals (the healer only heals; Greater Heal and Battle Heal run about 0.1), for its resting
	 * @param baseDeathsPerHour the death rate a death factor of 1 means
	 */
	public record PartyParams(boolean enabled, double healCoverage, double chainChance, double resetSeconds, double healMpPerHp, double baseDeathsPerHour)
	{
		public static PartyParams defaults()
		{
			return new PartyParams(true, 0.75, 0.3, 45.0, 0.12, 0.3);
		}
	}

	/**
	 * What a bot gets from hunting in the virtual party.
	 * @param killsPerMinute the party's kills per minute (after resting and resets)
	 * @param deathFactor the bot's death rate relative to the base rate (only where the HP model has no data; else see deathsPerHour)
	 * @param deathsPerHour the bot's deaths an hour in the party from the HP model, or -1 where the zone has no rest data
	 * @param expShare the share of a kill's experience the bot gets
	 * @param slot 0 tank, 1 damage dealer, 2 buffer, 3 healer
	 * @param buffer the buffer line giving the buffs
	 * @param lootShare the share of each drop (adena, items) the bot gets: one over the party size
	 * @param spoils whether the party spoils its kills (the bot or the damage dealer is a Scavenger, Bounty Hunter or Fortune Seeker): the spoil drops are shared too
	 */
	public record PartyOutcome(double killsPerMinute, double deathFactor, double deathsPerHour, double expShare, int slot, String buffer, double lootShare, boolean spoils)
	{
	}

	/** The server's party experience bonus by member count (Party.BONUS_EXP_SP, one member first). */
	private static final double[] EXP_BONUS = { 1.0, 1.10, 1.20, 1.30, 1.40, 1.50, 2.0, 2.10, 2.20 };
	/** How likely a virtual party is 4, 5 ... 9 members: small parties are the common ones, and the higher the level the bigger they get. Rows by level: below 61, 61+, 70+, 76+. */
	private static final double[][] SIZE_CHANCE = { { 0.50, 0.25, 0.12, 0.07, 0.04, 0.02 }, { 0.30, 0.25, 0.175, 0.125, 0.10, 0.05 }, { 0.0, 0.0, 0.35, 0.25, 0.25, 0.15 }, { 0.0, 0.0, 0.0, 0.20, 0.30, 0.50 } };

	/** @return the chance a non-healer bot that earns alone still joins a party (while alone is a loss it always does): none below level 20, 15% at 20, 25% at 30, 35% at 40, 50% at 50, 65% at 60, 80% at 70, 90% at 76 */
	public static double partyChance(int level)
	{
		final int[] from = { 20, 30, 40, 50, 60, 70, 76 };
		final double[] chance = { 0.15, 0.25, 0.35, 0.50, 0.65, 0.80, 0.90 };
		double result = 0.0;
		for (int i = 0; i < from.length; i++)
		{
			if (level >= from[i])
			{
				result = chance[i];
			}
		}
		return result;
	}

	private static int partySize(double roll, int level)
	{
		final double[] chance = SIZE_CHANCE[(level >= 76) ? 3 : (level >= 70) ? 2 : (level >= 61) ? 1 : 0];
		double left = roll * Arrays.stream(chance).sum();
		for (int i = 0; i < chance.length; i++)
		{
			left -= chance[i];
			if ((left < 0.0) && (chance[i] > 0.0))
			{
				return 4 + i;
			}
		}
		return 4 + chance.length - 1;
	}

	private static String otherBuffer(String buffer)
	{
		return BUFFERS[0].equals(buffer) ? BUFFERS[1] : BUFFERS[0];
	}

	/** The buffer lines in the order a set's key joins them (as tools/combat_sim/build_party_buffs.py does). */
	private static final List<String> BUFFER_ORDER = List.of("hierophant", "doom_cryer", "dominator", "sword_muse", "spectral_dancer", "evas_saint", "shillien_saint");

	/** @return the key of a set of buffer lines: its PBUFF rows hold what the set gives together, with same-effect buffs replacing each other as on the server instead of adding up */
	private static String bufferKey(Set<String> lines)
	{
		return BUFFER_ORDER.stream().filter(lines::contains).collect(Collectors.joining("+"));
	}

	private static final Set<Integer> HEALERS = Set.of(15, 16, 97, 29, 30, 105, 42, 43, 112);
	private static final Map<Integer, String> BUFFER_OF = Map.ofEntries(Map.entry(17, "hierophant"), Map.entry(98, "hierophant"), Map.entry(21, "sword_muse"), Map.entry(100, "sword_muse"), Map.entry(34, "spectral_dancer"), Map.entry(107, "spectral_dancer"), Map.entry(51, "dominator"), Map.entry(115, "dominator"), Map.entry(52, "doom_cryer"), Map.entry(116, "doom_cryer"));
	/** Every damage dealer line a party can have: Duelist, Dreadnought, Titan, Grand Khavatari, Fortune Seeker, Maestro, Adventurer, Wind Rider, Ghost Hunter, Sagittarius, Moonlight and Ghost Sentinel, Archmage, Soultaker, Arcana Lord, Mystic Muse, Elemental Master, Storm Screamer, Spectral Master. */
	private static final int[] DPS_CLASSES = { 88, 89, 113, 114, 117, 118, 93, 101, 108, 92, 102, 109, 94, 95, 96, 103, 104, 110, 111 };
	private static final int[] TANK_CLASSES = { 90, 91, 99, 106 }; // Phoenix Knight, Hell Knight, Eva's Templar, Shillien Templar: the reference tank a party draws from at random
	private static final String[] BUFFERS = { "hierophant", "doom_cryer" }; // the two main buffers a party picks from at random (a bot that is itself a buffer brings its own line)
	private final Map<String, Map<Role, double[][]>> _partyBuffs = new HashMap<>(); // buffer line -> role -> {damage, pDef, mDef} by level
	private volatile PartyParams _partyParams = PartyParams.defaults();
	private final Map<List<Object>, PartyOutcome> _partyCache = new ConcurrentHashMap<>();

	/** @return whether the class is a healer line (Cleric to Cardinal, the Oracles and Elders, Eva's and Shillien Saints) */
	public static boolean isHealer(int classId)
	{
		return HEALERS.contains(classId);
	}

	/** @param params the party tuning */
	public void setParty(PartyParams params)
	{
		_partyParams = params;
		_partyCache.clear();
	}

	/** @return the party tuning */
	public PartyParams partyParams()
	{
		return _partyParams;
	}

	private static int slotOf(int classId)
	{
		if (isHealer(classId))
		{
			return 3;
		}
		if (BUFFER_OF.containsKey(classId))
		{
			return 2;
		}
		return (roleOf(classId) == Role.TANK) ? 0 : 1;
	}

	private double partyBuff(String buffer, Role role, int kind, int level)
	{
		final double[][] table = (_partyBuffs.get(buffer) == null) ? null : _partyBuffs.get(buffer).get(role);
		if (table == null)
		{
			return 1.0;
		}
		final double v = table[kind][Math.max(0, Math.min(MAX_BUFF_LEVEL, level))];
		return (v <= 0.0) ? 1.0 : v;
	}

	/**
	 * The bot hunting in a virtual party in its zone: the party's kill rate, the bot's death factor and its share of the experience.
	 * The tank takes the hits and is healed; when it dies the next in line takes over until the mob is dead, and a death costs the whole
	 * party a reset. Experience and loot are the bot's quarter.
	 * @param variant picks the buffer and the damage dealer a party has when the bot is not one (any non-negative number)
	 * @return null when the zone or data is missing or parties are off
	 */
	public PartyOutcome party(String zone, int classId, int level, Stats stats, double skillFraction, double shotFraction, boolean blessed, int variant)
	{
		final PartyParams p = _partyParams;
		final Integer zi = knows(zone) ? _zoneIndex.get(zone) : null;
		if ((zi == null) || !p.enabled() || !_params.enabled())
		{
			return null;
		}
		final Role role = roleOf(classId);
		final int shots = (int) Math.round(Math.max(0.0, Math.min(1.0, shotFraction)) * 10.0);
		final long attack = Math.max(0, Math.min(8191, Math.round(stats.attack())));
		final int skills = (int) Math.round(Math.max(0.0, Math.min(1.0, skillFraction)) * 10.0);
		final List<Object> full = List.of(zi, level, classId, attack, Math.round(stats.pDef()), Math.round(stats.selfBuffs() * 10.0), skills, shots, blessed, variant & 63);
		return _partyCache.computeIfAbsent(full, k -> computeParty(zi, p, classId, role, level, stats, skills / 10.0, shots / 10.0, blessed, variant));
	}

	private PartyOutcome computeParty(int zi, PartyParams p, int classId, Role role, int level, Stats stats, double skillFraction, double shotFraction, boolean blessed, int variant)
	{
		final ZoneStats z = _zones.get(zi);
		final int grade = LivingSupplies.gradeFor(level);
		final int slot = slotOf(classId);
		final String buffer = ((slot == 2) && BUFFER_OF.containsKey(classId)) ? BUFFER_OF.get(classId) : BUFFERS[Math.floorMod(variant, BUFFERS.length)];
		// The tank: the bot's own when it is one, else a reference Phoenix Knight in the bot's grade of gear.
		final boolean botTank = slot == 0;
		final int tankClass = botTank ? classId : TANK_CLASSES[Math.floorMod(variant / (BUFFERS.length * DPS_CLASSES.length), TANK_CLASSES.length)];
		final Stats tankStats = botTank ? stats : curveStats(Role.TANK, grade, grade);
		final double tankSkills = botTank ? skillFraction : 1.0;
		// The party: tank, healer, one buffer and one damage dealer, plus up to five more members drawn from what is left (a second main buffer, a minor buffer, up to four more damage dealers).
		final Random dice = new Random((variant & 63) * 7919L + 17L);
		final int size = partySize(dice.nextDouble(), level);
		// The main damage dealer is the bot's own, the healer's Archmage, or a reference one; the seats beyond the four core ones are filled by rule: from 6 members a party has a spoiler (never two), from 7 a second main buffer, from 8 also a minor buffer; what is left is drawn at random.
		final int mainDps = (slot == 1) ? classId : (slot == 3) ? 94 : DPS_CLASSES[Math.floorMod(variant / BUFFERS.length, DPS_CLASSES.length)];
		final boolean needSpoiler = (size >= 6) && !LivingSupplies.isSpoiler(mainDps);
		final List<Integer> open = new ArrayList<>(List.of(0, 1, 1, 1, 1, 2)); // 0 second buffer, 1 damage dealer, 2 minor buffer (no data, it only takes a seat)
		final List<Integer> picked = new ArrayList<>();
		for (int forced : new int[] { (size >= 7) ? 0 : -1, (size >= 8) ? 2 : -1, needSpoiler ? 1 : -1 })
		{
			if (forced >= 0)
			{
				picked.add(forced);
				open.remove(Integer.valueOf(forced));
			}
		}
		Collections.shuffle(open, dice);
		picked.addAll(open.subList(0, size - 4 - picked.size()));
		final boolean secondBuffer = picked.contains(0);
		final Set<String> lines = new HashSet<>(List.of(buffer));
		if (secondBuffer)
		{
			lines.add(otherBuffer(buffer));
		}
		if (picked.contains(2))
		{
			final List<String> minors = new ArrayList<>(List.of("sword_muse", "spectral_dancer"));
			minors.removeAll(lines);
			if (!minors.isEmpty())
			{
				lines.add(minors.get(dice.nextInt(minors.size())));
			}
		}
		final String buffers = bufferKey(lines);
		final int dpsCount = 1 + Collections.frequency(picked, 1);
		final int[] dClass = new int[dpsCount];
		final Role[] dRole = new Role[dpsCount];
		final Stats[] dStats = new Stats[dpsCount];
		final double[] dSkills = new double[dpsCount];
		final double[] dShots = new double[dpsCount];
		final double[] dSelf = new double[dpsCount];
		final double[] dShare = new double[dpsCount];
		for (int i = 0; i < dpsCount; i++)
		{
			dSkills[i] = 1.0;
			dShots[i] = 1.0;
			dSelf[i] = 1.0;
			dShare[i] = 1.0;
			if (i == 0)
			{
				dClass[i] = mainDps;
			}
			else if ((i == 1) && needSpoiler)
			{
				dClass[i] = 117; // the party's one spoiler: a Fortune Seeker
			}
			else
			{
				int c;
				do
				{
					c = DPS_CLASSES[dice.nextInt(DPS_CLASSES.length)];
				}
				while (LivingSupplies.isSpoiler(c));
				dClass[i] = c;
			}
			dRole[i] = (i == 0) && (slot == 1) ? role : roleOf(dClass[i]);
			dStats[i] = ((i == 0) && ((slot == 1) || (slot == 3))) ? stats : curveStats(dRole[i], grade, grade);
			if ((i == 0) && (slot == 1))
			{
				dSkills[i] = skillFraction;
				dShots[i] = shotDamage(role, shotFraction, blessed);
				dSelf[i] = stats.selfBuffs();
			}
		}
		final Role dpsRole = dRole[0];
		final int dpsClass = dClass[0];
		final double tankShots = botTank ? shotDamage(Role.TANK, shotFraction, false) : 1.0;
		final double tankBuff = partyBuff(buffers, Role.TANK, 0, level);
		final double meleeBuff = partyBuff(buffers, Role.MELEE, 0, level);
		final double[] dBuff = new double[dpsCount];
		for (int i = 0; i < dpsCount; i++)
		{
			dBuff[i] = partyBuff(buffers, dRole[i], 0, level);
		}
		// What the party sits for anyway per kill: the tank's HP the healer does not cover, and the healer's mana (it only heals, and refills at a mage's MP regen). The casters refill their own MP in that sit.
		final int healerClass = (slot == 3) ? classId : 97;
		final double dataMpPerHp = _selfHealOn ? healMpPerHp(healerClass, level, curve("matk_mage", grade)) : 0.0; // the healer's own heals (Greater Heal, Battle Heal ...) set the mana per HP when the data has them
		final double mpPerHp = (dataMpPerHp > 0.0) ? dataMpPerHp : p.healMpPerHp();
		final double[] tankRow = _restOn ? restRow(z.name(), Role.TANK, level) : null;
		final double[] mageRow = _restOn ? restRow(z.name(), Role.MAGE, level) : null;
		final DoubleBinaryOperator sharedSit = (tankRow == null || mageRow == null) ? (f, c) -> 0.0 : (f, c) ->
		{
			final double lost = lostPerKill(z.name(), Role.TANK, level, f); // HP the mobs take off the tank per kill, beyond its standing regen
			final double sitHp = (lost * (1.0 - p.healCoverage())) / Math.max(1e-9, tankRow[2]);
			final double sitMp = (mageRow.length >= 5 && mageRow[4] > 0.0) ? ((lost * p.healCoverage() * mpPerHp) / mageRow[4]) : (mageRow[3] * (f / Math.max(1e-6, mageRow[0] - 2.5)) * 1.5);
			return Math.max(sitHp, sitMp);
		};
		// The fight, the casters' share of skills and the party's sit depend on each other: a few passes settle them.
		double tankShare = 1.0;
		double fight = 0.0;
		for (int pass = 0; pass < 4; pass++)
		{
			final int w = windowFor(Math.min(pass, 1), fight);
			double dps = rotationDpsShare(z, Role.TANK, tankClass, level, tankStats.attack(), tankSkills, botTank ? stats.selfBuffs() : 1.0, w, tankShare) * tankShots * tankBuff;
			for (int i = 0; i < dpsCount; i++)
			{
				dps += (rotationDpsShare(z, dRole[i], dClass[i], level, dStats[i].attack(), dSkills[i], dSelf[i], w, dShare[i]) * dShots[i] * dBuff[i]) + (petDps(z, dClass[i], level, dSkills[i]) * meleeBuff);
			}
			fight = z.hp() / Math.max(1e-6, dps);
			tankShare = partyShare(z, Role.TANK, tankClass, level, tankSkills, fight, fight + 2.5, sharedSit);
			for (int i = 0; i < dpsCount; i++)
			{
				dShare[i] = partyShare(z, dRole[i], dClass[i], level, dSkills[i], fight, fight + 2.5, sharedSit);
			}
		}
		// A ranged damage dealer (archer or mage, the healer's mage included) pulls the mob for the group, so the party walks less between kills.
		final double overhead = (60.0 / _params.baseKillsPerMinute()) * (1.0 - _params.fightShare()) * ((dpsRole == Role.BOW || dpsRole == Role.MAGE) ? _rangedWalk : 1.0);
		double kills = 60.0 / (overhead + fight);
		// Resting: the tank sits for what the healer does not heal, the healer sits for its MP (it burns more than an attacking mage); a death resets the party.
		double factor = 1.0;
		if (_restOn && (tankRow != null) && (mageRow != null))
		{
			final double cycle = fight + 2.5;
			final double[] ownRow = restRow(z.name(), role, level);
			final double sitSpoil = LivingSupplies.isSpoiler(classId) && (ownRow != null) ? spoilSitSeconds(ownRow, level, true) : 0.0; // only when the bot itself spoils
			factor = cycle / (cycle + Math.max(sharedSit.applyAsDouble(fight, cycle), sitSpoil));
		}
		// Deaths: the mobs hit the tank (it holds their attention) and the healer heals a share of its damage as it comes in. The healer tops the tank up to full between fights and the party waits when the healer is out of mana (the rest factor above), so every fight starts at full HP: a party death needs a burst the heals do not cover, or more monsters at once.
		final double pBuff = partyBuff(buffers, Role.TANK, 1, level);
		final double[] hpRow = restRow(z.name(), Role.TANK, level);
		final double chain = Math.pow(p.chainChance(), slot); // when the tank dies the next in line may follow
		double deathsPerHour = -1.0;
		double events;
		if (_hpDeaths && (hpRow != null) && (hpRow.length >= 6) && (hpRow[5] > 0.0))
		{
			deathsPerHour = hpDeathRate(z, hpRow, Role.TANK, level, Math.max(1.0, tankStats.pDef() * pBuff), fight, kills * factor * 60.0, p.healCoverage(), true);
			events = deathsPerHour / Math.max(1e-6, p.baseDeathsPerHour());
			deathsPerHour = Math.max(1e-9, deathsPerHour * chain);
		}
		else
		{
			// No rest data for the zone: the old threat formula.
			final double mean = _threatMedian[Role.TANK.ordinal()];
			final double mBuff = partyBuff(buffers, Role.TANK, 2, level);
			final double tankThreat = threat(z, tankStats.pDef(), tankStats.mDef(), pBuff, mBuff, hitChance(z, Role.TANK, level));
			final double tankFactor = (mean <= 0) ? 1.0 : Math.max(_params.minDeathFactor(), Math.min(_params.maxDeathFactor(), tankThreat / mean));
			events = tankFactor * (1.0 + (_aggroRisk * z.aggressivePercent() / 100.0)); // party deaths relative to the base rate
		}
		final double deathFactor = Math.max(1e-6, events * chain);
		final double resetShare = Math.min(0.9, (events * p.baseDeathsPerHour() * p.resetSeconds()) / 3600.0);
		kills = Math.min(_params.maxKillsPerMinute(), kills * factor * (1.0 - resetShare));
		boolean spoils = LivingSupplies.isSpoiler(classId);
		for (int c : dClass)
		{
			spoils |= LivingSupplies.isSpoiler(c);
		}
		return new PartyOutcome(kills, deathFactor, deathsPerHour, EXP_BONUS[size - 1] / size, slot, buffer, 1.0 / size, spoils);
	}

	/**
	 * The share of its rotation's skills a party member keeps up. It never holds the party up for its own MP: it casts until its pool is empty and auto-attacks after, and the MP it gets back
	 * (its regen over the cycle, and the refill during the sit the party takes anyway) is a bonus.
	 * @param fight the party's fight, in seconds
	 * @param sharedSit what the party sits for anyway per kill (the tank's HP the healer does not cover, the healer's mana) for a fight and cycle of these lengths
	 */
	private double partyShare(ZoneStats z, Role role, int classId, int level, double skillFraction, double fight, double cycle, DoubleBinaryOperator sharedSit)
	{
		final String line = _rotationLine.get(classId);
		final double mp = rotationMp(line, level);
		final double[] r = restRow(z.name(), role, level);
		final double need = Math.max(0.0, Math.min(1.0, skillFraction)) * mp * fight; // the MP of casting the whole fight
		if ((line == null) || (mp <= 0.0) || (r == null) || (r[4] <= 0.0) || (need <= 0.0))
		{
			return 1.0;
		}
		final double budget = (r[4] * 1.1 / 1.5 * cycle) + (r[4] * sharedSit.applyAsDouble(fight, cycle));
		return Math.min(1.0, budget / need);
	}

	private double[] restRow(String zone, Role role, int level)
	{
		final Map<Role, java.util.TreeMap<Integer, double[]>> byRole = _rest.get(zone);
		final java.util.TreeMap<Integer, double[]> table = (byRole == null) ? null : byRole.get(role);
		if ((table == null) || table.isEmpty())
		{
			return null;
		}
		return ((table.floorEntry(level) != null) ? table.floorEntry(level) : table.firstEntry()).getValue();
	}

	/** @return whether this class has a rotation line */
	public boolean hasRotation(int classId)
	{
		final String line = _rotationLine.get(classId);
		return (line != null) && _rotations.containsKey(line);
	}

	/**
	 * Seconds of fighting per kill from the rotation data: zone HP over the rotation's damage per second against the zone's defence.
	 * The rotation is the sim's best at the level (its best gear), scaled by the bot's weapon over the best weapon of its grade;
	 * skills the bot has not learned are skipped (their share of the rotation's damage is lost, auto-attacks stay).
	 */
	private double rotationFightSeconds(ZoneStats zone, Role role, int classId, int level, double weaponAttack, double skillFraction, double selfShare)
	{
		double seconds = 0.0;
		for (int pass = 0; pass < 2; pass++)
		{
			final int w = windowFor(pass, seconds);
			// Servitors are auto-attackers: they get the fighter buffs, not the summoner's (the caller then divides by the summoner's own buff).
			final double petRatio = buff(Role.MELEE, 0, level) / Math.max(1e-9, buff(role, 0, level));
			seconds = zone.hp() / Math.max(1e-6, rotationDps(zone, role, classId, level, weaponAttack, skillFraction, selfShare, w) + (petDps(zone, classId, level, skillFraction) * petRatio));
		}
		return seconds;
	}

	/**
	 * Damage per second of the class's rotation against the zone's defence over a window (0 to 6), scaled by the weapon and the skills it has.
	 * In a zone with undead monsters, a line that has an undead rotation (the healers' Turn Undead style skills, Phoenix Knight) uses it for the undead share of the kills.
	 */
	private double rotationDps(ZoneStats zone, Role role, int classId, int level, double weaponAttack, double skillFraction, double selfShare, int window)
	{
		return rotationDpsShare(zone, role, classId, level, weaponAttack, skillFraction, selfShare, window, sustainedShare(zone, role, classId, level, weaponAttack, skillFraction, selfShare));
	}

	/** @param share the share of the rotation's skills the bot keeps up (see {@link #sustainedShare} and {@link #partyShare}) */
	private double rotationDpsShare(ZoneStats zone, Role role, int classId, int level, double weaponAttack, double skillFraction, double selfShare, int window, double share)
	{
		final String line = _rotationLine.get(classId);
		final double plain = rotationDpsOf(zone, role, classId, level, weaponAttack, skillFraction, selfShare, window, _rotations.get(line), share);
		final double undead = _undeadShare.getOrDefault(zone.name(), 0.0);
		final java.util.TreeMap<Integer, double[]> undeadTable = (line == null) ? null : _rotationsUndead.get(line);
		if ((undead <= 0.0) || (undeadTable == null))
		{
			return plain;
		}
		final double against = Math.max(plain, rotationDpsOf(zone, role, classId, level, weaponAttack, skillFraction, selfShare, window, undeadTable, share)); // never slower than the plain rotation (Phoenix Knight's is no better)
		return 1.0 / (((1.0 - undead) / Math.max(1e-9, plain)) + (undead / Math.max(1e-9, against))); // kills take the time of their own kind: average the seconds, not the damage
	}

	/** @param share the share of the rotation's skills the bot keeps up (see {@link #sustainedShare}); 1 = the full rotation */
	private double rotationDpsOf(ZoneStats zone, Role role, int classId, int level, double weaponAttack, double skillFraction, double selfShare, int window, java.util.TreeMap<Integer, double[]> table, double share)
	{
		if (table == null)
		{
			return zone.hp() / Math.max(1e-6, _killScale[role.ordinal()] * rawTimeToKill(zone, role, level, weaponAttack, skillFraction)); // no rotation line: the relative model
		}
		final java.util.Map.Entry<Integer, double[]> entry = (table.floorEntry(level) != null) ? table.floorEntry(level) : table.firstEntry();
		final double[] r = entry.getValue();
		final double scale = (role == Role.MAGE) ? (SIM_MDEF / Math.max(1.0, zone.mDef())) : (SIM_PDEF / Math.max(1.0, zone.pDef()));
		final double refAttack = curve(role == Role.MAGE ? "matk_mage" : (role == Role.BOW ? "patk_bow" : "patk_melee"), LivingSupplies.gradeFor(level));
		final double ratio = (refAttack <= 0) ? 1.0 : Math.max(0.1, Math.min(1.5, weaponAttack / refAttack));
		final double gear = (role == Role.MAGE) ? Math.sqrt(ratio) : ratio;
		final double skills = ((role == Role.MAGE) ? Math.max(0.1, skillFraction) : Math.max(0.0, Math.min(1.0, skillFraction))) * share;
		final double auto = Math.min(r[0], r[1 + window]);
		double dps = (auto + ((r[1 + window] - auto) * skills)) * scale * gear;
		// Self buffs the class has learned (free and permanent): the share it has bought of the ratio the sim measured with them all on.
		final java.util.Map.Entry<Integer, double[]> self = selfEntry(_rotationLine.get(classId), level);
		if (self != null)
		{
			dps *= 1.0 + (Math.max(1.0, self.getValue()[window]) - 1.0) * (selfShare < 0 ? Math.max(0.0, Math.min(1.0, skillFraction)) : Math.min(1.0, selfShare));
		}
		return dps;
	}

	/** @return the servitor's damage per second against the zone's defence, unbuffed (0 for any other class); the buffs are applied by the caller */
	private double petDps(ZoneStats zone, int classId, int level, double skillFraction)
	{
		final double[] pet = servitor(classId, level);
		return (pet == null) ? 0.0 : (pet[0] * (SIM_PDEF / Math.max(1.0, zone.pDef())) * Math.max(0.1, Math.max(0.0, Math.min(1.0, skillFraction))));
	}

	private java.util.Map.Entry<Integer, double[]> selfEntry(String line, int level)
	{
		final java.util.TreeMap<Integer, double[]> table = (line == null) ? null : _rotSelf.get(line);
		return (table == null) ? null : table.floorEntry(level);
	}

	/** @return the servitor's {dps, HP, P.Def} for a summoner class at a level, or null for any other class (or before the first summon) */
	private double[] servitor(int classId, int level)
	{
		final java.util.TreeMap<Integer, double[]> table = _serv.get(_rotationLine.get(classId));
		final java.util.Map.Entry<Integer, double[]> entry = (table == null) ? null : table.floorEntry(level);
		return (entry == null) ? null : entry.getValue();
	}

	/**
	 * @param classId a class id
	 * @param level the level
	 * @return the damage and defence self buff skill ids the class's rotation data assumes at that level (empty when it has none)
	 */
	public int[] selfBuffIds(int classId, int level)
	{
		final java.util.TreeMap<Integer, int[]> table = _rotSelfIds.get(_rotationLine.get(classId));
		final java.util.Map.Entry<Integer, int[]> entry = (table == null) ? null : table.floorEntry(level);
		return (entry == null) ? new int[0] : entry.getValue();
	}

	/**
	 * The share of a monster's fights the summoner is not shielded from. Monsters hit the servitor first (its HP drops before the summoner's); when it dies the summoner is
	 * exposed until it summons again.
	 * @return {share of time without the servitor, the summoner's exposure to damage (0 to 1), servitor HP healed per kill}
	 */
	private double[] servitorShield(ZoneStats zone, double[] pet, int level, double fightSeconds, double killsPerMinute, double healedHp)
	{
		final double damagePerFight = Math.max(0.0, servitorHit(zone, pet, level, fightSeconds) - healedHp); // Servitor Heal repairs part of it between hits
		final double servitorDeathsPerHour = killsPerMinute * 60.0 * damagePerFight / Math.max(1.0, pet[1]);
		final double dead = Math.min(SERVITOR_MAX_DEAD_SHARE, servitorDeathsPerHour * SERVITOR_RESUMMON_SECONDS / 3600.0);
		return new double[] { dead, Math.min(1.0, SERVITOR_BASE_EXPOSURE + dead) };
	}

	/** @return the damage the monsters do to the servitor in one fight */
	private double servitorHit(ZoneStats zone, double[] pet, int level, double fightSeconds)
	{
		final double defence = pet[2] * buff(Role.MELEE, 1, level); // the servitor's P.Def and HP carry the bot's buffs (Shield, Blessed Body, the buffers')
		return fightSeconds * zone.hitsPerSecond() * MOB_DAMAGE * zone.pAtk() * zone.hitMultiple() / Math.max(1.0, defence);
	}

	/** @return the HP the monsters take off a bot per kill beyond its standing regen, for a fight of this length (the monsters' hits for the whole fight, with the extra monsters that join); 0 without rest data */
	private double lostPerKill(String zone, Role role, int level, double fightSeconds)
	{
		final double[] r = restRow(zone, role, level);
		final Integer zi = _zoneIndex.get(zone);
		if ((r == null) || (zi == null))
		{
			return 0.0;
		}
		return Math.max(0.0, damageTaken(_zones.get(zi), role, level, fightSeconds) - (r[2] * 1.1 / 1.5 * (fightSeconds + 2.5)));
	}

	/** @return the HP the zone's monsters take off a fighter in a fight of this length: one monster's average damage times the monsters that may join (n monsters do n(n+1)/2 times one's) */
	private double damageTaken(ZoneStats z, Role role, int level, double fight)
	{
		final int grade = LivingSupplies.gradeFor(level);
		final double defence = Math.max(1.0, curveStats(role, grade, grade).pDef() * buff(role, 1, level));
		return fightDamage(z, role, level, defence, fight)[0] * averageMonsters(z);
	}

	/** @return the average of n(n+1)/2 over the number of monsters in a fight of this zone: what the extra monsters multiply one monster's damage by */
	private double averageMonsters(ZoneStats z)
	{
		final double[] counts = monsterCounts(z);
		double weight = 0.0;
		for (int n = 1; n <= 6; n++)
		{
			weight += counts[n - 1] * (n * (n + 1) / 2.0);
		}
		return weight;
	}

	/** @return MP per second the line's 60 s rotation spends at the level, 0 without the data */
	private double rotationMp(String line, int level)
	{
		final java.util.TreeMap<Integer, Double> table = (line == null) ? null : _rotationMp.get(line);
		final java.util.Map.Entry<Integer, Double> entry = (table == null) ? null : ((table.floorEntry(level) != null) ? table.floorEntry(level) : table.firstEntry());
		return (entry == null) ? 0.0 : entry.getValue();
	}

	/** @return the seconds between one kill and the next that are not fighting: the walk and loot, from the flat kill rate and fight share (shorter for a faster bot, and for ranged classes) */
	private double overheadSeconds(Role role, int level)
	{
		return (60.0 / _params.baseKillsPerMinute()) * (1.0 - _params.fightShare()) / runSpeed(role, level) * ((role == Role.BOW || role == Role.MAGE) ? _rangedWalk : 1.0);
	}

	/**
	 * The share of the rotation's skills a class can keep up while hunting: the damage per second is the auto-attacks plus {@code share} of what the skills add, and the skills burn MP that regen does not
	 * make back. A bot sits for its HP anyway and refills MP in the same sit, so it casts as much as that sit covers; past that every skill costs sitting time. The share is the one with the most kills
	 * an hour (fight + walk + sit). Mages cast everything (no useful auto-attack). A party member never holds the party up for its MP, see {@link #partyShare}.
	 */
	private double sustainedShare(ZoneStats z, Role role, int classId, int level, double weaponAttack, double skillFraction, double selfShare)
	{
		final String line = _rotationLine.get(classId);
		final double mp = rotationMp(line, level);
		final double[] r = restRow(z.name(), role, level);
		final java.util.TreeMap<Integer, double[]> table = (line == null) ? null : _rotations.get(line);
		if ((role == Role.MAGE) || (mp <= 0.0) || (r == null) || (table == null) || (r[4] <= 0.0))
		{
			return 1.0;
		}
		final int window = windowFor(1, 60.0);
		final double overhead = overheadSeconds(role, level);
		final double hpSit = r[2];
		final double standingMp = r[4] * 1.1 / 1.5;
		final double standingHp = r[2] * 1.1 / 1.5;
		final double buffed = buff(role, 0, level);
		double best = 0.0;
		double bestKills = -1.0;
		for (int step = 0; step <= 10; step++)
		{
			final double share = step / 10.0;
			final double dps = rotationDpsOf(z, role, classId, level, weaponAttack, skillFraction, selfShare, window, table, share) * buffed;
			final double fight = z.hp() / Math.max(1e-6, dps);
			final double cycle = fight + overhead;
			final double sitHp = Math.max(0.0, damageTaken(z, role, level, fight) - (standingHp * cycle)) / Math.max(1e-9, hpSit);
			final double sitMp = Math.max(0.0, (share * Math.max(0.0, Math.min(1.0, skillFraction)) * mp * fight) - (standingMp * cycle)) / r[4];
			final double kills = 1.0 / (cycle + Math.max(sitHp, sitMp));
			if (kills >= bestKills - 1e-12)
			{
				bestKills = Math.max(bestKills, kills);
				best = share;
			}
		}
		return best;
	}

	/**
	 * What the heals a line has learned can do in one fight, cheapest mana per HP first. Each cast heals the skill's power plus the server's bonus from the caster's M.Atk
	 * (sqrt of 2 x M.Atk, 4 x with blessed spiritshots; with spiritshots a mage also adds the skill's mana cost, 2.4 x with blessed), costs its mana and cast time, and can be cast once per reuse.
	 * @return {HP the bot heals itself per kill, HP the servitor is healed per kill, mana for both, seconds spent casting}
	 */
	private double[] healPlan(String line, int level, double mAtk, boolean shots, boolean blessed, double fightSeconds, double selfNeed, double servitorNeed)
	{
		final java.util.TreeMap<Integer, List<double[]>> table = (line == null) ? null : _heals.get(line);
		final java.util.Map.Entry<Integer, List<double[]>> entry = (table == null) ? null : table.floorEntry(level);
		if (entry == null)
		{
			return new double[4];
		}
		final double bonusRoot = Math.sqrt((shots && blessed ? 4.0 : 2.0) * Math.max(0.0, mAtk));
		final List<double[]> casts = new ArrayList<>();
		for (double[] h : entry.getValue())
		{
			final double staticBonus = shots ? ((blessed ? 2.4 : 1.0) * h[2]) : 0.0;
			casts.add(new double[] { h[0], h[1] + bonusRoot + staticBonus, h[2], h[3], h[4] });
		}
		casts.sort((x, y) -> Double.compare(y[1] / Math.max(1e-9, y[2]), x[1] / Math.max(1e-9, x[2])));
		double self = 0.0;
		double servitor = 0.0;
		double mana = 0.0;
		double seconds = 0.0;
		double needSelf = Math.max(0.0, selfNeed);
		double needServitor = Math.max(0.0, servitorNeed);
		for (double[] c : casts)
		{
			final boolean forServitor = c[0] == 1127;
			final double need = forServitor ? needServitor : needSelf;
			if (need <= 0.0)
			{
				continue;
			}
			final double n = Math.min(need / c[1], fightSeconds / Math.max(c[3], c[4])); // as many casts as the need asks for and the reuse allows
			if (forServitor)
			{
				servitor += n * c[1];
				needServitor -= n * c[1];
			}
			else
			{
				self += n * c[1];
				needSelf -= n * c[1];
			}
			mana += n * c[2];
			seconds += n * c[3];
		}
		return new double[] { self, servitor, mana, seconds };
	}

	/** @return the mana per HP healed of the cheapest heal the line has learned at the level (0 without data); the party healer uses it */
	private double healMpPerHp(int classId, int level, double mAtk)
	{
		final java.util.TreeMap<Integer, List<double[]>> table = _heals.get(_rotationLine.get(classId));
		final java.util.Map.Entry<Integer, List<double[]>> entry = (table == null) ? null : table.floorEntry(level);
		if (entry == null)
		{
			return 0.0;
		}
		double best = 0.0;
		for (double[] h : entry.getValue())
		{
			if (h[0] != 1127)
			{
				best = Math.max(best, (h[1] + Math.sqrt(2.0 * Math.max(0.0, mAtk))) / Math.max(1e-9, h[2]));
			}
		}
		return (best <= 0.0) ? 0.0 : (1.0 / best);
	}

	/** @return the rotation window to read: the fixed one when {@link #setRotationWindow} set it, else 5 s on the first pass and the one nearest the fight after */
	private int windowFor(int pass, double seconds)
	{
		final int fixed = _rotationWindow;
		return (fixed >= 0) ? fixed : ((pass == 0) ? 0 : nearestWindow(seconds));
	}

	/**
	 * @param seconds the rotation window every fight uses (5, 15, 30, 45, 60, 90 or 120; the nearest is taken), or 0 to pick the window nearest each fight's length.
	 * A bot goes from monster to monster, so its long-run rate (60 s) is a better guide than the opening burst of a short window.
	 */
	public void setRotationWindow(int seconds)
	{
		_rotationWindow = (seconds <= 0) ? -1 : nearestWindow(seconds);
		_killCache.clear();
		_partyCache.clear();
	}

	private static int nearestWindow(double seconds)
	{
		int best = 0;
		for (int i = 1; i < ROTATION_WINDOWS.length; i++)
		{
			if (Math.abs(ROTATION_WINDOWS[i] - seconds) < Math.abs(ROTATION_WINDOWS[best] - seconds))
			{
				best = i;
			}
		}
		return best;
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
		_rateCache.clear();
	}

	/**
	 * Sets how much the shots add. The calibration assumes shots, so a bot without them does that much less damage.
	 * @param soulshot damage with soulshots over without, for physical roles (at least 1)
	 * @param spiritshot damage with spiritshots over without, for mages (at least 1)
	 */
	public void setBlessedDamage(double blessedSpiritshot)
	{
		_blessedDamage = Math.max(1.0, blessedSpiritshot);
	}

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
		_rateCache.clear();
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
		return killsPerMinute(zone, classId, level, stats, skillFraction, shotFraction, false);
	}

	/**
	 * Like the stats version, saying which spiritshot a mage fires.
	 * @param blessed true when the bot fires blessed spiritshots (a mage only; M.Atk x4 instead of x2)
	 * @return kills per minute
	 */
	public double killsPerMinute(String zone, int classId, int level, Stats stats, double skillFraction, double shotFraction, boolean blessed)
	{
		return killsPerMinute(zone, classId, level, stats, skillFraction, shotFraction, blessed, 0.0);
	}

	/**
	 * Like the blessed version, with the potions the bot drinks, which heal part of the HP it would otherwise sit to regain.
	 * @param potionsPerHour how many healing potions it drinks an hour (0 without potions)
	 * @return kills per minute
	 */
	public double killsPerMinute(String zone, int classId, int level, Stats stats, double skillFraction, double shotFraction, boolean blessed, double potionsPerHour)
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
		final boolean rotation = _rotationTtk && hasRotation(classId);
		final long selfIdx = (stats.selfBuffs() < 0) ? 11L : Math.round(Math.min(1.0, stats.selfBuffs()) * 10.0);
		final long key = (((((((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8192L + attack) * 16L * 22L + (skills * 22L) + (shots * 2L) + ((blessed && (role == Role.MAGE)) ? 1L : 0L)) * 128L + (rotation ? (classId & 127) + 0L : 0L)) * 256L + Math.min(255L, Math.round(potionsPerHour * 10.0))) * 12L + selfIdx) * 2L + (LivingSupplies.isSpoiler(classId) ? 1L : 0L);
		final double selfBuffs = stats.selfBuffs();
		return _killCache.computeIfAbsent(key, k ->
		{
			// A bot only heals itself when that nets it more experience (see healsHelp): the casting and the mana cost kills, the heals save deaths.
			return killRate(zi, role, classId, level, attack, skills, shots, blessed, potionsPerHour, selfBuffs, rotation, healsHelp(zi, role, classId, level, stats));
		});
	}

	private double killRate(int zi, Role role, int classId, int level, long attack, int skills, int shots, boolean blessed, double potionsPerHour, double selfBuffs, boolean rotation, boolean heal)
	{
		double fightSeconds = (rotation ? rotationFightSeconds(_zones.get(zi), role, classId, level, attack, skills / 10.0, selfBuffs) : (_killScale[role.ordinal()] * rawTimeToKill(_zones.get(zi), role, level, attack, skills / 10.0))) / buff(role, 0, level) / shotDamage(role, shots / 10.0, blessed);
		final double[] pet = rotation ? servitor(classId, level) : null;
		// Heals: what the bot's learned heals can cover in a fight of this length, the mana they cost and the casting time they take away from attacking (two passes: the casting lengthens the fight).
		double healCover = 0.0;
		double healMana = 0.0;
		double servHealed = 0.0;
		if (heal && rotation && (role == Role.MAGE))
		{
		for (int pass = 0; pass < 2; pass++)
		{
			final double lost = lostPerKill(_zones.get(zi).name(), role, level, fightSeconds);
			final double hit = (pet == null) ? 0.0 : servitorHit(_zones.get(zi), pet, level, fightSeconds);
			final double[] plan = healPlan(_rotationLine.get(classId), level, attack, shots > 0, blessed, fightSeconds, lost, hit);
			healCover = (lost <= 0.0) ? 0.0 : Math.min(1.0, plan[0] / lost);
			servHealed = plan[1];
			healMana = plan[2];
			if (pass == 0)
			{
				fightSeconds += plan[3];
			}
		}
		}
		final double overhead = overheadSeconds(role, level); // the walk between monsters shrinks with the bot's speed, and ranged classes move less
		double kills = 60.0 / (overhead + fightSeconds);
		final double[] shield = (pet == null) ? null : servitorShield(_zones.get(zi), pet, level, fightSeconds, kills, servHealed);
		if (_restOn)
		{
		final double mpUsed = rotation ? (rotationMp(_rotationLine.get(classId), level) * Math.max(0.0, Math.min(1.0, skills / 10.0)) * sustainedShare(_zones.get(zi), role, classId, level, attack, skills / 10.0, selfBuffs) * fightSeconds) : -1.0;
		kills *= restFactor(_zones.get(zi).name(), role, level, fightSeconds, overhead + fightSeconds, mpUsed, potionsPerHour, kills, LivingSupplies.isSpoiler(classId), healCover, healMana);
		}
		if (shield != null)
		{
		kills *= 1.0 - shield[0]; // time spent summoning again
		}
		return Math.min(_params.maxKillsPerMinute(), kills);
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
		final long selfIdx = (stats.selfBuffs() < 0) ? 11L : Math.round(Math.min(1.0, stats.selfBuffs()) * 10.0);
		final long key = (((((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8192L) + pDef) * 8192L + mDef) * 12L * 128L + (selfIdx * 128L) + (classId & 127);
		return _deathCache.computeIfAbsent(key, k -> deathCalc(zi, role, classId, level, stats, healsHelp(zi, role, classId, level, stats)));
	}

	private double deathCalc(int zi, Role role, int classId, int level, Stats stats, boolean heal)
	{
		final long pDef = Math.max(0, Math.min(8191, Math.round(stats.pDef())));
		final long mDef = Math.max(0, Math.min(8191, Math.round(stats.mDef())));
		final double mean = _threatMedian[role.ordinal()];
		final ZoneStats z = _zones.get(zi);
		// Self buffs that change defence (Majesty, Iron Will, Rage's penalty ...), in proportion to the share the bot has learned.
		final java.util.Map.Entry<Integer, double[]> self = _rotationTtk ? selfEntry(_rotationLine.get(classId), level) : null;
		final double share = (stats.selfBuffs() < 0) ? 1.0 : Math.min(1.0, stats.selfBuffs());
		final double selfP = (self == null) ? 1.0 : (1.0 + ((self.getValue()[ROTATION_WINDOWS.length] - 1.0) * share));
		final double selfM = (self == null) ? 1.0 : (1.0 + ((self.getValue()[ROTATION_WINDOWS.length + 1] - 1.0) * share));
		double gear = (mean <= 0) ? 1.0 : Math.max(_params.minDeathFactor(), Math.min(_params.maxDeathFactor(), threat(z, pDef, mDef, buff(role, 1, level) * selfP, buff(role, 2, level) * selfM, hitChance(z, role, level)) / mean));
		final double hpDeaths = _hpDeaths ? hpRate(zi, role, classId, level, stats, heal) : -1.0;
		if (hpDeaths >= 0.0)
		{
			// The HP model gives deaths an hour outright; this factor is only for callers that still multiply the base rate by it, so divide the base and the level position out.
			final double half = Math.max(1.0, (z.maxLevel() - z.minLevel()) / 2.0);
			final double weakness = Math.max(-1.5, Math.min(1.5, (((z.minLevel() + z.maxLevel()) / 2.0) - level) / half));
			return hpDeaths / (_deathBase * Math.pow(2.0, weakness));
		}
		final double[] pet = (_rotationTtk && hasRotation(classId)) ? servitor(classId, level) : null;
		if (pet != null)
		{
			// The servitor takes the hits first: the summoner is only exposed while it is down (plus a base share of attacks that still reach the master).
			final double fight = rotationFightSeconds(z, role, classId, level, curve(role == Role.MAGE ? "matk_mage" : "patk_melee", LivingSupplies.gradeFor(level)), 1.0, 1.0);
			final double[] petPlan = heal ? healPlan(_rotationLine.get(classId), level, stats.attack(), false, false, fight, 0.0, servitorHit(z, pet, level, fight)) : new double[4];
			gear *= servitorShield(z, pet, level, fight, _params.baseKillsPerMinute() * 0.6, petPlan[1])[1];
		}
		if (heal)
		{
			// Self heals keep it up in the middle of a fight: the share of the HP it loses that its heals cover takes that share (halved, a guess: bursts still kill) off the deaths.
			final double fight = rotationFightSeconds(z, role, classId, level, stats.attack(), 1.0, 1.0);
			final double lost = lostPerKill(z.name(), role, level, fight);
			final double cover = (lost <= 0.0) ? 0.0 : Math.min(1.0, healPlan(_rotationLine.get(classId), level, stats.attack(), false, false, fight, lost, 0.0)[0] / lost);
			gear *= 1.0 - (0.5 * cover);
		}
		return gear * (1.0 + (_aggroRisk * z.aggressivePercent() / 100.0));
	}

	/**
	 * Whether a bot is better off healing itself: the heals cost kills (casting and mana) and save deaths, so it uses them when the experience it nets an hour, after the experience its deaths cost, is higher.
	 * Without the experience model it uses them when they raise the kills.
	 */
	private boolean healsHelp(int zi, Role role, int classId, int level, Stats stats)
	{
		if (!_selfHealOn || !_rotationTtk || (role != Role.MAGE) || !hasRotation(classId))
		{
			return false;
		}
		final List<Object> key = List.of(zi, classId, level, Math.round(stats.attack()), Math.round(stats.pDef()), Math.round(stats.mDef()));
		return _healCache.computeIfAbsent(key, k ->
		{
			final long a = Math.round(stats.attack());
			final double killsWith = killRate(zi, role, classId, level, a, 10, 0, false, 0.0, stats.selfBuffs(), true, true);
			final double killsWithout = killRate(zi, role, classId, level, a, 10, 0, false, 0.0, stats.selfBuffs(), true, false);
			final java.util.function.IntToLongFunction span = _expSpan;
			if (span == null)
			{
				return killsWith > killsWithout;
			}
			final double loss = (ColdRisk.expLossPercent(level) / 100.0) * Math.max(1L, span.applyAsLong(level));
			final double gain = _zones.get(zi).expPerKill() * _expRate * 60.0;
			final double base = _partyParams.baseDeathsPerHour();
			final double netWith = (gain * killsWith) - (base * deathCalc(zi, role, classId, level, stats, true) * loss);
			final double netWithout = (gain * killsWithout) - (base * deathCalc(zi, role, classId, level, stats, false) * loss);
			return netWith > netWithout;
		});
	}

	/**
	 * @param span the experience between a level and the next (to weigh a bot's heals against the deaths they save)
	 * @param rate the server's experience rate
	 */
	public void setExpModel(java.util.function.IntToLongFunction span, double rate)
	{
		if (Math.abs(rate - _expRate) > 1e-9)
		{
			_expRate = rate;
			_killCache.clear();
			_deathCache.clear();
		_rateCache.clear();
			_healCache.clear();
			_partyCache.clear();
		}
		_expSpan = span;
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
				threats[i] = threat(zone, curveStats(role, grade, grade).pDef(), curveStats(role, grade, grade).mDef(), 1.0, 1.0, hitChance(zone, role, level));
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
	private double threat(ZoneStats zone, double pDef, double mDef, double pDefBuff, double mDefBuff, double hit)
	{
		final double physical = hit * zone.pAtk() / Math.max(1.0, pDef * pDefBuff);
		final double magical = zone.mAtk() / Math.max(1.0, mDef * mDefBuff);
		return Math.max(physical, magical);
	}

	/**
	 * @return damage relative to a bot that fires its plain shots all the time (what the calibration assumes): 1 with plain shots all
	 *         the time, down to 1 / (the shots' bonus) with none, and above 1 for a mage firing blessed spiritshots
	 */
	private double shotDamage(Role role, double fraction, boolean blessed)
	{
		final double base = (role == Role.MAGE) ? _spiritshotDamage : _soulshotDamage;
		final double bonus = (blessed && (role == Role.MAGE)) ? Math.max(base, _blessedDamage) : base;
		return ((fraction * bonus) + (1.0 - fraction)) / base;
	}

	/** @return the blended buff multiplier (kind 0 damage, 1 P.Def, 2 M.Def) for a role at a level: 1 with no share or no data */
	private double buff(Role role, int kind, int level)
	{
		if (_startingBuffs)
		{
			return startingBuff(role, kind, level); // a solo bot carries a Hierophant's or Doom Cryer's buffs, never the full buffer party's (BuffedLeveling is ignored)
		}
		final double share = _buffShare[role.ordinal()];
		final double[][] table = _buffs.get(role);
		if ((share <= 0.0) || (table == null))
		{
			return 1.0;
		}
		final double full = table[kind][Math.max(0, Math.min(MAX_BUFF_LEVEL, level))];
		return (full <= 0.0) ? 1.0 : (1.0 + (share * (full - 1.0)));
	}

	/**
	 * The buffs a solo bot sets out with: the Newbie Helper's from level 8 to 25, then a Hierophant's or a Doom Cryer's at the bot's level (the average of the two,
	 * since which one buffed it is not tracked). Nothing below level 8.
	 */
	private double startingBuff(Role role, int kind, int level)
	{
		if (level < NEWBIE_FROM_LEVEL)
		{
			return 1.0;
		}
		if (level <= NEWBIE_TO_LEVEL)
		{
			final double[][] table = _newbie.get(role);
			final double v = (table == null) ? 0.0 : table[kind][Math.min(MAX_BUFF_LEVEL, level)];
			return (v <= 0.0) ? 1.0 : v;
		}
		final double base = (partyBuff("hierophant", role, kind, level) + partyBuff("doom_cryer", role, kind, level)) / 2.0;
		final double[] extra = (kind == 0) ? null : bufferExtras(level);
		return (extra == null) ? base : (base * extra[3]); // Blessed Body counts as effective HP in the death rate
	}

	/**
	 * @return how much faster than a plain bot it runs between monsters: Wind Walk for Beginners at levels 8-24, a Hierophant's Wind Walk and Berserker Spirit from 26 (the class's own
	 *         run-speed self buffs are all short, so they are not counted); 1 without them
	 */
	private double runSpeed(Role role, int level)
	{
		double speed = 1.0;
		if (_startingBuffs && (level >= NEWBIE_FROM_LEVEL) && (level <= NEWBIE_TO_LEVEL) && (_newbie.get(role) != null))
		{
			speed = Math.max(speed, _newbie.get(role)[5][Math.min(MAX_BUFF_LEVEL, level)] <= 0.0 ? 1.0 : _newbie.get(role)[5][Math.min(MAX_BUFF_LEVEL, level)]);
		}
		final double[] extras = (_startingBuffs && (level > NEWBIE_TO_LEVEL)) ? bufferExtras(level) : null;
		if (extras != null)
		{
			speed = Math.max(speed, (120.0 + extras[0]) / 120.0);
		}
		return speed;
	}

	/** @return the average of a Hierophant's and a Doom Cryer's {run speed added, HP regen mul, absorb share, HP mul} at a level above the Newbie Helper's, or null without data */
	private double[] bufferExtras(int level)
	{
		final java.util.TreeMap<Integer, double[]> a = _bufferExtras.get("hierophant");
		final java.util.TreeMap<Integer, double[]> b = _bufferExtras.get("doom_cryer");
		if ((a == null) || (b == null) || (a.floorEntry(level) == null) || (b.floorEntry(level) == null))
		{
			return null;
		}
		final double[] x = a.floorEntry(level).getValue();
		final double[] y = b.floorEntry(level).getValue();
		return new double[] { (x[0] + y[0]) / 2.0, (x[1] + y[1]) / 2.0, (x[2] + y[2]) / 2.0, (x[3] + y[3]) / 2.0 };
	}

	/**
	 * @param on whether deaths come from the HP model: a bot sits when its HP falls to the level at which a fight is riskier than {@code fightRisk}, and dies in a fight whose damage exceeds the HP it started it with
	 * @param fightRisk the chance of dying in one fight that a bot accepts before it sits (0.002 = 1 in 500); a more dangerous zone raises the HP it keeps
	 * @param baseDeathsPerHour the cold death rate per hour that {@link #deathFactor} is a multiple of
	 */
	public void setHpDeaths(boolean on, double fightRisk, double baseDeathsPerHour)
	{
		_hpDeaths = on;
		_fightRisk = Math.max(1e-9, Math.min(0.5, fightRisk));
		_deathBase = Math.max(1e-6, baseDeathsPerHour);
		_deathCache.clear();
		_rateCache.clear();
	}

	/** Shots one attack (a cast for a mage) fires, by weapon grade (none, D, C, B, A, S): the datapack's median of weapons with that grade. */
	private static final double[] SHOTS_MELEE = { 2, 2, 3, 1, 1, 1 };
	private static final double[] SHOTS_BOW = { 6, 6, 8, 3, 2, 1 };
	private static final double[] SHOTS_MAGE = { 2, 2, 3, 1, 1, 1 };

	/**
	 * @param on whether a kill's shots follow its hits (see {@link #shotsPerKill}); off keeps the flat per-kill setting
	 * @param meleeInterval seconds between a melee bot's attacks
	 * @param bowInterval seconds between an archer's shots
	 * @param castInterval seconds between a mage's casts
	 */
	public void setShotModel(boolean on, double meleeInterval, double bowInterval, double castInterval)
	{
		_shotsFromHits = on;
		_hitInterval = new double[] { Math.max(0.2, meleeInterval), Math.max(0.2, bowInterval), Math.max(0.2, castInterval) };
	}

	/** @return whether shots per kill come from the hits it takes to kill */
	public boolean shotModel()
	{
		return _shotsFromHits;
	}

	/**
	 * Shots a kill uses: the hits it takes (the fight, with shots on, over the time between attacks) times the shots the weapon fires per attack.
	 * @param zone the zone
	 * @param classId the class
	 * @param level the level (it fixes the weapon grade)
	 * @param stats its gear stats
	 * @param skillFraction the share of its skills learned
	 * @param blessed true for blessed spiritshots (a mage's fight is shorter)
	 * @return shots per kill, or a negative number when the model is off or the zone is unknown (use the flat setting)
	 */
	public double shotsPerKill(String zone, int classId, int level, Stats stats, double skillFraction, boolean blessed)
	{
		final Integer zi = (_shotsFromHits && knows(zone)) ? _zoneIndex.get(zone) : null;
		if (zi == null)
		{
			return -1.0;
		}
		final Role role = roleOf(classId);
		final int skills = (int) Math.round(Math.max(0.0, Math.min(1.0, skillFraction)) * 10.0);
		final long attack = Math.max(0, Math.min(8191, Math.round(stats.attack())));
		final boolean rotation = _rotationTtk && hasRotation(classId);
		final double selfBuffs = stats.selfBuffs();
		final double fight = fightSecondsWithShots(zi, role, classId, level, stats, skillFraction, blessed);
		final boolean mage = role == Role.MAGE;
		final double interval = mage ? _hitInterval[2] : (role == Role.BOW) ? _hitInterval[1] : _hitInterval[0];
		final double[] table = mage ? SHOTS_MAGE : (role == Role.BOW) ? SHOTS_BOW : SHOTS_MELEE;
		return Math.max(1.0, Math.ceil(fight / interval)) * table[LivingSupplies.gradeFor(level)];
	}

	/**
	 * Potions a cold bot actually drinks an hour: the most it carries the habit of ({@code maxPerHour}), cut to what its zone gives it a use for.
	 * A potion only pays when HP is what makes the bot sit (a mana-bound caster or a bot that does not sit gains nothing), and it never drinks more than the HP it loses.
	 * @return potions per hour, or {@code maxPerHour} when the zone model does not know the zone
	 */
	public double potionsPerHour(String zone, int classId, int level, Stats stats, double skillFraction, double maxPerHour)
	{
		final Integer zi = (_restOn && knows(zone)) ? _zoneIndex.get(zone) : null;
		if ((zi == null) || (maxPerHour <= 0.0))
		{
			return Math.max(0.0, maxPerHour);
		}
		final double with = killsPerMinute(zone, classId, level, stats, skillFraction, 1.0, false, maxPerHour);
		final double without = killsPerMinute(zone, classId, level, stats, skillFraction, 1.0, false, 0.0);
		if (with <= (without * 1.0005))
		{
			return 0.0; // sitting for mana (or not sitting at all): a potion buys nothing
		}
		final Role role = roleOf(classId);
		final Map<Role, java.util.TreeMap<Integer, double[]>> byRole = _rest.get(zone);
		final java.util.TreeMap<Integer, double[]> table = (byRole == null) ? null : byRole.get(role);
		if ((table == null) || table.isEmpty())
		{
			return maxPerHour;
		}
		final double[] r = ((table.floorEntry(level) != null) ? table.floorEntry(level) : table.firstEntry()).getValue();
		final double lostPerHour = with * 60.0 * r[1];
		return Math.min(maxPerHour, lostPerHour / ((level < 20) ? POTION_HEAL_LESSER : POTION_HEAL));
	}

	/** @return the seconds a bot of this class fights one monster of the zone with its shots on (the rotation's window average, or the relative model) */
	private double fightSecondsWithShots(int zi, Role role, int classId, int level, Stats stats, double skillFraction, boolean blessed)
	{
		final int skills = (int) Math.round(Math.max(0.0, Math.min(1.0, skillFraction)) * 10.0);
		final long attack = Math.max(0, Math.min(8191, Math.round(stats.attack())));
		final boolean rotation = _rotationTtk && hasRotation(classId);
		return (rotation ? rotationFightSeconds(_zones.get(zi), role, classId, level, attack, skills / 10.0, stats.selfBuffs()) : (_killScale[role.ordinal()] * rawTimeToKill(_zones.get(zi), role, level, attack, skills / 10.0))) / buff(role, 0, level) / shotDamage(role, 1.0, blessed);
	}

	/** Upper tail of the standard normal: the chance a value lands above {@code z} standard deviations. */
	private static double normalTail(double z)
	{
		final double x = Math.abs(z) / Math.sqrt(2.0);
		final double t = 1.0 / (1.0 + (0.3275911 * x));
		final double erfc = t * (0.254829592 + (t * (-0.284496736 + (t * (1.421413741 + (t * (-1.453152027 + (t * 1.061405429)))))))) * Math.exp(-x * x);
		return (z >= 0) ? (0.5 * erfc) : (1.0 - (0.5 * erfc));
	}

	/**
	 * @param chances the chance that a 2nd, 3rd, 4th, 5th and 6th monster joins a fight (each only after the one before it joined; a zone's aggressive share raises them, see {@link #setAggroRisk})
	 */
	public void setExtraMonsters(double[] chances)
	{
		_extraMonsters = chances.clone();
		_deathCache.clear();
		_rateCache.clear();
		_partyCache.clear();
	}

	/** @return the chance of fighting exactly 1, 2 ... 6 monsters at once in this zone (index 0 is one monster) */
	private double[] monsterCounts(ZoneStats z)
	{
		final double scale = 1.0 + (_aggroRisk * z.aggressivePercent() / 100.0);
		final double[] reach = new double[8]; // reach[n] = the chance the fight has at least n monsters
		reach[1] = 1.0;
		for (int n = 2; n <= 6; n++)
		{
			reach[n] = reach[n - 1] * ((n - 2 < _extraMonsters.length) ? Math.min(1.0, _extraMonsters[n - 2] * scale) : 0.0);
		}
		final double[] exactly = new double[6];
		for (int n = 1; n <= 6; n++)
		{
			exactly[n - 1] = reach[n] - reach[n + 1];
		}
		return exactly;
	}

	/** @return {mean, standard deviation} of the damage one monster does to a fighter in a fight of this length: its hits (76 x P.Atk / P.Def, with crits and shots, times the chance it hits) are compound Poisson */
	private double[] fightDamage(ZoneStats z, Role role, int level, double defence, double fight)
	{
		final double plain = (MOB_DAMAGE * z.pAtk()) / defence;
		final double hit = hitChance(z, role, level);
		final double crit = z.critPercent() / 100.0;
		final double randomPct = 5.0 + Math.sqrt(Math.max(1.0, z.mobLevel()));
		final double meanHit = hit * plain * z.hitMultiple();
		final double secondHit = hit * plain * plain * (1.0 + (3.0 * z.shotShare())) * (1.0 + (3.0 * crit)) * (1.0 + ((randomPct * randomPct) / 30000.0));
		final double lambda = z.hitsPerSecond() * fight;
		return new double[] { lambda * meanHit, Math.sqrt(Math.max(1e-9, lambda * secondHit)) };
	}

	/**
	 * The HP model's death rate for a fighter taking monsters' hits for {@code fight} seconds per kill. A fight starts with one monster, and a 2nd, 3rd ... up to a 6th can join it (see {@link #setExtraMonsters});
	 * they all attack from the start and die one after another, so n monsters do n(n+1)/2 times one monster's damage. The fighter sits when its HP falls below the level at which a fight is riskier than {@code ZoneFightRisk}
	 * (the monsters that may join are counted, so a dangerous zone makes it sit sooner), sits until full, and dies in a fight whose damage is more than the HP it started with.
	 * @param r the fighter's rest row (HP pool and sitting regen)
	 * @param defence the fighter's P.Def with buffs
	 * @param healCoverage the share of the average damage a healer heals as it comes in (0 = none); the spread of the damage is not healed
	 * @param startsFull whether every fight starts at full HP (a party: the healer tops the tank up and the party waits while the healer rests for mana), else the fighter sits only below its threshold
	 * @return deaths per hour
	 */
	private double hpDeathRate(ZoneStats z, double[] r, Role role, int level, double defence, double fight, double killsPerHour, double healCoverage, boolean startsFull)
	{
		final double pool = r[5];
		final double[] d = fightDamage(z, role, level, defence, fight);
		final double mean = d[0];
		final double sigma = d[1];
		final double[] counts = monsterCounts(z);
		double averageWeight = 0.0;
		for (int n = 1; n <= 6; n++)
		{
			averageWeight += counts[n - 1] * (n * (n + 1) / 2.0);
		}
		final double taken = 1.0 - healCoverage;
		final double standing = r[2] * 1.1 / 1.5; // the rest row holds the sitting regen (standing x 1.5 / 1.1)
		final double loss = Math.max(0.0, (mean * averageWeight * taken) - (standing * (fight + 2.5)));
		// The HP at which a fight's death chance (over the monsters that may join) is the risk the bot accepts: a dangerous zone puts it high, an easy one low.
		final double cap = pool * 0.9;
		double sitBelow = cap;
		if (fightRisk(counts, mean, sigma, taken, cap) < _fightRisk)
		{
			double low = 0.0;
			double high = cap;
			for (int i = 0; i < 40; i++)
			{
				final double mid = (low + high) / 2.0;
				if (fightRisk(counts, mean, sigma, taken, mid) > _fightRisk)
				{
					low = mid;
				}
				else
				{
					high = mid;
				}
			}
			sitBelow = high;
		}
		double hp = pool;
		double sum = 0.0;
		int fights = 0;
		while (true)
		{
			for (int n = 1; n <= 6; n++)
			{
				final double weight = n * (n + 1) / 2.0;
				sum += counts[n - 1] * normalTail((hp - (mean * weight * taken)) / (sigma * Math.sqrt(weight)));
			}
			fights++;
			hp -= loss;
			if (startsFull || (loss <= 1e-9) || (hp < sitBelow) || (fights >= 5000))
			{
				break;
			}
		}
		return killsPerHour * (sum / fights);
	}

	/** @return the chance a fight that starts at {@code hp} kills the fighter, over the number of monsters that may join it */
	private static double fightRisk(double[] counts, double mean, double sigma, double taken, double hp)
	{
		double risk = 0.0;
		for (int n = 1; n <= 6; n++)
		{
			final double weight = n * (n + 1) / 2.0;
			risk += counts[n - 1] * normalTail((hp - (mean * weight * taken)) / (sigma * Math.sqrt(weight)));
		}
		return risk;
	}

	/** Self buffs that raise P.Def (Majesty, Iron Will, Rage's penalty ...), in proportion to the share the bot has learned: the multiple of its P.Def. */
	private double selfPDef(int classId, int level, Stats stats)
	{
		final java.util.Map.Entry<Integer, double[]> self = _rotationTtk ? selfEntry(_rotationLine.get(classId), level) : null;
		final double share = (stats.selfBuffs() < 0) ? 1.0 : Math.min(1.0, stats.selfBuffs());
		return (self == null) ? 1.0 : (1.0 + ((self.getValue()[ROTATION_WINDOWS.length] - 1.0) * share));
	}

	/**
	 * Deaths an hour of a solo bot from the HP model: monsters one at a time with more joining at the zone's chances, the bot resting by its threshold, its own heals covering a share of the damage,
	 * and a servitor taking the hits first.
	 * @return deaths per hour, or -1 when the zone has no rest data (the HP pool comes from it)
	 */
	private double hpRate(int zi, Role role, int classId, int level, Stats stats, boolean heal)
	{
		final ZoneStats z = _zones.get(zi);
		final double[] r = restRow(z.name(), role, level);
		if ((r == null) || (r.length < 6) || (r[5] <= 0.0))
		{
			return -1.0;
		}
		final double fight = fightSecondsWithShots(zi, role, classId, level, stats, 1.0, false);
		final long attackRounded = Math.max(0, Math.min(8191, Math.round(stats.attack())));
		final double killsPerHour = killRate(zi, role, classId, level, attackRounded, 10, 10, false, 0.0, stats.selfBuffs(), _rotationTtk && hasRotation(classId), false) * 60.0; // uncached: this runs inside the death and heal caches
		final double defence = Math.max(1.0, Math.round(stats.pDef()) * buff(role, 1, level) * selfPDef(classId, level, stats));
		double cover = 0.0;
		if (heal)
		{
			// Self heals keep it up in the middle of a fight: the share of an average fight's damage that its heals cover.
			final double mean = fightDamage(z, role, level, defence, fight)[0];
			cover = (mean <= 0.0) ? 0.0 : Math.min(1.0, healPlan(_rotationLine.get(classId), level, stats.attack(), false, false, fight, mean, 0.0)[0] / mean);
		}
		double rate = hpDeathRate(z, r, role, level, defence, fight, killsPerHour, cover, false);
		final double[] pet = (_rotationTtk && hasRotation(classId)) ? servitor(classId, level) : null;
		if (pet != null)
		{
			// The servitor takes the hits first: the summoner is only exposed while it is down (plus a base share of attacks that still reach the master).
			final double petFight = rotationFightSeconds(z, role, classId, level, curve(role == Role.MAGE ? "matk_mage" : "patk_melee", LivingSupplies.gradeFor(level)), 1.0, 1.0);
			final double[] petPlan = heal ? healPlan(_rotationLine.get(classId), level, stats.attack(), false, false, petFight, 0.0, servitorHit(z, pet, level, petFight)) : new double[4];
			rate *= servitorShield(z, pet, level, petFight, killsPerHour / 60.0, petPlan[1])[1];
		}
		return rate;
	}

	/**
	 * @param zone the zone it hunts in
	 * @param classId its class
	 * @param level its level
	 * @param stats its worn gear
	 * @return its deaths an hour hunting alone, straight from the HP model; -1 when the model is off or the zone has no rest data (then {@link #deathFactor} and the cold risk's base rate apply)
	 */
	public double deathsPerHour(String zone, int classId, int level, Stats stats)
	{
		final Integer zi = (_hpDeaths && knows(zone)) ? _zoneIndex.get(zone) : null;
		if (zi == null)
		{
			return -1.0;
		}
		final Role role = roleOf(classId);
		final long key = ((((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8192L + Math.max(0, Math.min(8191, Math.round(stats.pDef())))) * 8192L + Math.max(0, Math.min(8191, Math.round(stats.mDef())))) * 12L * 128L + ((stats.selfBuffs() < 0 ? 11L : Math.round(Math.min(1.0, stats.selfBuffs()) * 10.0)) * 128L) + (classId & 127);
		return _rateCache.computeIfAbsent(key, k -> hpRate(zi, role, classId, level, stats, healsHelp(zi, role, classId, level, stats)));
	}

	/** @param factor the share of the walk and targeting time between monsters that ranged classes (archers and mages) keep: 0.5 halves it, 1 turns it off */
	public void setRangedWalk(double factor)
	{
		_rangedWalk = Math.max(0.05, Math.min(1.0, factor));
		_killCache.clear();
		_partyCache.clear();
	}

	/** @param on whether mystic-line bots (mages, healers, summoners) heal themselves with the heals they have learned, and summoners heal the servitor (from the HEAL rows: power, mana, cast time, reuse) */
	public void setSelfHeal(boolean on)
	{
		_selfHealOn = on;
		_healCache.clear();
		_killCache.clear();
		_deathCache.clear();
		_rateCache.clear();
		_partyCache.clear();
	}

	/** @param on whether a solo bot hunts with the Newbie Helper's buffs (levels 8-25) and then a Hierophant's or Doom Cryer's buffs at its level */
	public void setStartingBuffs(boolean on)
	{
		_startingBuffs = on;
		_killCache.clear();
		_deathCache.clear();
		_rateCache.clear();
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
