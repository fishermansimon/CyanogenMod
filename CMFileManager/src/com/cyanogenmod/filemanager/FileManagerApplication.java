/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.filemanager;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.cyanogenmod.filemanager.console.Console;
import com.cyanogenmod.filemanager.console.ConsoleAllocException;
import com.cyanogenmod.filemanager.console.ConsoleBuilder;
import com.cyanogenmod.filemanager.console.ConsoleHolder;
import com.cyanogenmod.filemanager.console.shell.PrivilegedConsole;
import com.cyanogenmod.filemanager.preferences.AccessMode;
import com.cyanogenmod.filemanager.preferences.FileManagerSettings;
import com.cyanogenmod.filemanager.preferences.ObjectStringIdentifier;
import com.cyanogenmod.filemanager.preferences.Preferences;
import com.cyanogenmod.filemanager.util.ExceptionUtil;
import com.cyanogenmod.filemanager.util.FileHelper;
import com.cyanogenmod.filemanager.util.MimeTypeHelper;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/**
 * A class that wraps the information of the application (constants,
 * identifiers, statics variables, ...).
 * @hide
 */
public final class FileManagerApplication extends Application {

    private static final String TAG = "FileManagerApplication"; //$NON-NLS-1$

    private static boolean DEBUG = false;
    private static Properties sSystemProperties;

    /**
     * A constant that contains the main process name.
     * @hide
     */
    public static final String MAIN_PROCESS = "com.cyanogenmod.filemanager"; //$NON-NLS-1$

    //Static resources
    private static FileManagerApplication sApp;
    private static ConsoleHolder sBackgroundConsole;

    private static boolean sIsDebuggable = false;
    private static boolean sIsDeviceRooted = false;

    private final BroadcastReceiver mOnSettingChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null &&
                intent.getAction().compareTo(FileManagerSettings.INTENT_SETTING_CHANGED) == 0) {

                // The settings has changed
                String key = intent.getStringExtra(FileManagerSettings.EXTRA_SETTING_CHANGED_KEY);
                if (key != null &&
                    key.compareTo(FileManagerSettings.SETTINGS_SHOW_TRACES.getId()) == 0) {

                    // The debug traces setting has changed. Notify to consoles
                    Console c = null;
                    try {
                        c = getBackgroundConsole();
                    } catch (Exception e) {/**NON BLOCK**/}
                    if (c != null) {
                        c.reloadTrace();
                    }
                    try {
                        c = ConsoleBuilder.getConsole(context, false);
                        if (c != null) {
                            c.reloadTrace();
                        }
                    } catch (Throwable _throw) {/**NON BLOCK**/}
                }
            }
        }
    };


    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(TAG, "FileManagerApplication.onCreate"); //$NON-NLS-1$
        }
        register();
        init();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onTerminate() {
        if (DEBUG) {
            Log.d(TAG, "onTerminate"); //$NON-NLS-1$
        }
        try {
            unregisterReceiver(this.mOnSettingChangeReceiver);
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
        try {
            destroyBackgroundConsole();
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
        try {
            ConsoleBuilder.destroyConsole();
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
        super.onTerminate();
    }

    /**
     * Method that register the application context.
     */
    private void register() {
        //Save the static application reference
        sApp = this;

        // Read the system properties
        sSystemProperties = new Properties();
        readSystemProperties();

        // Check if the application is debuggable
        sIsDebuggable = (0 != (getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE));

        // Check if the device is rooted
        sIsDeviceRooted =
                new File(getString(R.string.su_binary)).exists() &&
                getSystemProperty("ro.cm.version") != null; //$NON-NLS-1$

        // Register the broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(FileManagerSettings.INTENT_SETTING_CHANGED);
        registerReceiver(this.mOnSettingChangeReceiver, filter);
    }

    /**
     * Method that initializes the application.
     */
    private void init() {
        //Sets the default preferences if no value is set yet
        FileHelper.ROOT_DIRECTORY = getString(R.string.root_dir);
        Preferences.loadDefaults();

        //Create a console for background tasks
        allocBackgroundConsole(getApplicationContext());

        //Force the load of mime types
        try {
            MimeTypeHelper.loadMimeTypes(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Mime-types failed.", e); //$NON-NLS-1$
        }
    }

    /**
     * Method that returns the singleton reference of the application.
     *
     * @return Application The application singleton reference
     * @hide
     */
    public static FileManagerApplication getInstance() {
        return sApp;
    }

    /**
     * Method that returns if the application is debuggable
     *
     * @return boolean If the application is debuggable
     */
    public static boolean isDebuggable() {
        return sIsDebuggable;
    }

    /**
     * Method that returns if the device is rooted
     *
     * @return boolean If the device is rooted
     */
    public static boolean isDeviceRooted() {
        return sIsDeviceRooted;
    }

    /**
     * Method that returns a system property value
     *
     * @param property The system property key
     * @return String The system property value
     */
    public static String getSystemProperty(String property) {
        return sSystemProperties.getProperty(property);
    }

    /**
     * Method that returns the application background console.
     *
     * @return Console The background console
     */
    public static Console getBackgroundConsole() {
        if (sBackgroundConsole == null ||
            sBackgroundConsole.getConsole() == null ||
            !sBackgroundConsole.getConsole().isActive()) {

            allocBackgroundConsole(getInstance().getApplicationContext());
        }
        return sBackgroundConsole.getConsole();
    }

    /**
     * Method that destroy the background console
     */
    public static void destroyBackgroundConsole() {
        try {
            sBackgroundConsole.dispose();
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
    }

    /**
     * Method that allocate a new background console
     *
     * @param ctx The current context
     */
    private static synchronized void allocBackgroundConsole(Context ctx) {
        try {
            // Dispose the current console
            if (sBackgroundConsole != null) {
                sBackgroundConsole.dispose();
                sBackgroundConsole = null;
            }

            //Create a console for background tasks
            if (ConsoleBuilder.isPrivileged()) {
                sBackgroundConsole =
                        new ConsoleHolder(
                                ConsoleBuilder.createPrivilegedConsole(
                                        ctx, FileHelper.ROOT_DIRECTORY));
            } else {
                sBackgroundConsole =
                        new ConsoleHolder(
                                ConsoleBuilder.createNonPrivilegedConsole(
                                        ctx, FileHelper.ROOT_DIRECTORY));
            }
        } catch (Exception e) {
            Log.e(TAG,
                    "Background console creation failed. " +  //$NON-NLS-1$
                    "This probably will cause a force close.", e); //$NON-NLS-1$
        }
    }

    /**
     * Method that changes the background console to a privileged console
     *
     * @throws ConsoleAllocException If the console can't be allocated
     */
    public static void changeBackgroundConsoleToPriviligedConsole()
            throws ConsoleAllocException {
        if (sBackgroundConsole == null ||
              !(sBackgroundConsole.getConsole() instanceof PrivilegedConsole)) {
            try {
                if (sBackgroundConsole != null) {
                    sBackgroundConsole.dispose();
                }
            } catch (Throwable ex) {/**NON BLOCK**/}

            // Change the privileged console
            try {
                sBackgroundConsole =
                        new ConsoleHolder(
                                ConsoleBuilder.createPrivilegedConsole(
                                        getInstance().getApplicationContext(),
                                        FileHelper.ROOT_DIRECTORY));
            } catch (Exception e) {
                try {
                    if (sBackgroundConsole != null) {
                        sBackgroundConsole.dispose();
                    }
                } catch (Throwable ex) {/**NON BLOCK**/}
                sBackgroundConsole = null;
                throw new ConsoleAllocException(
                        "Failed to alloc background console", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Method that check if the app is signed with the platform signature
     *
     * @param ctx The current context
     * @return boolean If the app is signed with the platform signature
     */
    public static boolean isAppPlatformSignature(Context ctx) {
        // TODO This need to be improved, checking if the app is really with the platform signature
        try {
            // For now only check that the app is installed in system directory
            PackageManager pm = ctx.getPackageManager();
            String appDir = pm.getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            String systemDir = ctx.getString(R.string.system_dir);
            return appDir.startsWith(systemDir);

        } catch (Exception e) {
            ExceptionUtil.translateException(ctx, e, true, false);
        }
        return false;
    }

    /**
     * Method that returns the access mode of the application
     *
     * @return boolean If the access mode of the application
     */
    public static AccessMode getAccessMode() {
        String defaultValue =
                ((ObjectStringIdentifier)FileManagerSettings.
                            SETTINGS_ACCESS_MODE.getDefaultValue()).getId();
        String id = FileManagerSettings.SETTINGS_ACCESS_MODE.getId();
        AccessMode mode =
                AccessMode.fromId(Preferences.getSharedPreferences().getString(id, defaultValue));
        return mode;
    }

    /**
     * Method that reads the system properties
     */
    private static void readSystemProperties() {
        try {
            String propsFile =
                    getInstance().getApplicationContext().getString(R.string.system_props_file);
            Properties props = new Properties();
            props.load(new FileInputStream(new File(propsFile)));
            sSystemProperties = props;
        } catch (Throwable e) {
            Log.e(TAG,
                    "Failed to read system properties.", e); //$NON-NLS-1$
        }
    }

}
