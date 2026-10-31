package io.github.y15173334444.create_schematic_compute.client;

/**
 * 公式预算的客户端权威显示态（K6）——{@code BudgetSyncPacket} 的落点。登录同步与
 * 运行期广播都写这里；设置面板的预算选项卡消费。volatile 静态字段由网络线程直写
 * （纯写、无锁）、渲染线程直读。
 * The client-side authoritative display state for the formula budget (K6) — where
 * BudgetSyncPacket lands. Both the login sync and runtime broadcasts write here; the
 * settings panel's budget tab consumes. Volatile statics written directly by the network
 * thread (plain writes, no locks) and read by the render thread.
 */
public final class BudgetClientState {

    /** 服务端最近一次广播的预算值,-1 = 尚未收到包(回落登录同步的 CONFIG 副本)。
     *  The last broadcast budget, -1 = no packet yet (fall back to the login-synced CONFIG copy). */
    private static volatile double serverMs = -1.0;
    /** 服务端最近一次裁定的调整权限。 / the server's latest can-adjust ruling. */
    private static volatile boolean canAdjust = false;

    private BudgetClientState() {}

    /** BudgetSyncPacket 客户端入口（网络线程直调）。 / client entry for BudgetSyncPacket (network thread). */
    public static void onSync(double ms, boolean adjust) {
        serverMs = ms;
        canAdjust = adjust;
    }

    public static double serverMs() { return serverMs; }

    public static boolean canAdjust() { return canAdjust; }
}
