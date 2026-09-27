package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 变速器的滚轮行为：官方 {@code KineticScrollValueBehaviour}（转速控制器同款）加上
 * **代理态**语义 —— 节点图在控制目标时，滚轮拒收输入、值盒显示「已应用目标 (代理)」。
 * The transmission's scroll behaviour: the official {@code KineticScrollValueBehaviour}
 * (speed-controller style) plus <b>proxied</b> semantics — while the node graph drives the
 * target, the scroll wheel refuses input and the value box reads "applied target (proxy)".
 *
 * <p><b>为什么拒收要走 {@code acceptsValueSettings}</b>：Create 在三处真检查这个闸门，
 * 缺一处就是个外观闸门：客户端输入（{@code ValueSettingsInputHandler.java:82}）、
 * 服务端收包（{@code ValueSettingsPacket.java:60}）、剪贴板复制/粘贴
 * （{@code ValueSettingsBehaviour.java:57-69}）。第三处顺带堵住「用剪贴板把滚轮值灌进来」
 * 的旁路。{@link #setValueSettings} 里的早退只是防御纵深。</p>
 * <p><b>Why the gate is {@code acceptsValueSettings}</b>: Create really checks it in three
 * places — client input ({@code ValueSettingsInputHandler.java:82}), the server packet handler
 * ({@code ValueSettingsPacket.java:60}) and clipboard copy/paste
 * ({@code ValueSettingsBehaviour.java:57-69}); the third also closes the "paste the old scroll
 * value back in" bypass. The early return in {@link #setValueSettings} is defence in depth.</p>
 *
 * <p><b>代理态下为什么还能看到值盒</b>：值盒必须继续渲染（{@code isActive()} 恒真），
 * 否则玩家既改不了值、也看不到「为什么改不了」。Create 悬停提示的第二行
 * （{@code gui.value_settings.hold_to_edit}，出厂硬编码在
 * {@code ScrollValueRenderer.java:76}）删不掉，所以由 label（第一行）改说
 * 「由节点控制」——见 {@code ProgrammableTransmissionBlockEntity.refreshScrollLabel}。</p>
 * <p><b>Why the box still renders while proxied</b>: the player must see <i>why</i> the wheel
 * does nothing. Create's second tip line is hardcoded in its renderer and cannot be removed, so
 * the label (first line) carries the correction instead.</p>
 */
public class TransmissionScrollValueBehaviour extends KineticScrollValueBehaviour {

    /** 代理态后缀（"(代理)" / "(proxy)"）。每次显示即时解析：不缓存字符串，
     *  切语言后下一帧自然生效（Create 只做 {@code literal(...)} 包装，没法走 translatable 组件）。
     *  Proxy suffix. Resolved per call — no cached string, so a language switch takes effect on
     *  the next frame (Create wraps our return value in {@code literal(...)}, so a translatable
     *  component cannot survive the trip). */
    private static final Component PROXY_SUFFIX =
            Component.translatable("container.create_schematic_compute.transmission.proxy");

    /** 宿主（读代理态与已应用目标）。 / Host (proxy flag and applied target). */
    private final ProgrammableTransmissionBlockEntity transmission;

    public TransmissionScrollValueBehaviour(Component label, ProgrammableTransmissionBlockEntity be,
                                            ValueBoxTransform slot) {
        super(label, be, slot);
        this.transmission = be;
    }

    /** 代理态拒收设置（客户端输入 / 服务端收包 / 剪贴板三处共用本闸门）。
     *  Refuse settings while proxied (all three Create check sites share this gate). */
    @Override
    public boolean acceptsValueSettings() {
        return !transmission.isProxyControlled();
    }

    /** 防御纵深：即便将来有新的调用路径绕过 {@link #acceptsValueSettings}，也不落值。
     *  Defence in depth: no value lands even if a future path bypasses the gate. */
    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
        if (transmission.isProxyControlled())
            return;
        super.setValueSettings(player, valueSetting, ctrlHeld);
    }

    /**
     * 值盒文本：手动态 = 滚轮值（官方口径），代理态 = **已应用**目标 + 后缀。
     * Value-box text: the scroll value while manual (official behaviour), the <b>applied</b>
     * target plus suffix while proxied.
     *
     * <p>代理态刻意显示已应用值（{@code CscTxApplied}，随包同步）而不是滚轮值：滚轮值此刻
     * 不生效，显示它就是在盒子里放一个假读数。也不新增「节点意图值」字段 —— 那会变成第二个
     * 需要维护的真相源。</p>
     * <p>While proxied it deliberately shows the applied target (synced in the block-entity
     * packet) rather than the scroll value, which is inert in that state — showing it would put a
     * fake reading in the box. No separate "node intent" field either: that would be a second
     * source of truth to maintain.</p>
     */
    @Override
    public String formatValue() {
        boolean proxied = transmission.isProxyControlled();
        int shown = proxied ? transmission.getAppliedTarget() : value;
        return TransmissionScrollDisplay.valueBoxText(shown, proxied, PROXY_SUFFIX.getString());
    }
}
