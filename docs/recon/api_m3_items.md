Cheat-sheet: MC 26.3 Fabric items, blocks, recipes, keys and screens (adversarially re-verified)

**Compile check result: PASSED on the Loom-transformed classpath, as expected FAILED on the raw merged jar.**

- **Command:** `javac --release 25 -proc:none` using JDK `C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot`. The classpath has 114 jars from `m3dec/cp.txt`: Fabric API 0.161.0 modules, fabric-loader 0.19.5, DFU, brigadier, joml, guava, slf4j, netty, fastutil, gson, authlib and logging.
- **Probe sources:** `C:/Users/Manec/AppData/Local/Temp/claude/C--Users-Manec/00f1c288-802c-470c-9e12-011d6cf62811/scratchpad/apicheck_items/src/dev/galactica/probe/`. These are the original 6 files plus a new `Extra.java`, which covers every remaining signature in this sheet.
- **Against `minecraft-merged-7e9a32a5b8-26.3.jar` (Loom-transformed, access-widened): exit 0.** Output is in `apicheck_items/outL`.
- **Against the raw `minecraft-merged-deobf-26.3.jar`: exit 1, with 3 errors.** All 3 are access errors that Fabric's classtweaker fixes, so they are expected:
  - `CreativeModeTabs.TOOLS_AND_UTILITIES has private access`
  - `CreativeModeTab.Output has protected access`, raised twice in the `displayItems((params, out) -> ...)` lambda. This was missing from the original sheet.
- The `fabric-creative-tab-api-v1-5.0.21` classtweaker contains `transitive-accessible field CreativeModeTabs TOOLS_AND_UTILITIES` and `transitive-accessible class CreativeModeTab$Output`. Mods depending on Fabric API therefore compile and run correctly.

## Breaking 26.3 surprises

1. **FIXED. Item and block registration helpers are private.**
   - `Items.registerItem` overloads take `ResourceKey<Item>` or `BlockItemId`.
   - `Items.registerBlock` overloads take `BlockItemId`.
   - `Blocks.register` is private and takes `BlockItemId` or `ResourceKey<Block>`.
   - `net.minecraft.references.ItemIds` holds `ResourceKey<Item>` constants and `BlockItemIds` holds `BlockItemId` constants. They are constant holders, not parameter types.
   - Copy the helper bodies shown in section 1.
2. **CONFIRMED.** Vanilla entity type constants are in `net.minecraft.world.entity.EntityTypes`.
   - `EntityTypes.ARMOR_STAND` is `EntityType<ArmorStand>`, and `EntityTypes.OAK_BOAT` exists.
   - `EntityType` keeps its instance methods and static methods.
3. **CONFIRMED.** Screens go through `Gui`.
   - `Minecraft` has no `setScreen` and no `screen()`.
   - `Gui` has `public Screen screen()` and `public void setScreen(Screen)`.
   - `Screen.onClose()` is `this.minecraft.gui.setScreen(null)`.
   - `Minecraft.setScreenAndShow(Screen)` exists.
4. **CONFIRMED (javap -constants).** Key codes are SDL scancodes: `KEY_W=26`, `KEY_M=16`, `KEY_SPACE=44`, `KEY_LSHIFT=225`, `KEY_G=10`, `KEY_V=25`, `KEY_R=21`. Always use `InputConstants.KEY_*`.
5. **FIXED (more is widened than the original said).** In vanilla, the creative tab keys are `private static final` and `CreativeModeTab.Output` is protected.
   - Fabric's classtweaker makes both transitive-accessible. You must depend on `fabric-creative-tab-api-v1` (or the full fabric-api) for them to compile.
   - The replacement for the old `ItemGroupEvents` is `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents`.
6. **CONFIRMED.** `Item.appendHoverText` is `@Deprecated` (decompiled `Item.java:349`). `ItemStack.java:836` still calls `getItem().appendHoverText(...)`, and javac reports a deprecation note.
7. **CONFIRMED.** The `Item` constructor runs a check when `SharedConstants.IS_RUNNING_IN_IDE` is true. If the class simple name does not end with `"Item"`, it logs `LOGGER.error("Item classes should end with Item and {} doesn't.")`.

---

## 1. Items

### Signatures (all CONFIRMED by compile)

- `Item(Item.Properties)`
- `Item.Properties#setId(ResourceKey<Item>)` is required. The NPE message "Item id not set" was not re-checked.
- `Item.Properties` builder methods, all compiled in one chain:
  - `stacksTo`, `durability`, `rarity(Rarity)`, `fireResistant()`
  - `component(type, val)`, `useBlockDescriptionPrefix()`, `useItemDescriptionPrefix()`
  - `equippable(EquipmentSlot)`, `humanoidArmor(ArmorMaterial, ArmorType)`, `useCooldown(float)`
- `public InteractionResult use(Level, Player, InteractionHand)`
- `public InteractionResult useOn(UseOnContext)`
- `public void inventoryTick(ItemStack, ServerLevel, Entity, EquipmentSlot)`, overridden in `Extra.TickItem`.
- `isBarVisible`, `getBarWidth` and `getBarColor` each take `(ItemStack)`.
- `getPlayerPOVHitResult(Level, Player, ClipContext.Fluid)` is accessible from a subclass.
- `InteractionResult` constants: `SUCCESS`, `SUCCESS_SERVER`, `CONSUME`, `FAIL`, `PASS`, `TRY_WITH_EMPTY_HAND`.
- `Registry.register(registry, ResourceKey, value)`
- `Registry.register(registry, Identifier, value)`
- `ResourceKey.create(Registries.ITEM, Identifier)`

### Registration helper (CONFIRMED)

```java
static Identifier id(String p){ return Identifier.fromNamespaceAndPath("galactica", p); }
static Item item(String name, Function<Item.Properties, Item> factory, Item.Properties props) {
    ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
    Item it = factory.apply(props.setId(key));
    if (it instanceof BlockItem bi) bi.registerBlocks(Item.BY_BLOCK, it);
    return Registry.register(BuiltInRegistries.ITEM, key, it);
}
public static final Item OXYGEN_TANK = item("oxygen_tank", OxygenTankItem::new, new Item.Properties().stacksTo(1));
```

### Creative tabs (fabric-creative-tab-api-v1 5.0.21): CONFIRMED by javap and compile

- `CreativeModeTabEvents.modifyOutputEvent(ResourceKey<CreativeModeTab>)` returns `Event<ModifyOutput>`, and the callback receives a `FabricCreativeModeTabOutput`.
- `FabricCreativeModeTabOutput` implements `CreativeModeTab.Output`. Its methods:
  - `accept(...)`
  - `prepend(ItemLike | ItemStack [, TabVisibility])`
  - `insertAfter` and `insertBefore`, taking `(ItemLike | ItemStack, ItemLike... | ItemStack... | Collection [, TabVisibility])`, or `(Predicate<ItemStack>, Collection, TabVisibility)`
- `FabricCreativeModeTab.builder()` returns `CreativeModeTab.Builder`.

```java
CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(out -> {
    out.accept(OXYGEN_TANK);
    out.insertAfter(Items.FLINT_AND_STEEL, ROCKET);
});
public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("main"),
    FabricCreativeModeTab.builder().title(Component.translatable("itemGroup.galactica"))
        .icon(() -> new ItemStack(ROCKET))
        .displayItems((params, out) -> { out.accept(ROCKET); out.accept(OXYGEN_TANK); })
        .build());
```

### Item that spawns an entity: CONFIRMED

- `EntityType<T>#create(Level, EntitySpawnReason)` returns `T`. This was verified as `ArmorStand` from `EntityTypes.ARMOR_STAND.create(...)`, so a typed `EntityType<RocketEntity>` returns `RocketEntity`.
- `EntityType.createDefaultStackConfig(sl, stack, player).apply(e)`
- `ItemStack#consume(int, LivingEntity)`
- `Entity#snapTo(x, y, z, yRot, xRot)`
- `level.noCollision(e, box)`
- `sl.gameEvent(player, GameEvent.ENTITY_PLACE, vec)`

```java
@Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
    ItemStack stack = player.getItemInHand(hand);
    BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
    if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
    RocketEntity e = ModEntities.ROCKET.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
    if (e == null) return InteractionResult.FAIL;
    Vec3 at = hit.getLocation();
    e.snapTo(at.x, at.y, at.z, player.getYRot(), 0.0F);
    if (!level.noCollision(e, e.getBoundingBox())) return InteractionResult.FAIL;
    if (level instanceof ServerLevel sl) {
        EntityType.createDefaultStackConfig(sl, stack, player).apply(e);
        sl.addFreshEntity(e);
        sl.gameEvent(player, GameEvent.ENTITY_PLACE, at);
        stack.consume(1, player);
    }
    return InteractionResult.SUCCESS;
}
```

### Assets: CONFIRMED against vanilla `oak_boat` JSON read from the jar

- `assets/galactica/items/oxygen_tank.json`:
  `{"model":{"type":"minecraft:model","model":"galactica:item/oxygen_tank"}}`
- `assets/galactica/models/item/oxygen_tank.json`:
  `{"parent":"minecraft:item/generated","textures":{"layer0":"galactica:item/oxygen_tank"}}`
- Texture: `assets/galactica/textures/item/oxygen_tank.png`

### Lang

UNCONFIRMED: the key format was not re-checked. These are the standard keys:
- Items: `item.galactica.<id>`
- Blocks and block items: `block.galactica.<id>`
- Tab: whatever key you pass, for example `itemGroup.galactica`

## 2. Armor and space suit: CONFIRMED

### Signatures

- `ArmorMaterial` is a record. Its constructor is confirmed by javap:
  `(int, Map<ArmorType,Integer>, int, Holder<SoundEvent>, float, float, TagKey<Item>, ResourceKey<EquipmentAsset>)`
- The following compile:
  - `ArmorType.HELMET`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`, `BODY`
  - `humanoidArmor(material, type)`
  - `EquipmentAssets.ROOT_ID`
  - `SoundEvents.ARMOR_EQUIP_IRON`
- Also compiled:
  - `ItemStack.is(Item)`
  - `getItemBySlot(EquipmentSlot)`
  - `setItemSlot(EquipmentSlot, ItemStack)`
- UNCONFIRMED, not re-checked:
  - What `humanoidArmor` sets internally.
  - The bootstrap-lookup timing of `repairable(TagKey)`.

```java
public static final ResourceKey<EquipmentAsset> SPACE_SUIT_ASSET = ResourceKey.create(EquipmentAssets.ROOT_ID, id("space_suit"));
public static final TagKey<Item> REPAIRS_SPACE_SUIT = TagKey.create(Registries.ITEM, id("repairs_space_suit"));
static Map<ArmorType,Integer> defense(){ Map<ArmorType,Integer> m=new EnumMap<>(ArmorType.class);
    m.put(ArmorType.BOOTS,2); m.put(ArmorType.LEGGINGS,4); m.put(ArmorType.CHESTPLATE,5); m.put(ArmorType.HELMET,2); m.put(ArmorType.BODY,5); return m; }
public static final ArmorMaterial SPACE_SUIT = new ArmorMaterial(20, defense(), 10, SoundEvents.ARMOR_EQUIP_IRON, 0.0F, 0.0F, REPAIRS_SPACE_SUIT, SPACE_SUIT_ASSET);
public static final Item SPACE_HELMET = item("space_helmet", Item::new, new Item.Properties().humanoidArmor(SPACE_SUIT, ArmorType.HELMET));
// likewise CHESTPLATE / LEGGINGS / BOOTS
public static boolean wearsFullSuit(Player p) {
    return p.getItemBySlot(EquipmentSlot.HEAD).is(SPACE_HELMET) && p.getItemBySlot(EquipmentSlot.CHEST).is(SPACE_CHESTPLATE)
        && p.getItemBySlot(EquipmentSlot.LEGS).is(SPACE_LEGGINGS) && p.getItemBySlot(EquipmentSlot.FEET).is(SPACE_BOOTS);
}
```

### Files

- **CONFIRMED.** `assets/galactica/equipment/space_suit.json`:
  `{"layers":{"humanoid":[{"texture":"galactica:space_suit"}],"humanoid_leggings":[{"texture":"galactica:space_suit"}],"humanoid_baby":[{"texture":"galactica:space_suit"}]}}`
  - **FIXED:** add `humanoid_baby`. Vanilla `iron.json` ships `humanoid`, `humanoid_baby`, `humanoid_leggings`, `horse_body` and `nautilus_body`, plus `trim_overrides`.
- **CONFIRMED.** The texture path is built by `EquipmentClientInfo` line 127 as `textures/entity/equipment/<layerType>/<path>.png`. The vanilla jar has `humanoid/iron.png`, `humanoid_baby/iron.png` and `humanoid_leggings/iron.png`.
  - `assets/galactica/textures/entity/equipment/humanoid/space_suit.png`
  - `assets/galactica/textures/entity/equipment/humanoid_leggings/space_suit.png`
  - `assets/galactica/textures/entity/equipment/humanoid_baby/space_suit.png`, optional
- **UNCONFIRMED in game:** what happens when the baby layer is missing. The expectation is that nothing is drawn on babies.
- Repair tag: `data/galactica/tags/item/repairs_space_suit.json` containing `{"values":["minecraft:iron_ingot"]}`.
- Each piece also needs `items/<piece>.json` and `models/item/<piece>.json`.

## 3. Blocks: CONFIRMED

### Signatures (compiled)

- `BlockBehaviour.Properties.of()` with `.mapColor`, `.requiresCorrectToolForDrops()`, `.strength(float, float)`, `.sound(SoundType)` and `.setId(ResourceKey<Block>)`.
- `BlockItem(Block, Item.Properties)`.
- `.lightLevel`, `.noOcclusion`, `.destroyTime` and `.strength(float)` were not compiled. Their status is UNCONFIRMED (standard methods).

```java
static Block block(String name, Function<BlockBehaviour.Properties, Block> f, BlockBehaviour.Properties props) {
    ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id(name));
    return Registry.register(BuiltInRegistries.BLOCK, key, f.apply(props.setId(key)));
}
public static final Block LAUNCH_PAD = block("launch_pad", Block::new,
    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).requiresCorrectToolForDrops().strength(5.0F, 6.0F).sound(SoundType.IRON));
public static final Item LAUNCH_PAD_ITEM = item("launch_pad", p -> new BlockItem(LAUNCH_PAD, p), new Item.Properties().useBlockDescriptionPrefix());
```

Register blocks before their items.

### Files: CONFIRMED verbatim against vanilla `iron_block` read from the jar

- `assets/galactica/blockstates/launch_pad.json`:
  `{"variants":{"":{"model":"galactica:block/launch_pad"}}}`
- `assets/galactica/models/block/launch_pad.json`:
  `{"parent":"minecraft:block/cube_all","textures":{"all":"galactica:block/launch_pad"}}`
- `assets/galactica/items/launch_pad.json`:
  `{"model":{"type":"minecraft:model","model":"galactica:block/launch_pad"}}`
- `data/galactica/loot_table/blocks/launch_pad.json`. The singular `"condition"` key is confirmed in vanilla `iron_block.json`:
  `{"type":"minecraft:block","pools":[{"condition":{"type":"minecraft:survives_explosion"},"entries":[{"type":"minecraft:item","name":"galactica:launch_pad"}],"rolls":1}],"random_sequence":"galactica:blocks/launch_pad"}`
- Tags: `data/minecraft/tags/block/mineable/pickaxe.json` and `needs_stone_tool.json`. Both paths are confirmed in the jar.

## 4. Recipes: CONFIRMED against vanilla `iron_helmet.json` and `oak_planks.json`

- Shaped:
  `{"type":"minecraft:crafting_shaped","category":"equipment","key":{"X":"minecraft:iron_ingot","G":"minecraft:glass"},"pattern":["XXX","XGX"],"result":{"id":"galactica:space_helmet"}}`
- Shapeless:
  `{"type":"minecraft:crafting_shapeless","category":"equipment","ingredients":["minecraft:iron_ingot","minecraft:flint"],"result":{"id":"galactica:oxygen_tank"}}`
- Tag ingredient with a count:
  `"ingredients":["#minecraft:oak_logs"],"result":{"count":4,"id":"..."}`
  Vanilla `oak_planks` also has `"group":"planks"`.

## 5. Data components: CONFIRMED (compiled)

### Signatures

- `DataComponentType.<T>builder()` with `.persistent(Codec)`, `.networkSynchronized(StreamCodec)`, `.ignoreSwapAnimation()`, `.cacheEncoding()` and `.build()`.
- Codecs: `ExtraCodecs.NON_NEGATIVE_INT`, `POSITIVE_INT`, `intRange(int,int)`.
- Stream codecs: `ByteBufCodecs.VAR_INT`, `INT`, `FLOAT`, `DOUBLE`, `BOOL`.
- `ItemStack` methods:
  - `get`, `getOrDefault`, `has`
  - `set`, which returns the old value
  - `update(type, def, UnaryOperator)`
  - `remove`

```java
public static final DataComponentType<Integer> OXYGEN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("oxygen"),
    DataComponentType.<Integer>builder().persistent(ExtraCodecs.NON_NEGATIVE_INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
int o2 = stack.getOrDefault(OXYGEN, 0); stack.set(OXYGEN, o2 - 1);
```

### Tooltip, option A: CONFIRMED (deprecated, still called)

```java
@Override public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
    out.accept(Component.translatable("tooltip.galactica.oxygen", stack.getOrDefault(OXYGEN, 0) / 20).withStyle(ChatFormatting.AQUA));
}
```

### Tooltip, option B: CONFIRMED (javap and compile)

- `TooltipProvider.addToTooltip(Item.TooltipContext, Consumer<Component>, TooltipFlag, DataComponentGetter)`.
- `ItemComponentTooltipProviderRegistry` has `addFirst`, `addLast`, `addBefore(DataComponentType<?>, type)` and `addAfter(DataComponentType<?>, type)`. Each has a generic overload `(…, DataComponentType<T>, TooltipProvider.Getter<T>)`.
- `ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> ...)` compiles. Its package is `net.fabricmc.fabric.api.client.item.v1`.

```java
public record OxygenLevel(int ticks, int capacity) implements TooltipProvider {
    public static final Codec<OxygenLevel> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("ticks").forGetter(OxygenLevel::ticks), Codec.INT.fieldOf("capacity").forGetter(OxygenLevel::capacity)).apply(i, OxygenLevel::new));
    public static final StreamCodec<ByteBuf, OxygenLevel> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, OxygenLevel::ticks, ByteBufCodecs.VAR_INT, OxygenLevel::capacity, OxygenLevel::new);
    public static final DataComponentType<OxygenLevel> TYPE = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
        Identifier.fromNamespaceAndPath("galactica","oxygen_level"),
        DataComponentType.<OxygenLevel>builder().persistent(CODEC).networkSynchronized(STREAM_CODEC).build());
    @Override public void addToTooltip(Item.TooltipContext c, Consumer<Component> out, TooltipFlag f, DataComponentGetter g) {
        out.accept(Component.translatable("tooltip.galactica.oxygen", ticks/20, capacity/20).withStyle(ChatFormatting.AQUA)); }
    public static void init(){ ItemComponentTooltipProviderRegistry.addBefore(DataComponents.ATTRIBUTE_MODIFIERS, TYPE); }
}
```

UNCONFIRMED: the cost of resyncing when `ItemStack.set` runs on an equipped armor stack every tick. Prefer decrementing every 20 ticks, or use a player attachment.

## 6. Key mappings (fabric-key-mapping-api-v1 2.0.8): CONFIRMED

### Signatures

- `KeyMappingHelper.registerKeyMapping(KeyMapping)` and `KeyMappingHelper.getBoundKeyOf(KeyMapping)` (returns `InputConstants.Key`) both compile.
- `KeyMapping` constructors, confirmed by javap:
  - `(String, int, Category)`
  - `(String, InputConstants.Type, int, Category)`
  - `(String, InputConstants.Type, int, Category, int order)`
- **FIXED (was UNCONFIRMED):** the 5th `int` is the **sort order** within the category. The 4-arg constructor passes 0. `compareTo` orders by `order` first and then by translated name.
- `consumeClick()` and `isDown()` compile.
- `KeyMapping.Category`:
  - `register(Identifier)` throws `IllegalArgumentException("Category '%s' is already registered.")`.
  - `label()` returns `Component.translatable(id.toLanguageKey("key.category"))`.
  - All built-ins compile: MOVEMENT, MISC, MULTIPLAYER, GAMEPLAY, INVENTORY, CREATIVE, SPECTATOR, DEBUG.
- `InputConstants.Type.KEYBOARD` and `MOUSE` both exist.
- `ClientTickEvents.END_CLIENT_TICK` receives a `Minecraft`.

```java
public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("galactica","main"));
public static final KeyMapping OPEN_MAP = KeyMappingHelper.registerKeyMapping(
    new KeyMapping("key.galactica.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));
ClientTickEvents.END_CLIENT_TICK.register(mc -> {
    while (OPEN_MAP.consumeClick())
        if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new StarMapScreen());
});
```

Lang entries: `"key.galactica.open_map": "Open Star Map"` and `"key.category.galactica.main": "Galactica"`.

## 7. Screens: CONFIRMED (compiled)

### Signatures

- `Screen(Component)`, `init()`, `extractRenderState(GuiGraphicsExtractor, int, int, float)` and `extractBackground(GuiGraphicsExtractor, int, int, float)` can all be overridden.
- UNCONFIRMED: the claim that `extractRenderStateWithTooltipAndSubtitles` calls the background first. It was not re-read.
- Input overrides that compile:
  - `mouseClicked(MouseButtonEvent, boolean)`, `mouseReleased(MouseButtonEvent)`, `mouseDragged(MouseButtonEvent, double, double)`, `mouseScrolled(double, double, double, double)`
  - `keyPressed(KeyEvent)`, `keyReleased(KeyEvent)`, `charTyped(CharacterEvent)`
- Event accessors:
  - `MouseButtonEvent`: `x()`, `y()`, `button()`, `modifiers()`
  - `KeyEvent`: `key()`, `keycode()`, `modifiers()`, `isEscape()`
- These override cleanly: `isPauseScreen`, `shouldCloseOnEsc`, `tick`, `removed`. `addRenderableWidget` is protected.
- `Button.builder(Component, OnPress)` with `.bounds(...)`, or with `.pos(x, y).size(w, h)`, then `.build()`.
- `GuiGraphicsExtractor` methods that compile:
  - `blit(RenderPipelines.GUI_TEXTURED, Identifier, int x, int y, float u, float v, int w, int h, int texW, int texH)`
  - `blit(Identifier, x0, y0, x1, y1, u0, u1, v0, v1)`
  - `centeredText(Font, Component, int, int, int)`
- The `GpuTextureView` and color-int overloads were not compiled. UNCONFIRMED.
- `DynamicTexture`: `new DynamicTexture(() -> "galactica:star_map", nativeImage)`, then `mc.getTextureManager().register(Identifier, tex)`, then `getPixels()` and `upload()`. All of these compile.

```java
public class StarMapScreen extends Screen {
    private static final Identifier MAP_TEX = Identifier.fromNamespaceAndPath("galactica","dynamic/star_map");
    private static final Identifier PANEL = Identifier.fromNamespaceAndPath("galactica","textures/gui/panel.png");
    public StarMapScreen(){ super(Component.translatable("screen.galactica.star_map")); }
    @Override protected void init(){ addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width/2-50, height-28, 100, 20).build()); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt){
        super.extractRenderState(g, mx, my, pt);
        int x = width/2-128, y = 20;
        g.blit(RenderPipelines.GUI_TEXTURED, PANEL, x-4, y-4, 0f, 0f, 264, 264, 264, 264);
        g.blit(MAP_TEX, x, y, x+256, y+256, 0f, 1f, 0f, 1f);
        g.centeredText(font, title, width/2, 6, 0xFFFFFFFF);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl){ if (e.button()==0){ /* e.x(), e.y() */ } return super.mouseClicked(e, dbl); }
    @Override public boolean isPauseScreen(){ return false; }
}
```

## 8. Damage, air and freezing: CONFIRMED (compile plus decompiled `LivingEntity`)

### Signatures

- `hurtServer(ServerLevel, DamageSource, float)` and `damageSources()` exist on both Entity and Level.
- All 10 `DamageSources` methods compile: `drown`, `freeze`, `inWall`, `generic`, `magic`, `starve`, `dryOut`, `outOfBorder`, `fellOutOfWorld`, `genericKill`.
- These compile: `getAirSupply`, `setAirSupply`, `getMaxAirSupply`, `getTicksFrozen`, `setTicksFrozen`, `isFullyFrozen`, `canFreeze`, `getTicksRequiredToFreeze`.
- `ServerPlayer.level()` returns `ServerLevel`.

### Vanilla behaviour (decompiled `LivingEntity`)

- **Refill:** `increaseAirSupply` is `min(air+4, max)`. It runs only when `air < max` and the eyes are not in water.
- **Drain:** `decreaseAirSupply` drops air by 1 per tick. The `OXYGEN_BONUS` (Respiration) attribute can skip a tick.
- **Drowning:** when `getAirSupply() <= -20`, vanilla sets air to 0, sends `broadcastEntityEvent(67)` and deals `drown()` for 2.0.
- **Freezing:**
  - Outside powder snow, `ticksFrozen` drops by 2 per tick.
  - Vanilla deals `freeze()` for 1.0 when `tickCount % 40 == 0 && isFullyFrozen() && canFreeze()`.
  - The `+3` below therefore nets +1 per tick, so the player is fully frozen after about 140 ticks.
- `Hud.extractAirBubbles` exists. Its show condition was not re-checked (UNCONFIRMED).

```java
ServerTickEvents.END_LEVEL_TICK.register(level -> {
    if (level.dimension() != SPACE) return;
    for (ServerPlayer p : level.players()) {
        if (p.isCreative() || p.isSpectator()) continue;
        if (wearsFullSuit(p) && hasOxygen(p)) { p.setAirSupply(p.getMaxAirSupply()); continue; }
        int air = p.getAirSupply() - 5;                 // -4 vanilla refill, -1 drain
        if (air <= -20) { p.setAirSupply(0); p.hurtServer(level, level.damageSources().drown(), 2.0F); }
        else p.setAirSupply(air);
        p.setTicksFrozen(Math.min(p.getTicksFrozen() + 3, p.getTicksRequiredToFreeze() + 20)); // net +1/t after vanilla -2 decay
    }
});
```

The `-5` overshoots by 4 on the first tick at full air, because vanilla only refills when `air < max`. That is harmless.

## Still UNCONFIRMED

- Whether a per-tick `ItemStack.set` on an armor slot costs a resync packet every tick.
- How armor renders in game without a `humanoid_baby` layer. Shipping the layer avoids the question.
- The `GuiGraphicsExtractor.blit(GpuTextureView, GpuSampler, …)` overload and the color-int overload.
- `.lightLevel`, `.noOcclusion`, `.destroyTime` and `.strength(float)`.
- The background call order in `extractRenderStateWithTooltipAndSubtitles` and the HUD air-bar condition.
- Nothing has been run in game. Everything above is checked by compile, javap or decompiled source only.