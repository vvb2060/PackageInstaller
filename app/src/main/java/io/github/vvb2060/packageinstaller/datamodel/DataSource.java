package io.github.vvb2060.packageinstaller.datamodel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public interface DataSource {
    long size();

    InputStream openStream(long offset, long size) throws IOException;

    void feed(long offset, long size, DataSink sink) throws IOException;

    ByteBuffer getByteBuffer(long offset, int size) throws IOException;

    DataSource slice(long offset, long size);
}
