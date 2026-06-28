package com.example.create_schematic_compute;

import com.example.create_schematic_compute.blocks.*;
import com.example.create_schematic_compute.client.ClientSetup;
import com.example.create_schematic_compute.entity.ControlSeatEntity;
import com.example.create_schematic_compute.items.PortableTerminalItem;
import com.simibubi.create.api.schematic.nbt.SafeNbtWriterRegistry;
import com.simibubi.create.api.schematic.nbt.SafeNbtWriterRegistry.SafeNbtWriter;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SchematicCompute.MOD_ID)
public class SchematicCompute {
    public static final String MOD_ID = "create_schematic_compute";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredHolder<Block, BlueprintBlock> BLUEPRINT_BLOCK =
            BLOCKS.register("blueprint", () -> new BlueprintBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> BLUEPRINT_ITEM =
            ITEMS.register("blueprint", () -> new BlockItem(BLUEPRINT_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlueprintBlockEntity>> BLUEPRINT_BE =
            BLOCK_ENTITIES.register("blueprint", () -> BlockEntityType.Builder.of(BlueprintBlockEntity::new, BLUEPRINT_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<BlueprintMenu>> BLUEPRINT_MENU =
            MENUS.register("blueprint", () -> IMenuTypeExtension.create((id, inv, buf) -> new BlueprintMenu(id, inv, buf)));

    // 转速代理控制器
    public static final DeferredHolder<Block, SpeedProxyBlock> SPEED_PROXY_BLOCK =
            BLOCKS.register("speed_proxy", () -> new SpeedProxyBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> SPEED_PROXY_ITEM =
            ITEMS.register("speed_proxy", () -> new BlockItem(SPEED_PROXY_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpeedProxyBlockEntity>> SPEED_PROXY_BE =
            BLOCK_ENTITIES.register("speed_proxy", () -> BlockEntityType.Builder.of(SpeedProxyBlockEntity::new, SPEED_PROXY_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<SpeedProxyMenu>> SPEED_PROXY_MENU =
            MENUS.register("speed_proxy", () -> IMenuTypeExtension.create((id, inv, buf) -> new SpeedProxyMenu(id, inv, buf)));

    // 编程计算机
    public static final DeferredHolder<Block, ProgramComputerBlock> PROGRAM_BLOCK =
            BLOCKS.register("program_computer", () -> new ProgramComputerBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> PROGRAM_ITEM =
            ITEMS.register("program_computer", () -> new BlockItem(PROGRAM_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProgramComputerBlockEntity>> PROGRAM_BE =
            BLOCK_ENTITIES.register("program_computer", () -> BlockEntityType.Builder.of(ProgramComputerBlockEntity::new, PROGRAM_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<ProgramComputerMenu>> PROGRAM_MENU =
            MENUS.register("program_computer", () -> IMenuTypeExtension.create((id, inv, buf) -> new ProgramComputerMenu(id, inv, buf)));

    // 姿态传感器
    public static final DeferredHolder<Block, SensorBlock> SENSOR_BLOCK =
            BLOCKS.register("sensor", () -> new SensorBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> SENSOR_ITEM =
            ITEMS.register("sensor", () -> new BlockItem(SENSOR_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SensorBlockEntity>> SENSOR_BE =
            BLOCK_ENTITIES.register("sensor", () -> BlockEntityType.Builder.of(SensorBlockEntity::create, SENSOR_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<SensorMenu>> SENSOR_MENU =
            MENUS.register("sensor", () -> IMenuTypeExtension.create((id, inv, buf) -> new SensorMenu(id, inv, buf)));

    // 控制座椅
    public static final DeferredHolder<Block, ControlSeatBlock> CONTROL_SEAT_BLOCK =
            BLOCKS.register("control_seat", () -> new ControlSeatBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> CONTROL_SEAT_ITEM =
            ITEMS.register("control_seat", () -> new BlockItem(CONTROL_SEAT_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControlSeatBlockEntity>> CONTROL_SEAT_BE =
            BLOCK_ENTITIES.register("control_seat", () -> BlockEntityType.Builder.of(ControlSeatBlockEntity::create, CONTROL_SEAT_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<ControlSeatMenu>> CONTROL_SEAT_MENU =
            MENUS.register("control_seat", () -> IMenuTypeExtension.create((id, inv, buf) -> new ControlSeatMenu(id, inv, buf)));
    public static final DeferredHolder<EntityType<?>, EntityType<ControlSeatEntity>> CONTROL_SEAT_ENTITY =
            ENTITIES.register("control_seat", () -> EntityType.Builder.of(ControlSeatEntity::new, MobCategory.MISC)
                    .sized(0.001f, 0.001f).noSummon().noSave().build("control_seat"));

    // 显示器
    public static final DeferredHolder<Block, MonitorBlock> MONITOR_BLOCK =
            BLOCKS.register("monitor", () -> new MonitorBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> MONITOR_ITEM =
            ITEMS.register("monitor", () -> new BlockItem(MONITOR_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>> MONITOR_BE =
            BLOCK_ENTITIES.register("monitor", () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, MONITOR_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<MonitorMenu>> MONITOR_MENU =
            MENUS.register("monitor", () -> IMenuTypeExtension.create((id, inv, buf) -> new MonitorMenu(id, inv, buf)));

    // 雷达方块
    public static final DeferredHolder<Block, RadarBlock> RADAR_BLOCK =
            BLOCKS.register("radar", () -> new RadarBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> RADAR_ITEM =
            ITEMS.register("radar", () -> new BlockItem(RADAR_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarBlockEntity>> RADAR_BE =
            BLOCK_ENTITIES.register("radar", () -> BlockEntityType.Builder.of(RadarBlockEntity::create, RADAR_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<RadarMenu>> RADAR_MENU =
            MENUS.register("radar", () -> IMenuTypeExtension.create((id, inv, buf) -> new RadarMenu(id, inv, buf)));

    // v1.2.2: 便携终端物品
    public static final DeferredHolder<Item, PortableTerminalItem> PORTABLE_TERMINAL =
            ITEMS.register("portable_terminal", () -> new PortableTerminalItem(new Item.Properties()));

    public SchematicCompute(IEventBus modEventBus) {
        LOGGER.info("{} initializing...", MOD_ID);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        ENTITIES.register(modEventBus);
        TABS.register(modEventBus);

        TABS.register("main", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup." + MOD_ID))
                .icon(() -> new ItemStack(BLUEPRINT_ITEM.get()))
                .displayItems((params, output) -> {
                    output.accept(BLUEPRINT_ITEM.get());
                    output.accept(SPEED_PROXY_ITEM.get());
                    output.accept(PROGRAM_ITEM.get());
                    output.accept(CONTROL_SEAT_ITEM.get());
                    output.accept(SENSOR_ITEM.get());
                    output.accept(MONITOR_ITEM.get());
                    output.accept(RADAR_ITEM.get());
                    output.accept(PORTABLE_TERMINAL.get());
                }).build());

        // 在注册事件完成后再注册 SafeNbtWriter
        modEventBus.addListener(net.neoforged.neoforge.registries.RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(Registries.BLOCK_ENTITY_TYPE)) {
                registerSafeNbtWriters();
            }
        });

        // 注册方块实体渲染器 (仅客户端)
        modEventBus.addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class, event -> {
            event.enqueueWork(() -> {
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                    MONITOR_BE.get(),
                    com.example.create_schematic_compute.client.renderer.MonitorBlockEntityRenderer::new
                );
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                    RADAR_BE.get(),
                    com.example.create_schematic_compute.client.renderer.RadarBlockEntityRenderer::new
                );
            });
        });

        // 服务器停止时清理全局状态（防止跨世界污染）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.neoforge.event.server.ServerStoppingEvent.class, event -> {
                ControlSeatBlockEntity.clearAllInputs();
                com.example.create_schematic_compute.radar.TargetAssignment.clearAll();
                com.example.create_schematic_compute.network.SignalBus.clear();
                LOGGER.info("{} cleared static state for server shutdown", MOD_ID);
            });
        // 玩家断开时清理其残留输入（防止下次重连时泄漏）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class, event -> {
                ControlSeatBlockEntity.clearPlayerInput(event.getEntity().getUUID());
            });

        LOGGER.info("{} loaded!", MOD_ID);
    }

    /** 注册 SafeNbtWriter — 让 Create 蓝图系统能保存我们的方块实体 NBT */
    private static void registerSafeNbtWriters() {
        SafeNbtWriter writer = (be, tag, registries) -> {
            var full = be.saveWithFullMetadata(registries);
            for (var key : full.getAllKeys()) {
                if (!key.equals("id") && !key.equals("x") && !key.equals("y") && !key.equals("z")) {
                    tag.put(key, full.get(key));
                }
            }
        };
        SafeNbtWriterRegistry.REGISTRY.register(BLUEPRINT_BE.get(), writer);
        SafeNbtWriterRegistry.REGISTRY.register(SPEED_PROXY_BE.get(), writer);
        SafeNbtWriterRegistry.REGISTRY.register(PROGRAM_BE.get(), writer);
        SafeNbtWriterRegistry.REGISTRY.register(CONTROL_SEAT_BE.get(), writer);
        SafeNbtWriterRegistry.REGISTRY.register(SENSOR_BE.get(), writer);
        SafeNbtWriterRegistry.REGISTRY.register(MONITOR_BE.get(), writer);
        SafeNbtWriterRegistry.REGISTRY.register(RADAR_BE.get(), writer);
        LOGGER.info("Registered SafeNbtWriters for all computers");
    }
}
