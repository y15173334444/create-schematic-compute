package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 侧栏终端控制台的喂料源：镜像客户端收到的聊天与系统消息（命令反馈、加入/退出提示都在内，
 * {@code isSystem()} 一并处理）。环形缓冲 200 行，全局共享——多开编辑器看同一条流。
 * Feed for the sidebar terminal console: mirrors chat and system messages the client
 * receives (command feedback and join/leave notices included — handled via the same
 * {@code isSystem()} event). A 200-line ring buffer, shared globally — multiple open
 * editors see the same stream.
 */
@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
final class SidebarConsoleFeed {
    private static final int MAX_LINES = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private SidebarConsoleFeed() {}

    /** 追加一段文本（按换行拆行，空行丢弃）。/ Append text (split on newlines, blanks dropped). */
    static synchronized void append(String s) {
        for (String part : s.split("\n")) {
            if (part.isBlank()) continue;
            LINES.addLast(part);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        }
    }

    /** 行快照（渲染用）。/ Snapshot of the lines (for rendering). */
    static synchronized List<String> snapshot() { return new ArrayList<>(LINES); }

    @SubscribeEvent
    private static void onChatReceived(ClientChatReceivedEvent e) {
        append(e.getMessage().getString());
    }
}
