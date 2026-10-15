package io.github.vvb2060.packageinstaller.datamodel;

import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class FileDataSource implements DataSource {
    private static final int MAX_READ_CHUNK_SIZE = 1024 * 1024;

    private final FileChannel mChannel;
    private final ParcelFileDescriptor mPfd;
    private final long mOffset;
    private final long mSize;

    public static DataSource asDataSource(AssetFileDescriptor afd) {
        var pfd = afd.getParcelFileDescriptor();
        // noinspection resource
        var fis = new FileInputStream(pfd.getFileDescriptor());
        return new FileDataSource(fis.getChannel(), pfd, afd.getStartOffset(), afd.getLength());
    }

    public static DataSource asDataSource(ParcelFileDescriptor pfd) {
        // noinspection resource
        var fis = new FileInputStream(pfd.getFileDescriptor());
        return new FileDataSource(fis.getChannel(), pfd, 0, pfd.getStatSize());
    }

    private FileDataSource(FileChannel channel, ParcelFileDescriptor pfd, long offset, long size) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("offset: " + offset);
        }
        if (size < 0) {
            throw new IndexOutOfBoundsException("size: " + size);
        }
        mChannel = channel;
        mPfd = pfd;
        mOffset = offset;
        mSize = size;
    }

    public AssetFileDescriptor getAssetFileDescriptor() {
        return new AssetFileDescriptor(mPfd, mOffset, mSize);
    }

    @Override
    public long size() {
        return mSize;
    }

    @Override
    public FileDataSource slice(long offset, long size) {
        checkChunkValid(offset, size, mSize);
        if ((offset == 0) && (size == mSize)) {
            return this;
        }

        return new FileDataSource(mChannel, mPfd, mOffset + offset, size);
    }

    @Override
    public InputStream openStream(long offset, long size) throws IOException {
        checkChunkValid(offset, size, mSize);

        var afd = new AssetFileDescriptor(mPfd, mOffset + offset, size);
        return new FilterInputStream(afd.createInputStream()) {
            @Override
            public void close() {
            }
        };
    }

    @Override
    public void feed(long offset, long size, DataSink sink) throws IOException {
        checkChunkValid(offset, size, mSize);
        if (size == 0) {
            return;
        }

        long chunkOffsetInFile = mOffset + offset;
        long remaining = size;
        ByteBuffer buf = ByteBuffer.allocateDirect((int) Math.min(remaining, MAX_READ_CHUNK_SIZE));

        while (remaining > 0) {
            int chunkSize = (int) Math.min(remaining, buf.capacity());
            int chunkRemaining = chunkSize;
            buf.limit(chunkSize);

            while (chunkRemaining > 0) {
                int read = mChannel.read(buf, chunkOffsetInFile);
                if (read < 0) {
                    throw new IOException("Unexpected EOF encountered");
                }
                chunkOffsetInFile += read;
                chunkRemaining -= read;
            }

            buf.flip();
            sink.consume(buf);
            buf.clear();
            remaining -= chunkSize;
        }
    }

    @Override
    public ByteBuffer getByteBuffer(long offset, int size) throws IOException {
        checkChunkValid(offset, size, mSize);
        if (size == 0) {
            return ByteBuffer.allocate(0);
        }

        var result = ByteBuffer.allocateDirect(size);
        long offsetInFile = mOffset + offset;
        int remaining = size;
        while (remaining > 0) {
            int read = mChannel.read(result, offsetInFile);
            if (read < 0) {
                throw new IOException("Unexpected EOF encountered");
            }
            offsetInFile += read;
            remaining -= read;
        }
        result.flip();
        return result;
    }

    private static void checkChunkValid(long offset, long size, long sourceSize) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("offset: " + offset);
        }
        if (size < 0) {
            throw new IndexOutOfBoundsException("size: " + size);
        }
        if (offset > sourceSize) {
            throw new IndexOutOfBoundsException(
                "offset (" + offset + ") > source size (" + sourceSize + ")");
        }
        long endOffset = offset + size;
        if (endOffset < offset) {
            throw new IndexOutOfBoundsException(
                "offset (" + offset + ") + size (" + size + ") overflow");
        }
        if (endOffset > sourceSize) {
            throw new IndexOutOfBoundsException(
                "offset (" + offset + ") + size (" + size
                    + ") > source size (" + sourceSize + ")");
        }
    }
}
