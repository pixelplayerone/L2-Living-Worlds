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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A living bot's gear, one item per slot, the way a player gears up: piece by piece, from what its town's shops sell,
 * from other players, and from what drops while it hunts. A piece is worn when its class uses it (weapon type, armor
 * type, shield), its grade is allowed at the bot's level (D from 20, C from 40, B from 52, A from 61, S from 76) and it is
 * better than what it wears: P.Atk for a fighter's weapon, M.Atk for a caster's, P.Def for armor, shield defense for a
 * shield, M.Def for jewelry. A piece its class does not use counts as nothing, so after a class change any fitting piece replaces it. Pure,
 * so it is tested without a server.
 */
public final class LivingGear
{
	/** How much better a piece must be to be worth a trip to town, in percent of what it replaces. */
	public static final int GOOD_UPGRADE_PERCENT = 15;

	/** The lowest level of each grade (no grade, D, C, B, A, S). */
	private static final int[] GRADE_LEVEL =
	{
		0,
		20,
		40,
		52,
		61,
		76
	};

	private static final String[] GRADE_NAME =
	{
		"no grade",
		"D",
		"C",
		"B",
		"A",
		"S"
	};

	private LivingGear()
	{
	}

	/** What kind of piece an item is. */
	public enum Kind
	{
		WEAPON,
		SHIELD,
		CHEST,
		LEGS,
		HEAD,
		GLOVES,
		FEET,
		NECK,
		EAR,
		RING
	}

	/** The slots a bot wears gear in. */
	public enum Slot
	{
		WEAPON(Kind.WEAPON, "weapon"),
		SHIELD(Kind.SHIELD, "shield"),
		CHEST(Kind.CHEST, "chest"),
		LEGS(Kind.LEGS, "legs"),
		HEAD(Kind.HEAD, "head"),
		GLOVES(Kind.GLOVES, "gloves"),
		FEET(Kind.FEET, "feet"),
		NECK(Kind.NECK, "necklace"),
		EAR1(Kind.EAR, "earring"),
		EAR2(Kind.EAR, "earring"),
		RING1(Kind.RING, "ring"),
		RING2(Kind.RING, "ring");

		private final Kind _kind;
		private final String _label;

		Slot(Kind kind, String label)
		{
			_kind = kind;
			_label = label;
		}

		/** @return the kind of piece this slot takes */
		public Kind kind()
		{
			return _kind;
		}

		/** @return the slot in plain words */
		public String label()
		{
			return _label;
		}

		/** @return the slot's key in the row text */
		public String key()
		{
			return name().toLowerCase();
		}
	}

	/**
	 * The order a bot shops in: weapon, then body armor from the chest down, then the shield, then jewelry.
	 */
	public static final List<Slot> SHOP_ORDER = List.of(Slot.WEAPON, Slot.CHEST, Slot.LEGS, Slot.HEAD, Slot.GLOVES, Slot.FEET, Slot.SHIELD, Slot.NECK, Slot.EAR1, Slot.EAR2, Slot.RING1, Slot.RING2);

	/**
	 * One piece of gear, as the item data describes it.
	 * @param itemId the item
	 * @param name its name
	 * @param kind what kind of piece it is
	 * @param grade 0 no grade, 1 D, 2 C, 3 B, 4 A, 5 S
	 * @param type its weapon type (SWORD, BOW, ...) or armor type (HEAVY, LIGHT, MAGIC for a robe, NONE, SHIELD)
	 * @param magic whether it is a caster's weapon
	 * @param twoHanded whether a weapon takes both hands (no shield with it)
	 * @param fullBody whether a chest piece covers the legs too
	 * @param pAtk its P.Atk
	 * @param mAtk its M.Atk
	 * @param pDef its P.Def (a shield's shield defense)
	 * @param mDef its M.Def
	 * @param sellPrice what a shop pays for it
	 */
	public record Piece(int itemId, String name, Kind kind, int grade, String type, boolean magic, boolean twoHanded, boolean fullBody, int pAtk, int mAtk, int pDef, int mDef, long sellPrice)
	{
		/** @return whether it is a shadow item: a copy of a normal weapon that cannot be sold and wears out with use */
		public boolean shadow()
		{
			return name.startsWith("Shadow Item:");
		}
	}

	/** From this level a bot buys shadow C and B grade weapons instead of the normal ones, and sells the C and B weapons that drop. */
	public static final int SHADOW_FROM_LEVEL = 40;

	/**
	 * Whether a shadow weapon that has been worn for a while has run out. The chance is the share of its life used since
	 * the last visit, rolled the same way for the same bot and minute.
	 * @param botId the bot
	 * @param now the time
	 * @param wornMs how long it has been worn since the bot last shopped
	 * @param minutes how long the weapon lasts, in minutes
	 * @return whether it has run out
	 */
	public static boolean wornOut(long botId, long now, long wornMs, int minutes)
	{
		if ((minutes <= 0) || (wornMs <= 0))
		{
			return false;
		}
		final long wornMinutes = wornMs / 60_000L;
		final long roll = Math.floorMod(((botId * 31) + (now / 60_000L)) * 0x9E3779B97F4A7C15L >>> 20, (long) minutes);
		return roll < wornMinutes;
	}

	/**
	 * What a class wears.
	 * @param armor the armor type of its chest and legs (HEAVY, LIGHT or MAGIC)
	 * @param weapons the weapon types it fights with; empty means any
	 * @param mage whether it fights with a caster's weapon (scored by M.Atk) rather than a physical one (P.Atk)
	 * @param shield whether it carries a shield next to a one-handed weapon
	 * @param hands 1 for one-handed weapons only, 2 for two-handed only, 0 for either
	 */
	public record Fit(String armor, Set<String> weapons, boolean mage, boolean shield, int hands)
	{
	}

	/** Item facts the gear rules need. */
	public interface Items
	{
		/**
		 * @param itemId an item
		 * @return the piece, or null when the item is not gear a player wears
		 */
		Piece piece(int itemId);
	}

	/**
	 * A piece for sale.
	 * @param piece the piece
	 * @param price what it costs
	 * @param shop true when a shop in the bot's town sells it, false when it is bought from another player (see
	 *            {@code GearCatalog.tradePrice})
	 */
	public record Offer(Piece piece, long price, boolean shop)
	{
	}

	/**
	 * A piece put on.
	 * @param slot the slot
	 * @param piece the piece
	 * @param price what it cost (0 for a drop)
	 * @param shop whether a shop sold it (false: traded, or a drop)
	 * @param gain how much better it is
	 * @param good whether the gain is big enough to be worth a trip to town (see {@link #good})
	 * @param removed the items it replaced (sold or taken off), may be empty
	 */
	public record Change(Slot slot, Piece piece, long price, boolean shop, int gain, boolean good, List<Integer> removed)
	{
	}

	/**
	 * @param level a bot level
	 * @return the highest grade it may wear (0 no grade to 5 S)
	 */
	public static int allowedGrade(int level)
	{
		return LivingSupplies.gradeFor(level);
	}

	/**
	 * @param grade a grade (0 to 5)
	 * @return its name
	 */
	public static String gradeName(int grade)
	{
		return GRADE_NAME[Math.max(0, Math.min(GRADE_NAME.length - 1, grade))];
	}

	/**
	 * The gear tier that matches a weapon grade, so the tier-based parts of the simulation (the soulshot grade, the
	 * monitor's gear level) follow the weapon a bot really holds.
	 * @param grade the weapon's grade
	 * @param step levels per gear tier
	 * @return the tier
	 */
	public static int tierFor(int grade, int step)
	{
		if ((step <= 0) || (grade <= 0))
		{
			return 0;
		}
		final int level = GRADE_LEVEL[Math.min(GRADE_LEVEL.length - 1, grade)];
		return (level + step - 1) / step;
	}

	/**
	 * @param piece a piece
	 * @param fit what the class wears
	 * @return whether the class uses it
	 */
	public static boolean fits(Piece piece, Fit fit)
	{
		switch (piece.kind())
		{
			case WEAPON:
			{
				if (fit.mage() != piece.magic())
				{
					return false;
				}
				if (!fit.weapons().isEmpty() && !fit.weapons().contains(piece.type()))
				{
					return false;
				}
				return (fit.hands() == 0) || ((fit.hands() == 2) == piece.twoHanded());
			}
			case SHIELD:
			{
				return fit.shield();
			}
			case CHEST:
			case LEGS:
			{
				return fit.armor().equals(piece.type());
			}
			default:
			{
				return true;
			}
		}
	}

	/**
	 * @param piece a piece
	 * @param fit what the class wears
	 * @return how good it is in its slot: P.Atk or M.Atk for a weapon, P.Def for armor, shield defense for a shield (kept
	 *         in {@link Piece#pDef}), M.Def for jewelry
	 */
	public static int score(Piece piece, Fit fit)
	{
		switch (piece.kind())
		{
			case WEAPON:
			{
				return fit.mage() ? piece.mAtk() : piece.pAtk();
			}
			case NECK:
			case EAR:
			case RING:
			{
				return piece.mDef();
			}
			default:
			{
				return piece.pDef();
			}
		}
	}

	/**
	 * The slot a piece would go into if the bot put it on now, or null when it would not: its class does not use it, its
	 * grade is too high for the bot's level, it does not fit next to what the bot wears (a shield with a two-handed
	 * weapon, legs under a full-body armor) or it is not better. An earring or a ring replaces the weaker of the two.
	 * @param gear what it wears
	 * @param piece the piece
	 * @param fit what the class wears
	 * @param level the bot's level
	 * @param items item facts
	 * @return the slot, or null
	 */
	public static Slot target(Map<Slot, Integer> gear, Piece piece, Fit fit, int level, Items items)
	{
		if ((piece.grade() > allowedGrade(level)) || !fits(piece, fit))
		{
			return null;
		}
		if ((piece.kind() == Kind.WEAPON) && !piece.shadow() && (level >= SHADOW_FROM_LEVEL) && ((piece.grade() == 2) || (piece.grade() == 3)))
		{
			return null; // a bot of this level buys shadow weapons at C and B grade and sells the drops
		}
		final Slot slot = slotFor(gear, piece, fit, items);
		if (slot == null)
		{
			return null;
		}
		return (gain(gear, slot, piece, fit, items) > 0) ? slot : null;
	}

	/**
	 * How much better a piece is than what it would replace. A chest piece is weighed with the legs: a full-body armor
	 * against the chest and legs it replaces, a plain chest with the legs kept.
	 * @return the gain (not positive when it is no better)
	 */
	public static int gain(Map<Slot, Integer> gear, Slot slot, Piece piece, Fit fit, Items items)
	{
		if (slot == Slot.CHEST)
		{
			final int legs = worth(gear.get(Slot.LEGS), fit, items);
			final int before = worth(gear.get(Slot.CHEST), fit, items) + legs;
			final int after = score(piece, fit) + (piece.fullBody() ? 0 : legs);
			return after - before;
		}
		return score(piece, fit) - worth(gear.get(slot), fit, items);
	}

	/**
	 * @return whether an upgrade is big enough to be worth a trip to town: it fills an empty (or unusable) slot or is at
	 *         least {@link #GOOD_UPGRADE_PERCENT} percent better than what it replaces
	 */
	public static boolean good(Map<Slot, Integer> gear, Slot slot, Piece piece, Fit fit, Items items)
	{
		final int before = (slot == Slot.CHEST) ? (worth(gear.get(Slot.CHEST), fit, items) + worth(gear.get(Slot.LEGS), fit, items)) : worth(gear.get(slot), fit, items);
		final int gain = gain(gear, slot, piece, fit, items);
		return (gain > 0) && ((before <= 0) || ((gain * 100L) >= (before * (long) GOOD_UPGRADE_PERCENT)));
	}

	/**
	 * Shops for gear, slot by slot in {@link #SHOP_ORDER}: for each slot the best upgrade its class uses, its level allows
	 * and it can afford, a shop's offer before another player's when they are equally good. Only an upgrade that is
	 * {@link #good} is bought, so a bot never pays for a piece that is barely better and sells the old one at half price.
	 * What it replaces is taken off (the caller sells it).
	 * @param gear what it wears; updated in place
	 * @param fit what the class wears
	 * @param level the bot's level
	 * @param budget the adena it may spend
	 * @param offers what is for sale
	 * @param items item facts
	 * @return what it bought, in order
	 */
	public static List<Change> shop(Map<Slot, Integer> gear, Fit fit, int level, long budget, List<Offer> offers, Items items)
	{
		return shop(gear, fit, level, budget, offers, items, Set.of());
	}

	/**
	 * As {@link #shop(Map, Fit, int, long, List, Items)}, leaving some slots alone.
	 * @param locked slots it does not shop for (the pieces it bought earlier in the same visit, so it never buys a piece
	 *            and sells it again a moment later)
	 * @return what it bought, in order
	 */
	public static List<Change> shop(Map<Slot, Integer> gear, Fit fit, int level, long budget, List<Offer> offers, Items items, Set<Slot> locked)
	{
		final List<Change> bought = new ArrayList<>();
		long left = Math.max(0L, budget);
		for (Slot slot : SHOP_ORDER)
		{
			if (locked.contains(slot))
			{
				continue;
			}
			Offer best = null;
			int bestGain = 0;
			for (Offer offer : offers)
			{
				if ((offer.piece().kind() != slot.kind()) || (offer.price() <= 0) || (offer.price() > left) || (target(gear, offer.piece(), fit, level, items) != slot) || !good(gear, slot, offer.piece(), fit, items))
				{
					continue;
				}
				final int gain = gain(gear, slot, offer.piece(), fit, items);
				if ((best == null) || (gain > bestGain) || ((gain == bestGain) && better(offer, best)))
				{
					best = offer;
					bestGain = gain;
				}
			}
			if (best != null)
			{
				left -= best.price();
				bought.add(new Change(slot, best.piece(), best.price(), best.shop(), bestGain, true, put(gear, slot, best.piece(), items)));
			}
		}
		return bought;
	}

	/**
	 * The upgrade a bot wants most but cannot afford yet: the first slot in {@link #SHOP_ORDER} with a good upgrade on
	 * sale, at its cheapest good offer. Used to say what it is saving up for.
	 * @return the offer and its slot, or null when nothing good is for sale
	 */
	public static Change wish(Map<Slot, Integer> gear, Fit fit, int level, List<Offer> offers, Items items)
	{
		for (Slot slot : SHOP_ORDER)
		{
			Offer cheapest = null;
			for (Offer offer : offers)
			{
				if ((offer.piece().kind() != slot.kind()) || (offer.price() <= 0) || (target(gear, offer.piece(), fit, level, items) != slot) || !good(gear, slot, offer.piece(), fit, items))
				{
					continue;
				}
				if ((cheapest == null) || (offer.price() < cheapest.price()) || ((offer.price() == cheapest.price()) && better(offer, cheapest)))
				{
					cheapest = offer;
				}
			}
			if (cheapest != null)
			{
				return new Change(slot, cheapest.piece(), cheapest.price(), cheapest.shop(), gain(gear, slot, cheapest.piece(), fit, items), true, List.of());
			}
		}
		return null;
	}

	/**
	 * Puts on the drops that are better than what it wears, best first. The rest are left for the caller to sell.
	 * @param gear what it wears; updated in place
	 * @param drops the pieces that dropped
	 * @param fit what the class wears
	 * @param level the bot's level
	 * @param items item facts
	 * @return what it put on, each with what it took off
	 */
	public static List<Change> wearDrops(Map<Slot, Integer> gear, List<Piece> drops, Fit fit, int level, Items items)
	{
		final List<Change> worn = new ArrayList<>();
		final List<Piece> left = new ArrayList<>(drops);
		boolean progress = true;
		while (progress && !left.isEmpty())
		{
			progress = false;
			Piece best = null;
			Slot bestSlot = null;
			int bestGain = 0;
			for (Piece piece : left)
			{
				final Slot slot = target(gear, piece, fit, level, items);
				if (slot == null)
				{
					continue;
				}
				final int gain = gain(gear, slot, piece, fit, items);
				if ((best == null) || (gain > bestGain))
				{
					best = piece;
					bestSlot = slot;
					bestGain = gain;
				}
			}
			if (best != null)
			{
				left.remove(best);
				final boolean good = good(gear, bestSlot, best, fit, items);
				worn.add(new Change(bestSlot, best, 0L, false, bestGain, good, put(gear, bestSlot, best, items)));
				progress = true;
			}
		}
		return worn;
	}

	/**
	 * Rolls what drops over a stretch of hunting: each item drops at least once with the chance that it drops in that
	 * many kills. One of each is plenty for gear.
	 * @param chances each item's chance per kill (0 to 1)
	 * @param kills how many kills
	 * @param random the dice
	 * @return the items that dropped
	 */
	public static List<Integer> roll(Map<Integer, Double> chances, double kills, Random random)
	{
		final List<Integer> dropped = new ArrayList<>();
		if (kills <= 0)
		{
			return dropped;
		}
		for (Map.Entry<Integer, Double> entry : chances.entrySet())
		{
			final double perKill = Math.max(0.0, Math.min(1.0, entry.getValue()));
			if ((perKill > 0) && (random.nextDouble() < (1.0 - Math.pow(1.0 - perKill, kills))))
			{
				dropped.add(entry.getKey());
			}
		}
		return dropped;
	}

	/**
	 * How far its gear lags its level: the grades its weapon and its chest armor are below the grade its level allows,
	 * whichever is further behind.
	 * @param gear what it wears
	 * @param level its level
	 * @param items item facts
	 * @return 0 when it wears gear of its grade
	 */
	public static int behind(Map<Slot, Integer> gear, int level, Items items)
	{
		final int allowed = allowedGrade(level);
		return Math.max(0, allowed - Math.min(grade(gear.get(Slot.WEAPON), items), grade(gear.get(Slot.CHEST), items)));
	}

	/**
	 * @param gear what it wears
	 * @param items item facts
	 * @return its weapon's grade (0 without a weapon)
	 */
	public static int weaponGrade(Map<Slot, Integer> gear, Items items)
	{
		return grade(gear.get(Slot.WEAPON), items);
	}

	/**
	 * @param gear what it wears
	 * @return the items in slot order (for putting them on)
	 */
	public static List<Integer> items(Map<Slot, Integer> gear)
	{
		final List<Integer> ids = new ArrayList<>();
		for (Slot slot : Slot.values())
		{
			final Integer id = gear.get(slot);
			if ((id != null) && (id > 0))
			{
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * @param gear what it wears
	 * @return the row text, "slot:item" pairs separated by commas
	 */
	public static String encode(Map<Slot, Integer> gear)
	{
		final StringBuilder sb = new StringBuilder();
		for (Slot slot : Slot.values())
		{
			final Integer id = gear.get(slot);
			if ((id == null) || (id <= 0))
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(slot.key()).append(':').append(id);
		}
		return sb.toString();
	}

	/**
	 * @param text the row text
	 * @return the gear, or null when the row has none recorded yet (a row from before gear was kept per slot)
	 */
	public static Map<Slot, Integer> decode(String text)
	{
		if (text == null)
		{
			return null;
		}
		final Map<Slot, Integer> gear = new EnumMap<>(Slot.class);
		for (String pair : text.split(","))
		{
			final int colon = pair.indexOf(':');
			if (colon <= 0)
			{
				continue;
			}
			try
			{
				final Slot slot = Slot.valueOf(pair.substring(0, colon).trim().toUpperCase());
				final int id = Integer.parseInt(pair.substring(colon + 1).trim());
				if (id > 0)
				{
					gear.put(slot, id);
				}
			}
			catch (IllegalArgumentException e)
			{
				// skip a damaged pair (NumberFormatException is an IllegalArgumentException)
			}
		}
		return gear;
	}

	/**
	 * Sorts a set of items into slots, the way the game fills them: the first earring and ring in the first slot, the
	 * second in the other. Items that are not gear, or for which the slot is taken, are left out.
	 * @param ids items, weapon first
	 * @param items item facts
	 * @return the gear
	 */
	public static Map<Slot, Integer> assign(List<Integer> ids, Items items)
	{
		final Map<Slot, Integer> gear = new EnumMap<>(Slot.class);
		for (int id : ids)
		{
			final Piece piece = items.piece(id);
			if (piece == null)
			{
				continue;
			}
			for (Slot slot : Slot.values())
			{
				if ((slot.kind() == piece.kind()) && !gear.containsKey(slot))
				{
					gear.put(slot, id);
					if (piece.fullBody())
					{
						gear.remove(Slot.LEGS);
					}
					break;
				}
			}
		}
		return gear;
	}

	/**
	 * Describes a change for the decision log, for example "a Sword of Revolution (weapon, D, P.Atk 107 over 64)".
	 * @param change the change
	 * @param fit what the class wears
	 * @return the text
	 */
	public static String describe(Change change, Fit fit)
	{
		final Piece piece = change.piece();
		final String stat = switch (piece.kind())
		{
			case WEAPON -> fit.mage() ? "M.Atk" : "P.Atk";
			case NECK, EAR, RING -> "M.Def";
			case SHIELD -> "Shield Def";
			default -> "P.Def";
		};
		return piece.name() + " (" + change.slot().label() + ", " + gradeName(piece.grade()) + ", " + stat + " " + score(piece, fit) + ", +" + change.gain() + ")";
	}

	/** Puts a piece in its slot and returns what came off. */
	private static List<Integer> put(Map<Slot, Integer> gear, Slot slot, Piece piece, Items items)
	{
		final List<Integer> removed = new ArrayList<>();
		final Integer old = gear.put(slot, piece.itemId());
		if ((old != null) && (old > 0))
		{
			removed.add(old);
		}
		if (piece.fullBody())
		{
			final Integer legs = gear.remove(Slot.LEGS);
			if ((legs != null) && (legs > 0))
			{
				removed.add(legs);
			}
		}
		if ((slot == Slot.WEAPON) && piece.twoHanded())
		{
			final Integer shield = gear.remove(Slot.SHIELD);
			if ((shield != null) && (shield > 0))
			{
				removed.add(shield);
			}
		}
		return removed;
	}

	/** The slot a piece goes into: its own, or the weaker of the two earring or ring slots. */
	private static Slot slotFor(Map<Slot, Integer> gear, Piece piece, Fit fit, Items items)
	{
		switch (piece.kind())
		{
			case EAR:
			{
				return weaker(gear, Slot.EAR1, Slot.EAR2, fit, items);
			}
			case RING:
			{
				return weaker(gear, Slot.RING1, Slot.RING2, fit, items);
			}
			case SHIELD:
			{
				final Piece weapon = (gear.get(Slot.WEAPON) == null) ? null : items.piece(gear.get(Slot.WEAPON));
				return ((weapon != null) && weapon.twoHanded()) ? null : Slot.SHIELD;
			}
			case LEGS:
			{
				final Piece chest = (gear.get(Slot.CHEST) == null) ? null : items.piece(gear.get(Slot.CHEST));
				return ((chest != null) && chest.fullBody()) ? null : Slot.LEGS;
			}
			default:
			{
				return Slot.valueOf(piece.kind().name());
			}
		}
	}

	/** The weaker of two slots; when they are equal, an empty one first. */
	private static Slot weaker(Map<Slot, Integer> gear, Slot first, Slot second, Fit fit, Items items)
	{
		final int one = worth(gear.get(first), fit, items);
		final int two = worth(gear.get(second), fit, items);
		if (one != two)
		{
			return (one < two) ? first : second;
		}
		return ((gear.get(first) == null) || (gear.get(second) != null)) ? first : second;
	}

	/** What a worn item counts for: its score, or nothing when there is none or its class does not use it. */
	private static int worth(Integer itemId, Fit fit, Items items)
	{
		if ((itemId == null) || (itemId <= 0))
		{
			return 0;
		}
		final Piece piece = items.piece(itemId);
		return ((piece == null) || !fits(piece, fit)) ? 0 : score(piece, fit);
	}

	private static int grade(Integer itemId, Items items)
	{
		final Piece piece = ((itemId == null) || (itemId <= 0)) ? null : items.piece(itemId);
		return (piece == null) ? 0 : piece.grade();
	}

	/** Between two equally good offers: the cheaper, then a shop's over a trade, then the lower item id. */
	private static boolean better(Offer offer, Offer than)
	{
		if (offer.price() != than.price())
		{
			return offer.price() < than.price();
		}
		if (offer.shop() != than.shop())
		{
			return offer.shop();
		}
		return offer.piece().itemId() < than.piece().itemId();
	}
}
