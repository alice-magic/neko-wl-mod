package com.furimoe.nekowhitelist.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * {@code /nekowhitelist reload} and {@code status}.
 *
 * <p>Permission level 4, the same as {@code /stop}: the command reaches an API key and can
 * decide who may join, which is not something an ordinary operator should reach for. The
 * console is level 4 too, so it always works there.
 */
public final class WhitelistCommand {

    private static final int PERMISSION_LEVEL = 4;

    private WhitelistCommand() {
    }

    /**
     * @param service supplies the running service, or null when the mod is idle because
     *                the config is incomplete
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<WhitelistSyncService> service) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("nekowhitelist")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL));

        root.then(Commands.literal("reload").executes(context -> {
            WhitelistSyncService running = service.get();
            if (running == null) {
                context.getSource().sendFailure(Component.literal(
                        "Neko Launcher whitelist is idle: set apiKey and instance in "
                                + "config/neko-whitelist.json, then restart."));
                return 0;
            }

            running.syncNow();
            context.getSource().sendSuccess(
                    () -> Component.literal("Syncing the whitelist from Neko Launcher..."), true);
            return 1;
        }));

        root.then(Commands.literal("status").executes(context -> {
            WhitelistSyncService running = service.get();
            String message = running == null
                    ? "Neko Launcher whitelist: idle (apiKey or instance not set)."
                    : running.status();
            context.getSource().sendSuccess(() -> Component.literal(message), false);
            return 1;
        }));

        dispatcher.register(root);
    }
}
