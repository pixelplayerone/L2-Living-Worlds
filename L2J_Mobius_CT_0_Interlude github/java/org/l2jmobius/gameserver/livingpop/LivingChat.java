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
 * Plain words for what a Living Population bot is doing, for the brain when a player whispers it, so it answers "where
 * are you?" and "what are you doing?" truthfully whether it is a live character or only a row. Pure, for tests.
 */
public final class LivingChat
{
	private LivingChat()
	{
	}

	/**
	 * @param activity the row's activity ({@link ColdLife#HUNTING} and so on; null counts as hunting)
	 * @param zone the hunting zone it is in or heading to, or null
	 * @param town the town of its current errand, or null
	 * @return what it is doing, as the end of "you are ..." (for example "hunting in Ruins of Agony")
	 */
	public static String activity(String activity, String zone, String town)
	{
		final String inZone = blank(zone) ? "" : (" in " + zone);
		final String toTown = blank(town) ? " to town" : (" to " + town);
		final String inTown = blank(town) ? " in town" : (" in " + town);
		switch ((activity == null) ? ColdLife.HUNTING : activity)
		{
			case ColdLife.ESCAPING:
				return "reading a Scroll of Escape" + toTown + " to sell loot and restock";
			case ColdLife.WALKING_TO_TOWN:
			case ColdLife.TO_TOWN:
				return "on the way" + toTown + " to sell loot and restock";
			case ColdLife.IN_TOWN:
				return "shopping and restocking" + inTown;
			case ColdLife.AFK:
				return "afk" + inTown + " for a bit";
			case ColdLife.TO_ZONE:
				return blank(zone) ? "on the way to your next hunting spot" : ("on the way to " + zone + " to hunt");
			case ColdLife.RESTING:
				return "sitting down to regen between pulls" + inZone;
			case ColdLife.DEAD:
				return "back" + inTown + " after dying, recovering before heading out again";
			case ColdLife.CLASS_MASTER:
				return "doing your class change" + inTown;
			case ColdLife.TRAINER:
				return "learning new skills at the trainer" + inTown;
			default:
				return "hunting" + inZone;
		}
	}

	private static boolean blank(String text)
	{
		return (text == null) || text.isBlank();
	}
}
