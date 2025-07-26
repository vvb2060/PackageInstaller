package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;

public interface IPackageInstallerSession extends IInterface {

    ParcelFileDescriptor openWrite(String name, long offsetBytes, long lengthBytes);
    ParcelFileDescriptor openRead(String name);

    abstract class Stub extends Binder implements IPackageInstallerSession {

        public static IPackageInstallerSession asInterface(IBinder binder) {
            throw new UnsupportedOperationException();
        }
    }
}
