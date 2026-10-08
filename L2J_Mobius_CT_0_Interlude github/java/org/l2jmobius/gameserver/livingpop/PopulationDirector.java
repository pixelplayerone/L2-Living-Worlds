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

import java.util.Arrays;

/**
 * Pure, dependency-free population director.
 *
 * <p>It keeps the cold world believably peered to the real player without hand-tuning spawns: it takes a target level
 * (normally the median level of the online human players) and returns a multiplier on a bot's experience rate. A bot
 * far below the target catches up faster (capped), a bot far above slows down, and a bot inside the band is unaffected.
 * With no target (no players online) it is neutral. The manager applies the returned multiplier to the kill-based
 * experience rate, so the whole world drifts toward the player's level over time.
 */
public final class PopulationDirector
{
	private PopulationDirector()
	{
	}

	/**
	 * The median of a set of player levels, used as the target level. Zero when there are no levels.
	 * @param levels the online human players' levels (may be empty or null)
	 * @return the median level, or 0 when there is nothing to target
	 */
	public static int targetLevel(int[] levels)
	{
		if ((levels == null) || (levels.length == 0))
		{
			return 0;
		}

		final int[] sorted = levels.clone();
		Arrays.sort(sorted);
		final int mid = sorted.length / 2;
		if ((sorted.length % 2) == 1)
		{
			return sorted[mid];
		}
		return Math.round((sorted[mid - 1] + sorted[mid]) / 2.0f);
	}

	/**
	 * The experience-rate multiplier for a bot at a given level, relative to the target.
	 * @param botLevel the bot's current level
	 * @param targetLevel the target level (0 disables the director, returning a neutral 1.0)
	 * @param params the director tuning
	 * @return a multiplier: greater than 1 to catch up, less than 1 to slow down, 1.0 in band or with no target
	 */
	public static double pressure(int botLevel, int targetLevel, Params params)
	{
		if ((targetLevel <= 0) || (params == null))
		{
			return 1.0;
		}

		final int delta = targetLevel - botLevel;
		if (delta > params.bandRadius())
		{
			return Math.min(params.maxCatchUp(), 1.0 + ((delta - params.bandRadius()) * params.catchUpSlope()));
		}
		if (delta < -params.bandRadius())
		{
			return params.slowdown();
		}
		return 1.0;
	}

	/**
	 * Director tuning.
	 * @param bandRadius levels within this distance of the target are considered in band and unaffected
	 * @param maxCatchUp the cap on the catch-up multiplier for bots below the band
	 * @param slowdown the multiplier applied to bots above the band (below 1 to slow them)
	 * @param catchUpSlope how sharply the catch-up multiplier grows per level below the band
	 */
	public record Params(int bandRadius, double maxCatchUp, double slowdown, double catchUpSlope)
	{
	}
}
