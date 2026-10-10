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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The items each town holds, like a back-end auction house: every item belongs to the bot that hunted it, and what a
 * buyer pays will go to that owner. Pure (no game types), so the standalone test lane covers it. Nothing takes items
 * out yet.
 */
public final class TownStock
{
	/**
	 * @param town the town the items are in
	 * @param owner the id of the bot they belong to
	 * @param itemId the item
	 * @param count how many
	 */
	public record Listing(String town, long owner, int itemId, long count)
	{
	}

	private record Key(String town, long owner, int itemId)
	{
	}

	private final Map<Key, Long> _listings = new ConcurrentHashMap<>();
	private volatile boolean _dirty;

	/**
	 * Adds the items a span of hunting dropped: each item's expected count rounded up or down at random, so small
	 * amounts still add up over many spans.
	 * @param town the town the bot sells in (ignored when null)
	 * @param owner the hunting bot's id
	 * @param perKill item id to expected count per kill
	 * @param kills how many kills the span had
	 * @param random the rounding source
	 */
	public synchronized void deposit(String town, long owner, Map<Integer, Double> perKill, double kills, Random random)
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
				add(town, owner, entry.getKey(), count);
			}
		}
	}

	/**
	 * @param town a town
	 * @param owner the bot the items belong to
	 * @param itemId an item
	 * @param count how many to add
	 */
	public synchronized void add(String town, long owner, int itemId, long count)
	{
		_listings.merge(new Key(town, owner, itemId), count, Long::sum);
		_dirty = true;
	}

	/**
	 * Takes items out of a town for a sale, from the owners in id order.
	 * @param town a town
	 * @param itemId an item
	 * @param count how many
	 * @return what was taken, by owner, or null when the town holds fewer than that (nothing is taken then)
	 */
	public synchronized List<Listing> take(String town, int itemId, long count)
	{
		if ((count < 1) || (count(town, itemId) < count))
		{
			return null;
		}
		final List<Listing> taken = new ArrayList<>();
		long left = count;
		for (Listing listing : listings())
		{
			if (!listing.town().equals(town) || (listing.itemId() != itemId))
			{
				continue;
			}
			final long part = Math.min(left, listing.count());
			final Key key = new Key(town, listing.owner(), itemId);
			if (part >= listing.count())
			{
				_listings.remove(key);
			}
			else
			{
				_listings.put(key, listing.count() - part);
			}
			taken.add(new Listing(town, listing.owner(), itemId, part));
			left -= part;
			if (left == 0)
			{
				break;
			}
		}
		_dirty = true;
		return taken;
	}

	/**
	 * Removes a share of every listing, as the town clearing out its unsold goods: each listing loses its count times
	 * the fraction, rounded up or down at random.
	 * @param fraction the share to remove (0 to 1)
	 * @param random the rounding source
	 * @return what was removed, so the owners can be paid for it
	 */
	public synchronized List<Listing> decay(double fraction, Random random)
	{
		final List<Listing> removed = new ArrayList<>();
		for (Listing listing : listings())
		{
			final double expected = listing.count() * Math.max(0.0, Math.min(1.0, fraction));
			final long whole = (long) expected;
			final long count = Math.min(listing.count(), whole + ((random.nextDouble() < (expected - whole)) ? 1 : 0));
			if (count < 1)
			{
				continue;
			}
			final Key key = new Key(listing.town(), listing.owner(), listing.itemId());
			if (count == listing.count())
			{
				_listings.remove(key);
			}
			else
			{
				_listings.put(key, listing.count() - count);
			}
			removed.add(new Listing(listing.town(), listing.owner(), listing.itemId(), count));
		}
		if (!removed.isEmpty())
		{
			_dirty = true;
		}
		return removed;
	}

	/**
	 * Puts back what {@link #take} took, to the same owners.
	 * @param taken the listings it returned
	 */
	public synchronized void restore(List<Listing> taken)
	{
		for (Listing listing : taken)
		{
			add(listing.town(), listing.owner(), listing.itemId(), listing.count());
		}
	}

	/**
	 * @param town a town
	 * @param itemId an item
	 * @return how many the town holds, whoever owns them
	 */
	public long count(String town, int itemId)
	{
		long total = 0;
		for (Map.Entry<Key, Long> entry : _listings.entrySet())
		{
			if (entry.getKey().town().equals(town) && (entry.getKey().itemId() == itemId))
			{
				total += entry.getValue();
			}
		}
		return total;
	}

	/**
	 * @param town a town
	 * @param owner a bot
	 * @param itemId an item
	 * @return how many of it the bot has in the town
	 */
	public long count(String town, long owner, int itemId)
	{
		return _listings.getOrDefault(new Key(town, owner, itemId), 0L);
	}

	/** @return every listing, in town, owner, item order */
	public List<Listing> listings()
	{
		final List<Listing> all = new ArrayList<>();
		_listings.forEach((key, count) -> all.add(new Listing(key.town(), key.owner(), key.itemId(), count)));
		all.sort(Comparator.comparing(Listing::town).thenComparingLong(Listing::owner).thenComparingInt(Listing::itemId));
		return all;
	}

	/** @return each town's items and how many it holds in all, towns and items in order */
	public Map<String, Map<Integer, Long>> totals()
	{
		final Map<String, Map<Integer, Long>> totals = new TreeMap<>();
		_listings.forEach((key, count) -> totals.computeIfAbsent(key.town(), town -> new TreeMap<>()).merge(key.itemId(), count, Long::sum));
		return totals;
	}

	/**
	 * Splits what a buyer paid among the owners of the items taken, by how many of them each owned; the last owner gets
	 * the remainder, so the shares always add up to the total.
	 * @param taken what {@link #take} returned
	 * @param total the adena paid
	 * @return each listing's share, in the same order
	 */
	public static List<Long> shares(List<Listing> taken, long total)
	{
		final long units = taken.stream().mapToLong(Listing::count).sum();
		final List<Long> shares = new ArrayList<>();
		long left = total;
		for (int i = 0; i < taken.size(); i++)
		{
			final long share = (i == (taken.size() - 1)) ? left : ((total * taken.get(i).count()) / units);
			shares.add(share);
			left -= share;
		}
		return shares;
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
