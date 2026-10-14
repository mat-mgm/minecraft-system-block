package com.systemblock;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to Client: Real-time live log chunk / terminal update for the opened GUI.
 * Uses high capacity UTF-8 codec (256 KB) and mode channel routing ("COMMAND" vs "TERMINAL").
 */
public record TerminalOutputPayload(BlockPos pos, String mode, String outputText, boolean append) implements CustomPacketPayload {
    public static final Type<TerminalOutputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SystemBlockMod.MOD_ID, "terminal_output")
    );

    public static final StreamCodec<ByteBuf, TerminalOutputPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            TerminalOutputPayload::pos,
            ByteBufCodecs.stringUtf8(256),
            TerminalOutputPayload::mode,
            ByteBufCodecs.stringUtf8(262144),
            TerminalOutputPayload::outputText,
            ByteBufCodecs.BOOL,
            TerminalOutputPayload::append,
            TerminalOutputPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
