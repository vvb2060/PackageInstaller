package android.content.pm;

import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

public interface IPackageInstaller extends IInterface {

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    void disableVerificationForUid(int uid) throws RemoteException;

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    void addDeveloperVerificationExperiment(String packageName, int verificationPolicy,
                                            int[] results) throws RemoteException;

    void setPermissionsResult(int sessionId, boolean accepted) throws RemoteException;

    abstract class Stub extends Binder implements IPackageInstaller {

        public static IPackageInstaller asInterface(IBinder binder) {
            throw new UnsupportedOperationException();
        }
    }
}
