package io.github.vvb2060.packageinstaller.datamodel;

import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class InflateDataSinkAdapter implements DataSink, Closeable {
    private final DataSink mDelegate;
    private final Inflater mInflater = new Inflater(true);
    private final ByteBuffer mOutputBuffer = ByteBuffer.allocateDirect(64 * 1024);

    private ByteBuffer mInputBuffer;
    private long mOutputByteCount;
    private boolean mClosed;

    public InflateDataSinkAdapter(DataSink delegate) {
        mDelegate = delegate;
    }

    private void inflate(byte[] buf, int offset, int length) throws IOException {
        mInflater.setInput(buf, offset, length);
        while (!mInflater.finished()) {
            int outputChunkSize;
            try {
                outputChunkSize = mInflater.inflate(mOutputBuffer.array(),
                    mOutputBuffer.arrayOffset(), mOutputBuffer.capacity());
            } catch (DataFormatException e) {
                throw new IOException("Failed to inflate data", e);
            }
            if (outputChunkSize == 0) {
                return;
            }
            mOutputBuffer.position(0);
            mOutputBuffer.limit(outputChunkSize);
            mDelegate.consume(mOutputBuffer);
            mOutputByteCount += outputChunkSize;
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private void inflate(ByteBuffer buf) throws IOException {
        mInflater.setInput(buf);
        while (!mInflater.finished()) {
            int outputChunkSize;
            try {
                outputChunkSize = mInflater.inflate(mOutputBuffer);
            } catch (DataFormatException e) {
                throw new IOException("Failed to inflate data", e);
            }
            if (outputChunkSize == 0) {
                return;
            }
            mOutputBuffer.flip();
            mDelegate.consume(mOutputBuffer);
            mOutputBuffer.clear();
            mOutputByteCount += outputChunkSize;
        }
    }

    @Override
    public void consume(ByteBuffer buf) throws IOException {
        if (mClosed) throw new IllegalStateException("Closed");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            inflate(buf);
            return;
        }
        if (buf.hasArray()) {
            inflate(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
            buf.position(buf.limit());
        } else {
            if (mInputBuffer == null) {
                mInputBuffer = ByteBuffer.allocateDirect(64 * 1024);
            }
            while (buf.hasRemaining()) {
                int chunkSize = Math.min(buf.remaining(), mInputBuffer.capacity());
                buf.get(mInputBuffer.array(), mInputBuffer.arrayOffset(), chunkSize);
                inflate(mInputBuffer.array(), mInputBuffer.arrayOffset(), chunkSize);
            }
        }
    }

    public long getOutputByteCount() {
        return mOutputByteCount;
    }

    @Override
    public void close() {
        mClosed = true;
        mInflater.end();
    }
}
