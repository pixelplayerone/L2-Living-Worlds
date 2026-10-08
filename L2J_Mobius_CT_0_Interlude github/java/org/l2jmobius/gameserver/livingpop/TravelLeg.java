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

import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;

/**
 * One timed step of a bot's travel or town visit: from a point to a point between two times. While it runs the bot's
 * position moves along the straight line (for walking) or stays put (for casting, shopping or AFK, where from equals to).
 * Stored in the row as a compact comma-separated string. Pure.
 * @param from where the step starts
 * @param to where it ends
 * @param startAt epoch ms it started
 * @param endAt epoch ms it ends
 */
public record TravelLeg(Point from, Point to, long startAt, long endAt)
{
	/**
	 * A step that stays in one place (casting, shopping, AFK).
	 * @param at the place
	 * @param startAt start time
	 * @param durationMs how long
	 * @return the step
	 */
	public static TravelLeg stay(Point at, long startAt, long durationMs)
	{
		return new TravelLeg(at, at, startAt, startAt + Math.max(0L, durationMs));
	}

	/**
	 * A walk at a given speed.
	 * @param from start
	 * @param to destination
	 * @param startAt start time
	 * @param speed game units per second (at least 1)
	 * @return the step
	 */
	public static TravelLeg walk(Point from, Point to, long startAt, double speed)
	{
		final long duration = (long) Math.ceil((from.distance(to) / Math.max(1.0, speed)) * 1000.0);
		return new TravelLeg(from, to, startAt, startAt + duration);
	}

	/**
	 * @param now the time
	 * @return whether the step is over
	 */
	public boolean done(long now)
	{
		return now >= endAt;
	}

	/** @return the step's length in milliseconds */
	public long durationMs()
	{
		return Math.max(0L, endAt - startAt);
	}

	/**
	 * Where the bot is at a time along this step.
	 * @param now the time
	 * @return the position
	 */
	public Point positionAt(long now)
	{
		if ((now >= endAt) || (endAt <= startAt))
		{
			return (now >= endAt) ? to : from;
		}
		if (now <= startAt)
		{
			return from;
		}
		final double t = (double) (now - startAt) / (double) (endAt - startAt);
		return new Point((int) Math.round(from.x() + ((to.x() - from.x()) * t)), (int) Math.round(from.y() + ((to.y() - from.y()) * t)), (int) Math.round(from.z() + ((to.z() - from.z()) * t)));
	}

	/** @return the stored form: eight comma-separated numbers */
	public String encode()
	{
		return from.x() + "," + from.y() + "," + from.z() + "," + to.x() + "," + to.y() + "," + to.z() + "," + startAt + "," + endAt;
	}

	/**
	 * Reads the stored form.
	 * @param text the stored text
	 * @return the step, or null when the text is empty or malformed
	 */
	public static TravelLeg decode(String text)
	{
		if ((text == null) || text.isBlank())
		{
			return null;
		}
		final String[] parts = text.split(",");
		if (parts.length != 8)
		{
			return null;
		}
		try
		{
			return new TravelLeg(new Point(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())), new Point(Integer.parseInt(parts[3].trim()), Integer.parseInt(parts[4].trim()), Integer.parseInt(parts[5].trim())), Long.parseLong(parts[6].trim()), Long.parseLong(parts[7].trim()));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
