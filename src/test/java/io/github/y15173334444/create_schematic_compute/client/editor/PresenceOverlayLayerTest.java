package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协作在场层渲染层级源码锚（条目 26；GUI 绘制顺序无法 headless 观测，走源码锚——
 * CustomNodeEditorFaceTest / PixelEditorThemeAdoptionTest 同款手法）。钉住：
 * renderPresenceOverlay 必须画在节点之后、chrome（顶栏/加节点菜单/框选）之前，
 * 否则远端光标/拖线预览会压住工具条与弹窗。
 * 批 1（拖动临场帧影）追加钉住：beginFrame 在渲染几何段之前、endFrame 在 finally——
 * 帧影必须与还原成对出现，临场值绝不允许滞留到帧外。
 */
class PresenceOverlayLayerTest {

    private static final String EDITOR =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java";

    @Test
    @DisplayName("在场层在节点之上、chrome 之下 / presence between nodes and chrome")
    void presenceBetweenNodesAndChrome() throws Exception {
        String t = Files.readString(Path.of(EDITOR));
        int nodes = t.indexOf("renderer.renderNodes(");
        // 裸调用（行首 12 空格——批 1 起该段落在拖动临场帧影的 try 块内）区别于方法体内委托
        // presence.renderPresenceOverlay(g);
        int presence = t.indexOf("\n            renderPresenceOverlay(g);");
        int topBar = t.indexOf("renderTopBar(g,", t.indexOf("public void render"));
        int menu = t.indexOf("renderAddNodeMenu(g,");
        int box = t.indexOf("renderBoxSelectRect(g);");

        assertTrue(nodes > 0 && presence > 0, "two call sites must exist"+nodes+" presence="+presence);
        assertTrue(presence > nodes, "presence must render after node bodies"+presence+" nodes="+nodes);
        assertTrue(presence < topBar, "presence must render before the top bar"+presence+" topBar="+topBar);
        assertTrue(presence < menu, "presence must render before the add-node menu"+presence+" menu="+menu);
        assertTrue(presence < box, "presence must render before box-select"+presence+" box="+box);
    }

    @Test
    @DisplayName("拖动临场帧影 beginFrame 先于几何段、endFrame 在 finally / drag frame shadow pairs begin-before-geometry with finally-restore")
    void dragFrameShadowPairsWithFinally() throws Exception {
        String t = Files.readString(Path.of(EDITOR));
        int begin = t.indexOf("dragStream.beginFrame(graph, ownerNodeId());");
        int end = t.indexOf("dragStream.endFrame(graph);");
        int comments = t.indexOf("renderer.renderCommentNodes(");
        int finallyKw = t.lastIndexOf("} finally {", end);

        assertTrue(begin > 0 && end > 0 && comments > 0, "shadow pair and geometry segment must exist");
        assertTrue(begin < comments, "beginFrame must run before the render-geometry segment");
        assertTrue(end > comments, "endFrame must restore after the geometry segment");
        assertTrue(finallyKw > 0 && finallyKw < end, "endFrame must sit inside a finally block");
    }
}
