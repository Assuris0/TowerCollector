/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package info.zamojski.soft.towercollector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.acra.ACRA;
import org.acra.ACRAConstants;
import org.acra.ReportField;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.config.HttpSenderConfigurationBuilder;
import org.acra.data.StringFormat;
import org.acra.sender.HttpSender;
import org.greenrobot.eventbus.EventBus;

import info.zamojski.soft.towercollector.analytics.CompositeReportingService;
import info.zamojski.soft.towercollector.analytics.GoogleAnalyticsReportingService;
import info.zamojski.soft.towercollector.analytics.IAnalyticsReportingService;
import info.zamojski.soft.towercollector.analytics.PiwikReportingService;
import info.zamojski.soft.towercollector.logging.AndroidFilePrinter;
import info.zamojski.soft.towercollector.providers.AppThemeProvider;
import info.zamojski.soft.towercollector.providers.preferences.PreferencesProvider;

import android.Manifest;
import android.app.Application;
import android.widget.Toast;

import info.zamojski.soft.towercollector.utils.PermissionUtils;
import trikita.log.Log;

public class MyApplication extends Application {

    private static final String TAG = MyApplication.class.getSimpleName();

    private static IAnalyticsReportingService analyticsService;
    private static MyApplication application;
    private static PreferencesProvider prefProvider;

    private static Thread.UncaughtExceptionHandler defaultHandler;

    private static int appTheme;

    private static String backgroundTaskName = null;

    // don't use BuildConfig as it sometimes doesn't set DEBUG to true
    private static final boolean EVENTBUS_SUBSCRIBER_CAN_THROW = true;

    @Override
    public void onCreate() {
        super.onCreate();
        application = this;
        // Logging to file is dependent on preferences but this will skip logging of initialization
        initPreferencesProvider();
        initLogger();
        initACRA();
        // Exception handling must be initialized after ACRA to obtain crash details
        initUnhandledExceptionHandler();
        initEventBus();
        initTheme();
        initAnalytics();
    }

    private void initUnhandledExceptionHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e("CRASHED", ex);
                defaultHandler.uncaughtException(thread, ex);
            }
        });
    }

    public void initLogger() {
        // Default configuration
        int consoleLogLevel = BuildConfig.DEBUG ? Log.V : Log.I;
        Log.usePrinter(Log.ANDROID, true).level(consoleLogLevel).useFormat(true);
        // File logging based on preferences
        String fileLoggingLevel = getPreferencesProvider().getFileLoggingLevel();
        if (fileLoggingLevel.equals(getString(R.string.preferences_file_logging_level_entries_value_disabled))) {
            Log.usePrinter(AndroidFilePrinter.getInstance(), false);
        } else {
            if (PermissionUtils.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                if (fileLoggingLevel.equals(getString(R.string.preferences_file_logging_level_entries_value_debug))) {
                    AndroidFilePrinter.getInstance().setMinLevel(Log.D);
                    Log.level(Math.min(consoleLogLevel, Log.D));
                } else if (fileLoggingLevel.equals(getString(R.string.preferences_file_logging_level_entries_value_info))) {
                    AndroidFilePrinter.getInstance().setMinLevel(Log.I);
                    Log.level(Math.min(consoleLogLevel, Log.I));
                } else if (fileLoggingLevel.equals(getString(R.string.preferences_file_logging_level_entries_value_warning))) {
                    AndroidFilePrinter.getInstance().setMinLevel(Log.W);
                    Log.level(Math.min(consoleLogLevel, Log.W));
                } else if (fileLoggingLevel.equals(getString(R.string.preferences_file_logging_level_entries_value_error))) {
                    AndroidFilePrinter.getInstance().setMinLevel(Log.E);
                    Log.level(Math.min(consoleLogLevel, Log.E));
                }
                Log.usePrinter(AndroidFilePrinter.getInstance(), true);
            } else {
                Toast.makeText(this, R.string.permission_logging_denied_temporarily_message, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initEventBus() {
        Log.d("initEventBus(): Initializing EventBus");
        EventBus.builder()
                .throwSubscriberException(EVENTBUS_SUBSCRIBER_CAN_THROW)
                .installDefaultEventBus();
    }

    private void initPreferencesProvider() {
        Log.d("initProviders(): Initializing preferences");
        prefProvider = new PreferencesProvider(this);
    }

    public void initTheme() {
        Log.d("initTheme(): Initializing theme");
        String appThemeName = getPreferencesProvider().getAppTheme();
        AppThemeProvider themeProvider = new AppThemeProvider(this);
        appTheme = themeProvider.getTheme(appThemeName);
    }

    private void initAnalytics() {
        boolean trackingEnabled = getPreferencesProvider().getTrackingEnabled();
        boolean dryRun = BuildConfig.DEBUG;
        Log.d("initAnalytics(): Initializing Google Analytics");
        IAnalyticsReportingService gaService = new GoogleAnalyticsReportingService(this, trackingEnabled, dryRun);
        Log.d("initAnalytics(): Initializing Piwik");
        IAnalyticsReportingService piwikService = new PiwikReportingService(this, trackingEnabled, dryRun);
        analyticsService = new CompositeReportingService(gaService, piwikService);
    }

    private void initACRA() {
        Log.d("initACRA(): Initializing ACRA");

        CoreConfigurationBuilder configBuilder = new CoreConfigurationBuilder(this);
        // Configure connection
        configBuilder.setBuildConfigClass(BuildConfig.class);
        configBuilder.setSendReportsInDevMode(BuildConfig.ACRA_SEND_REPORTS_IN_DEV_MODE);
        configBuilder.setReportFormat(StringFormat.valueOf(BuildConfig.ACRA_REPORT_TYPE));
        configBuilder.setExcludeMatchingSharedPreferencesKeys(new String[]{"api_key"});
        configBuilder.setReportContent(getCustomAcraReportFields());
        // Configure reported content
        HttpSenderConfigurationBuilder httpPluginConfigBuilder = configBuilder.getPluginConfigurationBuilder(HttpSenderConfigurationBuilder.class);
        httpPluginConfigBuilder.setUri(BuildConfig.ACRA_FORM_URI);
        httpPluginConfigBuilder.setBasicAuthLogin(BuildConfig.ACRA_FORM_URI_BASIC_AUTH_LOGIN);
        httpPluginConfigBuilder.setBasicAuthPassword(BuildConfig.ACRA_FORM_URI_BASIC_AUTH_PASSWORD);
        httpPluginConfigBuilder.setHttpMethod(HttpSender.Method.valueOf(BuildConfig.ACRA_HTTP_METHOD));
        httpPluginConfigBuilder.setEnabled(true);

        ACRA.init(this, configBuilder);
    }

    private ReportField[] getCustomAcraReportFields() {
        List<ReportField> customizedFields = new ArrayList<ReportField>(Arrays.asList(ACRAConstants.DEFAULT_REPORT_FIELDS));
        // remove Device ID to make sure it will not be included in report
        customizedFields.remove(ReportField.DEVICE_ID);
        // remove BuildConfig to avoid leakage of configuration data in report
        customizedFields.remove(ReportField.BUILD_CONFIG);
        return customizedFields.toArray(new ReportField[customizedFields.size()]);
    }

    public static IAnalyticsReportingService getAnalytics() {
        return analyticsService;
    }

    public static MyApplication getApplication() {
        return application;
    }

    public static int getCurrentAppTheme() {
        return appTheme;
    }

    public static PreferencesProvider getPreferencesProvider() {
        return prefProvider;
    }

    public synchronized static void startBackgroundTask(Object task) {
        backgroundTaskName = task.getClass().getName();
    }

    public synchronized static void stopBackgroundTask() {
        backgroundTaskName = null;
    }

    public synchronized static String getBackgroundTaskName() {
        return backgroundTaskName;
    }

    public synchronized static boolean isBackgroundTaskRunning(Class clazz) {
        return (backgroundTaskName != null && backgroundTaskName.equals(clazz.getName()));
    }
}
