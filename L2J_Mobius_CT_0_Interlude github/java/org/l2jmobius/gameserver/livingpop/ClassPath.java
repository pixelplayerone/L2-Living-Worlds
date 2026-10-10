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
import java.util.Set;

/**
 * The Interlude class tree as a living bot climbs it: which classes follow each one, at what level, which bot takes
 * which branch, and which class master handles the change. A bot always takes the same branch (picked from its id, as
 * L2Solo does), so a bot seen twice is the same class. Pure, so it is tested without a server.
 */
public final class ClassPath
{
	/** Levels of the first, second and third class change. */
	public static final int[] CHANGE_LEVELS =
	{
		20,
		40,
		76
	};

	private static final Map<Integer, List<Integer>> NEXT = new HashMap<>();
	private static final Map<Integer, Integer> TIER = new HashMap<>();
	private static final Map<Integer, String> NAMES = new HashMap<>();
	static
	{
		// Base classes and their first classes.
		base(0, "Human Fighter", 1, 4, 7);
		base(10, "Human Mystic", 11, 15);
		base(18, "Elven Fighter", 19, 22);
		base(25, "Elven Mystic", 26, 29);
		base(31, "Dark Fighter", 32, 35);
		base(38, "Dark Mystic", 39, 42);
		base(44, "Orc Fighter", 45, 47);
		base(49, "Orc Mystic", 50);
		base(53, "Dwarven Fighter", 54, 56);
		// First classes and their second classes.
		first(1, "Warrior", 2, 3);
		first(4, "Human Knight", 5, 6);
		first(7, "Rogue", 8, 9);
		first(11, "Human Wizard", 12, 13, 14);
		first(15, "Cleric", 16, 17);
		first(19, "Elven Knight", 20, 21);
		first(22, "Elven Scout", 23, 24);
		first(26, "Elven Wizard", 27, 28);
		first(29, "Elven Oracle", 30);
		first(32, "Palus Knight", 33, 34);
		first(35, "Assassin", 36, 37);
		first(39, "Dark Wizard", 40, 41);
		first(42, "Shillien Oracle", 43);
		first(45, "Orc Raider", 46);
		first(47, "Orc Monk", 48);
		first(50, "Orc Shaman", 51, 52);
		first(54, "Scavenger", 55);
		first(56, "Artisan", 57);
		// Second classes and their third classes.
		second(2, "Gladiator", 88);
		second(3, "Warlord", 89);
		second(5, "Paladin", 90);
		second(6, "Dark Avenger", 91);
		second(8, "Treasure Hunter", 93);
		second(9, "Hawkeye", 92);
		second(12, "Sorcerer", 94);
		second(13, "Necromancer", 95);
		second(14, "Warlock", 96);
		second(16, "Bishop", 97);
		second(17, "Prophet", 98);
		second(20, "Temple Knight", 99);
		second(21, "Swordsinger", 100);
		second(23, "Plains Walker", 101);
		second(24, "Silver Ranger", 102);
		second(27, "Spellsinger", 103);
		second(28, "Elemental Summoner", 104);
		second(30, "Elder", 105);
		second(33, "Shillien Knight", 106);
		second(34, "Bladedancer", 107);
		second(36, "Abyss Walker", 108);
		second(37, "Phantom Ranger", 109);
		second(40, "Spellhowler", 110);
		second(41, "Phantom Summoner", 111);
		second(43, "Shillien Elder", 112);
		second(46, "Destroyer", 113);
		second(48, "Tyrant", 114);
		second(51, "Overlord", 115);
		second(52, "Warcryer", 116);
		second(55, "Bounty Hunter", 117);
		second(57, "Warsmith", 118);
		third(88, "Duelist");
		third(89, "Dreadnought");
		third(90, "Phoenix Knight");
		third(91, "Hell Knight");
		third(92, "Sagittarius");
		third(93, "Adventurer");
		third(94, "Archmage");
		third(95, "Soultaker");
		third(96, "Arcana Lord");
		third(97, "Cardinal");
		third(98, "Hierophant");
		third(99, "Eva's Templar");
		third(100, "Sword Muse");
		third(101, "Wind Rider");
		third(102, "Moonlight Sentinel");
		third(103, "Mystic Muse");
		third(104, "Elemental Master");
		third(105, "Eva's Saint");
		third(106, "Shillien Templar");
		third(107, "Spectral Dancer");
		third(108, "Ghost Hunter");
		third(109, "Ghost Sentinel");
		third(110, "Storm Screamer");
		third(111, "Spectral Master");
		third(112, "Shillien Saint");
		third(113, "Titan");
		third(114, "Grand Khavatari");
		third(115, "Dominator");
		third(116, "Doomcryer");
		third(117, "Fortune Seeker");
		third(118, "Maestro");
	}

	private ClassPath()
	{
	}

	private static void base(int id, String name, Integer... next)
	{
		add(id, name, 0, next);
	}

	private static void first(int id, String name, Integer... next)
	{
		add(id, name, 1, next);
	}

	private static void second(int id, String name, Integer... next)
	{
		add(id, name, 2, next);
	}

	private static void third(int id, String name)
	{
		add(id, name, 3);
	}

	private static void add(int id, String name, int tier, Integer... next)
	{
		NAMES.put(id, name);
		TIER.put(id, tier);
		NEXT.put(id, List.of(next));
	}

	/**
	 * @param classId a class
	 * @return 0 for a base class, 1 to 3 after each class change, -1 for an unknown class
	 */
	public static int tier(int classId)
	{
		return TIER.getOrDefault(classId, -1);
	}

	/**
	 * @param classId a class
	 * @return its name, or "class N" for an unknown one
	 */
	public static String name(int classId)
	{
		return NAMES.getOrDefault(classId, "class " + classId);
	}

	/**
	 * @param classId a class
	 * @param level a level
	 * @return whether a bot of this class and level is due its next class change
	 */
	public static boolean due(int classId, int level)
	{
		final int tier = tier(classId);
		return (tier >= 0) && (tier < CHANGE_LEVELS.length) && (level >= CHANGE_LEVELS[tier]) && !NEXT.get(classId).isEmpty();
	}

	/**
	 * The class this bot changes into next. Always the same for the same bot, like L2Solo's stable branch pick.
	 * @param classId its class
	 * @param botId its id
	 * @return the next class, or -1 when there is none
	 */
	public static int next(int classId, long botId)
	{
		final List<Integer> options = NEXT.getOrDefault(classId, List.of());
		if (options.isEmpty())
		{
			return -1;
		}
		return options.get(Math.floorMod(mix(botId * 31 + classId), options.size()));
	}

	/**
	 * The class a bot buys gear for: the last class of its path. Every class change is a stable pick per bot (see
	 * {@link #next}), so the whole path first, second and third class is settled from level 1, and a bot buys weapon and
	 * armor for where it is going from the start (a fighter bound for a dagger third class carries daggers at level 1).
	 * @param classId its class
	 * @param botId its id
	 * @return the class at the end of its path (its own class when no change is left)
	 */
	public static int gearClass(int classId, long botId)
	{
		int result = classId;
		for (int next = next(result, botId); next >= 0; next = next(result, botId))
		{
			result = next;
		}
		return result;
	}

	/**
	 * @param classId a class
	 * @return whether it is a knight line tank (all of them learn Sword Blunt Mastery, so a sword and a blunt weapon both work)
	 */
	public static boolean tank(int classId)
	{
		return TANKS.contains(classId);
	}

	/**
	 * The class master script that handles changing from this class (the masters also teach the class its skills). The
	 * third class change has no master of its own in the data, so the second class masters stand in for it.
	 * @param classId the class it changes from
	 * @param nextClassId the class it changes into
	 * @return the script name, as {@code zones.xml} lists it in each town
	 */
	public static String master(int classId, int nextClassId)
	{
		final int tier = tier(classId);
		final int line = (tier >= 2) ? parent(classId) : classId; // a third change goes to the masters of the second
		final boolean first = (tier == 0);
		switch (line)
		{
			case 0:
			case 18:
			case 1:
			case 4:
			case 7:
			case 19:
			case 22:
			{
				return first ? "ElfHumanFighterChange1" : "ElfHumanFighterChange2";
			}
			case 10:
			case 25:
			{
				return "ElfHumanWizardChange1"; // wizards and clerics take their first class from the same masters
			}
			case 11:
			case 26:
			{
				return "ElfHumanWizardChange2";
			}
			case 15:
			case 29:
			{
				return "ElfHumanClericChange2";
			}
			case 31:
			case 38:
			case 32:
			case 35:
			case 39:
			case 42:
			{
				return first ? "DarkElfChange1" : "DarkElfChange2";
			}
			case 44:
			case 49:
			case 45:
			case 47:
			case 50:
			{
				return first ? "OrcChange1" : "OrcChange2";
			}
			case 53:
			{
				return (nextClassId == 56) ? "DwarfBlacksmithChange1" : "DwarfWarehouseChange1";
			}
			case 54:
			{
				return "DwarfWarehouseChange2";
			}
			case 56:
			{
				return "DwarfBlacksmithChange2";
			}
			default:
			{
				return null;
			}
		}
	}

	/**
	 * The trainer that teaches a class its skills: the class masters of its line (first class masters for a base class,
	 * second class masters after that).
	 * @param classId its class
	 * @return the script name, as {@code zones.xml} lists it in each town, or null for an unknown class
	 */
	public static String trainer(int classId)
	{
		int line = classId;
		while (tier(line) >= 2)
		{
			line = parent(line);
		}
		if (tier(line) < 0)
		{
			return null;
		}
		final List<Integer> next = NEXT.get(line);
		return master(line, next.isEmpty() ? -1 : next.get(0));
	}

	/**
	 * @param classId a class
	 * @return the class it came from, or -1 for a base or unknown class
	 */
	public static int parent(int classId)
	{
		for (Map.Entry<Integer, List<Integer>> entry : NEXT.entrySet())
		{
			if (entry.getValue().contains(classId))
			{
				return entry.getKey();
			}
		}
		return -1;
	}

	/** Where a bot is in its class-change quest. */
	public enum Stage
	{
		/** Due a class change: heading to a class master to take the quest. */
		TAKE,
		/** Took the quest: hunting for it until {@link Quest#endAt()}. */
		HUNT,
		/** Done with the quest: heading back to a class master to change class. */
		RETURN
	}

	/**
	 * A class-change quest in progress, stored on the row as text.
	 * @param stage where it is
	 * @param target the class it will become
	 * @param endAt when the quest hunt is done (used in {@link Stage#HUNT})
	 */
	public record Quest(Stage stage, int target, long endAt)
	{
		/** @return the row text */
		public String encode()
		{
			return stage.name() + "|" + target + "|" + endAt;
		}

		/**
		 * @param text the row text
		 * @return the quest, or null for missing or damaged text
		 */
		public static Quest decode(String text)
		{
			if ((text == null) || text.isEmpty())
			{
				return null;
			}
			final String[] parts = text.split("\\|", -1);
			if (parts.length != 3)
			{
				return null;
			}
			try
			{
				return new Quest(Stage.valueOf(parts[0]), Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
			}
			catch (IllegalArgumentException e)
			{
				return null;
			}
		}

		/** @return whether the bot has business with a class master (to take or to finish the quest) */
		public boolean needsMaster()
		{
			return (stage == Stage.TAKE) || (stage == Stage.RETURN);
		}
	}

	private static final Set<Integer> TANKS = Set.of(4, 19, 32, 5, 6, 20, 33, 90, 91, 99, 106);

	private static int mix(long value)
	{
		long hash = value * 0x9E3779B97F4A7C15L;
		hash ^= (hash >>> 32);
		hash *= 0xBF58476D1CE4E5B9L;
		hash ^= (hash >>> 29);
		return (int) (hash & 0x7FFFFFFF);
	}
}
