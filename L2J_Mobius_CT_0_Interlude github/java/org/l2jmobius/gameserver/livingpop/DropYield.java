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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a cold bot earns from one kill, worked out from the monster's real drop list the way L2Solo does it: the adena
 * it drops, plus what the other drops would sell for at a shop. Cold bots carry the loot's value and sell it on their
 * next town visit.
 * <p>This is the expected value per kill rather than a roll per kill, since a cold bot is resolved in spans of many
 * kills. It follows the server's own drop rules closely: group chance times item chance, the server's drop rates, the
 * level-gap penalty and the cap on how many chance-based drops one kill can give. Herbs and quest items count toward
 * that cap (they drop in the real game) but are worth nothing to sell. Pure, so it is tested without a server.
 */
public final class DropYield
{
	/** The adena item id. */
	public static final int ADENA = 57;

	private DropYield()
	{
	}

	/**
	 * One entry of a monster's drop or spoil list.
	 * @param itemId the item
	 * @param groupChance the chance of its group in percent (100 for an ungrouped entry)
	 * @param chance the item's chance in percent
	 * @param min the smallest amount
	 * @param max the largest amount
	 */
	public record Drop(int itemId, double groupChance, double chance, int min, int max)
	{
	}

	/**
	 * The server's drop rates and rules (Rates.ini).
	 * @param chance the death drop chance multiplier
	 * @param amount the death drop amount multiplier
	 * @param spoilChance the spoil chance multiplier
	 * @param spoilAmount the spoil amount multiplier
	 * @param chanceById per-item chance multipliers (replace {@code chance}); may be empty
	 * @param amountById per-item amount multipliers (replace {@code amount}); may be empty
	 * @param maxOccurrences how many chance-based drops one kill can give (0 or less: no cap)
	 * @param adenaGap the level gap rule for adena
	 * @param itemGap the level gap rule for other items
	 */
	public record Rates(double chance, double amount, double spoilChance, double spoilAmount, Map<Integer, Float> chanceById, Map<Integer, Float> amountById, int maxOccurrences, Gap adenaGap, Gap itemGap)
	{
		/** x1 rates, a cap of 2 drops per kill and no level gap penalty, for tests. */
		public static Rates plain()
		{
			return new Rates(1, 1, 1, 1, Map.of(), Map.of(), 2, Gap.NONE, Gap.NONE);
		}
	}

	/**
	 * The drop level-gap rule: a monster this many levels below the killer drops with a chance falling from 100% at
	 * {@code minDifference} to {@code minChance} at {@code maxDifference}.
	 * @param minDifference the gap where the penalty starts
	 * @param maxDifference the gap where it is full
	 * @param minChance the chance left at the full penalty, in percent
	 */
	public record Gap(int minDifference, int maxDifference, double minChance)
	{
		public static final Gap NONE = new Gap(1000, 1001, 100);

		/**
		 * @param levelDifference monster level minus killer level
		 * @return the chance in percent that a drop is not blocked by the gap
		 */
		public double chance(int levelDifference)
		{
			// Same as the server: scale the difference from [-max, -min] onto [minChance, 100], clamped.
			final double from = -maxDifference;
			final double to = -minDifference;
			if (to <= from)
			{
				return 100;
			}
			final double clamped = Math.max(from, Math.min(to, levelDifference));
			return minChance + (((clamped - from) / (to - from)) * (100 - minChance));
		}
	}

	/** Item facts the yield needs. */
	public interface Items
	{
		/**
		 * @param itemId an item
		 * @return what a shop pays for one, in adena (0 when it cannot be sold)
		 */
		long sellPrice(int itemId);

		/**
		 * @param itemId an item
		 * @return whether it is a herb (dropped but used on the spot, never carried)
		 */
		boolean herb(int itemId);
	}

	/**
	 * What one kill brings in.
	 * @param adena adena dropped
	 * @param loot what the other drops sell for at a shop
	 */
	public record Yield(double adena, double loot)
	{
		public static final Yield NONE = new Yield(0, 0);

		public Yield plus(Yield other)
		{
			return new Yield(adena + other.adena, loot + other.loot);
		}

		public Yield times(double factor)
		{
			return new Yield(adena * factor, loot * factor);
		}
	}

	/**
	 * The expected yield of one kill of a monster.
	 * @param drops its death drop list (grouped entries carry their group chance)
	 * @param spoils its spoil list, counted only when {@code spoiler}
	 * @param spoiler whether the killer spoils every kill (a Scavenger line dwarf)
	 * @param monsterLevel the monster's level
	 * @param killerLevel the bot's level
	 * @param rates the server's drop rates
	 * @param items item prices and kinds
	 * @return adena and loot value per kill
	 */
	public static Yield perKill(List<Drop> drops, List<Drop> spoils, boolean spoiler, int monsterLevel, int killerLevel, Rates rates, Items items)
	{
		final double itemGap = rates.itemGap().chance(monsterLevel - killerLevel) / 100.0;
		final double[] probability = probabilities(drops, monsterLevel, killerLevel, rates, items);
		double adena = 0;
		for (int i = 0; i < drops.size(); i++)
		{
			if (drops.get(i).itemId() == ADENA)
			{
				adena += probability[i] * average(drops.get(i)) * amountRate(drops.get(i), rates);
			}
		}
		if (spoiler)
		{
			for (Drop spoil : spoils)
			{
				if (spoil.itemId() == ADENA)
				{
					adena += Math.min(1.0, (spoil.chance() / 100.0) * rates.spoilChance()) * itemGap * average(spoil) * rates.spoilAmount();
				}
			}
		}
		double loot = 0;
		for (Map.Entry<Integer, Double> entry : counts(drops, spoils, spoiler, monsterLevel, killerLevel, rates, items).entrySet())
		{
			loot += entry.getValue() * items.sellPrice(entry.getKey());
		}
		return new Yield(adena, loot);
	}

	/**
	 * How many of each sellable item one kill drops on average, under the same rules as {@link #perKill} (adena, herbs and
	 * items a shop will not buy are left out).
	 * @param drops its death drop list
	 * @param spoils its spoil list, counted only when {@code spoiler}
	 * @param spoiler whether the killer spoils every kill
	 * @param monsterLevel the monster's level
	 * @param killerLevel the bot's level
	 * @param rates the server's drop rates
	 * @param items item prices and kinds
	 * @return item id to expected count per kill
	 */
	public static Map<Integer, Double> counts(List<Drop> drops, List<Drop> spoils, boolean spoiler, int monsterLevel, int killerLevel, Rates rates, Items items)
	{
		final Map<Integer, Double> counts = new HashMap<>();
		final double[] probability = probabilities(drops, monsterLevel, killerLevel, rates, items);
		for (int i = 0; i < drops.size(); i++)
		{
			final Drop drop = drops.get(i);
			if ((drop.itemId() != ADENA) && !items.herb(drop.itemId()) && (items.sellPrice(drop.itemId()) > 0))
			{
				counts.merge(drop.itemId(), probability[i] * average(drop) * amountRate(drop, rates), Double::sum);
			}
		}
		if (spoiler)
		{
			final double itemGap = rates.itemGap().chance(monsterLevel - killerLevel) / 100.0;
			for (Drop spoil : spoils)
			{
				if ((spoil.itemId() != ADENA) && (items.sellPrice(spoil.itemId()) > 0))
				{
					counts.merge(spoil.itemId(), Math.min(1.0, (spoil.chance() / 100.0) * rates.spoilChance()) * itemGap * average(spoil) * rates.spoilAmount(), Double::sum);
				}
			}
		}
		return counts;
	}

	private static double amountRate(Drop drop, Rates rates)
	{
		final Float byId = rates.amountById().get(drop.itemId());
		return (byId != null) ? byId : rates.amount();
	}

	/**
	 * The chance per kill that each item (adena and herbs aside) drops at least once, from the death drop list and, for
	 * a spoiler, the spoil list, under the same rules as {@link #perKill}. Used to roll real gear drops.
	 * @param drops its death drop list
	 * @param spoils its spoil list, counted only when {@code spoiler}
	 * @param spoiler whether the killer spoils every kill
	 * @param monsterLevel the monster's level
	 * @param killerLevel the bot's level
	 * @param rates the server's drop rates
	 * @param items item kinds
	 * @return item id to chance per kill (0 to 1)
	 */
	public static Map<Integer, Double> chances(List<Drop> drops, List<Drop> spoils, boolean spoiler, int monsterLevel, int killerLevel, Rates rates, Items items)
	{
		final Map<Integer, Double> chances = new HashMap<>();
		final double[] probability = probabilities(drops, monsterLevel, killerLevel, rates, items);
		for (int i = 0; i < drops.size(); i++)
		{
			final int itemId = drops.get(i).itemId();
			if ((itemId != ADENA) && !items.herb(itemId) && (probability[i] > 0))
			{
				chances.merge(itemId, probability[i], DropYield::either);
			}
		}
		if (spoiler)
		{
			final double itemGap = rates.itemGap().chance(monsterLevel - killerLevel) / 100.0;
			for (Drop spoil : spoils)
			{
				final double p = Math.min(1.0, (spoil.chance() / 100.0) * rates.spoilChance()) * itemGap;
				if ((spoil.itemId() != ADENA) && (p > 0))
				{
					chances.merge(spoil.itemId(), p, DropYield::either);
				}
			}
		}
		return chances;
	}

	/**
	 * Each death drop's chance per kill: group chance times item chance times the rate, the level-gap penalty, and the cap
	 * on chance-based drops one kill can give. Herbs and quest items count toward the cap as they drop in the real game.
	 */
	private static double[] probabilities(List<Drop> drops, int monsterLevel, int killerLevel, Rates rates, Items items)
	{
		final int gap = monsterLevel - killerLevel;
		final double adenaGap = rates.adenaGap().chance(gap) / 100.0;
		final double itemGap = rates.itemGap().chance(gap) / 100.0;
		final int count = drops.size();
		final double[] probability = new double[count];
		double chanceBased = 0;
		for (int i = 0; i < count; i++)
		{
			final Drop drop = drops.get(i);
			final Float byId = rates.chanceById().get(drop.itemId());
			final double rate = (byId != null) ? byId : (items.herb(drop.itemId()) ? 1.0 : rates.chance());
			final double p = Math.min(1.0, (drop.groupChance() / 100.0) * (drop.chance() / 100.0) * rate);
			probability[i] = p * ((drop.itemId() == ADENA) ? adenaGap : itemGap);
			if (p < 1.0)
			{
				chanceBased += probability[i];
			}
		}
		// One kill gives at most maxOccurrences chance-based drops: scale them down when they would add up to more.
		final double cap = ((rates.maxOccurrences() > 0) && (chanceBased > rates.maxOccurrences())) ? (rates.maxOccurrences() / chanceBased) : 1.0;
		for (int i = 0; i < count; i++)
		{
			if (probability[i] < 1.0)
			{
				probability[i] *= cap;
			}
		}
		return probability;
	}

	/** The chance that at least one of two independent drops happens. */
	private static double either(double a, double b)
	{
		return 1.0 - ((1.0 - a) * (1.0 - b));
	}

	/**
	 * Averages yields by how often each monster is met (its spawn count in the zone).
	 * @param yields per-monster yields
	 * @param weights matching spawn counts
	 * @return the zone's yield per kill, or {@link Yield#NONE} when there is nothing to average
	 */
	public static Yield average(List<Yield> yields, List<Integer> weights)
	{
		double total = 0;
		Yield sum = Yield.NONE;
		for (int i = 0; i < yields.size(); i++)
		{
			final int weight = Math.max(0, weights.get(i));
			sum = sum.plus(yields.get(i).times(weight));
			total += weight;
		}
		return (total <= 0) ? Yield.NONE : sum.times(1.0 / total);
	}

	private static double average(Drop drop)
	{
		return (Math.max(0, drop.min()) + Math.max(drop.min(), drop.max())) / 2.0;
	}
}
