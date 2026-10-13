package com.systemblock;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to Server: Terminal control signals (start, sigint/interrupt, kill, clear).
 */
public record TerminalControlPayload(BlockPos pos, String action) implements CustomPacketPayload {
    public static final Type<TerminalControlPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SystemBlockMod.MOD_ID, "terminal_control")
    );

    public static final StreamCodec<ByteBuf, TerminalControlPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            TerminalControlPayload::pos,
            ByteBufCodecs.STRING_UTF8,
            TerminalControlPayload::action,
            TerminalControlPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
