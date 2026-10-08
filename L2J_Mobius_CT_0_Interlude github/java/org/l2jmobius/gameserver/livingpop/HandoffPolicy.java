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

/**
 * The pure decisions behind the Phase 3 hot/cold handoff: whether a cold bot should go hot because a real player is near,
 * and whether a hot bot should cool back because no player has been near for long enough. It is deliberately free of any
 * game type so the boundaries (activation radius, hysteresis, the cooldown grace window) are locked down in the standalone
 * test lane, exactly like {@link ColdProgression} and {@link PopulationDirector}. The orchestration that reads player and
 * bot positions and actually spawns or despawns the phantom lives in {@link HotColdHandoff}.
 */
public final class HandoffPolicy
{
	private HandoffPolicy()
	{
	}

	/**
	 * Handoff tuning. Kept here, in the pure layer, so {@link LivingPopulationConfig} can build and expose it without
	 * depending on the game-typed {@link HotColdHandoff} (which the standalone test lane cannot compile).
	 * @param activationRadius how close a real player must be (planar units) for a cold bot to go hot
	 * @param deactivationRadius the wider radius a player must leave before a hot bot may cool (hysteresis)
	 * @param cooldownGraceMs how long no player may be within the deactivation radius before a hot bot cools
	 * @param maxHotBots the ceiling on simultaneously hot bots
	 */
	public record Params(double activationRadius, double deactivationRadius, long cooldownGraceMs, int maxHotBots)
	{
	}

	/**
	 * Planar (x/y) distance between two points. The handoff ignores z on purpose: a player and a bot on stacked geodata
	 * layers at the same map spot should still count as together.
	 * @param x1 first x
	 * @param y1 first y
	 * @param x2 second x
	 * @param y2 second y
	 * @return the distance in game units
	 */
	public static double planarDistance(int x1, int y1, int x2, int y2)
	{
		final double dx = (double) x1 - x2;
		final double dy = (double) y1 - y2;
		return Math.sqrt((dx * dx) + (dy * dy));
	}

	/**
	 * Whether a cold bot should be materialized. True when a real player is within the activation radius of the bot.
	 * @param nearestPlayerDistance the distance to the nearest real player, or a negative value when none are online
	 * @param activationRadius the radius within which a player triggers activation
	 * @return true to go hot
	 */
	public static boolean shouldActivate(double nearestPlayerDistance, double activationRadius)
	{
		return (nearestPlayerDistance >= 0.0) && (nearestPlayerDistance <= activationRadius);
	}

	/**
	 * Whether a hot bot is still being watched. True while a real player is within the (wider) deactivation radius, which
	 * refreshes the "last near" stamp so the cooldown grace never starts. The gap between this and
	 * {@link #shouldActivate(double, double)} is the hysteresis that stops spawn/despawn thrash at the edge.
	 * @param nearestPlayerDistance the distance to the nearest real player, or a negative value when none are online
	 * @param deactivationRadius the radius a player must leave before the cooldown grace can begin
	 * @return true while still watched
	 */
	public static boolean isStillWatched(double nearestPlayerDistance, double deactivationRadius)
	{
		return (nearestPlayerDistance >= 0.0) && (nearestPlayerDistance <= deactivationRadius);
	}

	/**
	 * Whether a hot bot should cool back to cold: no real player has been within the deactivation radius for at least the
	 * grace window.
	 * @param lastNearAt the epoch-ms stamp of the last time a player was within the deactivation radius
	 * @param now the current epoch-ms time
	 * @param graceMs the grace window in milliseconds
	 * @return true to cool back
	 */
	public static boolean shouldCool(long lastNearAt, long now, long graceMs)
	{
		return (now - lastNearAt) >= graceMs;
	}
}
