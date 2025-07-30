package com.github.luben.zstd;

import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;

import java.io.FilterInputStream;
import java.io.InputStream;

public class ZstdInputStream extends FilterInputStream {

    public ZstdInputStream(InputStream in) throws UnsupportedZipFeatureException {
        super(in);
        throw new UnsupportedZipFeatureException(UnsupportedZipFeatureException.Feature.METHOD);
    }
}
