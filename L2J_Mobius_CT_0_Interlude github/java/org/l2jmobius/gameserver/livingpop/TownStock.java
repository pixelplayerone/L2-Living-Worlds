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

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The items each town holds: what the cold bots hunted and sold there, kept as real items instead of only the adena
 * the vendor paid. Pure (no game types), so the standalone test lane covers it. Nothing takes items out yet.
 */
public final class TownStock
{
	private final Map<String, Map<Integer, Long>> _towns = new ConcurrentHashMap<>();
	private volatile boolean _dirty;

	/**
	 * Adds the items a span of hunting dropped: each item's expected count rounded up or down at random, so small
	 * amounts still add up over many spans.
	 * @param town the town the bot sells in (ignored when null)
	 * @param perKill item id to expected count per kill
	 * @param kills how many kills the span had
	 * @param random the rounding source
	 */
	public void deposit(String town, Map<Integer, Double> perKill, double kills, Random random)
	{
		if ((town == null) || (kills <= 0))
		{
			return;
		}
		for (Map.Entry<Integer, Double> entry : perKill.entrySet())
		{
			final double expected = entry.getValue() * kills;
			final long whole = (long) expected;
			final long count = whole + ((random.nextDouble() < (expected - whole)) ? 1 : 0);
			if (count > 0)
			{
				add(town, entry.getKey(), count);
			}
		}
	}

	/**
	 * @param town a town
	 * @param itemId an item
	 * @param count how many to add
	 */
	public void add(String town, int itemId, long count)
	{
		_towns.computeIfAbsent(town, key -> new ConcurrentHashMap<>()).merge(itemId, count, Long::sum);
		_dirty = true;
	}

	/**
	 * @param town a town
	 * @param itemId an item
	 * @return how many the town holds
	 */
	public long count(String town, int itemId)
	{
		final Map<Integer, Long> items = _towns.get(town);
		return (items == null) ? 0L : items.getOrDefault(itemId, 0L);
	}

	/** @return a copy of every town's items, towns and items in order */
	public Map<String, Map<Integer, Long>> snapshot()
	{
		final Map<String, Map<Integer, Long>> copy = new TreeMap<>();
		_towns.forEach((town, items) -> copy.put(town, new TreeMap<>(items)));
		return copy;
	}

	/** @return whether anything changed since the last {@link #clean()} */
	public boolean dirty()
	{
		return _dirty;
	}

	/** Marks the stock as saved. */
	public void clean()
	{
		_dirty = false;
	}
}
