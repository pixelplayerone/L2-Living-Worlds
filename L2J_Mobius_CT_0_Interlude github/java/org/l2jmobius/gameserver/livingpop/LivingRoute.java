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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;

/**
 * Plans a live bot's walk the way a player picks a road: around water, over bridges. The engine's own pathfinding
 * only knows the ground, and the ground goes on under a river or the sea, so its shortest path runs straight through
 * the water next to a bridge. This search walks a coarse grid over the terrain, where a step into water costs many
 * times a dry step, so a bridge or a way around wins over swimming whenever one is in reach. Water is never forbidden:
 * a bot already swimming still finds its way out, and a ford with no bridge is still crossed. A long walk is planned in
 * pieces: when the search runs out of nodes it returns the way to the dry point closest to the target, and the walker
 * plans the rest from there. Pure: the terrain comes in through {@link Terrain}.
 * <p>
 * FPC-278.
 */
public final class LivingRoute
{
	/** The terrain the search reads (the game's geodata and water zones on the server, a fake map in tests). */
	public interface Terrain
	{
		/**
		 * @return whether there is terrain data here at all (no data: the search gives up and the caller walks as before)
		 */
		boolean known(int x, int y);

		/** @return the ground height here, on the layer nearest {@code z} (a bridge deck, not the river bed under it) */
		int height(int x, int y, int z);

		/** @return whether this point is in water */
		boolean water(int x, int y, int z);

		/** @return whether a character can walk in a straight line from one point to the other */
		boolean canWalk(int x, int y, int z, int tx, int ty, int tz);
	}

	/** Grid spacing in game units: fine enough to find a bridge a few hundred units wide. */
	public static final int CELL = 80;
	/** A step into water costs this many dry steps, so a detour of up to that length to a bridge is preferred. */
	public static final double WATER_COST = 30.0;
	/** The most grid nodes one search opens before it settles for the best piece so far. */
	public static final int MAX_NODES = 5000;
	/** How long one straight stretch of the planned route may be. */
	public static final double MAX_LEG = 1500.0;
	// The heuristic is weighted a little, so open ground is crossed almost straight without opening every node.
	private static final double HEURISTIC_WEIGHT = 1.3;
	// Spacing of the water checks along a straightened stretch.
	private static final double SAMPLE_STEP = 40.0;

	private static final int[][] NEIGHBOURS =
	{
		{
			1,
			0
		},
		{
			-1,
			0
		},
		{
			0,
			1
		},
		{
			0,
			-1
		},
		{
			1,
			1
		},
		{
			1,
			-1
		},
		{
			-1,
			1
		},
		{
			-1,
			-1
		}
	};

	private LivingRoute()
	{
	}

	private static final class Node
	{
		final int i;
		final int j;
		final Point at;
		final boolean wet;
		Node parent;
		double cost;
		double estimate;
		boolean closed;

		Node(int i, int j, Point at, boolean wet)
		{
			this.i = i;
			this.j = j;
			this.at = at;
			this.wet = wet;
		}
	}

	/**
	 * Plans the way from one point to another.
	 * @param terrain the terrain
	 * @param from where the bot stands
	 * @param to where it goes
	 * @return the waypoints to walk in order (the last is {@code to} when the whole way was found, otherwise the dry
	 *         point closest to it), or null when there is no terrain data or no step toward the target can be made
	 */
	public static List<Point> plan(Terrain terrain, Point from, Point to)
	{
		return plan(terrain, from, to, MAX_NODES);
	}

	/**
	 * As {@link #plan(Terrain, Point, Point)} with a node budget (for tests).
	 */
	static List<Point> plan(Terrain terrain, Point from, Point to, int maxNodes)
	{
		if (!terrain.known(from.x(), from.y()) || !terrain.known(to.x(), to.y()))
		{
			return null;
		}
		final Point start = new Point(from.x(), from.y(), terrain.height(from.x(), from.y(), from.z()));
		final double total = start.distance(to);
		if (total <= CELL)
		{
			return terrain.canWalk(start.x(), start.y(), start.z(), to.x(), to.y(), to.z()) ? List.of(to) : null;
		}

		final Map<Long, Node> nodes = new HashMap<>();
		final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.cost + a.estimate, b.cost + b.estimate));
		final Node first = new Node(0, 0, start, false); // the start never counts as water: a swimming bot must get out
		first.estimate = HEURISTIC_WEIGHT * start.distance(to);
		nodes.put(key(0, 0), first);
		open.add(first);

		Node best = first; // the dry node closest to the target, for a partial plan
		double bestDistance = total;
		Node goal = null;
		int opened = 0;
		while (!open.isEmpty() && (opened < maxNodes))
		{
			final Node node = open.poll();
			if (node.closed)
			{
				continue;
			}
			node.closed = true;
			opened++;
			final double left = node.at.distance(to);
			if (!node.wet && (left < bestDistance))
			{
				best = node;
				bestDistance = left;
			}
			if ((left <= (CELL * 1.5)) && terrain.canWalk(node.at.x(), node.at.y(), node.at.z(), to.x(), to.y(), to.z()))
			{
				goal = node;
				break;
			}
			for (int[] step : NEIGHBOURS)
			{
				final int ni = node.i + step[0];
				final int nj = node.j + step[1];
				final long id = key(ni, nj);
				Node next = nodes.get(id);
				if ((next != null) && next.closed)
				{
					continue;
				}
				final int x = start.x() + (ni * CELL);
				final int y = start.y() + (nj * CELL);
				if (next == null)
				{
					if (!terrain.known(x, y))
					{
						continue;
					}
					final int z = terrain.height(x, y, node.at.z());
					next = new Node(ni, nj, new Point(x, y, z), terrain.water(x, y, z));
				}
				if (!terrain.canWalk(node.at.x(), node.at.y(), node.at.z(), next.at.x(), next.at.y(), next.at.z()))
				{
					continue;
				}
				final double length = ((step[0] != 0) && (step[1] != 0)) ? (CELL * Math.sqrt(2.0)) : CELL;
				final double cost = node.cost + (length * (next.wet ? WATER_COST : 1.0));
				if ((next.parent == null) && (next != first))
				{
					next.parent = node;
					next.cost = cost;
					next.estimate = HEURISTIC_WEIGHT * next.at.distance(to);
					nodes.put(id, next);
					open.add(next);
				}
				else if (cost < next.cost)
				{
					next.parent = node;
					next.cost = cost;
					open.add(next); // the stale entry is skipped once this one is closed
				}
			}
		}

		final List<Point> path = new ArrayList<>();
		if (goal != null)
		{
			path.add(to);
		}
		Node cursor = (goal != null) ? goal : best;
		if ((goal == null) && ((cursor == first) || ((total - bestDistance) < CELL)))
		{
			return null; // no step toward the target at all
		}
		for (; (cursor != null) && (cursor != first); cursor = cursor.parent)
		{
			path.addFirst(cursor.at);
		}
		return straighten(terrain, start, path);
	}

	/**
	 * Drops the grid points a straight walk can skip: a stretch is merged while it stays walkable, dry where the planned
	 * way was dry, and no longer than {@link #MAX_LEG}.
	 */
	private static List<Point> straighten(Terrain terrain, Point start, List<Point> path)
	{
		final List<Point> out = new ArrayList<>();
		Point anchor = start;
		int index = 0;
		while (index < path.size())
		{
			int reach = index;
			for (int k = index + 1; k < path.size(); k++)
			{
				final Point candidate = path.get(k);
				if ((anchor.distance(candidate) > MAX_LEG) || !terrain.canWalk(anchor.x(), anchor.y(), anchor.z(), candidate.x(), candidate.y(), candidate.z()) || (dry(terrain, path, index, k) && !dryLine(terrain, anchor, candidate)))
				{
					break;
				}
				reach = k;
			}
			anchor = path.get(reach);
			out.add(anchor);
			index = reach + 1;
		}
		return out;
	}

	/** @return whether the planned points from {@code from} to {@code to} are all dry */
	private static boolean dry(Terrain terrain, List<Point> path, int from, int to)
	{
		for (int k = from; k <= to; k++)
		{
			final Point point = path.get(k);
			if (terrain.water(point.x(), point.y(), point.z()))
			{
				return false;
			}
		}
		return true;
	}

	/** @return whether a straight walk between two points stays out of the water */
	static boolean dryLine(Terrain terrain, Point from, Point to)
	{
		final double length = from.distance(to);
		final int samples = Math.max(1, (int) Math.ceil(length / SAMPLE_STEP));
		int z = from.z();
		for (int s = 1; s <= samples; s++)
		{
			final double t = (double) s / samples;
			final int x = (int) Math.round(from.x() + ((to.x() - from.x()) * t));
			final int y = (int) Math.round(from.y() + ((to.y() - from.y()) * t));
			z = terrain.height(x, y, z);
			if (terrain.water(x, y, z))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * A spot of this bot's own around a point, so bots that share one cold position (a zone's hunting spot, a shop, the
	 * town respawn point) do not appear stacked on it when they go hot (FPC-292). The angle and distance come from the
	 * bot id, so a bot gets the same spot every time. The spot must have terrain data, be dry, and be reachable on foot
	 * in a straight line from {@code from}; up to eight angles are tried, and when none fits the point itself is kept.
	 * @param terrain the terrain
	 * @param id the bot id
	 * @param around the point to spread around
	 * @param from where the spot must be walkable from (usually {@code around})
	 * @param minRadius the nearest the spot may be
	 * @param maxRadius the farthest the spot may be
	 * @return the spot, or {@code around} when no spot fits
	 */
	public static Point spread(Terrain terrain, long id, Point around, Point from, double minRadius, double maxRadius)
	{
		if (!terrain.known(around.x(), around.y()))
		{
			return around;
		}
		final long mixed = (id * 0x9E3779B97F4A7C15L) ^ (id >>> 17);
		final double angle = ((mixed >>> 11) & 0xFFFF) * ((2 * Math.PI) / 0x10000);
		final double radius = minRadius + ((((mixed >>> 33) & 0xFF) / 255.0) * (maxRadius - minRadius));
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double turn = angle + (attempt * (Math.PI / 4));
			final int x = (int) Math.round(around.x() + (Math.cos(turn) * radius));
			final int y = (int) Math.round(around.y() + (Math.sin(turn) * radius));
			if (!terrain.known(x, y))
			{
				continue;
			}
			final int z = terrain.height(x, y, around.z());
			if (!terrain.water(x, y, z) && terrain.canWalk(from.x(), from.y(), from.z(), x, y, z))
			{
				return new Point(x, y, z);
			}
		}
		return around;
	}

	private static long key(int i, int j)
	{
		return (((long) i) << 32) ^ (j & 0xffffffffL);
	}
}
