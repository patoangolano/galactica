package dev.galactica.ship.yard;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.galactica.ship.ShipStructure;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.Locale;

/**
 * Block-built ship commands, merged into {@code /galactica} (operators):
 * <ul>
 *   <li>{@code /galactica ship <kestrel|shuttle|freighter|explorer>}: deploys a fuelled prefab ahead and boards it
 *       (plain {@code /galactica ship} still deploys the modelled Kestrel);</li>
 *   <li>{@code /galactica shipblocks <class>}: places a prefab as blocks ahead, helm facing the player's heading, to
 *       modify and assemble by hand;</li>
 *   <li>{@code /galactica shipassemble <helm>}: what a right-click on that helm does;</li>
 *   <li>{@code /galactica shipdisassemble}: sets the nearest empty block-built ship (within 64 blocks) down.</li>
 * </ul>
 */
public final class ShipBuildCommands {
    private ShipBuildCommands() {}

    private static final SuggestionProvider<CommandSourceStack> CLASSES =
            (c, b) -> SharedSuggestionProvider.suggest(ShipTemplate.NAMES, b);

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, registry, env) -> d.register(Commands.literal("galactica")
                .then(Commands.literal("ship").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("class", StringArgumentType.word()).suggests(CLASSES).executes(ShipBuildCommands::deploy)))
                .then(Commands.literal("shipblocks").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("class", StringArgumentType.word()).suggests(CLASSES).executes(ShipBuildCommands::blocks)))
                .then(Commands.literal("shipassemble").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("helm", BlockPosArgument.blockPos()).executes(ShipBuildCommands::assemble)))
                .then(Commands.literal("shipdisassemble").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ShipBuildCommands::disassemble))));
    }

    private static String className(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "class").toLowerCase(Locale.ROOT);
        if (!ShipTemplate.NAMES.contains(name)) throw new IllegalArgumentException(name);
        return name;
    }

    private static int deploy(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String name;
        try {
            name = className(c);
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("Unknown ship class " + e.getMessage() + "; one of " + ShipTemplate.NAMES));
            return 0;
        }
        ShipStructure ship = ShipBlueprintItem.deploy(p.level(), p, name);
        if (ship == null) {
            c.getSource().sendFailure(Component.translatable("message.galactica.ship.no_room"));
            return 0;
        }
        p.startRiding(ship);
        var s = ship.spec();
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s deployed and boarded: %d blocks, %.0f t, main %.1f g, lift %.1f g, H2 %.0f, jump %.0f ly",
                name, ship.blocks().size(), s.massKg() / 1000, s.mainAccel() / 9.80665, s.liftAccel() / 9.80665,
                s.maxHydrogen(), s.jumpRangeLy())), false);
        return 1;
    }

    private static int blocks(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String name;
        try {
            name = className(c);
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("Unknown ship class " + e.getMessage()));
            return 0;
        }
        ShipTemplate t = ShipTemplate.byName(name);
        var stats = ShipStats.compute(t.cells());
        var facing = p.getDirection();
        BlockPos helm = BlockPos.containing(p.position().add(net.minecraft.world.phys.Vec3.directionFromRotation(0F, p.getYRot())
                .scale(stats.spec().hullReach() + 2))).above(-stats.minY());
        ShipTemplates.place(p.level(), name, helm, facing);
        c.getSource().sendSuccess(() -> Component.literal(name + " placed as blocks, helm at " + helm.toShortString()), false);
        return 1;
    }

    private static int assemble(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        BlockPos helm = BlockPosArgument.getLoadedBlockPos(c, "helm");
        ServerPlayer p = c.getSource().getPlayer();
        ShipStructure ship = ShipAssembler.assemble(c.getSource().getLevel(), helm, p);
        if (ship == null) {
            c.getSource().sendFailure(Component.literal("No ship assembled at " + helm.toShortString()));
            return 0;
        }
        return 1;
    }

    private static int disassemble(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var src = c.getSource();
        var at = src.getPosition();
        var ships = src.getLevel().getEntitiesOfClass(ShipStructure.class, new AABB(at, at).inflate(64), s -> true);
        ShipStructure nearest = ships.stream().min(Comparator.comparingDouble(s -> s.position().distanceToSqr(at))).orElse(null);
        if (nearest == null) {
            src.sendFailure(Component.literal("No block-built ship within 64 blocks"));
            return 0;
        }
        return ShipAssembler.disassemble(nearest, src.getPlayer()) ? 1 : 0;
    }
}
