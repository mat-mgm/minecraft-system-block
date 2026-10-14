package com.systemblock.client;

import com.systemblock.SystemBlockMod;
import com.systemblock.TerminalOutputPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class SystemBlockClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(TerminalOutputPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                Screen current = Minecraft.getInstance().gui.screen();
                if (current instanceof SystemBlockScreen screen) {
                    if (screen.getBlockPos().equals(payload.pos())) {
                        screen.appendTerminalLog(payload.mode(), payload.outputText(), payload.append());
                    }
                }
            });
        });
    }
}
