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
import java.util.TreeMap;

/**
 * How a living bot learns its skills, like a player on a server without auto-learn: it earns SP while hunting, and on a
 * town visit its trainer teaches it every skill its level allows that it can pay for, in SP and, for the skills that need
 * one, a spellbook bought at the item's real price. Skills a book is not sold for (reference price 0) wait. Skills the
 * game gives for free (auto-get) are learned at no cost. L2Solo instead grants every skill on level up. Pure, so it is
 * tested without a server.
 */
public final class SkillPlanner
{
	private SkillPlanner()
	{
	}

	/**
	 * One level of one skill in a class's skill tree.
	 * @param skillId the skill
	 * @param level its level
	 * @param minLevel the character level it needs
	 * @param sp its SP cost
	 * @param bookId the spellbook it needs, or 0
	 * @param bookPrice what the spellbook costs in a shop (0 when it is not sold)
	 * @param free whether the game gives it without a trainer (auto-get)
	 * @param order its place in the class's SP priority list (lower learns first); {@link #UNRANKED} when it is not on the list
	 * @param tier its tier on that list: {@link #TIER_A} to save SP for, 1 and 2 for the rest, {@link #UNRANKED_TIER} when it is not on the list
	 */
	public record Entry(int skillId, int level, int minLevel, long sp, int bookId, long bookPrice, boolean free, int order, int tier)
	{
		/** The order of a skill that is not on the priority list: after every listed one, cheapest first. */
		public static final int UNRANKED = Integer.MAX_VALUE;
		public static final int TIER_A = 0;
		public static final int UNRANKED_TIER = 3;

		/** An entry that is not on any priority list. */
		public Entry(int skillId, int level, int minLevel, long sp, int bookId, long bookPrice, boolean free)
		{
			this(skillId, level, minLevel, sp, bookId, bookPrice, free, UNRANKED, UNRANKED_TIER);
		}

		/** @return whether it needs a book that no shop sells */
		public boolean unsold()
		{
			return (bookId > 0) && (bookPrice <= 0);
		}
	}

	/**
	 * A trainer visit.
	 * @param learned the skill levels learned, in order
	 * @param sp SP spent
	 * @param adena adena spent on spellbooks
	 * @param books spellbooks bought
	 * @param shortSp skills its level allows that wait for more SP
	 * @param shortAdena skills that wait for adena for their spellbook
	 */
	public record Lesson(List<Entry> learned, long sp, long adena, int books, int shortSp, int shortAdena)
	{
		/** @return whether it learned anything */
		public boolean any()
		{
			return !learned.isEmpty();
		}
	}

	/**
	 * Learns what it can, in the class's SP priority order: free skills first, then the listed skills best first, then the
	 * unlisted ones cheapest first. When the best skill left is a tier A one it cannot yet pay for in SP, it saves for it
	 * and buys nothing lower; any other skill it cannot pay for is passed over. Next levels of a skill open up as the
	 * previous one is learned.
	 * @param tree the class's complete skill tree
	 * @param known the skills it knows (skill id to level); updated in place
	 * @param level its level
	 * @param sp its SP
	 * @param budget adena it may spend on spellbooks
	 * @return what it learned and paid, and what waits
	 */
	public static Lesson learn(List<Entry> tree, Map<Integer, Integer> known, int level, long sp, long budget)
	{
		final List<Entry> learned = new ArrayList<>();
		long spLeft = Math.max(0L, sp);
		long adenaLeft = Math.max(0L, budget);
		int books = 0;
		boolean saving = false;
		boolean progress = true;
		while (progress)
		{
			progress = false;
			saving = false;
			for (Entry entry : next(tree, known, level))
			{
				if (entry.unsold())
				{
					continue;
				}
				final long sp1 = entry.free() ? 0L : entry.sp();
				final long book = (entry.free() || (entry.bookId() <= 0)) ? 0L : entry.bookPrice();
				if ((sp1 <= spLeft) && (book <= adenaLeft))
				{
					spLeft -= sp1;
					adenaLeft -= book;
					if (book > 0)
					{
						books++;
					}
					known.put(entry.skillId(), entry.level());
					learned.add(entry);
					progress = true; // the list changes (a next level opens up), so look again from the top
					break;
				}
				if ((entry.tier() == Entry.TIER_A) && (sp1 > spLeft))
				{
					saving = true; // it saves for this one and buys nothing lower
					break;
				}
			}
		}
		int shortSp = 0;
		int shortAdena = 0;
		for (Entry entry : next(tree, known, level))
		{
			if (entry.unsold())
			{
				continue;
			}
			if (!entry.free() && ((entry.sp() > spLeft) || saving))
			{
				shortSp++;
			}
			else
			{
				shortAdena++;
			}
		}
		return new Lesson(List.copyOf(learned), Math.max(0L, sp) - spLeft, Math.max(0L, budget) - adenaLeft, books, shortSp, shortAdena);
	}

	/**
	 * Every skill level its level allows, as if it had always learned on time (unsold books aside). Used once for a row
	 * from before skills were tracked, so an existing bot keeps the skills it was given.
	 * @param tree the class's complete skill tree
	 * @param known the skills it knows; updated in place
	 * @param level its level
	 */
	public static void grantAll(List<Entry> tree, Map<Integer, Integer> known, int level)
	{
		boolean progress = true;
		while (progress)
		{
			progress = false;
			for (Entry entry : next(tree, known, level))
			{
				if (!entry.unsold())
				{
					known.put(entry.skillId(), entry.level());
					progress = true;
				}
			}
		}
	}

	/**
	 * @param tree the class's complete skill tree
	 * @param known the skills it knows
	 * @param level its level
	 * @return the SP cost of everything its level allows right now (the next level of each skill), unsold books aside
	 */
	public static long spWanted(List<Entry> tree, Map<Integer, Integer> known, int level)
	{
		long total = 0L;
		for (Entry entry : next(tree, known, level))
		{
			if (!entry.unsold() && !entry.free())
			{
				total += entry.sp();
			}
		}
		return total;
	}

	/** The next learnable level of each skill: free ones first, then by priority order, then cheapest first. */
	private static List<Entry> next(List<Entry> tree, Map<Integer, Integer> known, int level)
	{
		final List<Entry> result = new ArrayList<>();
		for (Entry entry : tree)
		{
			if ((entry.minLevel() <= level) && (entry.level() == (known.getOrDefault(entry.skillId(), 0) + 1)))
			{
				result.add(entry);
			}
		}
		result.sort(Comparator.comparing((Entry e) -> !e.free()).thenComparingInt(Entry::order).thenComparingInt(Entry::minLevel).thenComparingLong(Entry::sp).thenComparingInt(Entry::skillId));
		return result;
	}

	/**
	 * @param known the skills it knows
	 * @return the row text, "id:level" pairs separated by commas
	 */
	public static String encode(Map<Integer, Integer> known)
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> skill : new TreeMap<>(known).entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(skill.getKey()).append(':').append(skill.getValue());
		}
		return sb.toString();
	}

	/**
	 * @param text the row text
	 * @return the skills, or null when the row has none recorded yet (a row from before skills were tracked)
	 */
	public static Map<Integer, Integer> decode(String text)
	{
		if (text == null)
		{
			return null;
		}
		final Map<Integer, Integer> known = new TreeMap<>();
		for (String pair : text.split(","))
		{
			final int colon = pair.indexOf(':');
			if (colon <= 0)
			{
				continue;
			}
			try
			{
				known.put(Integer.parseInt(pair.substring(0, colon).trim()), Integer.parseInt(pair.substring(colon + 1).trim()));
			}
			catch (NumberFormatException e)
			{
				// skip a damaged pair
			}
		}
		return known;
	}
}
