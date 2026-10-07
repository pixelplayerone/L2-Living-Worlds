package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.clientpackets.impl.AddTradeItemPacketImplementation;

import java.util.Map;

/**
 * Handles item exchange via /trade between two different Player entities, which includes phantoms and the actual human player
 *
 * Why this class exists:
 * Let's take a look at this code snippet
 * { @snippet :
 *   npc.startTrade(owner);
 * 	 items.forEach((itemId, itemCount) -> npc.getActiveTradeList().addItemByItemId(itemId, itemCount, 0));
 * 	 npc.getActiveTradeList().confirm()
 * }
 *
 * This snippet will create a valid exchange between two players, BUT the receiving party won't be able to see a single item in the trade window.
 * Although, if both players confirm such trade items listed in the items Map<ItemObjectId, ItemCount> will be transferred successfully,
 * which makes the whole process completely unimmersive. To fix this, clients have to send corresponding packets to the server, which, in turn,
 * sends its own packets to clients.
 */
public class PhantomExchangeManager
{

    protected PhantomExchangeManager() {
    }

    void runTrade(Player sender, Player receiver, Map<Integer, Integer> itemObjIdsToCounts)
    {
        sender.startTrade(receiver);
        itemObjIdsToCounts.forEach((itemObjectId, itemCount) -> addTradeItem(sender, itemObjectId, itemCount));
        sender.getActiveTradeList().confirm();
    }

    private void addTradeItem(
            Player sender,
            int itemObjectId,
            int count
    )
    {
        int tradeId = sender.getActiveTradeList().getPartner().getObjectId();
        AddTradeItemPacketImplementation.runImplementation(sender, tradeId, itemObjectId, count);
    }

    public static PhantomExchangeManager newInstance()
    {
        return new PhantomExchangeManager();
    }
}
