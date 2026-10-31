package io.github.y15173334444.create_schematic_compute.network;

/** 显示类 op 的**限流全量同步**参与方窄接口（反向包边解环，2026-10-31 拍板「MonitorBlockEntity
 *  收成请求全量同步窄接口」）：拖拽流 20Hz 的显示 op 只推 ~0.5Hz 全图 NBT——目前仅
 *  `blocks.MonitorBlockEntity` 实现；`EditSessionRegistry` 借本口认出参与方，不再
 *  `instanceof` 具体 BE 类型（E2 `EditAckSink` 同款缝-实现分离手法，窄口住消费方包）。
 *  Narrow port for the coalesced full sync on display-affecting ops (2026-10-31 ruling:
 *  "narrow the MonitorBlockEntity site to a request-full-sync interface"): 20Hz display-drag
 *  ops push the full graph NBT at ~0.5Hz — only `blocks.MonitorBlockEntity` implements it
 *  today; `EditSessionRegistry` recognises participants through this port instead of an
 *  `instanceof` on a concrete BE type (the E2 `EditAckSink` playbook — the port lives in
 *  the consumer's package).
 */
public interface CoalescedFullSync {

    /** 请求全量同步 / request a full sync. */
    void requestFullSync();
}
