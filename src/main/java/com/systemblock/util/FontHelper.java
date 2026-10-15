package com.systemblock.util;

import java.lang.reflect.Field;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

public class FontHelper {
    private static final FontDescription TERMINAL_FONT_DESC =
        new FontDescription.Resource(Identifier.fromNamespaceAndPath("minecraft_system_block", "default"));

    private static Font cachedDefaultFont = null;
    private static Font cachedTerminalFont = null;

    public static Font getTerminalFont(Font defaultFont) {
        if (defaultFont == null) return null;
        if (cachedDefaultFont == defaultFont && cachedTerminalFont != null) {
            return cachedTerminalFont;
        }

        try {
            Field providerField = Font.class.getDeclaredField("provider");
            providerField.setAccessible(true);
            Font.Provider baseProvider = (Font.Provider) providerField.get(defaultFont);

            Font.Provider terminalProvider = new Font.Provider() {
                @Override
                public GlyphSource glyphs(FontDescription description) {
                    if (description == null || description.equals(FontDescription.DEFAULT)) {
                        return baseProvider.glyphs(TERMINAL_FONT_DESC);
                    }
                    return baseProvider.glyphs(description);
                }

                @Override
                public EffectGlyph effect() {
                    return baseProvider.effect();
                }
            };

            cachedDefaultFont = defaultFont;
            cachedTerminalFont = new Font(terminalProvider);
            return cachedTerminalFont;
        } catch (Throwable t) {
            t.printStackTrace();
            return defaultFont;
        }
    }
}
