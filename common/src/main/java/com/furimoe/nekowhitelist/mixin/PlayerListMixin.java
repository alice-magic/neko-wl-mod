package com.furimoe.nekowhitelist.mixin;

import com.furimoe.nekowhitelist.NekoWhitelistMod;
import com.furimoe.nekowhitelist.mc.JoinGate;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * Re-checks a player vanilla is about to turn away.
 *
 * <p>The synced whitelist is only as fresh as the last poll, so someone added in the
 * dashboard moments ago would be rejected until the next sync. Hooking the login check
 * itself means they get in immediately, and it is the same decision point vanilla uses,
 * so nothing else in the login flow has to change.
 *
 * <p>26.x note: the target takes {@code NameAndId} where 1.x took {@code GameProfile}.
 * A handler whose parameters do not match is an {@code InvalidInjectionException} at
 * startup, so a mistake here fails loudly rather than silently doing nothing.
 *
 * <p>Only rejections are examined, and only those for the whitelist. A player vanilla
 * already accepts is let through untouched, which keeps the common case free of any
 * network call, and a ban, an IP ban or a full server is never overridden: the same
 * method reports all of them, and only the whitelist is ours to speak for.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    /** The rejection vanilla produces for a missing whitelist entry, and the only one we touch. */
    private static final String NOT_WHITELISTED = "multiplayer.disconnect.not_whitelisted";

    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void nekoWhitelist$recheckRejectedLogin(
            SocketAddress address, NameAndId profile, CallbackInfoReturnable<Component> info) {

        // A null return means vanilla is happy; nothing to add.
        if (!isWhitelistRejection(info.getReturnValue())) {
            return;
        }

        JoinGate gate = NekoWhitelistMod.joinGate();
        if (gate == null) {
            return;
        }

        // Runs on a login thread with the player waiting, so the gate bounds its own
        // API call and falls back to the synced list rather than hanging the connection.
        if (gate.allowsFresh(profile.id(), profile.name())) {
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
