package com.boydti.fawe.jnbt.anvil;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.jnbt.NBTStreamer;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.RunnableVal;
import com.boydti.fawe.object.RunnableVal4;
import com.boydti.fawe.object.exception.FaweException;
import com.boydti.fawe.object.io.BufferedRandomAccessFile;
import com.boydti.fawe.object.io.FastByteArrayInputStream;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.MathMan;
import com.sk89q.jnbt.NBTInputStream;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Chunk format: http://minecraft.gamepedia.com/Chunk_format#Entity_format
 * e.g.: `.Level.Entities.#` (Starts with a . as the root tag is unnamed)
 */
public class MCAFile {

    private static Field fieldBuf2;
    private static Field fieldBuf3;
    static {
        try {
            fieldBuf2 = InflaterInputStream.class.getDeclaredField("buf");
            fieldBuf2.setAccessible(true);
            fieldBuf3 = NBTInputStream.class.getDeclaredField("buf");
            fieldBuf3.setAccessible(true);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private final FaweQueue queue;
    private final File file;
    private RandomAccessFile raf;
    private byte[] locations;
    private boolean deleted;
    private final int X, Z;
    private final Int2ObjectOpenHashMap<MCAChunk> chunks = new Int2ObjectOpenHashMap<>();

    final ThreadLocal<byte[]> byteStore1 = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[4096];
        }
    };
    final ThreadLocal<byte[]> byteStore2 = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[4096];
        }
    };
    final ThreadLocal<byte[]> byteStore3 = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[1024];
        }
    };

    public MCAFile(FaweQueue parent, File file) {
        this.queue = parent;
        this.file = file;
        if (!file.exists()) {
            throw new FaweException.FaweChunkLoadException();
        }
        String[] split = file.getName().split("\\.");
        X = Integer.parseInt(split[1]);
        Z = Integer.parseInt(split[2]);
    }

    public void clear() {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        chunks.clear();
        locations = null;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public FaweQueue getParent() {
        return queue;
    }

    public void init() {
        try {
            if (raf == null) {
                this.locations = new byte[4096];
                this.raf = new RandomAccessFile(file, "rw");
                if (raf.length() < 8192) {
                    raf.setLength(8192);
                } else {
                    raf.seek(0);
                    raf.readFully(locations);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public MCAFile(FaweQueue parent, int mcrX, int mcrZ) throws Exception {
        this(parent, new File(parent.getSaveFolder(), "r." + mcrX + "." + mcrZ + ".mca"));
    }

    public int getX() {
        return X;
    }

    public int getZ() {
        return Z;
    }

    public File getFile() {
        return file;
    }

    public MCAChunk getCachedChunk(int cx, int cz) {
        int pair = MathMan.pair((short) (cx & 31), (short) (cz & 31));
        synchronized (chunks) {
            return chunks.get(pair);
        }
    }

    public void setChunk(MCAChunk chunk) {
        int cx = chunk.getX();
        int cz = chunk.getZ();
        int pair = MathMan.pair((short) (cx & 31), (short) (cz & 31));
        chunks.put(pair, chunk);
    }

    public MCAChunk getChunk(int cx, int cz) throws IOException {
        MCAChunk cached = getCachedChunk(cx, cz);
        if (cached != null) {
            return cached;
        } else {
            return readChunk(cx, cz);
        }
    }

    public MCAChunk readChunk(int cx, int cz) throws IOException {
        int i = ((cx & 31) << 2) + ((cz & 31) << 7);
        int offset = (((locations[i] & 0xFF) << 16) + ((locations[i + 1] & 0xFF) << 8) + ((locations[i+ 2] & 0xFF))) << 12;
        int size = (locations[i + 3] & 0xFF) << 12;
        if (offset == 0) {
            return null;
        }
        NBTInputStream nis = getChunkIS(offset);
        MCAChunk chunk = new MCAChunk(nis, queue, cx, cz, size);
        nis.close();
        int pair = MathMan.pair((short) (cx & 31), (short) (cz & 31));
        synchronized (chunks) {
            chunks.put(pair, chunk);
        }
        return chunk;
    }

    public void forEachSortedChunk(RunnableVal4<Integer, Integer, Integer, Integer> onEach) throws IOException {
        char[] offsets = new char[(int) (raf.length() / 4096) - 2];
        Arrays.fill(offsets, Character.MAX_VALUE);
        char i = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++, i += 4) {
                int offset = (((locations[i] & 0xFF) << 16) + ((locations[i + 1] & 0xFF) << 8) + ((locations[i+ 2] & 0xFF))) - 2;
                int size = locations[i + 3] & 0xFF;
                if (size != 0) {
                    if (offset < offsets.length) {
                        offsets[offset] = i;
                    } else {
                        Fawe.debug("Ignoring invalid offset " + offset);
                    }
                }
            }
        }
        for (i = 0; i < offsets.length; i++) {
            int index = offsets[i];
            if (index != Character.MAX_VALUE) {
                int offset = i + 2;
                int size = locations[index + 3] & 0xFF;
                int index2 = index >> 2;
                int x = (index2) & 31;
                int z = (index2) >> 5;
                onEach.run(x, z, offset << 12, size << 12);
            }
        }
    }

    /**
     * @param onEach cx, cz, offset, size
     */
    public void forEachChunk(RunnableVal4<Integer, Integer, Integer, Integer> onEach) {
        int i = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++, i += 4) {
                int offset = (((locations[i] & 0xFF) << 16) + ((locations[i + 1] & 0xFF) << 8) + ((locations[i+ 2] & 0xFF)));
                int size = locations[i + 3] & 0xFF;
                if (size != 0) {
                    onEach.run(x, z, offset << 12, size << 12);
                }
            }
        }
    }

    public void forEachChunk(RunnableVal<MCAChunk> onEach) {
        int i = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++, i += 4) {
                int offset = (((locations[i] & 0xFF) << 16) + ((locations[i + 1] & 0xFF) << 8) + ((locations[i+ 2] & 0xFF)));
                int size = locations[i + 3] & 0xFF;
                if (size != 0) {
                    try {
                        onEach.run(getChunk(x, z));
                    } catch (Throwable ignore) {}
                }
            }
        }
    }

    public int getOffset(int cx, int cz) {
        int i = ((cx & 31) << 2) + ((cz & 31) << 7);
        int offset = (((locations[i] & 0xFF) << 16) + ((locations[i + 1] & 0xFF) << 8) + ((locations[i+ 2] & 0xFF)));
        return offset << 12;
    }

    public int getSize(int cx, int cz) {
        int i = ((cx & 31) << 2) + ((cz & 31) << 7);
        return (locations[i + 3] & 0xFF) << 12;
    }

    public List<Integer> getChunks() {
        final List<Integer> values = new ArrayList<>(chunks.size());
        for (int i = 0; i < locations.length; i+=4) {
            int offset = (((locations[i] & 0xFF) << 16) + ((locations[i + 1] & 0xFF) << 8) + ((locations[i+ 2] & 0xFF)));
            values.add(offset);
        }
        return values;
    }

    private byte[] getChunkCompressedBytes(int offset) throws IOException{
        if (offset == 0) {
            return null;
        }
        synchronized (raf) {
            raf.seek(offset);
            int size = raf.readInt();
            int compression = raf.read();
            byte[] data = new byte[size];
            raf.readFully(data);
            return data;
        }
    }

    private NBTInputStream getChunkIS(int offset) throws IOException {
        try {
            byte[] data = getChunkCompressedBytes(offset);
            FastByteArrayInputStream bais = new FastByteArrayInputStream(data);
            InflaterInputStream iis = new InflaterInputStream(bais, new Inflater(), 1);
            fieldBuf2.set(iis, byteStore2.get());
            BufferedInputStream bis = new BufferedInputStream(iis);
            NBTInputStream nis = new NBTInputStream(bis);
            fieldBuf3.set(nis, byteStore3.get());
            return nis;
        } catch (IllegalAccessException unlikely) {
            unlikely.printStackTrace();
            return null;
        }
    }

    public void streamChunk(int cx, int cz, RunnableVal<NBTStreamer> addReaders) throws IOException {
        streamChunk(getOffset(cx, cz), addReaders);
    }

    public void streamChunk(int offset, RunnableVal<NBTStreamer> addReaders) throws IOException {
        if (offset == 0) {
            return;
        }
        NBTInputStream is = getChunkIS(offset);
        NBTStreamer ns = new NBTStreamer(is);
        addReaders.run(ns);
        ns.readFully();
        is.close();
    }

    /**
     * @param onEach chunk
     */
    public void forEachCachedChunk(RunnableVal<MCAChunk> onEach) {
        synchronized (chunks) {
            for (Map.Entry<Integer, MCAChunk> entry : chunks.entrySet()) {
                onEach.run(entry.getValue());
            }
        }
    }

    public List<MCAChunk> getCachedChunks() {
        synchronized (chunks) {
            return new ArrayList<>(chunks.values());
        }
    }

    public void uncache(int cx, int cz) {
        int pair = MathMan.pair((short) (cx & 31), (short) (cz & 31));
        synchronized (chunks) {
            chunks.remove(pair);
        }
    }

    private byte[] toBytes(MCAChunk chunk) throws Exception {
        if (chunk.isDeleted()) {
            return null;
        }
        byte[] uncompressed = chunk.toBytes(byteStore3.get());
        byte[] compressed = MainUtil.compress(uncompressed, byteStore2.get(), null);
        return compressed;
    }

    private byte[] getChunkBytes(int cx, int cz) throws  Exception{
        MCAChunk mca = getCachedChunk(cx, cz);
        if (mca == null) {
            int offset = getOffset(cx, cz);
            if (offset == 0) {
                return null;
            }
            return getChunkCompressedBytes(offset);
        }
        return toBytes(mca);
    }


    private void writeSafe(RandomAccessFile raf, int offset, byte[] data) throws IOException {
        int len = data.length + 5;
        raf.seek(offset);
        if (raf.length() - offset < len) {
            raf.setLength(((offset + len + 4095) / 4096) * 4096);
        }
        raf.writeInt(data.length);
        raf.write(2);
        raf.write(data);
    }

    private void writeHeader(RandomAccessFile raf, int cx, int cz, int offsetMedium, int sizeByte, boolean writeTime) throws IOException {
        int i = ((cx & 31) << 2) + ((cz & 31) << 7);
        locations[i] = (byte) (offsetMedium >> 16);
        locations[i + 1] = (byte) (offsetMedium >> 8);
        locations[i + 2] = (byte) (offsetMedium);
        locations[i + 3] = (byte) sizeByte;
        raf.seek(i);
        raf.write((offsetMedium >> 16));
        raf.write((offsetMedium >> 8));
        raf.write((offsetMedium >> 0));
        raf.write(sizeByte);
        raf.seek(i + 4096);
        if (offsetMedium == 0 && sizeByte == 0) {
            raf.writeInt(0);
        } else {
            raf.writeInt((int) (System.currentTimeMillis() / 1000L));
        }
    }

    public void close(ForkJoinPool pool) {
        if (raf == null) return;
        synchronized (raf) {
            if (raf != null) {
                flush(pool);
                try {
                    raf.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                raf = null;
                locations = null;
            }
        }
    }

    public void flush(ForkJoinPool pool) {
        synchronized (raf) {
            boolean wait;
            if (pool == null) {
                wait = true;
                pool = new ForkJoinPool();
            } else wait = false;
            Int2ObjectOpenHashMap<byte[]> relocate = new Int2ObjectOpenHashMap<>();
            final Int2ObjectOpenHashMap<Integer> offsetMap = new Int2ObjectOpenHashMap<>(); // Offset -> <byte cx, byte cz, short size>
            final Int2ObjectOpenHashMap<byte[]> compressedMap = new Int2ObjectOpenHashMap<>();
            final Int2ObjectOpenHashMap<byte[]> append = new Int2ObjectOpenHashMap<>();
            boolean modified = false;
            for (MCAChunk chunk : getCachedChunks()) {
                if (chunk.isModified() || chunk.isDeleted()) {
                    modified = true;
                    if (!chunk.isDeleted()) {
                        pool.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    byte[] compressed = toBytes(chunk);
                                    int pair = MathMan.pair((short) (chunk.getX() & 31), (short) (chunk.getZ() & 31));
                                    Int2ObjectOpenHashMap map;
                                    if (getOffset(chunk.getX(), chunk.getZ()) == 0) {
                                        map = append;
                                    } else {
                                        map = compressedMap;
                                    }
                                    synchronized (map) {
                                        map.put(pair, compressed);
                                    }
                                } catch (Throwable e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }
            }
            if (modified) {
                forEachChunk(new RunnableVal4<Integer, Integer, Integer, Integer>() {
                    @Override
                    public void run(Integer cx, Integer cz, Integer offset, Integer size) {
                        short pair1 = MathMan.pairByte((byte) (cx & 31), (byte) (cz & 31));
                        short pair2 = (short) (size >> 12);
                        offsetMap.put((int) offset, (Integer) MathMan.pair(pair1, pair2));
                    }
                });
                pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                int start = 8192;
                int written = start;
                int end = 8192;
                int nextOffset = 8192;
                try {
                    for (int count = 0; count < offsetMap.size(); count++) {
                        Integer loc = offsetMap.get(nextOffset);
                        while (loc == null) {
                            nextOffset += 4096;
                            loc = offsetMap.get(nextOffset);
                        }
                        int offset = nextOffset;
                        short cxz = MathMan.unpairX(loc);
                        int cx = MathMan.unpairShortX(cxz);
                        int cz = MathMan.unpairShortY(cxz);
                        int size = MathMan.unpairY(loc) << 12;
                        nextOffset += size;
                        end = Math.min(start + size, end);
                        int pair = MathMan.pair((short) (cx & 31), (short) (cz & 31));
                        byte[] newBytes = relocate.get(pair);
                        if (newBytes == null) {
                            MCAChunk cached = getCachedChunk(cx, cz);
                            if (offset == start) {
                                if (cached == null || !cached.isModified()) {
                                    writeHeader(raf, cx, cz, start >> 12, size >> 12, true);
                                    start += size;
                                    written = start + size;
                                    continue;
                                } else {
                                    newBytes = compressedMap.get(pair);
                                }
                            } else {
                                newBytes = compressedMap.get(pair);
                                if (newBytes == null) {
                                    if (cached == null || !cached.isDeleted()) {
                                        newBytes = getChunkCompressedBytes(getOffset(cx, cz));
                                    }
                                }
                            }
                        }
                        if (newBytes == null) {
                            writeHeader(raf, cx, cz, 0, 0, false);
                            continue;
                        }
                        int len = newBytes.length + 5;
                        int oldSize = (size + 4095) >> 12;
                        int newSize = (len + 4095) >> 12;
                        int nextOffset2 = end;
                        while (start + len > end) {
                            Integer nextLoc = offsetMap.get(nextOffset2);
                            if (nextLoc != null) {
                                short nextCXZ = MathMan.unpairX(nextLoc);
                                int nextCX = MathMan.unpairShortX(nextCXZ);
                                int nextCZ = MathMan.unpairShortY(nextCXZ);
                                MCAChunk cached = getCachedChunk(nextCX, nextCZ);
                                if (cached == null || !cached.isModified()) {
                                    byte[] nextBytes = getChunkCompressedBytes(nextOffset2);
                                    relocate.put(MathMan.pair((short) (nextCX & 31), (short) (nextCZ & 31)), nextBytes);
                                }
                                int nextSize = MathMan.unpairY(nextLoc) << 12;
                                end += nextSize;
                                nextOffset2 += nextSize;
                            } else {
                                end += 4096;
                                nextOffset2 += 4096;
                            }
                        }
                        writeSafe(raf, start, newBytes);
                        writeHeader(raf, cx, cz, start >> 12, newSize, true);
                        written = start + newBytes.length + 5;
                        start += newSize << 12;
                    }
                    if (!append.isEmpty()) {
                        for (Int2ObjectMap.Entry<byte[]> entry : append.int2ObjectEntrySet()) {
                            int pair = entry.getIntKey();
                            short cx = MathMan.unpairX(pair);
                            short cz = MathMan.unpairY(pair);
                            byte[] bytes = entry.getValue();
                            int len = bytes.length + 5;
                            int newSize = (len + 4095) >> 12;
                            writeSafe(raf, start, bytes);
                            writeHeader(raf, cx, cz, start >> 12, newSize, true);
                            written = start + bytes.length + 5;
                            start += newSize << 12;
                        }
                    }
                    raf.setLength(4096 * ((written + 4095) / 4096));
                    if (raf instanceof BufferedRandomAccessFile) {
                        ((BufferedRandomAccessFile) raf).flush();
                    }
                    raf.close();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                if (wait) {
                    pool.shutdown();
                    pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                }
            }
        }
    }
}
