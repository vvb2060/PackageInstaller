package io.github.vvb2060.packageinstaller.datamodel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class OutputStreamDataSink implements DataSink {
    private final OutputStream out;
    private final Consumer<Integer> callback;
    private ByteBuffer tmpBuffer;

    public OutputStreamDataSink(OutputStream out, Consumer<Integer> callback) {
        this.out = out;
        this.callback = callback;
    }

    @Override
    public void consume(ByteBuffer buf) throws IOException {
        if (!buf.hasRemaining()) {
            return;
        }
        var remaining = buf.remaining();

        if (buf.hasArray()) {
            out.write(buf.array(), buf.arrayOffset() + buf.position(), remaining);
            buf.position(buf.limit());
        } else {
            if (tmpBuffer == null) {
                tmpBuffer = ByteBuffer.allocateDirect(64 * 1024);
            }
            while (buf.hasRemaining()) {
                int chunkSize = Math.min(buf.remaining(), tmpBuffer.capacity());
                buf.get(tmpBuffer.array(), tmpBuffer.arrayOffset(), chunkSize);
                out.write(tmpBuffer.array(), tmpBuffer.arrayOffset(), chunkSize);
            }
        }
        callback.accept(remaining);
    }
}
