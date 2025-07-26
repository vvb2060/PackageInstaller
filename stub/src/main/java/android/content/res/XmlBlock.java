package android.content.res;

public class XmlBlock implements AutoCloseable {
    public XmlBlock(byte[] data) {

    }

    public native XmlResourceParser newParser();

    public native void close();
}
