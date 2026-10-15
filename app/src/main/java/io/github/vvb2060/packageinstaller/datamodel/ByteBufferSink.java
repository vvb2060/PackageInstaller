package io.github.vvb2060.packageinstaller.datamodel;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

public class ByteBufferSink implements DataSink {

    private final ByteBuffer mBuffer;

    public ByteBufferSink(ByteBuffer buffer) {
        mBuffer = buffer;
    }

    public ByteBuffer getBuffer() {
        return mBuffer;
    }

    @Override
    public void consume(ByteBuffer buf) throws IOException {
        try {
            mBuffer.put(buf);
        } catch (BufferOverflowException e) {
            throw new IOException(e);
        }
    }
}
