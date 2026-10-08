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

import java.util.Set;

/**
 * The real items a living bot buys at the grocer, by level and gear: the healing potion a player of that level buys, the
 * soulshot grade its weapon fires (same grade thresholds as the phantom gear: D 20, C 40, B 52, A 61, S 76), and the
 * Scroll of Escape. Pure.
 */
public final class LivingSupplies
{
	public static final int SCROLL_OF_ESCAPE = 736;
	public static final int LESSER_HEALING_POTION = 1060;
	public static final int HEALING_POTION = 1061;

	private static final int[] SOULSHOT_BY_GRADE =
	{
		1835, // no grade
		1463, // D
		1464, // C
		1465, // B
		1466, // A
		1467, // S
	};

	// Class ids (Interlude) by how many potions the role carries, following L2Solo: tanks take the most, casters, archers
	// and healers the fewest (they rest for mana and fight from range). Everyone else is melee.
	private static final Set<Integer> TANKS = Set.of(4, 5, 6, 19, 20, 32, 33, 90, 91, 99, 106);
	private static final Set<Integer> LIGHT = Set.of(
		// every mystic class (mages, summoners, healers, buffers)
		10, 11, 12, 13, 14, 15, 16, 17, 25, 26, 27, 28, 29, 30, 38, 39, 40, 41, 42, 43, 49, 50, 51, 52, 94, 95, 96, 97, 98, 103, 104, 105, 110, 111, 112, 115, 116,
		// archers
		9, 24, 37, 92, 102, 109);

	private LivingSupplies()
	{
	}

	/**
	 * How many healing potions a bot of this class carries, scaled from the melee stock: tanks half again as many
	 * (L2Solo: 12 against 8), casters, archers and healers half as many (L2Solo: 4).
	 * @param classId the bot's class id
	 * @param meleeStock the configured stock for a melee fighter
	 * @return the stock for this class
	 */
	public static int potionStockFor(int classId, int meleeStock)
	{
		if (TANKS.contains(classId))
		{
			return (int) Math.round(meleeStock * 1.5);
		}
		if (LIGHT.contains(classId))
		{
			return Math.max(1, meleeStock / 2);
		}
		return meleeStock;
	}

	/**
	 * @param classId a class id
	 * @return whether the class is a tank (knights and their successors)
	 */
	public static boolean isTank(int classId)
	{
		return TANKS.contains(classId);
	}

	/**
	 * @param classId a class id
	 * @return whether the class is a caster or an archer (lightly armored)
	 */
	public static boolean isLight(int classId)
	{
		return LIGHT.contains(classId);
	}

	/**
	 * @param classId a class id
	 * @return whether the class spoils its kills (Scavenger, Bounty Hunter and Fortune Seeker)
	 */
	public static boolean isSpoiler(int classId)
	{
		return (classId == 54) || (classId == 55) || (classId == 117);
	}

	/**
	 * @param level a gear level
	 * @return its grade: 0 no grade, 1 D, 2 C, 3 B, 4 A, 5 S
	 */
	public static int gradeFor(int level)
	{
		if (level < 20)
		{
			return 0;
		}
		if (level < 40)
		{
			return 1;
		}
		if (level < 52)
		{
			return 2;
		}
		if (level < 61)
		{
			return 3;
		}
		if (level < 76)
		{
			return 4;
		}
		return 5;
	}

	/**
	 * @param level the bot level
	 * @return the healing potion it buys: Lesser Healing Potion below level 20, Healing Potion from 20
	 */
	public static int potionIdFor(int level)
	{
		return (level < 20) ? LESSER_HEALING_POTION : HEALING_POTION;
	}

	/**
	 * @param gearLevel the level its gear corresponds to (0 for the newbie starter gear)
	 * @return the soulshot item id for that gear's grade
	 */
	public static int soulshotIdFor(int gearLevel)
	{
		return SOULSHOT_BY_GRADE[gradeFor(gearLevel)];
	}
}
