package com.systemblock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.logging.Logger;

public class SystemBlockMod implements ModInitializer {
    public static final String MOD_ID = "minecraft_system_block";
    public static final Logger LOGGER = Logger.getLogger("MinecraftSystemBlock");

    public static final Identifier BLOCK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "system_block");
    public static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, BLOCK_ID);
    public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, BLOCK_ID);
    public static final ResourceKey<BlockEntityType<?>> BLOCK_ENTITY_KEY = ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, BLOCK_ID);

    public static final Block SYSTEM_BLOCK = new SystemBlock(BlockBehaviour.Properties.of().setId(BLOCK_KEY));
    public static final Item SYSTEM_BLOCK_ITEM = new BlockItem(SYSTEM_BLOCK, new Item.Properties().setId(ITEM_KEY));
    public static final BlockEntityType<SystemBlockEntity> SYSTEM_BLOCK_ENTITY_TYPE = new BlockEntityType<>(
            SystemBlockEntity::new,
            Set.of(SYSTEM_BLOCK)
    );

    @Override
    public void onInitialize() {
        LOGGER.info("[SystemBlock] Initializing minecraft-system-block mod...");

        Registry.register(BuiltInRegistries.BLOCK, BLOCK_ID, SYSTEM_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, BLOCK_ID, SYSTEM_BLOCK_ITEM);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, BLOCK_ID, SYSTEM_BLOCK_ENTITY_TYPE);

        // Register custom network payloads
        PayloadTypeRegistry.serverboundPlay().register(TerminalInputPayload.TYPE, TerminalInputPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TerminalControlPayload.TYPE, TerminalControlPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TerminalOutputPayload.TYPE, TerminalOutputPayload.STREAM_CODEC);

        // Server handlers
        ServerPlayNetworking.registerGlobalReceiver(TerminalInputPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                // In multiplayer, require OP / Game Master permissions to execute or modify host shell commands
                if (!context.player().canUseGameMasterBlocks() && !context.server().isSingleplayer()) {
                    LOGGER.warning("[SystemBlock] Unauthorized configuration attempt at " + payload.pos() + " by player: " + context.player().getName().getString());
                    return;
                }

                BlockEntity be = context.player().level().getBlockEntity(payload.pos());
                if (be instanceof SystemBlockEntity systemBlockEntity) {
                    if (payload.executeDirect()) {
                        systemBlockEntity.sendInputToProcess(payload.input(), true);
                    } else {
                        systemBlockEntity.setCommand(payload.input());
                        LOGGER.info("[SystemBlock] Saved command at " + payload.pos() + " by " + context.player().getName().getString());
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(TerminalControlPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                // In multiplayer, require OP / Game Master permissions
                if (!context.player().canUseGameMasterBlocks() && !context.server().isSingleplayer()) {
                    LOGGER.warning("[SystemBlock] Unauthorized control attempt at " + payload.pos() + " by player: " + context.player().getName().getString());
                    return;
                }

                BlockEntity be = context.player().level().getBlockEntity(payload.pos());
                if (be instanceof SystemBlockEntity systemBlockEntity) {
                    switch (payload.action()) {
                        case "START" -> systemBlockEntity.startProcess(null);
                        case "INTERRUPT" -> systemBlockEntity.interruptProcess();
                        case "KILL" -> systemBlockEntity.killProcess();
                        case "CLEAR" -> systemBlockEntity.clearLogs();
                        case "TOGGLE_PULSE" -> systemBlockEntity.setPulseOnSuccess(!systemBlockEntity.isPulseOnSuccess());
                        case "TOGGLE_MODE" -> {
                            String next = "COMMAND".equals(systemBlockEntity.getMode()) ? "TERMINAL" : "COMMAND";
                            systemBlockEntity.setMode(next);
                        }
                    }
                }
            });
        });

        ResourceKey<CreativeModeTab> redstoneTabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("minecraft", "redstone_blocks"));
        CreativeModeTabEvents.modifyOutputEvent(redstoneTabKey).register(output -> {
            output.prepend(SYSTEM_BLOCK_ITEM);
        });

        LOGGER.info("[SystemBlock] System Block, terminal streaming, and networking registered successfully!");
    }
}
