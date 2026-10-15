package io.github.vvb2060.packageinstaller.datamodel;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface DataSink {
    void consume(ByteBuffer buf) throws IOException;
}
