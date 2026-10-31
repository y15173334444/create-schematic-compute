package io.github.y15173334444.cscexample;

import io.github.y15173334444.create_schematic_compute.api.CustomNodes;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create: Schematic Compute 公开 API 的第三方示例模组（验收①的参考载体）。
 * 演示三件事：
 * <ol>
 *   <li><b>注册自定义节点</b>：schema（{@link CustomNodeType}）+ 求值行为
 *       （{@code CustomNodeBehavior}）一次注册（{@link CustomNodes#register}）；</li>
 *   <li><b>名单制登记</b>：注册 ≠ 准入 —— 节点 id 还须经
 *       {@link CustomNodes#admitToHost} 登记进蓝图机的准入名单（D5 默认拒绝）；</li>
 *   <li><b>提供方直连</b>：节点在求值回调里经宿主世界读自己的方块。</li>
 * </ol>
 * 图内闭环：放置 {@link SignalProviderBlock}（右键调信号）→ 蓝图机图里放
 * {@code cscexample:signal} 节点（TEXT 参数填方块坐标 "x y z"）→ 连到 REDSTONE_OUT——
 * 第三方方块 ↔ 节点 ↔ 图计算 ↔ 输出，同 tick 双向。{@code cscexample:heavy} 是一个
 * 可恢复重节点样例（循环边界检查 {@code sliceExpired()}，进度随检查点跨 tick 续算）。
 * <p>The third-party example addon for Create: Schematic Compute's public API (the
 * reference carrier of acceptance 1). Demonstrates: (1) registering custom nodes —
 * schema plus evaluation behavior in one call; (2) roster enrolment — registering ≠
 * admitting, the node ids must also be enrolled into the blueprint host's roster
 * (D5 default-deny); (3) provider-direct exchange — the node reads its own block through
 * the host's world inside the callback. The in-game loop: place the signal provider
 * (right-click to step its signal), put the cscexample:signal node in a blueprint graph
 * (TEXT param = "x y z"), wire it to a REDSTONE_OUT — third-party block ↔ node ↔ graph ↔
 * output, same tick both ways. cscexample:heavy is the resumable heavy-node sample
 * (sliceExpired() at loop boundaries, progress rides the checkpoint across ticks).</p>
 */
@Mod(CscExampleMod.MOD_ID)
public final class CscExampleMod {

    public static final String MOD_ID = "cscexample";

    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);

    public static final DeferredBlock<SignalProviderBlock> SIGNAL_PROVIDER =
        BLOCKS.registerBlock("signal_provider", SignalProviderBlock::new);

    public static final DeferredItem<BlockItem> SIGNAL_PROVIDER_ITEM =
        ITEMS.registerSimpleBlockItem("signal_provider", SIGNAL_PROVIDER);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SignalProviderBlockEntity>> SIGNAL_PROVIDER_BE =
        BLOCK_ENTITIES.register("signal_provider", () -> BlockEntityType.Builder
            .of(SignalProviderBlockEntity::new, SIGNAL_PROVIDER.get()).build(null));

    public CscExampleMod(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);

        // ① 注册自定义节点：schema + 求值行为一次注册。
        // ① Register the custom nodes: schema + behavior in one call.
        CustomNodes.register(ExampleNodes.signalSchema(), ExampleNodes.signalBehavior());
        CustomNodes.register(ExampleNodes.heavySchema(), ExampleNodes.heavyBehavior());

        // ② 名单制登记：注册 ≠ 准入 —— id 显式登记进蓝图机的准入名单。
        // ② Roster enrolment: registering ≠ admitting — enrol the ids into the
        // blueprint host's roster explicitly.
        CustomNodes.admitToHost("cscexample:signal", CustomNodes.HOST_BLUEPRINT);
        CustomNodes.admitToHost("cscexample:heavy", CustomNodes.HOST_BLUEPRINT);
    }
}
