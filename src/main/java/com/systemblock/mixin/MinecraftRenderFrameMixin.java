package com.systemblock.mixin;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.systemblock.util.AcquiredSurfaceResetter;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftRenderFrameMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow private boolean surfaceIsInvalid;
    @Shadow private boolean windowSurfaceNeedsReconfiguring;
    @Shadow @Final private GpuSurface windowSurface;

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void systemblock$onRenderFrameHead(CallbackInfo ci) {
        // If the surface was marked invalid or needs reconfiguring from a previous frame,
        // ensure any lingering acquired state is cleared so Minecraft does not abort at line 0
        if (this.surfaceIsInvalid || this.windowSurfaceNeedsReconfiguring) {
            if (this.windowSurface instanceof AcquiredSurfaceResetter resetter) {
                resetter.systemblock$resetAcquiredState();
            }
        }
    }

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
            LOGGER.warn("[MinecraftSystemBlock] Recovered from Vulkan acquireNextTexture timeout: {}", e.getMessage());
            if (surface instanceof AcquiredSurfaceResetter resetter) {
                resetter.systemblock$resetAcquiredState();
            }
            throw new SurfaceException(e);
        } catch (Throwable t) {
            LOGGER.warn("[MinecraftSystemBlock] Recovered from acquireNextTexture failure: {}", t.getMessage());
            if (surface instanceof AcquiredSurfaceResetter resetter) {
                resetter.systemblock$resetAcquiredState();
            }
            throw new SurfaceException(t);
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
            if (surface instanceof AcquiredSurfaceResetter resetter) {
                resetter.systemblock$resetAcquiredState();
            }
        }
    }
}
