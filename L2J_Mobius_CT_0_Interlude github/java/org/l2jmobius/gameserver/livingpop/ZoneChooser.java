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

import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Teleport;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Town;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Zone;

/**
 * Picks the hunting zone a bot goes to next and how it gets there, the way L2Solo's spot selection does: newbies stay in
 * their race's newbie grounds, a bot keeps its zone while it still fits and is not crowded, and otherwise it picks among
 * the zones that fit its level best, skipping full ones and ones it cannot afford to reach. Pure (randomness comes from
 * the caller's {@link Random}).
 */
public final class ZoneChooser
{
	/** A zone counts as reachable on foot from a town when its center is this close to the town. */
	public static final double WALK_RANGE = 15000;
	/** Levels past a zone's top a bot keeps hunting there before it counts as outleveled (L2Solo allows about 4). */
	public static final int OUTLEVEL_SLACK = 4;
	/** Up to this level a bot prefers zones near its race's home village, like a new player (L2Solo: about 20). */
	public static final int LOCAL_UNTIL_LEVEL = 20;
	/** How far from its home village's newbie grounds a zone may be to count as local (L2Solo's starter region). */
	public static final double LOCAL_RADIUS = 42000;

	private ZoneChooser()
	{
	}

	/** How a bot gets from a town to a zone. */
	public enum Way
	{
		/** On foot from the town. */
		WALK,
		/** This town's gatekeeper goes there directly. */
		GATEKEEPER,
		/** Gatekeeper to another town first, then that town's gatekeeper. */
		HOP
	}

	/**
	 * A zone and the way there.
	 * @param zone the zone
	 * @param way how to get there
	 * @param viaTown for {@link Way#HOP}, the town to hop through; otherwise null
	 * @param fee total gatekeeper fees
	 * @param arrival where the last teleport lands (for {@link Way#WALK}, the town arrival point)
	 */
	public record Choice(Zone zone, Way way, String viaTown, long fee, Point arrival)
	{
	}

	/**
	 * The situation the choice is made in.
	 * @param level bot level
	 * @param race bot race label
	 * @param currentZone the zone it hunts in now, or null
	 * @param town the town it leaves from
	 * @param budget adena it may spend on fees
	 * @param occupancy how many bots are in or heading to each zone (the bot itself excluded)
	 * @param capacity how many bots a zone takes before it counts as full
	 * @param avoid a zone it is staying away from after dying there, or null
	 */
	public record Situation(int level, String race, Zone currentZone, Town town, long budget, Map<String, Integer> occupancy, int capacity, String avoid)
	{
		public Situation(int level, String race, Zone currentZone, Town town, long budget, Map<String, Integer> occupancy, int capacity)
		{
			this(level, race, currentZone, town, budget, occupancy, capacity, null);
		}

		boolean avoids(Zone zone)
		{
			return (avoid != null) && avoid.equals(zone.name());
		}
	}

	/**
	 * Chooses the zone.
	 * @param situation the situation
	 * @param catalog the catalog
	 * @param random randomness for variety among equally good zones
	 * @return the choice, or null when no fitting zone can be reached (the bot then stays where it is)
	 */
	public static Choice choose(Situation situation, ZoneCatalog catalog, Random random)
	{
		final Town town = situation.town();
		if (town == null)
		{
			return null;
		}

		// Newbies hunt in their own race's newbie grounds, like new players.
		for (Zone zone : catalog.zones())
		{
			if (zone.isStarter() && zone.starterRace().equals(situation.race()) && (situation.level() <= zone.maxLevel()) && !situation.avoids(zone))
			{
				// On foot from a town on their land, by gatekeeper from one across the water (FPC-277).
				final Choice starter = route(zone, town, catalog);
				if ((starter != null) && (starter.fee() <= situation.budget()))
				{
					return starter;
				}
			}
		}

		// Keep the current zone while it still fits (up to OUTLEVEL_SLACK levels past its top) and is not crowded.
		final Zone current = situation.currentZone();
		if ((current != null) && !current.isStarter() && !situation.avoids(current) && (situation.level() >= current.minLevel()) && (situation.level() <= (current.maxLevel() + OUTLEVEL_SLACK)) && !full(current, situation))
		{
			final Choice stay = route(current, town, catalog);
			if ((stay != null) && (stay.fee() <= situation.budget()))
			{
				return stay;
			}
		}

		final List<Choice> options = new ArrayList<>();
		for (Zone zone : catalog.zones())
		{
			if (zone.isStarter() || !zone.fits(situation.level()) || full(zone, situation) || situation.avoids(zone))
			{
				continue;
			}
			final Choice choice = route(zone, town, catalog);
			if ((choice != null) && (choice.fee() <= situation.budget()))
			{
				options.add(choice);
			}
		}
		if (options.isEmpty())
		{
			return null;
		}

		// Young bots stay near home when there is anything suitable there, instead of crossing the map at level 11.
		if (situation.level() <= LOCAL_UNTIL_LEVEL)
		{
			final Point home = home(situation.race(), catalog);
			if (home != null)
			{
				final List<Choice> local = new ArrayList<>();
				for (Choice option : options)
				{
					if (option.zone().center().distance(home) <= LOCAL_RADIUS)
					{
						local.add(option);
					}
				}
				if (!local.isEmpty())
				{
					options.clear();
					options.addAll(local);
				}
			}
		}

		// Best fit first: a level about a third of the way into the zone's range (room to grow), then the cheapest trip.
		options.sort(Comparator.comparingDouble((Choice c) -> fitScore(c.zone(), situation.level())).thenComparingLong(Choice::fee));
		final int pool = Math.min(3, options.size());
		return options.get((random == null) ? 0 : random.nextInt(pool));
	}

	/**
	 * The cheapest way from a town to a zone.
	 * @param zone the zone
	 * @param town the town
	 * @param catalog the catalog (for hops)
	 * @return the way, or null when the zone cannot be reached from this town
	 */
	public static Choice route(Zone zone, Town town, ZoneCatalog catalog)
	{
		// Never on foot across water (FPC-277): a zone on another island is reached through the gatekeepers only.
		final boolean sameLand = catalog.sameLand(zone.center(), town.arrival());
		if (sameLand && (zone.isStarter() || (zone.center().distance(town.arrival()) <= WALK_RANGE)))
		{
			return new Choice(zone, Way.WALK, null, 0L, town.arrival());
		}
		if (zone.isStarter())
		{
			// Newbie grounds across the water: the gatekeeper to the shopping town on their land, then on foot.
			final Town home = catalog.nearestShoppingTown(zone.center());
			final long fee = (home == null) ? -1L : town.feeTo(home.name());
			return ((fee < 0) || !catalog.sameLand(zone.center(), home.arrival())) ? null : new Choice(zone, Way.GATEKEEPER, null, fee, home.arrival());
		}
		final Teleport direct = zone.teleportFrom(town.name());
		if (direct != null)
		{
			return new Choice(zone, Way.GATEKEEPER, null, direct.fee(), direct.arrival());
		}
		Choice best = null;
		for (Teleport teleport : zone.teleports())
		{
			final long hop = town.feeTo(teleport.town());
			if (hop < 0)
			{
				continue;
			}
			final long fee = hop + teleport.fee();
			if ((best == null) || (fee < best.fee()))
			{
				best = new Choice(zone, Way.HOP, teleport.town(), fee, teleport.arrival());
			}
		}
		return best;
	}

	/** The center of the race's newbie grounds, standing in for its home village; null when the race has none. */
	private static Point home(String race, ZoneCatalog catalog)
	{
		for (Zone zone : catalog.zones())
		{
			if (zone.isStarter() && zone.starterRace().equals(race))
			{
				return zone.center();
			}
		}
		return null;
	}

	private static boolean full(Zone zone, Situation situation)
	{
		final Integer count = (situation.occupancy() == null) ? null : situation.occupancy().get(zone.name());
		return (situation.capacity() > 0) && (count != null) && (count.intValue() >= situation.capacity());
	}

	private static double fitScore(Zone zone, int level)
	{
		final double sweet = zone.minLevel() + ((zone.maxLevel() - zone.minLevel()) / 3.0);
		return Math.abs(level - sweet);
	}
}
