package com.systemblock.mixin;

import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FrontendGpuSurface.class)
public abstract class FrontendGpuSurfaceMixin implements AcquiredSurfaceResetter {
    @Shadow private boolean hasImageAcquired;
    @Shadow private boolean hasBlittedTexture;

    @Override
    public void systemblock$resetAcquiredState() {
        this.hasImageAcquired = false;
        this.hasBlittedTexture = false;
    }

    @Redirect(
        method = "present",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/api/GpuSurfaceBackend;present()V"
        )
    )
    private void systemblock$safeBackendPresent(GpuSurfaceBackend backend) {
        try {
            backend.present();
        } finally {
            this.hasImageAcquired = false;
            this.hasBlittedTexture = false;
        }
    }
}
