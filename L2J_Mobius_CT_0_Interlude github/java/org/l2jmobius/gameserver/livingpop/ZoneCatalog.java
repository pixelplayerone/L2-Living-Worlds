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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The travel catalog the Living Population moves through: towns (Scroll of Escape arrival point, gatekeeper, grocer) and
 * named hunting zones (level range, gatekeeper routes with their fees, and real monster spawn points to hunt at). It is
 * read from the module's generated {@code data/zones.xml} (see {@code tools/build_living_zones.py}), so every coordinate
 * and fee comes from the server's own data. Pure and immutable once loaded.
 */
public final class ZoneCatalog
{
	/** A point on the map. */
	public record Point(int x, int y, int z)
	{
		/**
		 * @param other another point
		 * @return the planar (x/y) distance to it
		 */
		public double distance(Point other)
		{
			return Math.hypot((double) x - other.x, (double) y - other.y);
		}
	}

	/**
	 * A town bots can travel to.
	 * @param name the town name
	 * @param arrival where a Scroll of Escape lands
	 * @param gatekeeper the gatekeeper's position, or null
	 * @param grocer the grocer's position (sells potions, soulshots and scrolls), or null when the town has none
	 * @param routes gatekeeper fees to other towns, by town name
	 * @param masters the class masters here (who change classes and teach skills), by their class-change script name
	 */
	public record Town(String name, Point arrival, Point gatekeeper, Point grocer, Map<String, Long> routes, Map<String, Point> masters)
	{
		/**
		 * @param script a class-change script name ({@link ClassPath#master})
		 * @return where that master stands here, or null when this town has none
		 */
		public Point master(String script)
		{
			return (script == null) ? null : masters.get(script);
		}

		/**
		 * @param town another town's name
		 * @return the gatekeeper fee to it, or -1 when this town's gatekeeper does not go there
		 */
		public long feeTo(String town)
		{
			final Long fee = routes.get(town);
			return (fee == null) ? -1L : fee.longValue();
		}

		/** @return whether bots can shop here */
		public boolean hasGrocer()
		{
			return grocer != null;
		}
	}

	/**
	 * A gatekeeper route into a zone.
	 * @param town the town whose gatekeeper offers it
	 * @param arrival where the teleport lands
	 * @param fee the adena fee
	 */
	public record Teleport(String town, Point arrival, long fee)
	{
	}

	/**
	 * A monster that lives in a zone, with how many of it spawn there (its share of a bot's kills).
	 * @param npcId the monster's npc id
	 * @param count how many spawn in the zone
	 */
	public record Monster(int npcId, int count)
	{
	}

	/**
	 * A named hunting zone.
	 * @param name the zone name
	 * @param minLevel the lowest level it suits
	 * @param maxLevel the highest level it suits
	 * @param starterRace the race whose newbie grounds this is, or null for a normal zone
	 * @param teleports the gatekeeper routes into it (empty for newbie grounds, which are reached on foot)
	 * @param spots real monster spawn points to hunt at (never empty)
	 * @param monsters the monsters living there, whose real drop lists pay a cold bot (empty in an older catalog)
	 */
	public record Zone(String name, int minLevel, int maxLevel, String starterRace, List<Teleport> teleports, List<Point> spots, List<Monster> monsters)
	{
		/**
		 * @param level a bot level
		 * @return whether the level is inside this zone's range
		 */
		public boolean fits(int level)
		{
			return (level >= minLevel) && (level <= maxLevel);
		}

		/** @return whether this is a race's newbie grounds */
		public boolean isStarter()
		{
			return starterRace != null;
		}

		/**
		 * @param town a town name
		 * @return the route from that town's gatekeeper, or null when it offers none
		 */
		public Teleport teleportFrom(String town)
		{
			for (Teleport teleport : teleports)
			{
				if (teleport.town().equals(town))
				{
					return teleport;
				}
			}
			return null;
		}

		/** @return the zone's center (the first spot) */
		public Point center()
		{
			return spots.get(0);
		}
	}

	private final List<Town> _towns;
	private final List<Zone> _zones;

	public ZoneCatalog(List<Town> towns, List<Zone> zones)
	{
		_towns = Collections.unmodifiableList(new ArrayList<>(towns));
		_zones = Collections.unmodifiableList(new ArrayList<>(zones));
	}

	/** @return an empty catalog (travel is then inert) */
	public static ZoneCatalog empty()
	{
		return new ZoneCatalog(List.of(), List.of());
	}

	public List<Town> towns()
	{
		return _towns;
	}

	public List<Zone> zones()
	{
		return _zones;
	}

	/** @return whether the catalog has at least one shopping town and one zone, the minimum travel needs */
	public boolean isUsable()
	{
		return !_zones.isEmpty() && _towns.stream().anyMatch(Town::hasGrocer);
	}

	/**
	 * @param name a zone name
	 * @return the zone, or null
	 */
	public Zone zone(String name)
	{
		if (name != null)
		{
			for (Zone zone : _zones)
			{
				if (zone.name().equals(name))
				{
					return zone;
				}
			}
		}
		return null;
	}

	/**
	 * @param name a town name
	 * @return the town, or null
	 */
	public Town town(String name)
	{
		if (name != null)
		{
			for (Town town : _towns)
			{
				if (town.name().equals(name))
				{
					return town;
				}
			}
		}
		return null;
	}

	/**
	 * The nearest town with a grocer, measured to its arrival point.
	 * @param from where the bot is
	 * @return the town, or null when the catalog has none
	 */
	public Town nearestShoppingTown(Point from)
	{
		Town best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Town town : _towns)
		{
			if (!town.hasGrocer())
			{
				continue;
			}
			final double distance = town.arrival().distance(from);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = town;
			}
		}
		return best;
	}

	/**
	 * Where a bot in a town goes to see a class master: this town when it has one, otherwise the town with one that
	 * this town's gatekeeper reaches for the lowest fee.
	 * @param script the class master's script ({@link ClassPath#master})
	 * @param from the town the bot is in
	 * @return that town, or null when no master is here or one gatekeeper hop away
	 */
	public Town masterTown(String script, Town from)
	{
		if ((script == null) || (from == null))
		{
			return null;
		}
		if (from.master(script) != null)
		{
			return from;
		}
		Town best = null;
		long bestFee = Long.MAX_VALUE;
		for (Town town : _towns)
		{
			final long fee = from.feeTo(town.name());
			if ((town.master(script) != null) && (fee >= 0) && (fee < bestFee))
			{
				bestFee = fee;
				best = town;
			}
		}
		return best;
	}

	/**
	 * The zone whose nearest spot is closest to a point, used to recognise where a bot is standing (for example after a
	 * hot bot moved on its own).
	 * @param at the point
	 * @param maxDistance how far the nearest spot may be for the point to count as inside the zone
	 * @return the zone, or null when none is that close
	 */
	public Zone zoneAt(Point at, double maxDistance)
	{
		Zone best = null;
		double bestDistance = maxDistance;
		for (Zone zone : _zones)
		{
			for (Point spot : zone.spots())
			{
				final double distance = spot.distance(at);
				if (distance <= bestDistance)
				{
					bestDistance = distance;
					best = zone;
				}
			}
		}
		return best;
	}

	/**
	 * Loads the catalog from a file. A missing or unreadable file gives an empty catalog rather than failing the module.
	 * @param file the zones file
	 * @return the catalog
	 * @throws Exception when the file exists but cannot be parsed
	 */
	public static ZoneCatalog load(Path file) throws Exception
	{
		if ((file == null) || !Files.isRegularFile(file))
		{
			return empty();
		}
		try (InputStream in = Files.newInputStream(file))
		{
			return parse(in);
		}
	}

	/**
	 * Parses catalog XML text (used by tests).
	 * @param xml the XML
	 * @return the catalog
	 * @throws Exception when the XML cannot be parsed
	 */
	public static ZoneCatalog parse(String xml) throws Exception
	{
		return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	private static ZoneCatalog parse(InputStream in) throws Exception
	{
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		final DocumentBuilder builder = factory.newDocumentBuilder();
		final Document document = builder.parse(in);
		final List<Town> towns = new ArrayList<>();
		final List<Zone> zones = new ArrayList<>();
		final NodeList children = document.getDocumentElement().getChildNodes();
		for (int i = 0; i < children.getLength(); i++)
		{
			final Node node = children.item(i);
			if (!(node instanceof Element element))
			{
				continue;
			}
			if ("town".equals(element.getTagName()))
			{
				Point gatekeeper = null;
				Point grocer = null;
				final Map<String, Long> routes = new LinkedHashMap<>();
				final Map<String, Point> masters = new LinkedHashMap<>();
				for (Element child : elements(element))
				{
					if ("master".equals(child.getTagName()))
					{
						masters.putIfAbsent(child.getAttribute("script"), point(child));
						continue;
					}
					if ("gatekeeper".equals(child.getTagName()) && (gatekeeper == null))
					{
						gatekeeper = point(child);
					}
					else if ("grocer".equals(child.getTagName()) && (grocer == null))
					{
						grocer = point(child);
					}
					else if ("route".equals(child.getTagName()))
					{
						routes.put(child.getAttribute("town"), Long.parseLong(child.getAttribute("fee")));
					}
				}
				towns.add(new Town(element.getAttribute("name"), point(element), gatekeeper, grocer, Map.copyOf(routes), Map.copyOf(masters)));
			}
			else if ("zone".equals(element.getTagName()))
			{
				final List<Teleport> teleports = new ArrayList<>();
				final List<Point> spots = new ArrayList<>();
				final List<Monster> monsters = new ArrayList<>();
				for (Element child : elements(element))
				{
					if ("teleport".equals(child.getTagName()))
					{
						teleports.add(new Teleport(child.getAttribute("town"), point(child), Long.parseLong(child.getAttribute("fee"))));
					}
					else if ("spot".equals(child.getTagName()))
					{
						spots.add(point(child));
					}
					else if ("monster".equals(child.getTagName()))
					{
						monsters.add(new Monster(Integer.parseInt(child.getAttribute("npcId")), Math.max(1, Integer.parseInt(child.getAttribute("count")))));
					}
				}
				if (spots.isEmpty())
				{
					continue; // a zone with nowhere to stand is unusable
				}
				final String race = element.getAttribute("starterRace");
				zones.add(new Zone(element.getAttribute("name"), Integer.parseInt(element.getAttribute("minLevel")), Integer.parseInt(element.getAttribute("maxLevel")), race.isEmpty() ? null : race, List.copyOf(teleports), List.copyOf(spots), List.copyOf(monsters)));
			}
		}
		return new ZoneCatalog(towns, zones);
	}

	private static List<Element> elements(Element parent)
	{
		final List<Element> out = new ArrayList<>();
		final NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++)
		{
			if (children.item(i) instanceof Element element)
			{
				out.add(element);
			}
		}
		return out;
	}

	private static Point point(Element element)
	{
		return new Point(Integer.parseInt(element.getAttribute("x")), Integer.parseInt(element.getAttribute("y")), Integer.parseInt(element.getAttribute("z")));
	}
}
