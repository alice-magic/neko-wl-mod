package com.furimoe.nekowhitelist.fabric;

import com.furimoe.nekowhitelist.NekoWhitelistMod;

import net.fabricmc.api.DedicatedServerModInitializer;

public final class NekoWhitelistFabric implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        NekoWhitelistMod.init();
    }
}
