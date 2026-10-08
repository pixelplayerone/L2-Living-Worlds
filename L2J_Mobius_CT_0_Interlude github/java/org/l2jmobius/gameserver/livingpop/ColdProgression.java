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

import java.util.function.IntToLongFunction;

/**
 * Pure, dependency-free cold-progression arithmetic.
 *
 * <p>This is the "cold models the outcomes" half of the life cycle. Rather than imposing a flat pace, it models a real
 * player's leveling: a cold bot earns experience per kill, at a per-minute rate the caller supplies for the bot's level
 * (representative mob experience times a kill rate times the server experience rate), and levels up when it has enough
 * against the server's real experience table. Because the per-level requirement grows far faster than the per-kill
 * experience, low levels fly and high levels take much longer, exactly like the real game. The caller injects both
 * per-level functions so this class stays free of game dependencies and fully testable offline.
 */
public final class ColdProgression
{
	private static final double MS_PER_MINUTE = 60_000.0;

	private ColdProgression()
	{
	}

	/**
	 * Advances a bot's level and experience-into-level over an elapsed span, capped at the maximum level. Pure: it reads
	 * nothing and mutates nothing, returning a new {@link Progress}. It walks level by level so a span that crosses
	 * several early levels is spent correctly, using each level's own experience rate.
	 * @param level the current level
	 * @param expIntoLevel the experience accumulated toward the next level
	 * @param elapsedMs elapsed real time in milliseconds
	 * @param maxLevel the level at which progression stops
	 * @param expToNextLevel gives the experience required to advance from a given level to the next (real values from the
	 *            server experience table)
	 * @param expPerMinuteForLevel gives the experience a bot earns per minute at a given level (mob experience times kill
	 *            rate times the server experience rate); a non-positive value stops progress
	 * @return the resulting level and experience-into-level
	 */
	public static Progress resolve(int level, long expIntoLevel, long elapsedMs, int maxLevel, IntToLongFunction expToNextLevel, IntToLongFunction expPerMinuteForLevel)
	{
		int newLevel = Math.max(1, level);
		if (newLevel >= maxLevel)
		{
			return new Progress(maxLevel, 0L);
		}
		if ((elapsedMs <= 0) || (expToNextLevel == null) || (expPerMinuteForLevel == null))
		{
			return new Progress(newLevel, Math.max(0L, expIntoLevel));
		}

		long exp = Math.max(0L, expIntoLevel);
		long remainingMs = elapsedMs;
		while ((newLevel < maxLevel) && (remainingMs > 0))
		{
			final long required = Math.max(1L, expToNextLevel.applyAsLong(newLevel));
			if (exp >= required)
			{
				// Carried experience already covers this level (for example after a rate change or legacy data).
				exp -= required;
				newLevel++;
				continue;
			}

			final long perMinute = expPerMinuteForLevel.applyAsLong(newLevel);
			if (perMinute <= 0L)
			{
				break;
			}

			final double perMs = perMinute / MS_PER_MINUTE;
			final double msToLevel = (required - exp) / perMs;
			if (msToLevel <= remainingMs)
			{
				remainingMs -= (long) Math.ceil(msToLevel);
				newLevel++;
				exp = 0L;
			}
			else
			{
				exp += (long) Math.floor(perMs * remainingMs);
				remainingMs = 0L;
			}
		}

		if (newLevel >= maxLevel)
		{
			return new Progress(maxLevel, 0L);
		}

		final long required = Math.max(1L, expToNextLevel.applyAsLong(newLevel));
		if (exp < 0L)
		{
			exp = 0L;
		}
		else if (exp >= required)
		{
			exp = required - 1L;
		}
		return new Progress(newLevel, exp);
	}

	/**
	 * The result of a cold progression step.
	 * @param level the resulting level
	 * @param expIntoLevel the resulting experience toward the next level
	 */
	public record Progress(int level, long expIntoLevel)
	{
	}
}
