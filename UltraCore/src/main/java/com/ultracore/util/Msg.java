package com.ultracore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

public final class Msg {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private Msg() {}

    public static Component c(String legacy) { return LEGACY.deserialize(legacy); }
    public static void send(CommandSender to, String legacy) { to.sendMessage(c(legacy)); }
}
