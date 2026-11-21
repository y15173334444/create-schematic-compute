package io.github.y15173334444.create_schematic_compute.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;

/**
 * 引擎柄（宿主套件波 2）：{@code HostGraphs.bind} 交给第三方宿主 BE 的不透明句柄，
 * 也是宿主与引擎之间的全部驱动面 —— tick 推进、NBT 读写（含客户端包的全量同步门控，
 * 组合线同款）与生命周期转发。宿主 BE 自己的 tick()/write()/read()/onLoad()/
 * onChunkUnloaded()/remove()（或 setRemoved()）各一行转发到这里即可，图域对象
 * 永不经第三方之手。
 * The engine handle (host suite, wave 2): the opaque handle {@code HostGraphs.bind}
 * hands to the third-party host BE, and the entire drive surface between host and
 * engine — tick advancement, NBT read/write (with the client-packet full-sync gate,
 * the composition line's policy), and lifecycle forwards. The host BE's tick() /
 * write() / read() / onLoad() / onChunkUnloaded() / remove() (or setRemoved()) each
 * forward one line here; graph-domain objects never reach third-party code.
 *
 * <p><b>NBT/同步接线口径</b>（第三方 BE 的原版管线写法）：{@code getUpdateTag(r)} =
 * {@code handle.save(new CompoundTag(), r, false)}（全图随包，SyncedGraphBlockEntity
 * 口径）；{@code getUpdatePacket()} = {@code ClientboundBlockEntityDataPacket.create(this)}；
 * {@code handleUpdateTag(tag, r)} = {@code handle.load(tag, r)}。继承 Create 系 BE
 * （write/read 带 clientPacket）的宿主照组合线：write 里调 {@link #save}、read 里调
 * {@link #load}，门控已在柄内。</p>
 * <p><b>NBT/sync wiring</b> (the vanilla pipeline for third-party BEs): {@code
 * getUpdateTag(r)} = {@code handle.save(new CompoundTag(), r, false)} (full graph on
 * the wire); {@code getUpdatePacket()} = {@code ClientboundBlockEntityDataPacket.create(this)};
 * {@code handleUpdateTag(tag, r)} = {@code handle.load(tag, r)}. Hosts extending Create
 * BEs (whose write/read carry clientPacket) follow the composition line: call {@link #save}
 * in write, {@link #load} in read — the gate lives inside the handle.</p>
 */
public interface HostGraphHandle {

    /** 每 tick 推进（BE 的 tick() 转发位；Create 系宿主先调自己的 super.tick()）。
     *  Per-tick advancement (forward from the BE's tick(); Create hosts call their own
     *  super.tick() first). */
    void tick();

    /** 写图/运行/运行时 NBT（客户端包仅在显式全量同步挂起时携带整图——组合线门控
     *  口径，其余包只带 running 标志）。Write graph/running/runtime NBT (client packets
     *  carry the full graph only while an explicit full sync is pending — the
     *  composition-line gate; other packets carry just the running flag). */
    void save(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket);

    /** 读图/运行/运行时 NBT（回弹保护在引擎内）。Read graph/running/runtime NBT (the
     *  bounce-back protection lives in the engine). */
    void load(CompoundTag tag, HolderLookup.Provider registries);

    /** BE 加入世界（onLoad 转发位）。The BE joined the world (forward from onLoad). */
    void onHostLoad();

    /** 区块休眠（onChunkUnloaded 转发位）。The chunk went dormant (forward from
     *  onChunkUnloaded). */
    void onHostChunkUnloaded();

    /** BE 拆除/替换（remove 或 setRemoved 转发位；顺带注销适配器登记与编辑会话）。
     *  The BE was broken/replaced (forward from remove or setRemoved; also deregisters
     *  the adapter mapping and the edit session). */
    void onHostRemoved();

    /** 停机收尾（{@link HostGraphBehavior#stopBody} 的默认实现走这里）。
     *  The stop epilogue (the default {@link HostGraphBehavior#stopBody} rides this). */
    void stopRunning();
}
