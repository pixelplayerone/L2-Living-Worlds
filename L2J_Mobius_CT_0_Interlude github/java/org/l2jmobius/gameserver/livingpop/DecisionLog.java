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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.modules.Json;

/**
 * A bot's recent decisions, in plain words, so an operator can see why it does what it does. Each entry is a dated line
 * such as "Restock: potions 80 below 100. Bought 500 for 30,000 adena". It keeps only the newest {@link #CAPACITY}
 * entries and is persisted in the row's {@code stats_json} column, so it survives restarts.
 *
 * <p><b>Keyed entries.</b> Some decisions repeat every resolver tick while nothing changes (for example "potions low but
 * cannot afford them yet"). Those carry a key. A keyed entry is logged once when its situation starts and not again until
 * the situation has ended ({@link #retainActiveKeys(Collection)} was called without that key) and starts anew. Unkeyed
 * entries (level ups, purchases, handoffs) are always logged.
 *
 * <p>Thread-safe: the resolver thread and the game thread (handoff capture) may both add entries.
 */
public final class DecisionLog
{
	/** How many entries a bot keeps. */
	public static final int CAPACITY = 20;

	private static final String DECISIONS = "decisions";

	private final ArrayDeque<Entry> _entries = new ArrayDeque<>(); // oldest first
	private final Set<String> _activeKeys = new HashSet<>();

	/**
	 * One logged decision.
	 * @param at the epoch-ms time it was made
	 * @param text the decision in plain words
	 */
	public record Entry(long at, String text)
	{
	}

	/**
	 * A decision reported by a pure layer (such as {@link ColdEconomy}) for the caller to log.
	 * @param key a stable key for a repeating situation, or null for a one-off decision that is always logged
	 * @param text the decision in plain words
	 */
	public record Event(String key, String text)
	{
	}

	/**
	 * Logs a decision.
	 * @param at the epoch-ms time
	 * @param key a stable key for a repeating situation (logged only when it starts), or null to always log
	 * @param text the decision in plain words; blank text is ignored
	 */
	public synchronized void add(long at, String key, String text)
	{
		if ((text == null) || text.isBlank())
		{
			return;
		}
		if (key != null)
		{
			if (!_activeKeys.add(key))
			{
				return; // the same situation is still ongoing; it was logged when it started
			}
		}
		_entries.addLast(new Entry(at, text));
		while (_entries.size() > CAPACITY)
		{
			_entries.removeFirst();
		}
	}

	/**
	 * Logs every event of a batch at the same time, then ends any keyed situation that was not reported in it.
	 * @param at the epoch-ms time
	 * @param events the events reported this tick (may be empty)
	 */
	public synchronized void addTick(long at, List<Event> events)
	{
		final Set<String> reported = new HashSet<>();
		for (Event event : events)
		{
			add(at, event.key(), event.text());
			if (event.key() != null)
			{
				reported.add(event.key());
			}
		}
		retainActiveKeys(reported);
	}

	/**
	 * Ends every keyed situation that is not in the given set, so it is logged again if it comes back.
	 * @param stillActive the keys reported as still ongoing
	 */
	public synchronized void retainActiveKeys(Collection<String> stillActive)
	{
		_activeKeys.retainAll(stillActive);
	}

	/**
	 * @return a copy of the entries, newest first
	 */
	public synchronized List<Entry> newestFirst()
	{
		final List<Entry> out = new ArrayList<>(_entries.size());
		final Iterator<Entry> it = _entries.descendingIterator();
		while (it.hasNext())
		{
			out.add(it.next());
		}
		return out;
	}

	/**
	 * @return how many entries are held
	 */
	public synchronized int size()
	{
		return _entries.size();
	}

	/**
	 * Renders the log as the row's {@code stats_json} value: {@code {"decisions":[{"t":<ms>,"m":"<text>"},...]}}, oldest
	 * first.
	 * @return the JSON text
	 */
	public synchronized String toJson()
	{
		final StringBuilder sb = new StringBuilder(64 + (_entries.size() * 80));
		sb.append("{\"").append(DECISIONS).append("\":[");
		boolean first = true;
		for (Entry entry : _entries)
		{
			if (!first)
			{
				sb.append(',');
			}
			first = false;
			sb.append("{\"t\":").append(entry.at()).append(",\"m\":").append(ColdBotStatus.quote(entry.text())).append('}');
		}
		sb.append("]}");
		return sb.toString();
	}

	/**
	 * Replaces the entries with those stored in a {@code stats_json} value. Tolerant: null, blank, malformed, or
	 * unexpected JSON leaves the log empty rather than failing the load of the bot.
	 * @param json the stored JSON text
	 */
	public synchronized void loadJson(String json)
	{
		_entries.clear();
		_activeKeys.clear();
		if ((json == null) || json.isBlank())
		{
			return;
		}

		try
		{
			final Object root = Json.parse(json);
			if (!(root instanceof Map<?, ?> map) || !(map.get(DECISIONS) instanceof List<?> list))
			{
				return;
			}
			for (Object item : list)
			{
				if ((item instanceof Map<?, ?> entry) && (entry.get("t") instanceof Number at) && (entry.get("m") instanceof String text) && !text.isBlank())
				{
					_entries.addLast(new Entry(at.longValue(), text));
				}
			}
			while (_entries.size() > CAPACITY)
			{
				_entries.removeFirst();
			}
		}
		catch (Json.JsonException e)
		{
			_entries.clear(); // unreadable history is dropped; the bot itself still loads
		}
	}

	/**
	 * Formats a count with thousands separators, independent of the server locale, for log text.
	 * @param value the number
	 * @return the formatted number, for example {@code 30,000}
	 */
	public static String num(long value)
	{
		return String.format(Locale.US, "%,d", value);
	}
}
