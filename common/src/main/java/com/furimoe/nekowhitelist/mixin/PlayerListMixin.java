package com.furimoe.nekowhitelist.mixin;

import com.furimoe.nekowhitelist.NekoWhitelistMod;
import com.furimoe.nekowhitelist.mc.JoinGate;
import com.mojang.authlib.GameProfile;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * Checks whitelist decisions against the latest cached API snapshot.
 *
 * <p>No API request runs during login. Players absent from the cached list are rejected.
 *
 * <p>A ban, an IP ban or a full server is never overridden: only whitelist decisions are
 * ours to speak for.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    /** The rejection vanilla produces for a missing whitelist entry, and the only one we touch. */
    private static final String NOT_WHITELISTED = "multiplayer.disconnect.not_whitelisted";

    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void nekoWhitelist$checkCachedWhitelist(
            SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> info) {

        JoinGate gate = NekoWhitelistMod.joinGate();
        if (gate == null) {
            return;
        }

        Component rejection = info.getReturnValue();
        if (rejection == null) {
            if (!gate.allows(profile.getId(), profile.getName())) {
                info.setReturnValue(Component.translatable(NOT_WHITELISTED));
            }
            return;
        }
        if (!isWhitelistRejection(rejection)) {
            return;
        }

        if (gate.allows(profile.getId(), profile.getName())) {
            info.setReturnValue(null);
        }
    }

    /**
     * Whether this rejection is the whitelist one.
     *
     * <p>Matching on the translation key rather than the rendered text keeps it working
     * whatever language the server runs in.
     */
    private static boolean isWhitelistRejection(Component rejection) {
        return rejection != null
                && rejection.getContents() instanceof TranslatableContents translatable
                && NOT_WHITELISTED.equals(translatable.getKey());
    }
}
