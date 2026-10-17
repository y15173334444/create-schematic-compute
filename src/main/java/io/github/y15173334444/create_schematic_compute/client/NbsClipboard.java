package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * NBS 剪贴板片段（批次三 B1）：一组音符 + 相对 (tick, layer) 偏移（曲目片段），
 * 序列化为<strong>社区压缩串</strong>——Note Block Studio 选区编码的管道分隔格式
 * （列跳 | 行跳|乐器|键|力度|声像|细音高 … −1 | …），够长时 zlib+base64 压缩
 * （与参考实现 {@code selection_code_update}/{@code try_compress_selection} 语义对齐，
 * MIT——对齐格式语义，未移植代码）。系统剪贴板存该串，故与社区编辑器互贴可用。
 * 值约定与内存一致：乐器 0–15 原版、键 0–87、力度 0–100、声像 0–200（100=居中）、
 * 细音高 ±32767（参考实现内部同为 0–200 声像，无需换算）。
 * <p>NBS clipboard fragment (batch 3 B1): a set of notes with relative (tick, layer)
 * offsets, serialised as the <b>community compressed string</b> — the Note Block Studio
 * selection code (pipe-delimited: {@code colJump|} then
 * {@code rowJump|ins|key|vel|pan|pit|} per note, {@code -1|} per column), zlib+base64
 * compressed when that is shorter (semantics aligned with the reference's
 * {@code selection_code_update} / {@code try_compress_selection}, MIT — the format is
 * aligned, no code is ported). The system clipboard stores that string, so pasting
 * between our editor and the community ones works. Values match our memory convention:
 * instrument 0–15 vanilla, key 0–87, velocity 0–100, panning 0–200 (100 = centre),
 * fine pitch ±32767 (the reference stores panning 0–200 internally too).</p>
 */
public final class NbsClipboard {

    /** 片段内一条音符（相对片段原点）。 / one note in the fragment (relative to its origin). */
    public static final class Entry {
        public final int dTick, dLayer, instrument, key, velocity, panning, pitch;
        public Entry(int dTick, int dLayer, int instrument, int key, int velocity, int panning, int pitch) {
            this.dTick = dTick; this.dLayer = dLayer; this.instrument = instrument; this.key = key;
            this.velocity = velocity; this.panning = panning; this.pitch = pitch;
        }

        // 值相等（往返测试语义比较）/ value equality (round-trip tests compare semantics)
        @Override public boolean equals(Object o) {
            if (!(o instanceof Entry e)) return false;
            return dTick == e.dTick && dLayer == e.dLayer && instrument == e.instrument && key == e.key
                && velocity == e.velocity && panning == e.panning && pitch == e.pitch;
        }

        @Override public int hashCode() {
            return ((dTick * 31 + dLayer) * 31 + instrument) * 31 + key;
        }
    }

    private final List<Entry> entries;

    private NbsClipboard(List<Entry> entries) { this.entries = entries; }

    public static NbsClipboard of(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.dTick != b.dTick ? Integer.compare(a.dTick, b.dTick)
                                                 : Integer.compare(a.dLayer, b.dLayer));
        return new NbsClipboard(List.copyOf(sorted));
    }

    /**
     * 从选区收集片段（原点 = 选中音符的最小 tick/layer；无选中内容返回空片段）。
     * Collect a fragment from the selection (origin = the selected notes' min tick/layer;
     * an empty selection yields an empty fragment).
     */
    public static NbsClipboard fromSelection(NbsSong song, NbsSelection sel) {
        List<int[]> cs = sel.cells();
        List<NbsSong.Note> notes = new ArrayList<>();
        for (int[] c : cs) {
            NbsSong.Note n = song.getNote(c[0], c[1]);
            if (n != null) notes.add(n);
        }
        if (notes.isEmpty()) return of(List.of());
        int minT = Integer.MAX_VALUE, minL = Integer.MAX_VALUE;
        for (NbsSong.Note n : notes) {
            minT = Math.min(minT, n.tick);
            minL = Math.min(minL, n.layer);
        }
        List<Entry> out = new ArrayList<>(notes.size());
        for (NbsSong.Note n : notes) {
            out.add(new Entry(n.tick - minT, n.layer - minL, n.instrument, n.key,
                n.velocity, n.panning, n.pitch));
        }
        return of(out);
    }

    public List<Entry> entries() { return entries; }

    public boolean isEmpty() { return entries.isEmpty(); }

    // ── 序列化 / serialisation ──────────────────────────────────────────

    /** 编码为社区选区串（够长且更短时压缩为 base64）。 / encode as the community selection code. */
    public String encode() {
        return tryCompress(encodeRaw());
    }

    /** 未压缩选区串（社区原格式；编解码对称与格式锚点都用它）。
     *  The raw selection code (the community's plain form — the format anchor). */
    String encodeRaw() {
        StringBuilder sb = new StringBuilder();
        int prevTick = 0;                 // 上一条音符列的相对 tick（首列即绝对）/ previous note column
        boolean firstCol = true;
        int i = 0;
        while (i < entries.size()) {
            Entry head = entries.get(i);
            sb.append(firstCol ? head.dTick : head.dTick - prevTick).append('|');
            firstCol = false;
            int prevLayer = 0;            // 列内上一条音符的相对 layer（首条即绝对）/ previous row in column
            boolean firstRow = true;
            int tick = head.dTick;
            while (i < entries.size() && entries.get(i).dTick == tick) {
                Entry e = entries.get(i);
                sb.append(firstRow ? e.dLayer : e.dLayer - prevLayer).append('|')
                  .append(e.instrument).append('|').append(e.key).append('|')
                  .append(e.velocity).append('|').append(e.panning).append('|').append(e.pitch).append('|');
                firstRow = false;
                prevLayer = e.dLayer;
                i++;
            }
            sb.append("-1|");
            prevTick = tick;
        }
        return sb.toString();
    }

    /**
     * 解码社区选区串；不合法 / 无法解压返回 null（调用方回退或提示）。
     * 含 "|" = 未压缩原串；不含 = base64(zlib)（参考实现同款判定——base64 字符集不含 "|"）。
     * Decode a community selection code; null when malformed / undecodable. A "|" means
     * the raw form; its absence means base64(zlib) (the reference's same detection —
     * base64 never contains "|").
     */
    public static NbsClipboard decode(String s) {
        if (s == null) return null;
        String raw = s.trim();
        if (raw.isEmpty()) return null;
        if (raw.indexOf('|') < 0) {
            raw = tryDecompress(raw);
            if (raw == null) return null;
        }
        return parse(raw);
    }

    private static NbsClipboard parse(String raw) {
        String[] toks = raw.split("\\|", -1);
        int end = toks.length;
        while (end > 0 && toks[end - 1].isEmpty()) end--;    // 尾随分隔符的空段 / trailing empties
        List<Entry> out = new ArrayList<>();
        int pos = 0;
        try {
            int tick = 0;
            while (pos < end) {
                int colGap = Integer.parseInt(toks[pos++]);
                if (colGap < 0) return null;                 // −1 只作列终止符 / -1 only closes columns
                tick += colGap;
                int layer = 0;
                while (true) {
                    if (pos >= end) return null;             // 列未闭合 / column never closed
                    int rowOrEnd = Integer.parseInt(toks[pos++]);
                    if (rowOrEnd == -1) break;
                    if (rowOrEnd < 0 || pos + 5 > toks.length) return null;
                    layer += rowOrEnd;
                    out.add(new Entry(tick, layer,
                        Integer.parseInt(toks[pos]), Integer.parseInt(toks[pos + 1]),
                        Integer.parseInt(toks[pos + 2]), Integer.parseInt(toks[pos + 3]),
                        Integer.parseInt(toks[pos + 4])));
                    pos += 5;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out.isEmpty() ? null : of(out);
    }

    /** 够长才压（与参考同：&lt;16 不压）；压后更短才用。 / compress at ≥16 chars; use it only when shorter. */
    private static String tryCompress(String raw) {
        if (raw.length() < 16) return raw;
        byte[] src = raw.getBytes(StandardCharsets.UTF_8);
        Deflater def = new Deflater();
        try {
            def.setInput(src);
            def.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(src.length);
            byte[] buf = new byte[256];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            String b64 = Base64.getEncoder().encodeToString(bos.toByteArray());
            return b64.length() < raw.length() ? b64 : raw;
        } finally {
            def.end();
        }
    }

    private static String tryDecompress(String b64) {
        byte[] zip;
        try {
            zip = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(zip);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(zip.length * 4);
            byte[] buf = new byte[256];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }
}
