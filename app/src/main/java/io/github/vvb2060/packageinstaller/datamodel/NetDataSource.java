package io.github.vvb2060.packageinstaller.datamodel;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public class NetDataSource implements DataSource {
    private static final int PERSISTENT_CAPACITY = 8;
    private static final int CHUNK_SIZE = 512 * 1024;
    private static final int MAX_CONCURRENT = 64;

    private final DownloadEngine engine;
    private final long size;
    private final int chunkCount;

    private final Map<Integer, ByteBuffer> persistentCache = new HashMap<>();
    private long persistentCount;

    private final ArrayDeque<ByteBuffer> spareBuffers = new ArrayDeque<>();

    public static NetDataSource open(DownloadEngine engine, URI uri) throws IOException {
        var buf = ByteBuffer.allocateDirect(CHUNK_SIZE);
        var size = engine.open(uri, buf);
        var file = new NetDataSource(engine, size);
        file.persistentCache.put(file.chunkCount - 1, buf);
        return file;
    }

    public void stopPersistentCache() {
        persistentCount += PERSISTENT_CAPACITY;
    }

    private NetDataSource(DownloadEngine engine, long size) {
        this.engine = engine;
        this.size = size;
        chunkCount = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void feed(long offset, long length, DataSink sink) throws IOException {
        checkRange(offset, length);
        if (length == 0) {
            return;
        }

        try (var window = new ReadWindow(offset, length)) {
            while (window.hasRemaining()) {
                var buf = window.ensureCurrent();
                if (buf == null) {
                    break;
                }
                int avail = window.availableInCurrent();
                int inChunk = window.currentPos();

                buf.limit(inChunk + avail);
                buf.position(inChunk);

                sink.consume(buf);
                window.advanceCurrent(avail);
            }
        }
    }

    @Override
    public InputStream openStream(long offset, long length) {
        checkRange(offset, length);
        return new DataSourceInputStream(new ReadWindow(offset, length));
    }

    @Override
    public ByteBuffer getByteBuffer(long offset, int length) throws IOException {
        checkRange(offset, length);
        if (length == 0) {
            return ByteBuffer.allocate(0);
        }

        int startChunk = chunkIndexFor(offset);
        int endChunk = chunkIndexFor(offset + length - 1);

        if (startChunk == endChunk) {
            var cached = persistentCache.get(startChunk);
            if (cached != null) {
                int inChunk = (int) (offset - chunkStart(startChunk));
                cached.limit(inChunk + length);
                cached.position(inChunk);
                return cached.slice();
            }
        }

        var out = ByteBuffer.allocateDirect(length);
        try (var window = new ReadWindow(offset, length)) {
            while (window.hasRemaining()) {
                var buf = window.ensureCurrent();
                if (buf == null) {
                    break;
                }
                int avail = window.availableInCurrent();
                int inChunk = window.currentPos();

                buf.limit(inChunk + avail);
                buf.position(inChunk);

                out.put(buf);
                window.advanceCurrent(avail);
            }
        }
        out.flip();
        return out;
    }

    @Override
    public DataSource slice(long offset, long length) {
        checkRange(offset, length);
        return new Slice(offset, length);
    }

    private long chunkStart(int i) {
        return Math.max(0L, size - (long) (chunkCount - i) * CHUNK_SIZE);
    }

    private int chunkLength(int i) {
        long end = size - (long) (chunkCount - 1 - i) * CHUNK_SIZE;
        return (int) (end - chunkStart(i));
    }

    private int chunkIndexFor(long offset) {
        return chunkCount - 1 - (int) ((size - 1 - offset) / CHUNK_SIZE);
    }

    private ByteBuffer acquireBuffer(int blockLen) {
        if (blockLen != CHUNK_SIZE) {
            return ByteBuffer.allocateDirect(blockLen);
        }
        var buf = spareBuffers.poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(CHUNK_SIZE);
        }
        return buf;
    }

    private void releaseBuffer(ByteBuffer buf) {
        if (buf.capacity() != CHUNK_SIZE) {
            return;
        }
        buf.clear();
        spareBuffers.add(buf);
    }

    private void checkRange(long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException(
                "offset=" + offset + " length=" + length + " size=" + size);
        }
    }

    private final class ReadWindow implements Closeable {
        private final long endOffset;
        private final int endChunk;

        private final ArrayDeque<Pending> pipeline = new ArrayDeque<>();
        private int nextSubmit;
        private int inFlight;
        private long pos;

        private ByteBuffer currentBuf;
        private int currentPos;
        private boolean currentOwned;

        ReadWindow(long offset, long length) {
            this.pos = offset;
            this.endOffset = offset + length;
            this.nextSubmit = chunkIndexFor(offset);
            this.endChunk = chunkIndexFor(endOffset - 1);
            fillPipeline();
        }

        int currentPos() {
            return currentPos;
        }

        boolean hasRemaining() {
            return pos < endOffset;
        }

        int availableInCurrent() {
            if (currentBuf == null) {
                return 0;
            }
            long remainingInWindow = endOffset - pos;
            int remainingInBlock = currentBuf.capacity() - currentPos;
            return (int) Math.min(remainingInWindow, remainingInBlock);
        }

        ByteBuffer ensureCurrent() throws IOException {
            if (currentBuf != null) {
                return currentBuf;
            }
            if (!hasRemaining()) {
                return null;
            }

            Pending head = pipeline.poll();
            if (head == null) {
                throw new IllegalStateException("pipeline empty while data remains");
            }
            if (head.task != null) {
                try {
                    head.task.await();
                } catch (IOException e) {
                    inFlight--;
                    head.onError();
                    throw e;
                }
                inFlight--;
                head.onAwaitSuccess();
            }

            currentBuf = head.buf;
            currentOwned = head.ownedByPool();
            currentPos = (int) (pos - chunkStart(head.chunkIndex));

            fillPipeline();
            return currentBuf;
        }

        void advanceCurrent(int n) {
            pos += n;
            currentPos += n;
            if (currentPos >= currentBuf.capacity()) {
                if (currentOwned) {
                    releaseBuffer(currentBuf);
                }
                currentBuf = null;
                currentOwned = false;
            }
        }

        @Override
        public void close() {
            if (currentBuf != null) {
                if (currentOwned) {
                    releaseBuffer(currentBuf);
                }
                currentBuf = null;
                currentOwned = false;
            }
            for (var pending = pipeline.pollLast(); pending != null; ) {
                if (!pending.onCancel()) {
                    pipeline.addFirst(pending);
                }
                pending = pipeline.pollLast();
            }
            inFlight = 0;
        }

        private void fillPipeline() {
            while (nextSubmit <= endChunk && inFlight < MAX_CONCURRENT) {
                var p = submit(nextSubmit++);
                if (p.task != null) {
                    inFlight++;
                }
                pipeline.add(p);
            }
        }

        private Pending submit(int chunkIndex) {
            var cached = persistentCache.get(chunkIndex);
            if (cached != null) {
                return new Pending(chunkIndex, cached, null, false);
            }

            boolean persistent = persistentCount + 1 <= PERSISTENT_CAPACITY;
            if (persistent) {
                persistentCount++;
            }

            int blockLen = chunkLength(chunkIndex);
            var buf = acquireBuffer(blockLen);
            long off = chunkStart(chunkIndex);
            var task = engine.fetch(off, buf);
            return new Pending(chunkIndex, buf, task, persistent);
        }
    }

    private final class Pending {
        final int chunkIndex;
        final ByteBuffer buf;
        final DownloadEngine.Task task;
        final boolean persistent;
        boolean cancelled;

        private Pending(int chunkIndex, ByteBuffer buf,
                        DownloadEngine.Task task, boolean persistent) {
            this.chunkIndex = chunkIndex;
            this.buf = buf;
            this.task = task;
            this.persistent = persistent;
        }

        boolean ownedByPool() {
            return task != null && !persistent;
        }

        void onAwaitSuccess() {
            if (persistent) {
                persistentCache.put(chunkIndex, buf);
            }
        }

        void onError() {
            if (persistent) {
                persistentCount--;
            } else if (task != null) {
                releaseBuffer(buf);
            }
        }

        boolean onCancel() {
            if (task == null) {
                return true;
            }
            var done = task.cancel(cancelled);
            cancelled = true;
            if (done) {
                if (persistent) {
                    persistentCount--;
                } else {
                    releaseBuffer(buf);
                }
            }
            return done;
        }
    }

    private static class DataSourceInputStream extends InputStream {
        private final ReadWindow window;
        private boolean closed;

        DataSourceInputStream(ReadWindow window) {
            this.window = window;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            if (n < 0) {
                return -1;
            }
            return one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("stream closed");
            }
            if (len == 0) {
                return 0;
            }
            if (!window.hasRemaining()) {
                return -1;
            }

            int total = 0;
            while (total < len && window.hasRemaining()) {
                var cur = window.ensureCurrent();
                if (cur == null) {
                    break;
                }
                int avail = window.availableInCurrent();
                if (avail <= 0) {
                    break;
                }
                int n = Math.min(len - total, avail);

                int inChunk = window.currentPos();
                cur.limit(inChunk + n);
                cur.position(inChunk);
                cur.get(b, off + total, n);

                window.advanceCurrent(n);
                total += n;
            }
            return total == 0 ? -1 : total;
        }

        @Override
        public int available() {
            if (closed) {
                return 0;
            }
            return window.availableInCurrent();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            window.close();
        }
    }

    private final class Slice implements DataSource {
        private final long base;
        private final long length;

        Slice(long base, long length) {
            this.base = base;
            this.length = length;
        }

        @Override
        public long size() {
            return length;
        }

        @Override
        public void feed(long offset, long len, DataSink sink) throws IOException {
            checkSliceRange(offset, len);
            NetDataSource.this.feed(base + offset, len, sink);
        }

        @Override
        public ByteBuffer getByteBuffer(long offset, int len) throws IOException {
            checkSliceRange(offset, len);
            return NetDataSource.this.getByteBuffer(base + offset, len);
        }

        @Override
        public DataSource slice(long offset, long len) {
            checkSliceRange(offset, len);
            return new Slice(base + offset, len);
        }

        @Override
        public InputStream openStream(long offset, long len) {
            checkSliceRange(offset, len);
            return NetDataSource.this.openStream(base + offset, len);
        }

        private void checkSliceRange(long offset, long len) {
            if (offset < 0 || len < 0 || offset + len > length) {
                throw new IndexOutOfBoundsException(
                    "offset=" + offset + " len=" + len + " sliceSize=" + length);
            }
        }
    }
}
