package android.os;

public interface IDeviceIdleController extends IInterface {
    void addPowerSaveWhitelistApp(String name) throws RemoteException;

    void removePowerSaveWhitelistApp(String name) throws RemoteException;

    void addPowerSaveTempWhitelistApp(String packageName, long duration, int userId,
                                      int reasonCode, String reason) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
