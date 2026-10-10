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

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.data.xml.InitialEquipmentData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Town;
import org.l2jmobius.gameserver.managers.FakePlayerGearFilter;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.buylist.BuyListHolder;
import org.l2jmobius.gameserver.model.buylist.Product;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.holders.InitialEquipment;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;

/**
 * The gear side of the item data for {@link LivingGear}: each item as a piece with its real stats, what each town's
 * shops sell and at what price, what else can be bought from other players, and the starter gear of each class. Built
 * lazily from the loaded item, buylist and spawn data, and cached.
 */
public class GearCatalog implements LivingGear.Items, ColdLife.GearShop
{
	private static final Logger LOGGER = Logger.getLogger(GearCatalog.class.getName());

	/** What a shadow weapon of the usual 300 minutes costs, in percent of the normal weapon (a rough number to tune once the market is reworked). */
	private static final long SHADOW_PRICE_PERCENT = 20;

	/** The wear time the shadow price percent is for. */
	private static final long SHADOW_REFERENCE_MINUTES = 300;

	/** A shop NPC this close to a town's arrival point or grocer counts as that town's shop. */
	private static final double TOWN_RADIUS = 10_000.0;

	private final Map<Integer, Optional<LivingGear.Piece>> _pieces = new ConcurrentHashMap<>();
	private final Map<String, List<LivingGear.Offer>> _offers = new ConcurrentHashMap<>();
	private final Map<Integer, LivingGear.Fit> _fits = new ConcurrentHashMap<>();
	private volatile List<LivingGear.Piece> _tradePool;
	private volatile List<LivingGear.Offer> _shadowOffers;
	private volatile List<ShopList> _shopLists;
	private final boolean _trade;
	private final int _tierStep;

	/** A buylist with gear on it and where its merchants stand. */
	private record ShopList(List<Point> merchants, Map<Integer, Long> prices)
	{
	}

	/**
	 * @param trade whether gear no shop in a town sells can be bought as if from another player (see {@link #tradePrice})
	 * @param tierStep levels per gear tier
	 */
	public GearCatalog(boolean trade, int tierStep)
	{
		_trade = trade;
		_tierStep = tierStep;
	}

	@Override
	public LivingGear.Items items()
	{
		return this;
	}

	@Override
	public int tierStep()
	{
		return _tierStep;
	}

	@Override
	public LivingGear.Piece piece(int itemId)
	{
		return _pieces.computeIfAbsent(itemId, id -> Optional.ofNullable(build(ItemData.getInstance().getTemplate(id)))).orElse(null);
	}

	/**
	 * @param itemId an item
	 * @return whether a living bot may wear it when it drops or is for sale: gear the phantoms render safely on every race
	 */
	@Override
	public boolean wearable(int itemId)
	{
		final LivingGear.Piece piece = piece(itemId);
		return (piece != null) && (FakePlayerGearFilter.isPlayerGear(itemId) || (piece.shadow() && (shadowOriginal(piece) != null)));
	}

	@Override
	public int shadowMinutes(int itemId)
	{
		final LivingGear.Piece piece = piece(itemId);
		return ((piece != null) && piece.shadow()) ? ItemData.getInstance().getTemplate(itemId).getDuration() : 0;
	}

	/**
	 * @param shadow a shadow item
	 * @return the normal item it copies (same name and grade, gear the phantoms render safely), or null when there is none
	 */
	private ItemTemplate shadowOriginal(LivingGear.Piece shadow)
	{
		final String name = shadow.name().substring("Shadow Item:".length()).trim();
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item != null) && item.getName().equals(name) && (item.getCrystalType().ordinal() == shadow.grade()) && item.isTradeable() && FakePlayerGearFilter.isPlayerGear(item))
			{
				return item;
			}
		}
		return null;
	}

	/**
	 * The shadow weapons of C and B grade for sale. No shop sells them, so they are bought as if from another player, at a
	 * share of what the normal weapon costs (they cannot be sold and wear out, so they are cheaper): scaled by how long
	 * the copy lasts.
	 */
	private List<LivingGear.Offer> shadowOffers()
	{
		List<LivingGear.Offer> offers = _shadowOffers;
		if (offers != null)
		{
			return offers;
		}
		offers = new ArrayList<>();
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || !item.getName().startsWith("Shadow Item:"))
			{
				continue;
			}
			final LivingGear.Piece piece = piece(item.getId());
			if ((piece == null) || (piece.kind() != LivingGear.Kind.WEAPON) || ((piece.grade() != 2) && (piece.grade() != 3)))
			{
				continue;
			}
			final ItemTemplate original = shadowOriginal(piece);
			if ((original == null) || (original.getReferencePrice() <= 0) || (item.getDuration() <= 0))
			{
				continue;
			}
			offers.add(new LivingGear.Offer(piece, Math.max(1L, (original.getReferencePrice() * SHADOW_PRICE_PERCENT * item.getDuration()) / (100L * SHADOW_REFERENCE_MINUTES)), false));
		}
		offers = List.copyOf(offers);
		if (!offers.isEmpty())
		{
			_shadowOffers = offers; // only cached once the gear allow-list is loaded
		}
		return offers;
	}

	/**
	 * What a bot pays for a piece of gear no shop in its town sells, as if it bought it from another player: the item's
	 * reference price. This is a stand-in for a real market until bots trade with each other; change it here (or turn it
	 * off with GearTrade) when the player and bot economy is reworked.
	 * @param item the item
	 * @return the price
	 */
	public static long tradePrice(ItemTemplate item)
	{
		return Math.max(0L, item.getReferencePrice());
	}

	/**
	 * @param classId a class
	 * @return what it wears
	 */
	@Override
	public LivingGear.Fit fit(int classId)
	{
		return _fits.computeIfAbsent(classId, id -> PhantomManager.livingFit(PlayerClass.getPlayerClass(id)));
	}

	/**
	 * What a bot can buy in a town: what that town's merchants sell at their price, and, with trade on, every other piece
	 * of player gear at its {@link #tradePrice}. Cached per town.
	 * @param town the town
	 * @return the offers
	 */
	@Override
	public List<LivingGear.Offer> offers(Town town)
	{
		if (town == null)
		{
			return withShadow(tradeOffers(Map.of()));
		}
		return _offers.computeIfAbsent(town.name(), name ->
		{
			final Map<Integer, Long> sold = new HashMap<>();
			for (ShopList list : shopLists())
			{
				if (near(list.merchants(), town))
				{
					for (Map.Entry<Integer, Long> entry : list.prices().entrySet())
					{
						sold.merge(entry.getKey(), entry.getValue(), Math::min);
					}
				}
			}
			final List<LivingGear.Offer> offers = new ArrayList<>();
			for (Map.Entry<Integer, Long> entry : sold.entrySet())
			{
				final LivingGear.Piece piece = piece(entry.getKey());
				if (piece != null)
				{
					offers.add(new LivingGear.Offer(piece, entry.getValue(), true));
				}
			}
			final List<LivingGear.Offer> traded = tradeOffers(sold);
			offers.addAll(traded);
			final List<LivingGear.Offer> shadow = shadowOffers();
			offers.addAll(shadow);
			LOGGER.info("LivingPopulation: " + name + " sells " + (offers.size() - traded.size() - shadow.size()) + " gear pieces" + (_trade ? (", and " + traded.size() + " more can be bought from players") : "") + ", plus " + shadow.size() + " shadow weapons from players");
			return List.copyOf(offers);
		});
	}

	/**
	 * The gear a new character of this class starts with (its base class's, for a class that has changed).
	 * @param classId the class
	 * @return the gear
	 */
	public Map<LivingGear.Slot, Integer> starter(int classId)
	{
		PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
		while (playerClass != null)
		{
			final Collection<InitialEquipment> equipment = InitialEquipmentData.getInstance().getClassEquipment(playerClass);
			if ((equipment != null) && !equipment.isEmpty())
			{
				final List<Integer> ids = new ArrayList<>();
				for (InitialEquipment item : equipment)
				{
					if (item.isEquipped())
					{
						ids.add(item.getId());
					}
				}
				return LivingGear.assign(ids, this);
			}
			playerClass = playerClass.getParent();
		}
		return new EnumMap<>(LivingGear.Slot.class);
	}

	/**
	 * A full set of one grade for a class, the best of that grade in every slot, as a bot from before gear was kept per
	 * slot is given for the gear tier it had bought.
	 * @param classId the class
	 * @param grade the grade (0 to 5)
	 * @return the gear
	 */
	public Map<LivingGear.Slot, Integer> kit(int classId, int grade)
	{
		final List<LivingGear.Offer> pool = new ArrayList<>();
		for (LivingGear.Piece piece : tradePool())
		{
			if (piece.grade() == grade)
			{
				pool.add(new LivingGear.Offer(piece, 0L, true));
			}
		}
		final Map<LivingGear.Slot, Integer> gear = new EnumMap<>(LivingGear.Slot.class);
		LivingGear.shop(gear, fit(classId), 80, Long.MAX_VALUE, pool, this);
		return gear;
	}

	private List<LivingGear.Offer> withShadow(List<LivingGear.Offer> offers)
	{
		final List<LivingGear.Offer> all = new ArrayList<>(offers);
		all.addAll(shadowOffers());
		return all;
	}

	private List<LivingGear.Offer> tradeOffers(Map<Integer, Long> sold)
	{
		final List<LivingGear.Offer> offers = new ArrayList<>();
		if (!_trade)
		{
			return offers;
		}
		for (LivingGear.Piece piece : tradePool())
		{
			if (!sold.containsKey(piece.itemId()))
			{
				offers.add(new LivingGear.Offer(piece, tradePrice(ItemData.getInstance().getTemplate(piece.itemId())), false));
			}
		}
		return offers;
	}

	/** Every piece of player gear that has a price. */
	private List<LivingGear.Piece> tradePool()
	{
		List<LivingGear.Piece> pool = _tradePool;
		if (pool != null)
		{
			return pool;
		}
		pool = new ArrayList<>();
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || !item.isTradeable() || (item.getReferencePrice() <= 0) || !FakePlayerGearFilter.isPlayerGear(item))
			{
				continue;
			}
			final LivingGear.Piece piece = piece(item.getId());
			if (piece != null)
			{
				pool.add(piece);
			}
		}
		pool = List.copyOf(pool);
		if (!pool.isEmpty())
		{
			_tradePool = pool; // only cached once the gear allow-list is loaded
		}
		return pool;
	}

	/** The buylists that sell gear, with the spots their merchants stand at. Read once from the buylist files. */
	private List<ShopList> shopLists()
	{
		List<ShopList> lists = _shopLists;
		if (lists != null)
		{
			return lists;
		}
		lists = new ArrayList<>();
		for (int listId : buyListIds())
		{
			final BuyListHolder holder = BuyListData.getInstance().getBuyList(listId);
			if ((holder == null) || (holder.getNpcsAllowed() == null))
			{
				continue;
			}
			final Map<Integer, Long> prices = new HashMap<>();
			for (Product product : holder.getProducts())
			{
				if (wearable(product.getItemId())) // the render-safety filter applies to shop gear as well as drops
				{
					prices.put(product.getItemId(), (long) product.getPrice());
				}
			}
			if (prices.isEmpty())
			{
				continue;
			}
			final List<Point> merchants = new ArrayList<>();
			for (int npcId : holder.getNpcsAllowed())
			{
				if ((npcId >= 35000) && (npcId < 37000))
				{
					continue; // castle, fortress and clan hall NPCs: not a town shop anyone can use
				}
				for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
				{
					merchants.add(new Point(spawn.getX(), spawn.getY(), spawn.getZ()));
				}
			}
			if (!merchants.isEmpty())
			{
				lists.add(new ShopList(merchants, prices));
			}
		}
		_shopLists = List.copyOf(lists);
		return _shopLists;
	}

	/** The buylist ids, from the buylist file names (the file name is the list id). */
	private static List<Integer> buyListIds()
	{
		final List<Integer> ids = new ArrayList<>();
		for (String folder : new String[]
		{
			"data/buylists",
			"data/buylists/custom"
		})
		{
			final File[] files = new File(ServerConfig.DATAPACK_ROOT, folder).listFiles();
			if (files == null)
			{
				continue;
			}
			for (File file : files)
			{
				final String name = file.getName();
				if (file.isFile() && name.endsWith(".xml"))
				{
					try
					{
						ids.add(Integer.parseInt(name.substring(0, name.length() - 4)));
					}
					catch (NumberFormatException e)
					{
						// not a buylist file
					}
				}
			}
		}
		return ids;
	}

	private static boolean near(List<Point> merchants, Town town)
	{
		for (Point merchant : merchants)
		{
			if (close(merchant, town.arrival()) || close(merchant, town.grocer()))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean close(Point a, Point b)
	{
		return (b != null) && (Math.hypot(a.x() - b.x(), a.y() - b.y()) <= TOWN_RADIUS);
	}

	/** An item as a piece of gear, or null when it is not something a player wears in a gear slot. */
	private static LivingGear.Piece build(ItemTemplate item)
	{
		if ((item == null) || !item.isEquipable() || item.isForNpc())
		{
			return null;
		}
		final LivingGear.Kind kind;
		String type;
		boolean magic = false;
		boolean twoHanded = false;
		boolean fullBody = false;
		final BodyPart part = item.getBodyPart();
		if (item instanceof Weapon weapon)
		{
			final WeaponType weaponType = weapon.getItemType();
			if ((weaponType == WeaponType.FISHINGROD) || (weaponType == WeaponType.FLAG) || (weaponType == WeaponType.OWNTHING) || (weaponType == WeaponType.ETC) || (weaponType == WeaponType.NONE))
			{
				return null;
			}
			if ((part != BodyPart.R_HAND) && (part != BodyPart.LR_HAND))
			{
				return null;
			}
			kind = LivingGear.Kind.WEAPON;
			type = weaponType.name();
			magic = item.isMagicWeapon();
			twoHanded = part == BodyPart.LR_HAND;
		}
		else if (item instanceof Armor armor)
		{
			final ArmorType armorType = armor.getItemType();
			type = armorType.name();
			if ((armorType == ArmorType.SHIELD) || (part == BodyPart.L_HAND))
			{
				if (armorType == ArmorType.SIGIL)
				{
					return null;
				}
				kind = LivingGear.Kind.SHIELD;
			}
			else
			{
				switch (part)
				{
					case CHEST:
					{
						kind = LivingGear.Kind.CHEST;
						break;
					}
					case FULL_ARMOR:
					{
						kind = LivingGear.Kind.CHEST;
						fullBody = true;
						break;
					}
					case LEGS:
					{
						kind = LivingGear.Kind.LEGS;
						break;
					}
					case HEAD:
					{
						kind = LivingGear.Kind.HEAD;
						break;
					}
					case GLOVES:
					{
						kind = LivingGear.Kind.GLOVES;
						break;
					}
					case FEET:
					{
						kind = LivingGear.Kind.FEET;
						break;
					}
					case NECK:
					{
						kind = LivingGear.Kind.NECK;
						break;
					}
					case R_EAR:
					case L_EAR:
					case LR_EAR:
					{
						kind = LivingGear.Kind.EAR;
						break;
					}
					case R_FINGER:
					case L_FINGER:
					case LR_FINGER:
					{
						kind = LivingGear.Kind.RING;
						break;
					}
					default:
					{
						return null;
					}
				}
			}
		}
		else
		{
			return null;
		}

		int pAtk = 0;
		int mAtk = 0;
		int pDef = 0;
		int mDef = 0;
		for (AbstractFunction function : item.getStatFuncs(null, null))
		{
			final int value = (int) Math.round(function.getValue());
			if (function.getStat() == Stat.POWER_ATTACK)
			{
				pAtk = Math.max(pAtk, value);
			}
			else if (function.getStat() == Stat.MAGIC_ATTACK)
			{
				mAtk = Math.max(mAtk, value);
			}
			else if ((function.getStat() == Stat.POWER_DEFENCE) || (function.getStat() == Stat.SHIELD_DEFENCE))
			{
				// A shield's defense is its shield defense (sDef), counted like armor's P.Def.
				pDef = Math.max(pDef, value);
			}
			else if (function.getStat() == Stat.MAGIC_DEFENCE)
			{
				mDef = Math.max(mDef, value);
			}
		}
		final int grade = Math.max(0, Math.min(5, item.getCrystalType().ordinal()));
		final long sellPrice = (item.isSellable() && !item.isQuestItem()) ? Math.max(0L, item.getReferencePrice() / 2L) : 0L;
		return new LivingGear.Piece(item.getId(), item.getName(), kind, grade, type, magic, twoHanded, fullBody, pAtk, mAtk, pDef, mDef, sellPrice);
	}
}
