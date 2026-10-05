package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 音频频段端口（接口下沉）：AUDIO/PRIVATE/BUS 的音频面发布与游标读取——graph 不直连
 * {@code network.AudioBands}，由宿主注入实现（生产接 {@code AudioBands.PORT}）。
 * <p>Audio band port (interface sinking): the publish/cursor-read surface for the audio
 * plane of AUDIO/PRIVATE/BUS nodes — the graph package never touches
 * {@code network.AudioBands}; the host injects the implementation (production wires
 * {@code AudioBands.PORT}).</p>
 */
public interface AudioBandsPort {

    /** 频段键（频道\0频段）。 / the band key (channel\0band). */
    String bandKey(String channel, String band);

    /** 发布音源引用（owner/冲突纪律在实现侧）；false = 冲突未发布。
     *  Publish an audio ref (owner/conflict discipline lives in the impl); false = the
     *  publish lost to a conflict. */
    boolean publish(String bandKey, AudioRef ref, long gameTick, String owner);

    /** 按消费者游标读取（exactly-once + 新鲜度门控在实现侧）；缺席/陈旧 = 空音源。
     *  Cursor read per consumer (exactly-once + freshness gate live in the impl);
     *  absent/stale yields the empty source. */
    AudioRef get(String consumer, String bandKey, long nowTick);
}
