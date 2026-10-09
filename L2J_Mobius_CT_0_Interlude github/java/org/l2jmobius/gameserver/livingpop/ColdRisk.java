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

import java.util.ArrayList;
import java.util.List;

/**
 * The dangers of cold hunting, modeled on L2Solo: a hunt can go wrong and the bot dies, losing experience like a player
 * (6.5% minus 0.07% per level of the level's experience) and recovering in the nearest town; it sits down to rest between
 * fights; and after dying twice in the same zone within the avoidance window it stays away from that zone for a while,
 * choosing zones a couple of levels below its own. L2Solo simulates every fight; a cold bot here is resolved in spans, so
 * death is a chance per hour of hunting that grows with how hard the zone is for it. Pure, so it is tested without a
 * server. Only cold bots are subject to it: a hot bot lives and dies for real.
 */
public final class ColdRisk
{
	/** Deaths in the same zone within the avoidance window that make a bot give the zone a rest. */
	public static final int AVOID_AFTER_DEATHS = 2;
	/** How many levels lower a bot picks its zones while avoiding one (L2Solo drops 2 to 6). */
	public static final int AVOID_LEVEL_PENALTY = 2;

	private ColdRisk()
	{
	}

	/**
	 * Tuning, from the module config.
	 * @param deathsPerHour deaths per hour of hunting for a bot in the middle of a fitting zone, stocked and geared
	 * @param recoverMs how long a dead bot takes to get back on its feet in town
	 * @param restEveryMs hunting between rests
	 * @param restMs how long a rest lasts (casters and archers rest half again as long)
	 * @param avoidMs how long a zone is avoided after repeated deaths there; also the window those deaths must fall in
	 */
	public record Params(double deathsPerHour, long recoverMs, long restEveryMs, long restMs, long avoidMs)
	{
	}

	/**
	 * What a bot remembers about the risks it took. Stored on the row as text.
	 * @param huntedSinceRestMs hunting time since its last rest
	 * @param deathZone the zone it last died in, or null
	 * @param zoneDeaths deaths in that zone inside the avoidance window
	 * @param lastDeathAt when it last died
	 * @param avoidZone the zone it is avoiding, or null
	 * @param avoidUntil until when
	 */
	public record State(long huntedSinceRestMs, String deathZone, int zoneDeaths, long lastDeathAt, String avoidZone, long avoidUntil)
	{
		public static final State EMPTY = new State(0L, null, 0, 0L, null, 0L);

		/**
		 * @param zoneName a zone
		 * @param now the time
		 * @return whether the bot is staying away from that zone right now
		 */
		public boolean avoids(String zoneName, long now)
		{
			return (zoneName != null) && zoneName.equals(avoidZone) && (now < avoidUntil);
		}

		/**
		 * @param now the time
		 * @return whether it is avoiding any zone (and so picks zones a little below its level)
		 */
		public boolean avoiding(long now)
		{
			return (avoidZone != null) && (now < avoidUntil);
		}

		public State withHunted(long huntedMs)
		{
			return new State(huntedMs, deathZone, zoneDeaths, lastDeathAt, avoidZone, avoidUntil);
		}

		/** @return the row text */
		public String encode()
		{
			return huntedSinceRestMs + "|" + text(deathZone) + "|" + zoneDeaths + "|" + lastDeathAt + "|" + text(avoidZone) + "|" + avoidUntil;
		}

		/**
		 * @param text the row text
		 * @return the state, or {@link #EMPTY} for missing or damaged text
		 */
		public static State decode(String text)
		{
			if ((text == null) || text.isEmpty())
			{
				return EMPTY;
			}
			final String[] parts = text.split("\\|", -1);
			if (parts.length != 6)
			{
				return EMPTY;
			}
			try
			{
				return new State(Long.parseLong(parts[0]), zone(parts[1]), Integer.parseInt(parts[2]), Long.parseLong(parts[3]), zone(parts[4]), Long.parseLong(parts[5]));
			}
			catch (NumberFormatException e)
			{
				return EMPTY;
			}
		}

		private static String text(String zone)
		{
			return (zone == null) ? "" : zone.replace('|', '/');
		}

		private static String zone(String text)
		{
			return text.isEmpty() ? null : text;
		}
	}

	/**
	 * How dangerous the bot's hunting is right now.
	 * @param deathsPerHour its death rate
	 * @param reasons what makes it riskier than usual, in words (empty when nothing does)
	 */
	public record Danger(double deathsPerHour, List<String> reasons)
	{
	}

	/**
	 * @param params tuning
	 * @param level bot level
	 * @param zoneMin its zone's lowest level
	 * @param zoneMax its zone's highest level
	 * @param potions healing potions carried
	 * @param gearTier gear tier owned
	 * @param tierCeiling gear tier its level allows
	 * @param classId its class (tanks survive better, casters and archers worse)
	 * @return its death rate and why
	 */
	public static Danger danger(Params params, int level, int zoneMin, int zoneMax, long potions, int gearTier, int tierCeiling, int classId)
	{
		return danger(params, level, zoneMin, zoneMax, potions, gearTier, tierCeiling, classId, 0.0);
	}

	/**
	 * Like {@link #danger(Params, int, int, int, long, int, int, int)} with the zone's own factor from {@link ZoneCombat}.
	 * @param zoneFactor the multiple of the base rate its gear and the zone's monsters give it; above 0 it replaces the
	 *            gear-behind step (that is already in it), 0 or less keeps the old behavior
	 */
	public static Danger danger(Params params, int level, int zoneMin, int zoneMax, long potions, int gearTier, int tierCeiling, int classId, double zoneFactor)
	{
		final List<String> reasons = new ArrayList<>();
		// Where it stands in the zone's range: at the bottom the monsters hit twice as hard as in the middle, at the top half.
		final double half = Math.max(1.0, (zoneMax - zoneMin) / 2.0);
		final double weakness = Math.max(-1.5, Math.min(1.5, (((zoneMin + zoneMax) / 2.0) - level) / half));
		double rate = params.deathsPerHour() * Math.pow(2.0, weakness);
		if (level < zoneMin)
		{
			reasons.add("below the zone's level");
		}
		else if (weakness >= 0.5)
		{
			reasons.add("at the low end of the zone's levels");
		}
		if (potions <= 0)
		{
			rate *= 2.0;
			reasons.add("out of potions");
		}
		if (zoneFactor > 0.0)
		{
			rate *= zoneFactor;
			if (zoneFactor >= 1.5)
			{
				reasons.add("the zone's monsters hit hard for its gear");
			}
		}
		else
		{
			final int missing = Math.max(0, tierCeiling - gearTier);
			if (missing > 0)
			{
				rate *= 1.0 + (0.5 * missing);
				reasons.add("gear behind its level");
			}
		}
		if (zoneFactor <= 0.0)
		{
			// Only without the zone model: its factor already uses the role's defence and evasion, which replaces this class guess.
			rate *= LivingSupplies.isTank(classId) ? 0.75 : (LivingSupplies.isLight(classId) ? 1.25 : 1.0);
		}
		return new Danger(Math.max(0.0, rate), List.copyOf(reasons));
	}

	/**
	 * @param deathsPerHour a death rate
	 * @param elapsedMs hunting time
	 * @return the chance of at least one death in that time
	 */
	public static double deathChance(double deathsPerHour, long elapsedMs)
	{
		return 1.0 - Math.exp(-Math.max(0.0, deathsPerHour) * (Math.max(0L, elapsedMs) / 3_600_000.0));
	}

	/** The server's experience loss per level (percent of the level's experience span), or null for the built-in estimate. */
	private static volatile double[] _expLossTable;

	/**
	 * @param percentByLevel the server's loss in percent of a level's experience, indexed by level (0 unused); null returns to the estimate
	 */
	public static void setExpLossTable(double[] percentByLevel)
	{
		_expLossTable = (percentByLevel == null) ? null : percentByLevel.clone();
	}

	/**
	 * @param level the level it died at
	 * @return the share of the level's experience it loses, in percent: the server's table when it was loaded (capped at the server's 10%), else a straight-line estimate
	 */
	public static double expLossPercent(int level)
	{
		final double[] table = _expLossTable;
		if ((table != null) && (table.length > 1))
		{
			return Math.max(0.0, Math.min(10.0, table[Math.max(1, Math.min(level, table.length - 1))]));
		}
		return Math.max(0.0, 6.5 - (0.07 * level));
	}

	/**
	 * Records a death.
	 * @param state before
	 * @param zoneName where it died
	 * @param now when
	 * @param params tuning
	 * @return after; {@link State#avoidZone()} is set to the zone when this death makes it avoid the zone
	 */
	public static State died(State state, String zoneName, long now, Params params)
	{
		final boolean sameZone = (zoneName != null) && zoneName.equals(state.deathZone()) && ((now - state.lastDeathAt()) < params.avoidMs());
		final int deaths = sameZone ? (state.zoneDeaths() + 1) : 1;
		if ((zoneName != null) && (deaths >= AVOID_AFTER_DEATHS))
		{
			return new State(0L, null, 0, now, zoneName, now + params.avoidMs());
		}
		return new State(0L, zoneName, deaths, now, state.avoidZone(), state.avoidUntil());
	}

	/**
	 * @param params tuning
	 * @param classId its class
	 * @return how long this bot rests
	 */
	public static long restMs(Params params, int classId)
	{
		return LivingSupplies.isLight(classId) ? Math.round(params.restMs() * 1.5) : params.restMs();
	}
}
