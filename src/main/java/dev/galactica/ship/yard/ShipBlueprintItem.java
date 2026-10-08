package dev.galactica.ship.yard;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipSpec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deploys a prefab ship ({@link ShipTemplate}): standing on the ground in front of the player, nose along the
 * player's heading, far enough out that no block of it lands on them; in open space, floating ahead. Tanks full.
 * Consumed outside creative.
 */
public class ShipBlueprintItem extends Item {
    public static final Map<String, ShipBlueprintItem> BY_NAME = new LinkedHashMap<>();
    private final String template;

    public ShipBlueprintItem(Properties props, String template) {
        super(props);
        this.template = template;
    }

    public String template() {
        return template;
    }

    public static void init() {
        for (String name : ShipTemplate.NAMES) {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Galactica.id("ship_blueprint_" + name));
            BY_NAME.put(name, Registry.register(BuiltInRegistries.ITEM, key,
                    new ShipBlueprintItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON).setId(key), name)));
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        ItemStack stack = player.getItemInHand(hand);
        if (deploy(sl, player, template) == null) {
            player.sendOverlayMessage(Component.translatable("message.galactica.ship.no_room").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        stack.consume(1, player);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Places a prefab ahead of {@code player} if its hull fits there; null otherwise. Shared with
     * {@code /galactica ship <class>}.
     */
    public static dev.galactica.ship.ShipStructure deploy(ServerLevel level, Player player, String template) {
        ShipTemplate t = ShipTemplate.byName(template);
        ShipStats.Result stats = ShipStats.compute(t.cells());
        ShipSpec spec = stats.spec();
        Direction facing = player.getDirection();
        Vec3 look = Vec3.directionFromRotation(0F, player.getYRot());
        double out = spec.hullReach() + 2.0;
        Vec3 centre = player.position().add(look.scale(out));
        // Ground under the spot (up to 24 blocks down), else hang it level with the player.
        Vec3 top = new Vec3(centre.x, player.getY() + 4, centre.z);
        BlockHitResult hit = level.clip(new ClipContext(top, top.add(0, -28, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double floor = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : player.getY();
        // The centre of mass sits over the spot; the lowest cell stands on the floor.
        double[] t2 = ShipAssembler.turn(ShipAssembler.toWorld(facing), stats.com().x - 0.5, stats.com().z - 0.5);
        Vec3 helmBottom = new Vec3(Math.floor(centre.x - t2[0]) + 0.5, Math.ceil(floor) - stats.minY(), Math.floor(centre.z - t2[1]) + 0.5);
        var ship = ShipTemplates.spawn(level, template, helmBottom, facing);
        if (ship == null) return null;
        if (!level.noCollision(ship, ship.getBoundingBox()) || !ship.hullClearAt(level, ship.position(), ship.attitude())) {
            ship.discard();
            return null;
        }
        return ship;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> out, net.minecraft.world.item.TooltipFlag flag) {
        out.accept(Component.translatable("tooltip.galactica.ship_blueprint").withStyle(ChatFormatting.GRAY));
    }
}
