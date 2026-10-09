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
import java.util.Set;
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
	public record ZoneStats(String name, int minLevel, int maxLevel, double mobLevel, double hp, double pDef, double mDef, double pAtk, double mAtk, double respawnPerMinute, int spots, double aggressivePercent, double expPerKill, double accuracy)
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
	private final Map<String, java.util.TreeMap<Integer, double[]>> _rotations = new HashMap<>(); // line -> level -> {auto dps, dps over 5, 15, 30, 45, 60, 90, 120 s} against the sim's dummy
	private final Map<Integer, String> _rotationLine = new HashMap<>(); // class id -> its rotation line
	private final Map<String, java.util.TreeMap<Integer, double[]>> _rotSelf = new HashMap<>(); // line -> level -> {dps ratio over 5 .. 120 s with the self buffs, P.Def mul, M.Def mul}
	private final Map<String, java.util.TreeMap<Integer, int[]>> _rotSelfIds = new HashMap<>(); // line -> level -> the self buff skill ids those ratios assume
	private final Map<Role, double[][]> _newbie = new java.util.EnumMap<>(Role.class); // Newbie Helper buffs: role -> {damage, pDef, mDef} by level (8-25)
	private final Map<String, java.util.TreeMap<Integer, double[]>> _bufferExtras = new HashMap<>(); // buffer -> level -> {run speed added, HP regen mul, absorb share, HP mul}
	private volatile boolean _startingBuffs;
	private volatile double _selfHeal; // share of the HP a mystic-line bot loses that it heals itself (Heal, Battle Heal, Self Heal), and of the damage its servitor takes that Servitor Heal repairs; 0 = off
	private volatile double _healMpPerHp = 0.12; // mana per HP healed
	private volatile double _rangedWalk = 1.0; // share of the walk between monsters that archers and casters keep
	private final Map<String, java.util.TreeMap<Integer, double[]>> _serv = new HashMap<>(); // summoner line -> level -> {servitor dps, HP, P.Def}
	private static final int[] ROTATION_WINDOWS = { 5, 15, 30, 45, 60, 90, 120 };
	private static final double SIM_PDEF = 400.0;
	private static final int NEWBIE_FROM_LEVEL = 8; // the Newbie Helper's support magic (SupportMagic.java)
	private static final int NEWBIE_TO_LEVEL = 25;
	private static final double MOB_HITS_PER_SECOND = 0.5; // a monster's attacks on its target
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
		final Map<String, java.util.TreeMap<Integer, double[]>> rotations = new HashMap<>();
		final Map<String, java.util.TreeMap<Integer, double[]>> rotationsUndead = new HashMap<>();
		final Map<String, Double> undeadShare = new HashMap<>();
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
					else if (f[0].equals("ROTU") && (f.length >= 11))
					{
						final double[] values = new double[1 + ROTATION_WINDOWS.length];
						for (int i = 0; i < values.length; i++)
						{
							values[i] = Double.parseDouble(f[3 + i]);
						}
						rotationsUndead.computeIfAbsent(f[1], k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[2]), values);
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
						rest.computeIfAbsent(f[1], k -> new java.util.EnumMap<>(Role.class)).computeIfAbsent(Role.valueOf(f[2].toUpperCase(java.util.Locale.ROOT)), k -> new java.util.TreeMap<>()).put(Integer.parseInt(f[3]), new double[] { Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), (f.length >= 9) ? Double.parseDouble(f[8]) : 0.0 });
					}
					else if (f[0].equals("ROTCLASS") && (f.length >= 3))
					{
						rotationLine.put(Integer.parseInt(f[1]), f[2]);
					}
					else if (f[0].equals("ZONE") && (f.length >= 10))
					{
						final boolean more = f.length >= 14; // older data files stop at M.Atk, or before the experience
						zones.add(new ZoneStats(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9]), more ? Double.parseDouble(f[10]) : 0.0, more ? Integer.parseInt(f[11]) : 0, more ? Double.parseDouble(f[12]) : 0.0, more ? Double.parseDouble(f[13]) : 0.0, (f.length >= 15) ? Double.parseDouble(f[14]) : 0.0));
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
		final ZoneCombat model = new ZoneCombat(use, curves, zones, buffs);
		model._rotations.putAll(rotations);
		model._rotationsUndead.putAll(rotationsUndead);
		model._undeadShare.putAll(undeadShare);
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
	 * @param on whether monsters miss a bot with evasion, so it takes fewer hits (a lower death factor for the nimble: archers, then fighters, then mages)
	 */
	public void setEvasion(boolean on)
	{
		_evasionOn = on;
		_deathCache.clear();
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

	/** Kills factor from sitting: the cycle over the cycle plus the sit; potions heal part of the HP deficit so it sits less. 1 without rest data. */
	private double restFactor(String zone, Role role, int level, double killsPerMinute, double potionsPerHour, boolean spoiler, double servitorHealHp)
	{
		final Map<Role, java.util.TreeMap<Integer, double[]>> byRole = _rest.get(zone);
		final java.util.TreeMap<Integer, double[]> table = (byRole == null) ? null : byRole.get(role);
		if ((table == null) || table.isEmpty())
		{
			return 1.0;
		}
		final java.util.Map.Entry<Integer, double[]> entry = (table.floorEntry(level) != null) ? table.floorEntry(level) : table.firstEntry();
		final double[] r = entry.getValue(); // cycle, HP deficit per kill, HP sit regen per s, MP sit s
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
		// Mystic-line bots (mages, healers, summoners) heal themselves with Heal, Battle Heal and Self Heal for a share of the HP they lose, and summoners repair the servitor with Servitor Heal; the mana comes out of the same pool they sit to refill.
		final double deficit = Math.max(0.0, r[1] - healPerKill);
		final double selfHealed = (role == Role.MAGE) ? (deficit * _selfHeal) : 0.0;
		final double healMp = (r.length >= 5 && r[4] > 0.0) ? (((selfHealed + servitorHealHp) * _healMpPerHp) / r[4]) : 0.0;
		final double sitHp = (deficit - selfHealed) / Math.max(1e-9, sitRegen);
		// A spoiler bot casts Spoil on every monster: that mana is refilled by sitting too (a spoiler in the party that is not the bot costs it nothing).
		final double sit = Math.max(sitHp, r[3] + healMp + spoilSitSeconds(r, level, spoiler));
		return r[0] / (r[0] + sit);
	}

	/**
	 * The rough party model: a virtual party of a tank, a damage dealer, a buffer and a healer, of which the bot is one (by its class).
	 * @param enabled whether bots may party
	 * @param expBonus the party's experience bonus; the bot gets expBonus / 4 of a kill's experience (the server gives 1.30 for 4, so 32.5%; 1.0 would be a quarter)
	 * @param healReduction how much of the solo tank death rate remains with a healer behind it (0.2 = a fifth)
	 * @param healCoverage the share of the tank's HP loss the healer heals (so the tank sits less)
	 * @param chainChance when a member dies, the chance the next in line (tank, damage dealer, buffer, healer) dies before the mob does
	 * @param resetSeconds how long the party takes to resurrect and get going after a death
	 * @param healMpPerHp mana the healer spends per HP it heals (the healer only heals; Greater Heal and Battle Heal run about 0.1), for its resting
	 * @param baseDeathsPerHour the death rate a death factor of 1 means
	 * @param gearPenalty stop-gap: party members are rarely in the best gear of their level, so their damage and defence are cut by this share (0.15 = 15%)
	 */
	public record PartyParams(boolean enabled, double expBonus, double healReduction, double healCoverage, double chainChance, double resetSeconds, double healMpPerHp, double baseDeathsPerHour, double gearPenalty)
	{
		public static PartyParams defaults()
		{
			return new PartyParams(true, 1.3, 0.2, 0.75, 0.3, 45.0, 0.12, 0.3, 0.15);
		}
	}

	/**
	 * What a bot gets from hunting in the virtual party.
	 * @param killsPerMinute the party's kills per minute (after resting and resets)
	 * @param deathFactor the bot's death rate relative to the base rate (the zone factor)
	 * @param expShare the share of a kill's experience the bot gets
	 * @param slot 0 tank, 1 damage dealer, 2 buffer, 3 healer
	 * @param buffer the buffer line giving the buffs
	 * @param lootShare the share of each drop (adena, items) the bot gets: one over the party size
	 * @param spoils whether the party spoils its kills (the bot or the damage dealer is a Scavenger, Bounty Hunter or Fortune Seeker): the spoil drops are shared too
	 */
	public record PartyOutcome(double killsPerMinute, double deathFactor, double expShare, int slot, String buffer, double lootShare, boolean spoils)
	{
	}

	/** Members in the virtual party: tank, damage dealer, buffer, healer. */
	public static final int PARTY_SIZE = 4;

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
		// The damage dealer: the bot's own, the healer's mage that wears its gear, or a reference one chosen by the variant.
		final int dpsClass;
		final Role dpsRole;
		final Stats dpsStats;
		final double dpsSkills;
		if (slot == 1)
		{
			dpsClass = classId;
			dpsRole = role;
			dpsStats = stats;
			dpsSkills = skillFraction;
		}
		else if (slot == 3)
		{
			dpsClass = 94;
			dpsRole = Role.MAGE;
			dpsStats = stats;
			dpsSkills = 1.0;
		}
		else
		{
			dpsClass = DPS_CLASSES[Math.floorMod(variant / BUFFERS.length, DPS_CLASSES.length)];
			dpsRole = roleOf(dpsClass);
			dpsStats = curveStats(dpsRole, grade, grade);
			dpsSkills = 1.0;
		}
		final double tankShots = botTank ? shotDamage(Role.TANK, shotFraction, false) : 1.0;
		final double dpsShots = (slot == 1) ? shotDamage(role, shotFraction, blessed) : 1.0;
		final double tankBuff = partyBuff(buffer, Role.TANK, 0, level);
		final double dpsBuff = partyBuff(buffer, dpsRole, 0, level);
		final double gearCut = Math.max(0.0, 1.0 - p.gearPenalty());
		double fight = 0.0;
		for (int pass = 0; pass < 2; pass++)
		{
			final int w = (pass == 0) ? 0 : nearestWindow(fight);
			final double dps = (rotationDps(z, Role.TANK, tankClass, level, tankStats.attack(), tankSkills, botTank ? stats.selfBuffs() : 1.0, w) * tankShots * tankBuff) + (rotationDps(z, dpsRole, dpsClass, level, dpsStats.attack(), dpsSkills, (slot == 1) ? stats.selfBuffs() : 1.0, w) * dpsShots * dpsBuff) + (petDps(z, dpsClass, level, dpsSkills) * partyBuff(buffer, Role.MELEE, 0, level));
			fight = z.hp() / Math.max(1e-6, dps * gearCut);
		}
		// A ranged damage dealer (archer or mage, the healer's mage included) pulls the mob for the group, so the party walks less between kills.
		final double overhead = (60.0 / _params.baseKillsPerMinute()) * (1.0 - _params.fightShare()) * ((dpsRole == Role.BOW || dpsRole == Role.MAGE) ? _rangedWalk : 1.0);
		double kills = 60.0 / (overhead + fight);
		// Deaths: the mobs hit the tank, and the healer behind it cuts the rate; when the tank dies the next in line takes over until the mob is dead.
		final double mean = _threatMedian[Role.TANK.ordinal()];
		final double pBuff = partyBuff(buffer, Role.TANK, 1, level);
		final double mBuff = partyBuff(buffer, Role.TANK, 2, level);
		final double tankThreat = threat(z, tankStats.pDef() * gearCut, tankStats.mDef() * gearCut, pBuff, mBuff, hitChance(z, Role.TANK, level));
		final double tankFactor = (mean <= 0) ? 1.0 : Math.max(_params.minDeathFactor(), Math.min(_params.maxDeathFactor(), tankThreat / mean));
		final double events = tankFactor * (1.0 + (_aggroRisk * z.aggressivePercent() / 100.0)) * p.healReduction(); // party deaths relative to the base rate
		final double deathFactor = events * Math.pow(p.chainChance(), slot);
		// Resting: the tank sits for what the healer does not heal, the healer sits for its MP (it burns more than an attacking mage); a death resets the party.
		double factor = 1.0;
		if (_restOn)
		{
			final double[] tankRow = restRow(z.name(), Role.TANK, level);
			final double[] mageRow = restRow(z.name(), Role.MAGE, level);
			if ((tankRow != null) && (mageRow != null))
			{
				final double lost = tankRow[1] * (fight / Math.max(1e-6, tankRow[0] - 2.5)); // HP the mobs take off the tank per kill, beyond its standing regen
				final double sitHp = (lost * (1.0 - p.healCoverage())) / Math.max(1e-9, tankRow[2]);
				// The healer only heals: its mana goes to the HP it heals (no attack spells), and it refills by sitting at a mage's MP regen.
				final double sitMp = (mageRow.length >= 5 && mageRow[4] > 0.0) ? ((lost * p.healCoverage() * p.healMpPerHp()) / mageRow[4]) : (mageRow[3] * (fight / Math.max(1e-6, mageRow[0] - 2.5)) * 1.5);
				final double cycle = fight + 2.5;
				final double[] ownRow = restRow(z.name(), role, level);
				final double sitSpoil = LivingSupplies.isSpoiler(classId) && (ownRow != null) ? spoilSitSeconds(ownRow, level, true) : 0.0; // only when the bot itself spoils
				factor = cycle / (cycle + Math.max(sitHp, Math.max(sitMp, sitSpoil)));
			}
		}
		final double resetShare = Math.min(0.9, (events * p.baseDeathsPerHour() * p.resetSeconds()) / 3600.0);
		kills = Math.max(_params.minKillsPerMinute(), Math.min(_params.maxKillsPerMinute(), kills * factor * (1.0 - resetShare)));
		return new PartyOutcome(kills, deathFactor, p.expBonus() / PARTY_SIZE, slot, buffer, 1.0 / PARTY_SIZE, LivingSupplies.isSpoiler(classId) || LivingSupplies.isSpoiler(dpsClass));
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
			final int w = (pass == 0) ? 0 : nearestWindow(seconds);
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
		final String line = _rotationLine.get(classId);
		final double plain = rotationDps(zone, role, classId, level, weaponAttack, skillFraction, selfShare, window, _rotations.get(line));
		final double undead = _undeadShare.getOrDefault(zone.name(), 0.0);
		final java.util.TreeMap<Integer, double[]> undeadTable = (line == null) ? null : _rotationsUndead.get(line);
		if ((undead <= 0.0) || (undeadTable == null))
		{
			return plain;
		}
		final double against = Math.max(plain, rotationDps(zone, role, classId, level, weaponAttack, skillFraction, selfShare, window, undeadTable)); // never slower than the plain rotation (Phoenix Knight's is no better)
		return 1.0 / (((1.0 - undead) / Math.max(1e-9, plain)) + (undead / Math.max(1e-9, against))); // kills take the time of their own kind: average the seconds, not the damage
	}

	private double rotationDps(ZoneStats zone, Role role, int classId, int level, double weaponAttack, double skillFraction, double selfShare, int window, java.util.TreeMap<Integer, double[]> table)
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
		final double skills = (role == Role.MAGE) ? Math.max(0.1, skillFraction) : Math.max(0.0, Math.min(1.0, skillFraction));
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
	private double[] servitorShield(ZoneStats zone, double[] pet, int level, double fightSeconds, double killsPerMinute)
	{
		final double defence = pet[2] * buff(Role.MELEE, 1, level); // the servitor's P.Def and HP carry the bot's buffs (Shield, Blessed Body, the buffers')
		final double hitPerFight = fightSeconds * MOB_HITS_PER_SECOND * 70.0 * zone.pAtk() / Math.max(1.0, defence);
		final double damagePerFight = hitPerFight * (1.0 - _selfHeal); // Servitor Heal repairs a share of it between hits
		final double servitorDeathsPerHour = killsPerMinute * 60.0 * damagePerFight / Math.max(1.0, pet[1]);
		final double dead = Math.min(SERVITOR_MAX_DEAD_SHARE, servitorDeathsPerHour * SERVITOR_RESUMMON_SECONDS / 3600.0);
		return new double[] { dead, Math.min(1.0, SERVITOR_BASE_EXPOSURE + dead), Math.min(pet[1], hitPerFight * _selfHeal) }; // the third is the servitor HP healed per kill
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
		return _killCache.computeIfAbsent(key, k ->
		{
			final double fightSeconds = (rotation ? rotationFightSeconds(_zones.get(zi), role, classId, level, attack, skills / 10.0, stats.selfBuffs()) : (_killScale[role.ordinal()] * rawTimeToKill(_zones.get(zi), role, level, attack, skills / 10.0))) / buff(role, 0, level) / shotDamage(role, shots / 10.0, blessed);
			final double overhead = (60.0 / _params.baseKillsPerMinute()) * (1.0 - _params.fightShare()) / runSpeed(role, level) * ((role == Role.BOW || role == Role.MAGE) ? _rangedWalk : 1.0); // the walk between monsters shrinks with the bot's speed, and ranged classes move less
			double kills = 60.0 / (overhead + fightSeconds);
			final double[] pet = rotation ? servitor(classId, level) : null;
			final double[] shield = (pet == null) ? null : servitorShield(_zones.get(zi), pet, level, fightSeconds, kills);
			if (_restOn)
			{
				kills *= restFactor(_zones.get(zi).name(), role, level, kills, potionsPerHour, LivingSupplies.isSpoiler(classId), (shield == null) ? 0.0 : shield[2]);
			}
			if (shield != null)
			{
				kills *= 1.0 - shield[0]; // time spent summoning again
			}
			return Math.max(_params.minKillsPerMinute(), Math.min(_params.maxKillsPerMinute(), kills));
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
		final long selfIdx = (stats.selfBuffs() < 0) ? 11L : Math.round(Math.min(1.0, stats.selfBuffs()) * 10.0);
		final long key = (((((((zi * 4L) + role.ordinal()) * 128L) + Math.min(127, level)) * 8192L) + pDef) * 8192L + mDef) * 12L * 128L + (selfIdx * 128L) + (classId & 127);
		return _deathCache.computeIfAbsent(key, k ->
		{
			final double mean = _threatMedian[role.ordinal()];
			final ZoneStats z = _zones.get(zi);
			// Self buffs that change defence (Majesty, Iron Will, Rage's penalty ...), in proportion to the share the bot has learned.
			final java.util.Map.Entry<Integer, double[]> self = _rotationTtk ? selfEntry(_rotationLine.get(classId), level) : null;
			final double share = (stats.selfBuffs() < 0) ? 1.0 : Math.min(1.0, stats.selfBuffs());
			final double selfP = (self == null) ? 1.0 : (1.0 + ((self.getValue()[ROTATION_WINDOWS.length] - 1.0) * share));
			final double selfM = (self == null) ? 1.0 : (1.0 + ((self.getValue()[ROTATION_WINDOWS.length + 1] - 1.0) * share));
			double gear = (mean <= 0) ? 1.0 : Math.max(_params.minDeathFactor(), Math.min(_params.maxDeathFactor(), threat(z, pDef, mDef, buff(role, 1, level) * selfP, buff(role, 2, level) * selfM, hitChance(z, role, level)) / mean));
			final double[] pet = (_rotationTtk && hasRotation(classId)) ? servitor(classId, level) : null;
			if (pet != null)
			{
				// The servitor takes the hits first: the summoner is only exposed while it is down (plus a base share of attacks that still reach the master).
				final double fight = rotationFightSeconds(z, role, classId, level, curve(role == Role.MAGE ? "matk_mage" : "patk_melee", LivingSupplies.gradeFor(level)), 1.0, 1.0);
				gear *= servitorShield(z, pet, level, fight, _params.baseKillsPerMinute() * 0.6)[1];
			}
			if (role == Role.MAGE)
			{
				gear *= 1.0 - (0.5 * _selfHeal); // self heals keep it up in the middle of a fight: half of the healed share of the damage no longer kills
			}
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

	/** @param factor the share of the walk and targeting time between monsters that ranged classes (archers and mages) keep: 0.5 halves it, 1 turns it off */
	public void setRangedWalk(double factor)
	{
		_rangedWalk = Math.max(0.05, Math.min(1.0, factor));
		_killCache.clear();
		_partyCache.clear();
	}

	/**
	 * @param coverage the share of the HP lost that mystic-line bots (mages, healers, summoners) heal themselves, and of the servitor's damage that Servitor Heal repairs (0 = off)
	 * @param mpPerHp the mana the heals cost per HP healed
	 */
	public void setSelfHeal(double coverage, double mpPerHp)
	{
		_selfHeal = Math.max(0.0, Math.min(0.95, coverage));
		_healMpPerHp = Math.max(0.0, mpPerHp);
		_killCache.clear();
		_deathCache.clear();
		_partyCache.clear();
	}

	/** @param on whether a solo bot hunts with the Newbie Helper's buffs (levels 8-25) and then a Hierophant's or Doom Cryer's buffs at its level */
	public void setStartingBuffs(boolean on)
	{
		_startingBuffs = on;
		_killCache.clear();
		_deathCache.clear();
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
