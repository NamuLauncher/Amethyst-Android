package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/** Singleton class made to log on one file
 * The singleton part can be removed but will require more implementation from the end-dev
 */
@Keep
public class Logger {
    private static final CopyOnWriteArrayList<eventLogListener> logListeners = new CopyOnWriteArrayList<>();

    /** Print the text to the log file if not censored */
    public static void appendToLog(String text) {
        appendBytes((text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static native void appendBytes(byte[] text);

    /** Reset the log file, effectively erasing any previous logs */
    public static native void begin(String logFilePath);

    public static native void end();

    /** Add a listener for the logfile, ask the native side for a listener if needed */
    public static synchronized void addLogListener(eventLogListener logListeners) {
        boolean wasEmpty = Logger.logListeners.isEmpty();
        Logger.logListeners.add(logListeners);
        if (wasEmpty) setLogListener(true);
    }

    /** Remove a listener for the logfile, unset the native listener if no listeners left */
    public static synchronized void removeLogListener(eventLogListener logListener) {
        Logger.logListeners.remove(logListener);
        if (Logger.logListeners.isEmpty()){
            // Makes the JNI code be able to skip expensive logger callbacks
            // NOTE: was tested by rapidly smashing the log on/off button, no sync issues found :)
            setLogListener(false);
        }
    }

    @Keep
    private static void onNativeLog(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (eventLogListener logListener: Logger.logListeners) {
            logListener.onEventLogged(text);
        }
    }

    /** Small listener for anything listening to the log
     *  Performs double duty as being the interface for java listeners and the native callback
     */
    @Keep
    public interface eventLogListener {
        void onEventLogged(String text);
    }

    /** Link a log listener to the logger */
    private static native void setLogListener(boolean enabled);
}
