package com.systemblock.mixin;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class MinecraftRenderFrameMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow private boolean surfaceIsInvalid;
    @Shadow private boolean windowSurfaceNeedsReconfiguring;

    @Redirect(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"
        )
    )
    private void systemblock$safeAcquireNextTexture(GpuSurface surface) throws SurfaceException {
        try {
            surface.acquireNextTexture();
        } catch (IllegalStateException e) {
            // RenderPearl Vulkan backend throws IllegalStateException instead of SurfaceException
            // when vkAcquireNextImageKHR times out (e.g. monitor asleep or Wayland compositor paused).
            // Converting it to SurfaceException triggers Minecraft's built-in surface invalidation
            // and reconfiguration recovery path without crashing the game.
            LOGGER.warn("[MinecraftSystemBlock] Recovered from Vulkan acquireNextTexture timeout: {}", e.getMessage());
            throw new SurfaceException(e);
        }
    }

    @Redirect(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"
        )
    )
    private void systemblock$safePresent(GpuSurface surface) {
        try {
            surface.present();
        } catch (Throwable t) {
            // Catch Vulkan VK_ERROR_DEVICE_LOST / hard recovery failures during presentation
            // and gracefully request swapchain re-creation on next frame
            LOGGER.warn("[MinecraftSystemBlock] Recovered from Vulkan present failure: {}", t.getMessage());
            this.surfaceIsInvalid = true;
            this.windowSurfaceNeedsReconfiguring = true;
        }
    }
}
