package android.os;

import androidx.annotation.RequiresApi;

public interface IDeviceIdleController extends IInterface {
    void addPowerSaveWhitelistApp(String name) throws RemoteException;

    void removePowerSaveWhitelistApp(String name) throws RemoteException;

    void addPowerSaveTempWhitelistApp(String name, long duration, int userId, String reason);

    @RequiresApi(Build.VERSION_CODES.S)
    void addPowerSaveTempWhitelistApp(String packageName, long duration, int userId,
                                      int reasonCode, String reason) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
