package io.github.y15173334444.create_schematic_compute.graph;

/**
 * Blob 字节取用（接口下沉）：SET_SONG 从数据面按句柄取曲目字节——graph 不直连
 * {@code network.BlobRegistry}，由根包/宿主注入实现。
 * <p>Blob byte fetch (interface sinking): SET_SONG pulls song bytes from the data plane
 * by handle — the graph package never touches {@code network.BlobRegistry}; the
 * root/host injects the implementation.</p>
 */
public interface BlobStore {

    /** 取走并移除一个重组完成的 blob；缺席/过期/未接线返回 null（SET_SONG 按坏数据拒存）。
     *  Fetch and remove a reassembled blob; absent/expired/unwired yields null (SET_SONG
     *  treats it as malformed and keeps the old song). */
    byte[] poll(int blobId);
}
