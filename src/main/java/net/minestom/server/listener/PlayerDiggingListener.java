package net.minestom.server.listener;

import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.item.ItemUpdateStateEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.StackingRule;
import net.minestom.server.network.packet.client.play.ClientPlayerDiggingPacket;
import net.minestom.server.network.packet.server.play.AcknowledgePlayerDiggingPacket;
import net.minestom.server.network.packet.server.play.EntityEffectPacket;
import net.minestom.server.network.packet.server.play.RemoveEntityEffectPacket;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.utils.BlockPosition;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerDiggingListener {

    private static final List<Player> playersEffect = new CopyOnWriteArrayList<>();

    public static void playerDiggingListener(ClientPlayerDiggingPacket packet, Player player) {
        final ClientPlayerDiggingPacket.Status status = packet.status;
        final BlockPosition blockPosition = packet.blockPosition;

        final Instance instance = player.getInstance();

        if (instance == null)
            return;

        if (status == ClientPlayerDiggingPacket.Status.STARTED_DIGGING) {
            final Block block = instance.getBlock(blockPosition);

            //Check if the player is allowed to break blocks based on their game mode
            if (player.getGameMode() == GameMode.SPECTATOR) {
                sendAcknowledgePacket(player, blockPosition, block,
                        ClientPlayerDiggingPacket.Status.STARTED_DIGGING, false);
                return; //Spectators can't break blocks
            } else if (player.getGameMode() == GameMode.ADVENTURE) {
                //Check if the item can break the block with the current item
                ItemStack itemInMainHand = player.getItemInMainHand();
                Block destroyedBlock = instance.getBlock(blockPosition);
                if (!itemInMainHand.getMeta().getCanDestroy().contains(destroyedBlock)) {
                    sendAcknowledgePacket(player, blockPosition, block,
                            ClientPlayerDiggingPacket.Status.STARTED_DIGGING, false);
                    return;
                }
            }

            final boolean instantBreak = player.isCreative() ||
                    player.isInstantBreak() ||
                    block.getData().getHardness() == 0;

            if (instantBreak) {
                // No need to check custom block
                breakBlock(instance, player, blockPosition, block, status);
            } else {
                PlayerStartDiggingEvent playerStartDiggingEvent = new PlayerStartDiggingEvent(player, block, blockPosition);
                player.callEvent(PlayerStartDiggingEvent.class, playerStartDiggingEvent);

                if (playerStartDiggingEvent.isCancelled()) {
                    addEffect(player);

                    // Unsuccessful digging
                    sendAcknowledgePacket(player, blockPosition, block,
                            ClientPlayerDiggingPacket.Status.STARTED_DIGGING, false);
                } else if (false) {
                    // TODO: Handle Custom Block here
                }
            }

        } else if (status == ClientPlayerDiggingPacket.Status.CANCELLED_DIGGING) {

            final Block block = instance.getBlock(blockPosition);
            // Remove custom block target
            player.resetTargetBlock();

            sendAcknowledgePacket(player, blockPosition, block,
                    ClientPlayerDiggingPacket.Status.CANCELLED_DIGGING, true);

        } else if (status == ClientPlayerDiggingPacket.Status.FINISHED_DIGGING) {

            final Block block = instance.getBlock(blockPosition);

            if (false) {
                // TODO: Handle custom block with block break delay here
            } else {
                // Vanilla block
                breakBlock(instance, player, blockPosition, block, status);
            }

        } else if (status == ClientPlayerDiggingPacket.Status.DROP_ITEM_STACK) {

            final ItemStack droppedItemStack = player.getInventory().getItemInMainHand();
            dropItem(player, droppedItemStack, ItemStack.AIR);

        } else if (status == ClientPlayerDiggingPacket.Status.DROP_ITEM) {

            final int dropAmount = 1;

            ItemStack handItem = player.getInventory().getItemInMainHand();
            final StackingRule stackingRule = handItem.getStackingRule();
            final int handAmount = stackingRule.getAmount(handItem);

            if (handAmount <= dropAmount) {
                // Drop the whole item without copy
                dropItem(player, handItem, ItemStack.AIR);
            } else {
                // Drop a single item, need a copy
                ItemStack droppedItemStack2 = stackingRule.apply(handItem, dropAmount);

                handItem = stackingRule.apply(handItem, handAmount - dropAmount);

                dropItem(player, droppedItemStack2, handItem);
            }

        } else if (status == ClientPlayerDiggingPacket.Status.UPDATE_ITEM_STATE) {
            Player.Hand hand = null;
            if (player.getItemInHand(Player.Hand.OFF).getMaterial().hasState()) {
                hand = Player.Hand.OFF;
            } else if (player.getItemInHand(Player.Hand.MAIN).getMaterial().hasState()) {
                hand = Player.Hand.MAIN;
            }

            player.refreshEating(null);
            ItemUpdateStateEvent itemUpdateStateEvent = player.callItemUpdateStateEvent(false, hand);

            if (itemUpdateStateEvent == null) {
                player.refreshActiveHand(true, false, false);
            } else {
                final boolean isOffHand = itemUpdateStateEvent.getHand() == Player.Hand.OFF;
                player.refreshActiveHand(itemUpdateStateEvent.hasHandAnimation(), isOffHand, false);
            }

        } else if (status == ClientPlayerDiggingPacket.Status.SWAP_ITEM_HAND) {

            final PlayerInventory playerInventory = player.getInventory();
            final ItemStack mainHand = playerInventory.getItemInMainHand();
            final ItemStack offHand = playerInventory.getItemInOffHand();

            PlayerSwapItemEvent swapItemEvent = new PlayerSwapItemEvent(player, offHand, mainHand);
            player.callCancellableEvent(PlayerSwapItemEvent.class, swapItemEvent, () -> {
                playerInventory.setItemInMainHand(swapItemEvent.getMainHandItem());
                playerInventory.setItemInOffHand(swapItemEvent.getOffHandItem());
            });

        }
    }

    private static void breakBlock(Instance instance,
                                   Player player,
                                   BlockPosition blockPosition, Block block,
                                   ClientPlayerDiggingPacket.Status status) {
        // Finished digging, remove effect if any
        player.resetTargetBlock();

        // Unverified block break, client is fully responsible
        final boolean result = instance.breakBlock(player, blockPosition);

        final Block updatedBlock = instance.getBlock(blockPosition);

        // Send acknowledge packet to allow or cancel the digging process
        sendAcknowledgePacket(player, blockPosition, updatedBlock, status, result);

        if (!result) {
            if (block.isSolid()) {
                final BlockPosition playerBlockPosition = player.getPosition().toBlockPosition();

                // Teleport the player back if he broke a solid block just below him
                if (playerBlockPosition.subtract(0, 1, 0).equals(blockPosition))
                    player.teleport(player.getPosition());
            }
        }
    }

    private static void dropItem(@NotNull Player player,
                                 @NotNull ItemStack droppedItem, @NotNull ItemStack handItem) {
        final PlayerInventory playerInventory = player.getInventory();
        if (player.dropItem(droppedItem)) {
            playerInventory.setItemInMainHand(handItem);
        } else {
            playerInventory.update();
        }
    }

    /**
     * Adds the effect {@link PotionEffect#MINING_FATIGUE} to the player.
     * <p>
     * Used for CustomBlock break delay or when the {@link PlayerStartDiggingEvent} is cancelled
     * to remove the player break animation.
     *
     * @param player the player to add the effect to
     */
    private static void addEffect(@NotNull Player player) {
        playersEffect.add(player);

        EntityEffectPacket entityEffectPacket = new EntityEffectPacket();
        entityEffectPacket.entityId = player.getEntityId();
        entityEffectPacket.potion = new Potion(
                PotionEffect.MINING_FATIGUE,
                (byte) -1,
                0,
                false,
                false,
                false
        );
        player.getPlayerConnection().sendPacket(entityEffectPacket);
    }

    /**
     * Used to remove the affect from {@link #addEffect(Player)}.
     * <p>
     * Called when the player cancelled or finished digging the CustomBlock.
     *
     * @param player the player to remove the effect to
     */
    public static void removeEffect(@NotNull Player player) {
        if (playersEffect.contains(player)) {
            playersEffect.remove(player);

            RemoveEntityEffectPacket removeEntityEffectPacket = new RemoveEntityEffectPacket();
            removeEntityEffectPacket.entityId = player.getEntityId();
            removeEntityEffectPacket.effect = PotionEffect.MINING_FATIGUE;
            player.getPlayerConnection().sendPacket(removeEntityEffectPacket);
        }
    }

    /**
     * Sends an {@link AcknowledgePlayerDiggingPacket} to a connection.
     *
     * @param player        the player
     * @param blockPosition the block position
     * @param block         the block
     * @param status        the status of the digging
     * @param success       true to notify of a success, false otherwise
     */
    private static void sendAcknowledgePacket(@NotNull Player player, @NotNull BlockPosition blockPosition, Block block,
                                              @NotNull ClientPlayerDiggingPacket.Status status, boolean success) {
        AcknowledgePlayerDiggingPacket acknowledgePlayerDiggingPacket = new AcknowledgePlayerDiggingPacket();
        acknowledgePlayerDiggingPacket.blockPosition = blockPosition;
        acknowledgePlayerDiggingPacket.blockStateId = block.getStateId();
        acknowledgePlayerDiggingPacket.status = status;
        acknowledgePlayerDiggingPacket.successful = success;

        player.getPlayerConnection().sendPacket(acknowledgePlayerDiggingPacket);
    }

}
