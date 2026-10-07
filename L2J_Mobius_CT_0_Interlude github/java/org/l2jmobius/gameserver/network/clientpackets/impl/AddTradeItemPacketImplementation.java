package org.l2jmobius.gameserver.network.clientpackets.impl;

import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.network.PacketLogger;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.TradeOtherAdd;
import org.l2jmobius.gameserver.network.serverpackets.TradeOwnAdd;
import org.l2jmobius.gameserver.network.serverpackets.TradeUpdate;

/**
 * @author Heelix
 */
public final class AddTradeItemPacketImplementation
{

    public static void runImplementation(Player player, int tradeId, int objectId, int count)
    {
        if (player == null)
        {
            return;
        }

        if (count < 1)
        {
            return;
        }

        final TradeList trade = player.getActiveTradeList();
        if (trade == null)
        {
            PacketLogger.warning("Character: " + player.getName() + " requested item:" + objectId + " add without active tradelist:" + tradeId);
            return;
        }

        final Player partner = trade.getPartner();
        if ((partner == null) || (World.getInstance().getPlayer(partner.getObjectId()) == null) || (partner.getActiveTradeList() == null))
        {
            // Trade partner not found, cancel trade
            if (partner != null)
            {
                PacketLogger.warning("Character:" + player.getName() + " requested invalid trade object: " + objectId);
            }

            player.sendPacket(SystemMessageId.THAT_PLAYER_IS_NOT_ONLINE);
            player.cancelActiveTrade();
            return;
        }

        if (trade.isConfirmed() || partner.getActiveTradeList().isConfirmed())
        {
            player.sendPacket(SystemMessageId.YOU_MAY_NO_LONGER_ADJUST_ITEMS_IN_THE_TRADE_BECAUSE_THE_TRADE_HAS_BEEN_CONFIRMED);
            return;
        }

        if (!player.getAccessLevel().allowTransaction())
        {
            player.sendMessage("Transactions are disabled for your Access Level.");
            player.cancelActiveTrade();
            return;
        }

        if (!player.validateItemManipulation(objectId, ItemProcessType.TRANSFER))
        {
            player.sendPacket(SystemMessageId.NOTHING_HAPPENED);
            return;
        }

        final TradeItem item = trade.addItem(objectId, count);
        if (item != null)
        {
            player.sendPacket(new TradeOwnAdd(item));
            player.sendPacket(new TradeUpdate(trade, player));
            trade.getPartner().sendPacket(new TradeOtherAdd(item));
        }
    }

    private AddTradeItemPacketImplementation()
    {
    }
}
