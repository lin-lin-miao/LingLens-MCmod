package com.linglens.forge;

import net.minecraftforge.fml.common.Mod;

import com.linglens.LingLensModMain;

@Mod(LingLensModMain.MOD_ID)
public final class LingLensModMainForge {
    public LingLensModMainForge() {
        // Run our common setup.
        LingLensModMain.init();
    }
}
