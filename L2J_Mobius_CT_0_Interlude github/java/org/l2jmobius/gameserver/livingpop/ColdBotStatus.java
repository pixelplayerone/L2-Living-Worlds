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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntToLongFunction;

/**
 * Pure, dependency-free rendering of a monitoring snapshot for the Living Population.
 *
 * <p>The manager calls {@link #render(List, long)} on a timer and writes the result to the configured file; a monitor
 * view (a {@code tools/l2admin} tab, or a GM command's text) reads it. Keeping this a pure function of the bot list plus
 * a timestamp means the exact JSON shape is locked down by the standalone test, and the manager stays a thin writer.
 *
 * <p>This is a read-only observation surface. It reports what the cold rows say; it never changes them.
 */
public final class ColdBotStatus
{
	/** Gear grade names by {@link LivingSupplies#gradeFor(int)}. */
	private static final String[] GRADES =
	{
		"none",
		"D",
		"C",
		"B",
		"A",
		"S"
	};

	private ColdBotStatus()
	{
	}

	/**
	 * Renders the snapshot as a compact JSON object: {@code generatedAt}, {@code total}, {@code byRace},
	 * {@code byLevelBand}, and a {@code bots} array of per-bot briefs, each with its recent {@code decisions}
	 * (newest first).
	 * @param bots the current cold bots (may be empty; never null)
	 * @param directorTarget the population director's current target level, or 0 when none
	 * @param now the snapshot timestamp in epoch milliseconds
	 * @return a JSON document string
	 */
	public static String render(List<ColdBot> bots, int directorTarget, long now)
	{
		return render(bots, directorTarget, now, 0, Map.of(), null, 0);
	}

	/**
	 * Renders the snapshot with the gear view: each bot's gear level and grade (from its owned tier) and, for a hot bot,
	 * the items its live character is actually wearing.
	 * @param bots the current cold bots (may be empty; never null)
	 * @param directorTarget the population director's current target level, or 0 when none
	 * @param now the snapshot timestamp in epoch milliseconds
	 * @param gearTierLevelStep levels per gear tier (0 leaves the gear level at 0, the starter gear)
	 * @param equipment by bot id, the worn items as {slot, item name}; bots not in the map get none
	 * @param expToNext experience from a level to the next, for the progress percentage (null leaves it out)
	 * @param maxLevel the level cap, where the progress reads 100
	 * @return a JSON document string
	 */
	public static String render(List<ColdBot> bots, int directorTarget, long now, int gearTierLevelStep, Map<Long, List<String[]>> equipment, IntToLongFunction expToNext, int maxLevel)
	{
		final Map<String, Integer> byRace = new TreeMap<>();
		final Map<String, Integer> byBand = new TreeMap<>();
		for (ColdBot bot : bots)
		{
			byRace.merge(bot.getRace() == null ? "unknown" : bot.getRace(), 1, Integer::sum);
			byBand.merge(levelBand(bot.getLevel()), 1, Integer::sum);
		}

		final StringBuilder sb = new StringBuilder(256 + (bots.size() * 96));
		sb.append('{');
		sb.append("\"generatedAt\":").append(now).append(',');
		sb.append("\"directorTarget\":").append(directorTarget).append(',');
		sb.append("\"total\":").append(bots.size()).append(',');
		sb.append("\"byRace\":").append(countObject(byRace)).append(',');
		sb.append("\"byLevelBand\":").append(countObject(byBand)).append(',');
		sb.append("\"bots\":[");
		for (int index = 0; index < bots.size(); index++)
		{
			if (index > 0)
			{
				sb.append(',');
			}

			final ColdBot bot = bots.get(index);
			sb.append('{');
			sb.append("\"id\":").append(bot.getId()).append(',');
			sb.append("\"name\":").append(quote(bot.getName())).append(',');
			sb.append("\"race\":").append(quote(bot.getRace())).append(',');
			sb.append("\"classId\":").append(bot.getClassId()).append(',');
			sb.append("\"level\":").append(bot.getLevel()).append(',');
			sb.append("\"activity\":").append(quote(bot.getActivity())).append(',');
			sb.append("\"region\":").append(quote(bot.getRegion())).append(',');
			sb.append("\"phase\":").append(quote(bot.getPhase())).append(',');
			sb.append("\"hot\":").append(bot.isHotLock()).append(',');
			sb.append("\"goal\":").append(quote(bot.getGoal())).append(',');
			sb.append("\"adena\":").append(bot.getAdena()).append(',');
			sb.append("\"soulshots\":").append(bot.getSoulshots()).append(',');
			sb.append("\"potions\":").append(bot.getPotions()).append(',');
			sb.append("\"gearTier\":").append(bot.getGearTier()).append(',');
			sb.append("\"escapes\":").append(bot.getEscapes()).append(',');
			sb.append("\"loot\":").append(bot.getLoot()).append(',');
			sb.append("\"deaths\":").append(bot.getDeaths()).append(',');
			sb.append("\"kills\":").append(Math.round(bot.getKills())).append(',');
			sb.append("\"adenaEarned\":").append(bot.getAdenaEarned()).append(',');
			final int gearLevel = ((bot.getGearTier() <= 0) || (gearTierLevelStep <= 0)) ? 0 : Math.min(bot.getLevel(), bot.getGearTier() * gearTierLevelStep);
			sb.append("\"gearLevel\":").append(gearLevel).append(',');
			sb.append("\"gearGrade\":").append(quote(GRADES[LivingSupplies.gradeFor(gearLevel)])).append(',');
			final List<String[]> worn = equipment.get(bot.getId());
			if (worn != null)
			{
				sb.append("\"equipment\":[");
				for (int item = 0; item < worn.size(); item++)
				{
					if (item > 0)
					{
						sb.append(',');
					}
					sb.append("{\"slot\":").append(quote(worn.get(item)[0])).append(",\"item\":").append(quote(worn.get(item)[1])).append('}');
				}
				sb.append("],");
			}
			sb.append("\"sp\":").append(bot.getSp()).append(',');
			final Map<Integer, Integer> skills = SkillPlanner.decode(bot.getSkills());
			sb.append("\"skills\":").append((skills == null) ? -1 : skills.size()).append(',');
			sb.append("\"classQuest\":").append(quote((bot.getQuest() == null) ? null : (bot.getQuest().stage().name() + " " + ClassPath.name(bot.getQuest().target())))).append(',');
			sb.append("\"zone\":").append(quote(bot.getZone())).append(',');
			sb.append("\"town\":").append(quote(bot.getTown())).append(',');
			if (expToNext != null)
			{
				sb.append("\"levelProgress\":").append(levelProgress(bot.getLevel(), bot.getExpIntoLevel(), expToNext, maxLevel)).append(',');
			}
			sb.append("\"x\":").append(bot.getX()).append(',');
			sb.append("\"y\":").append(bot.getY()).append(',');
			sb.append("\"z\":").append(bot.getZ()).append(',');
			sb.append("\"decisions\":[");
			boolean firstDecision = true;
			for (DecisionLog.Entry entry : bot.getDecisions().newestFirst())
			{
				if (!firstDecision)
				{
					sb.append(',');
				}
				firstDecision = false;
				sb.append("{\"t\":").append(entry.at()).append(",\"m\":").append(quote(entry.text())).append('}');
			}
			sb.append(']');
			sb.append('}');
		}
		sb.append("]}");
		return sb.toString();
	}

	/**
	 * The ten-wide level band a level falls in, as an inclusive label such as {@code "1-9"} or {@code "40-49"}.
	 * @param level the bot level
	 * @return the band label
	 */
	public static String levelBand(int level)
	{
		if (level < 10)
		{
			return "1-9";
		}

		final int lower = (level / 10) * 10;
		return lower + "-" + (lower + 9);
	}

	private static String countObject(Map<String, Integer> counts)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append('{');
		boolean first = true;
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			if (!first)
			{
				sb.append(',');
			}
			first = false;
			sb.append(quote(entry.getKey())).append(':').append(entry.getValue().intValue());
		}
		sb.append('}');
		return sb.toString();
	}

	/**
	 * @param level the bot's level
	 * @param expIntoLevel its experience into that level
	 * @param expToNext experience from a level to the next
	 * @param maxLevel the level cap
	 * @return how far it is toward the next level, in whole percent (0 to 99; 100 at the cap)
	 */
	public static int levelProgress(int level, long expIntoLevel, IntToLongFunction expToNext, int maxLevel)
	{
		if ((maxLevel > 0) && (level >= maxLevel))
		{
			return 100;
		}
		final long required = Math.max(1L, expToNext.applyAsLong(level));
		return (int) Math.min(99L, (Math.max(0L, expIntoLevel) * 100L) / required);
	}

	/**
	 * Renders a string as a JSON string literal, escaping the characters JSON requires. A null becomes JSON
	 * {@code null}.
	 * @param value the value to quote
	 * @return the JSON token
	 */
	public static String quote(String value)
	{
		if (value == null)
		{
			return "null";
		}

		final StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int index = 0; index < value.length(); index++)
		{
			final char c = value.charAt(index);
			switch (c)
			{
				case '"':
				{
					sb.append("\\\"");
					break;
				}
				case '\\':
				{
					sb.append("\\\\");
					break;
				}
				case '\n':
				{
					sb.append("\\n");
					break;
				}
				case '\r':
				{
					sb.append("\\r");
					break;
				}
				case '\t':
				{
					sb.append("\\t");
					break;
				}
				default:
				{
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
					break;
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
