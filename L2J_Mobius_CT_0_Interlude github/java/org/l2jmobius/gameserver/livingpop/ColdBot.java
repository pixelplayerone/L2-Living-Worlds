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

import java.util.Locale;
import java.util.Map;

import org.l2jmobius.gameserver.modules.Json;

/**
 * The durable state of one cold bot: a plain, dependency-free data row that is the single source of truth for that bot
 * across the cold/hot boundary and across server restarts. The database persists exactly these fields; the resolver
 * mutates the progression fields; the monitoring snapshot reads them.
 *
 * <p>It carries no game types on purpose, so it can be built and asserted in the standalone test lane. In Phase 3 the
 * hot/cold handoff materializes a bot as a real persistent character: {@link #getCharId()} binds the row to that
 * character (0 until one exists), and {@link #isHotLock()} guards a bot that is currently hot from also being advanced by
 * the resolver.
 *
 * <p>The fields the handoff touches from the game thread while the resolver runs on its own thread ({@code level},
 * {@code expIntoLevel}, position, {@code phase}, {@code charId}, {@code hotLock} and the resolve stamps) are
 * {@code volatile}. The hot lock is the ordering guard: the resolver skips a locked bot, and only the resolver thread
 * clears it (the handoff queues that step back to it), so a cooled bot's captured state is written and visible before the
 * resolver picks it up again.
 */
public class ColdBot
{
	private long _id;
	private String _name;
	private String _race;
	private int _classId;
	private volatile int _level;
	private volatile long _expIntoLevel;
	private long _sp;
	private long _adena;
	private volatile String _region;
	private volatile int _x;
	private volatile int _y;
	private volatile int _z;
	private volatile String _activity;
	private volatile String _phase;
	private long _createdAt;
	private volatile long _updatedAt;
	private volatile long _lastResolvedAt;
	private volatile long _nextResolveAt;
	private volatile boolean _hotLock;
	private volatile long _charId;
	private volatile long _soulshots;
	private volatile long _potions;
	private volatile int _gearTier;
	private volatile String _goal;
	private volatile boolean _rewardClaimed;
	private volatile String _zone;
	private volatile String _town;
	private volatile long _escapes;
	private volatile TravelLeg _leg;
	private volatile long _loot;
	private volatile int _deaths;
	private volatile ColdRisk.State _risk = ColdRisk.State.EMPTY;
	private volatile ClassPath.Quest _quest;
	private volatile String _skills;
	private volatile String _gear;
	private final DecisionLog _decisions = new DecisionLog();
	// Lifetime hunting counters for the monitor, persisted next to the decision log in stats_json (no schema change).
	private volatile double _partyDeathFactor; // zone model: the death factor while hunting in the virtual party (0 = hunting alone); not saved
	private volatile double _kills;
	private volatile long _adenaEarned;
	private volatile long _shoppedAt; // when it last finished its errands in town (0 = never), kept with the counters

	/** @return the death factor of the virtual party the bot hunts in, 0 when it hunts alone (not saved) */
	public double getPartyDeathFactor()
	{
		return _partyDeathFactor;
	}

	public void setPartyDeathFactor(double factor)
	{
		_partyDeathFactor = factor;
	}

	public long getId()
	{
		return _id;
	}

	public void setId(long id)
	{
		_id = id;
	}

	public String getName()
	{
		return _name;
	}

	public void setName(String name)
	{
		_name = name;
	}

	public String getRace()
	{
		return _race;
	}

	public void setRace(String race)
	{
		_race = race;
	}

	public int getClassId()
	{
		return _classId;
	}

	public void setClassId(int classId)
	{
		_classId = classId;
	}

	public int getLevel()
	{
		return _level;
	}

	public void setLevel(int level)
	{
		_level = level;
	}

	public long getExpIntoLevel()
	{
		return _expIntoLevel;
	}

	public void setExpIntoLevel(long expIntoLevel)
	{
		_expIntoLevel = expIntoLevel;
	}

	public long getSp()
	{
		return _sp;
	}

	public void setSp(long sp)
	{
		_sp = sp;
	}

	public long getAdena()
	{
		return _adena;
	}

	public void setAdena(long adena)
	{
		_adena = adena;
	}

	public String getRegion()
	{
		return _region;
	}

	public void setRegion(String region)
	{
		_region = region;
	}

	public int getX()
	{
		return _x;
	}

	public void setX(int x)
	{
		_x = x;
	}

	public int getY()
	{
		return _y;
	}

	public void setY(int y)
	{
		_y = y;
	}

	public int getZ()
	{
		return _z;
	}

	public void setZ(int z)
	{
		_z = z;
	}

	public String getActivity()
	{
		return _activity;
	}

	public void setActivity(String activity)
	{
		_activity = activity;
	}

	public String getPhase()
	{
		return _phase;
	}

	public void setPhase(String phase)
	{
		_phase = phase;
	}

	public long getCreatedAt()
	{
		return _createdAt;
	}

	public void setCreatedAt(long createdAt)
	{
		_createdAt = createdAt;
	}

	public long getUpdatedAt()
	{
		return _updatedAt;
	}

	public void setUpdatedAt(long updatedAt)
	{
		_updatedAt = updatedAt;
	}

	public long getLastResolvedAt()
	{
		return _lastResolvedAt;
	}

	public void setLastResolvedAt(long lastResolvedAt)
	{
		_lastResolvedAt = lastResolvedAt;
	}

	public long getNextResolveAt()
	{
		return _nextResolveAt;
	}

	public void setNextResolveAt(long nextResolveAt)
	{
		_nextResolveAt = nextResolveAt;
	}

	public boolean isHotLock()
	{
		return _hotLock;
	}

	public void setHotLock(boolean hotLock)
	{
		_hotLock = hotLock;
	}

	/**
	 * @return the object id of the persistent character this bot is bound to, or 0 when it has never been materialized
	 */
	public long getCharId()
	{
		return _charId;
	}

	public void setCharId(long charId)
	{
		_charId = charId;
	}

	/** @return the abstract soulshot count the bot holds (Phase 4 economy; a hot phantom uses its own working stack) */
	public long getSoulshots()
	{
		return _soulshots;
	}

	public void setSoulshots(long soulshots)
	{
		_soulshots = soulshots;
	}

	/** @return the abstract healing-potion count the bot holds (Phase 4 economy) */
	public long getPotions()
	{
		return _potions;
	}

	public void setPotions(long potions)
	{
		_potions = potions;
	}

	/** @return the bot's abstract gear tier (Phase 4 economy; 0 = starting gear) */
	public int getGearTier()
	{
		return _gearTier;
	}

	public void setGearTier(int gearTier)
	{
		_gearTier = gearTier;
	}

	/** @return the bot's current high-level goal (Phase 4): {@code hunting}, {@code restock}, or {@code upgrade} */
	public String getGoal()
	{
		return _goal;
	}

	public void setGoal(String goal)
	{
		_goal = goal;
	}

	/** @return whether the one-time newbie soulshot reward has been granted (Phase 4 milestone) */
	public boolean isRewardClaimed()
	{
		return _rewardClaimed;
	}

	public void setRewardClaimed(boolean rewardClaimed)
	{
		_rewardClaimed = rewardClaimed;
	}

	/** @return the hunting zone the bot is in or heading to (Phase 5), or null */
	public String getZone()
	{
		return _zone;
	}

	public void setZone(String zone)
	{
		_zone = zone;
	}

	/** @return the town the bot is in or heading to (Phase 5), or null while in the field */
	public String getTown()
	{
		return _town;
	}

	public void setTown(String town)
	{
		_town = town;
	}

	/** @return how many Scrolls of Escape the bot carries (Phase 5) */
	public long getEscapes()
	{
		return _escapes;
	}

	public void setEscapes(long escapes)
	{
		_escapes = escapes;
	}

	/** @return what the loot it carries would sell for at a shop, in adena (sold on its next town visit) */
	public long getLoot()
	{
		return _loot;
	}

	public void setLoot(long loot)
	{
		_loot = loot;
	}

	/** @return how many times it has died while cold */
	public int getDeaths()
	{
		return _deaths;
	}

	public void setDeaths(int deaths)
	{
		_deaths = deaths;
	}

	/** @return its rest timing, recent deaths and any zone it is avoiding (never null) */
	public ColdRisk.State getRisk()
	{
		return _risk;
	}

	public void setRisk(ColdRisk.State risk)
	{
		_risk = (risk == null) ? ColdRisk.State.EMPTY : risk;
	}

	/** @return its class-change quest in progress, or null */
	public ClassPath.Quest getQuest()
	{
		return _quest;
	}

	public void setQuest(ClassPath.Quest quest)
	{
		_quest = quest;
	}

	/** @return the skills it has learned, as {@link SkillPlanner#encode} text, or null when not tracked yet */
	public String getSkills()
	{
		return _skills;
	}

	public void setSkills(String skills)
	{
		_skills = skills;
	}

	/** @return the gear it wears, as {@link LivingGear#encode} text, or null when not kept per slot yet */
	public String getGear()
	{
		return _gear;
	}

	public void setGear(String gear)
	{
		_gear = gear;
	}

	/** @return the travel or town step in progress (Phase 5), or null while hunting */
	public TravelLeg getLeg()
	{
		return _leg;
	}

	public void setLeg(TravelLeg leg)
	{
		_leg = leg;
	}

	/** @return the bot's recent decisions, in plain words (persisted in {@code stats_json}) */
	public DecisionLog getDecisions()
	{
		return _decisions;
	}

	/** @return how many mobs the bot has killed while simulated (fractional: kills accrue per resolve) */
	public double getKills()
	{
		return _kills;
	}

	/** @return adena earned from hunting while simulated: adena drops plus the sale value of loot */
	public long getAdenaEarned()
	{
		return _adenaEarned;
	}

	/**
	 * Adds one resolve's hunting gains to the lifetime counters. Negative values are ignored.
	 * @param kills mobs killed
	 * @param adena adena earned (drops plus loot value)
	 */
	public void addHunting(double kills, long adena)
	{
		_kills += Math.max(0.0, kills);
		_adenaEarned = Math.min(Long.MAX_VALUE / 2, _adenaEarned + Math.max(0L, adena));
	}

	/** @return when it last finished its errands in town, or 0 */
	public long getShoppedAt()
	{
		return _shoppedAt;
	}

	public void setShoppedAt(long shoppedAt)
	{
		_shoppedAt = Math.max(0L, shoppedAt);
	}

	/**
	 * @return the {@code stats_json} column value: the bot's decision log, plus its hunting counters once it has any
	 *         ({@code {"decisions":[...],"counters":{"kills":..,"adena":..}}})
	 */
	public String getStatsJson()
	{
		final String decisions = _decisions.toJson();
		if ((_kills <= 0.0) && (_adenaEarned <= 0L) && (_shoppedAt <= 0L))
		{
			return decisions;
		}
		return decisions.substring(0, decisions.length() - 1) + ",\"counters\":{\"kills\":" + String.format(Locale.US, "%.1f", _kills) + ",\"adena\":" + _adenaEarned + ((_shoppedAt > 0L) ? (",\"shoppedAt\":" + _shoppedAt) : "") + "}}";
	}

	/**
	 * Loads the {@code stats_json} column value. Unreadable content is ignored so it never blocks loading the bot.
	 * @param statsJson the stored value
	 */
	public void setStatsJson(String statsJson)
	{
		_decisions.loadJson(statsJson);
		_kills = 0.0;
		_adenaEarned = 0L;
		_shoppedAt = 0L;
		if ((statsJson == null) || statsJson.isBlank())
		{
			return;
		}
		try
		{
			if ((Json.parse(statsJson) instanceof Map<?, ?> root) && (root.get("counters") instanceof Map<?, ?> counters))
			{
				if (counters.get("kills") instanceof Number kills)
				{
					_kills = Math.max(0.0, kills.doubleValue());
				}
				// An older row may still carry an "exp" counter; it is no longer kept and drops on the next write.
				if (counters.get("adena") instanceof Number adena)
				{
					_adenaEarned = Math.max(0L, adena.longValue());
				}
				if (counters.get("shoppedAt") instanceof Number shoppedAt)
				{
					_shoppedAt = Math.max(0L, shoppedAt.longValue());
				}
			}
		}
		catch (Json.JsonException e)
		{
			// unreadable counters start again from zero; the bot itself still loads
		}
	}
}
