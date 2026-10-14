package com.systemblock;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to Server: Send a line of input (or command) to the running session or update saved command.
 * Includes target channel mode ("COMMAND" vs "TERMINAL").
 */
public record TerminalInputPayload(BlockPos pos, String mode, String input, boolean executeDirect) implements CustomPacketPayload {
    public static final Type<TerminalInputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SystemBlockMod.MOD_ID, "terminal_input")
    );

    public static final StreamCodec<ByteBuf, TerminalInputPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            TerminalInputPayload::pos,
            ByteBufCodecs.stringUtf8(32),
            TerminalInputPayload::mode,
            ByteBufCodecs.stringUtf8(262144),
            TerminalInputPayload::input,
            ByteBufCodecs.BOOL,
            TerminalInputPayload::executeDirect,
            TerminalInputPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
