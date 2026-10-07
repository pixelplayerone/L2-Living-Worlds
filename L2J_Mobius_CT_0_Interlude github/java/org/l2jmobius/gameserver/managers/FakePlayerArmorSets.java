/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.holders.ArmorSet;
import org.l2jmobius.gameserver.data.xml.ArmorSetData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.CrystalType;

/**
 * Turns the game's own armor-set definitions ({@code data/stats/armorsets/**}, loaded by {@link ArmorSetData})
 * into ready-to-wear matching outfits for fake players and phantoms, so a bot wears a coherent set (Karmian,
 * Full Plate, Mithril, ...) instead of a random mix of unrelated pieces. Only sets whose chest is player gear
 * (see {@link FakePlayerGearFilter}) are used, and each piece is likewise kept only if it is player gear, so the
 * outfits stay render-safe on every race. Family (light/heavy/robe) and grade come from the chest item.
 */
public class FakePlayerArmorSets
{
	/** A matching armor outfit. Any slot may be 0 (the set does not define it / it is not player gear); when the
	 * chest is a one-piece full body armor, {@code legs} is 0 by design. */
	public static class Outfit
	{
		public final int chest;
		public final boolean onepiece;
		public final int legs;
		public final int gloves;
		public final int feet;
		public final int head;
		public final int shield;

		Outfit(int chest, boolean onepiece, int legs, int gloves, int feet, int head, int shield)
		{
			this.chest = chest;
			this.onepiece = onepiece;
			this.legs = legs;
			this.gloves = gloves;
			this.feet = feet;
			this.head = head;
			this.shield = shield;
		}
	}

	// family (LIGHT/HEAVY/MAGIC) -> grade -> matching outfits.
	private static final EnumMap<ArmorType, EnumMap<CrystalType, List<Outfit>>> SETS = new EnumMap<>(ArmorType.class);
	private static volatile boolean _built = false;
	private static volatile int _builtVersion = -1;

	private FakePlayerArmorSets()
	{
	}

	/** First player-gear id in {@code ids}, or 0 if none (drops set pieces that are not render-safe player gear). */
	private static int firstAllowed(List<Integer> ids)
	{
		if (ids != null)
		{
			for (int id : ids)
			{
				if (FakePlayerGearFilter.isPlayerGear(id))
				{
					return id;
				}
			}
		}
		return 0;
	}

	private static synchronized void build()
	{
		final int version = FakePlayerGearFilter.getVersion();
		if (_built && (_builtVersion == version))
		{
			return;
		}
		SETS.clear();
		_built = false;
		_builtVersion = -1;
		for (ArmorType family : new ArmorType[]
		{
			ArmorType.LIGHT,
			ArmorType.HEAVY,
			ArmorType.MAGIC
		})
		{
			final EnumMap<CrystalType, List<Outfit>> byGrade = new EnumMap<>(CrystalType.class);
			for (CrystalType g : CrystalType.values())
			{
				byGrade.put(g, new ArrayList<>());
			}
			SETS.put(family, byGrade);
		}
		int built = 0;
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if (!(item instanceof Armor))
			{
				continue;
			}
			final int chestId = item.getId();
			if (!FakePlayerGearFilter.isPlayerGear(chestId) || !ArmorSetData.getInstance().isArmorSet(chestId))
			{
				continue;
			}
			final ArmorType family = ((Armor) item).getItemType();
			if ((family != ArmorType.LIGHT) && (family != ArmorType.HEAVY) && (family != ArmorType.MAGIC))
			{
				continue; // the set chest must be a light/heavy/robe body piece
			}
			final ArmorSet set = ArmorSetData.getInstance().getSet(chestId);
			if (set == null)
			{
				continue;
			}
			final boolean onepiece = item.getBodyPart() == BodyPart.FULL_ARMOR;
			final Outfit outfit = new Outfit(chestId, onepiece, firstAllowed(set.getLegs()), firstAllowed(set.getGloves()), firstAllowed(set.getFeet()), firstAllowed(set.getHead()), firstAllowed(set.getShieldIds()));
			SETS.get(family).get(item.getCrystalType()).add(outfit);
			built++;
		}
		if (built > 0) // only cache once real data is present (guards against a call before ArmorSetData/BuyListData load)
		{
			_builtVersion = version;
			_built = true;
		}
	}

	/**
	 * A random matching outfit for the armor {@code family} at {@code grade}, staying in that family and stepping
	 * <b>down</b> only (never up, so a level-10 heavy role never lands a D-grade set). Returns {@code null} when the
	 * family has no set at or below the grade - notably no-grade heavy, which has no set at all: the caller then
	 * assembles the outfit from individual family pieces (Bronze Breastplate/Gaiters + Bone Shield) so a low-level
	 * heavy role still looks heavy instead of borrowing a leather set.
	 * @return an {@link Outfit}, or {@code null} if the family has no set at or below {@code grade}
	 */
	public static Outfit random(ArmorType family, CrystalType grade)
	{
		build();
		final CrystalType[] grades = CrystalType.values();
		for (int ord = grade.ordinal(); ord >= 0; ord--)
		{
			final Outfit outfit = randomFrom(family, grades[ord]);
			if (outfit != null)
			{
				return outfit;
			}
		}
		return null;
	}

	private static Outfit randomFrom(ArmorType family, CrystalType grade)
	{
		final EnumMap<CrystalType, List<Outfit>> byGrade = SETS.get(family);
		if (byGrade == null)
		{
			return null;
		}
		final List<Outfit> list = byGrade.get(grade);
		return ((list == null) || list.isEmpty()) ? null : list.get(Rnd.get(list.size()));
	}
}
