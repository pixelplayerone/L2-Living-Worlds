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

import java.util.function.BiPredicate;

import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Town;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.ai.Intention;

/**
 * A Living Population bot that joined a party from far away travels to its leader "on the clock": the same time and
 * gatekeeper fees a walk and a teleport would take, but while no real player can see it, it is moved along the route in
 * steps instead of pathfinding across the map. Once it is within sight of the leader (or any real player sees it), it
 * walks for real; close by, the party's own follow takes over. Driven by the party tick (game thread).
 */
public final class LivingPartyTravel implements BiPredicate<Player, Player>
{
	// Within this of the leader the party's own follow takes over.
	static final double ARRIVE_RANGE = 1200.0;
	// Within this of the leader (about out of sight) it stops stepping and walks the rest for real.
	static final double APPEAR_RANGE = 2200.0;
	// Further than this a trip uses a gatekeeper when there is one on the way and it can pay.
	static final double WALK_RANGE = 15000.0;
	// How often an unseen bot is moved along its route.
	private static final long STEP_MS = 3000L;
	// How far a real player sees: inside this, it walks instead of stepping.
	private static final int SEEN_RANGE = 2500;
	// A walk issued for real is aimed this far ahead, so the engine's pathfinding only ever plans a short way.
	private static final double REAL_WALK_STEP = 600.0;

	/**
	 * How a bot gets to a point: on foot all the way, or on foot to its town's gatekeeper, a teleport to the town nearest
	 * the point (paid), then on foot.
	 * @param gatekeeper where it takes the teleport, or null for a walk all the way
	 * @param arrival where the teleport lands
	 * @param fee the gatekeeper fee
	 * @param town the town it teleports to
	 * @param seconds the whole trip's time at its walking speed (the teleport itself counted as instant)
	 */
	public record Plan(Point gatekeeper, Point arrival, long fee, String town, long seconds)
	{
		public boolean teleports()
		{
			return gatekeeper != null;
		}
	}

	/**
	 * Plans a trip like a player: walk when it is close or when the gatekeepers do not help, else a gatekeeper teleport
	 * it can afford from the town nearest to it to the town nearest the destination.
	 * @param from where it is
	 * @param to where it goes
	 * @param catalog the towns
	 * @param speed walking speed, units per second
	 * @param adena what it can spend
	 * @return the plan
	 */
	public static Plan plan(Point from, Point to, ZoneCatalog catalog, double speed, long adena)
	{
		final double direct = from.distance(to);
		final double pace = Math.max(1.0, speed);
		final Plan walk = new Plan(null, null, 0L, null, (long) Math.ceil(direct / pace));
		if ((direct <= WALK_RANGE) || (catalog == null))
		{
			return walk;
		}
		final Town start = catalog.nearestShoppingTown(from);
		final Town end = catalog.nearestShoppingTown(to);
		if ((start == null) || (end == null) || (start == end) || (start.gatekeeper() == null))
		{
			return walk;
		}
		final long fee = start.feeTo(end.name());
		if ((fee < 0) || (fee > adena))
		{
			return walk;
		}
		final double onFoot = from.distance(start.gatekeeper()) + end.arrival().distance(to);
		if (onFoot >= direct)
		{
			return walk;
		}
		return new Plan(start.gatekeeper(), end.arrival(), fee, end.name(), (long) Math.ceil(onFoot / pace));
	}

	/**
	 * @param from where it is
	 * @param to where it heads
	 * @param step how far it moves
	 * @return the point {@code step} along the straight line, or {@code to} when that is closer
	 */
	static Point toward(Point from, Point to, double step)
	{
		final double distance = from.distance(to);
		if (distance <= step)
		{
			return to;
		}
		final double t = step / distance;
		return new Point((int) Math.round(from.x() + ((to.x() - from.x()) * t)), (int) Math.round(from.y() + ((to.y() - from.y()) * t)), (int) Math.round(from.z() + ((to.z() - from.z()) * t)));
	}

	private final ZoneCatalog _catalog;
	private final double _speed;
	private Plan _plan;
	private boolean _toGatekeeper;
	private long _lastStepAt;
	private long _lastWalkAt;

	/**
	 * @param catalog the towns and gatekeepers
	 * @param speed walking speed, units per second
	 */
	public LivingPartyTravel(ZoneCatalog catalog, double speed)
	{
		_catalog = catalog;
		_speed = Math.max(1.0, speed);
	}

	/**
	 * Plans the trip to the leader now (paying nothing yet), for the bot's "omw" line.
	 * @return the plan
	 */
	public Plan start(Player bot, Player leader)
	{
		_plan = plan(point(bot), point(leader), _catalog, _speed, bot.getAdena());
		_toGatekeeper = _plan.teleports();
		_lastStepAt = System.currentTimeMillis();
		return _plan;
	}

	/**
	 * One party tick for a far member.
	 * @return true while it is still on its way (the party's follow stands aside)
	 */
	@Override
	public boolean test(Player bot, Player leader)
	{
		final long now = System.currentTimeMillis();
		final double distance = bot.calculateDistance2D(leader);
		if (distance <= ARRIVE_RANGE)
		{
			_plan = null; // arrived; a later trip (the leader teleported away) is planned afresh
			return false;
		}
		if (_plan == null)
		{
			start(bot, leader);
		}
		if (bot.isSitting())
		{
			bot.standUp();
		}
		if ((distance <= APPEAR_RANGE) || seen(bot))
		{
			walkForReal(bot, _toGatekeeper ? new Location(_plan.gatekeeper().x(), _plan.gatekeeper().y(), _plan.gatekeeper().z()) : leader.getLocation(), now);
			if (_toGatekeeper && (point(bot).distance(_plan.gatekeeper()) <= 150.0))
			{
				takeGatekeeper(bot);
			}
			_lastStepAt = now;
			return true;
		}
		if ((now - _lastStepAt) < STEP_MS)
		{
			return true;
		}
		final double step = (_speed * (now - _lastStepAt)) / 1000.0;
		_lastStepAt = now;
		if (_toGatekeeper)
		{
			final Point next = toward(point(bot), _plan.gatekeeper(), step);
			place(bot, next);
			if (next.equals(_plan.gatekeeper()))
			{
				takeGatekeeper(bot);
			}
			return true;
		}
		// On foot to the leader, wherever the leader is now; stop just out of sight and walk in from there.
		final Point here = point(bot);
		final Point target = point(leader);
		final double left = here.distance(target);
		if (left > (WALK_RANGE * 2))
		{
			_plan = null; // the leader went far away (teleported): plan again, maybe with a gatekeeper
			return true;
		}
		place(bot, toward(here, target, Math.min(step, Math.max(0.0, left - APPEAR_RANGE + 100.0))));
		return true;
	}

	/** Pays the gatekeeper and lands at the town nearest the leader; walks the whole way if it cannot pay any more. */
	private void takeGatekeeper(Player bot)
	{
		_toGatekeeper = false;
		if ((_plan.fee() > 0) && !bot.reduceAdena(ItemProcessType.FEE, (int) Math.min(Integer.MAX_VALUE, _plan.fee()), null, false))
		{
			return; // spent its money meanwhile: walks on from here
		}
		place(bot, _plan.arrival());
	}

	private void walkForReal(Player bot, Location target, long now)
	{
		if (bot.isMoving() && ((now - _lastWalkAt) < STEP_MS))
		{
			return;
		}
		_lastWalkAt = now;
		final Point next = toward(point(bot), new Point(target.getX(), target.getY(), target.getZ()), REAL_WALK_STEP);
		bot.setRunning();
		bot.getAI().setIntention(Intention.MOVE_TO, new Location(next.x(), next.y(), GeoEngine.getInstance().getHeight(next.x(), next.y(), next.z())));
	}

	private static void place(Player bot, Point at)
	{
		bot.abortCast();
		bot.teleToLocation(new Location(at.x(), at.y(), GeoEngine.getInstance().getHeight(at.x(), at.y(), at.z())));
		bot.onTeleported();
	}

	/** @return whether a real player (not a bot) is close enough to see it */
	private static boolean seen(Player bot)
	{
		for (Player player : World.getInstance().getVisibleObjectsInRange(bot, Player.class, SEEN_RANGE))
		{
			if ((player.getClient() != null) && !player.getClient().isDetached())
			{
				return true;
			}
		}
		return false;
	}

	private static Point point(Player player)
	{
		return new Point(player.getX(), player.getY(), player.getZ());
	}
}
