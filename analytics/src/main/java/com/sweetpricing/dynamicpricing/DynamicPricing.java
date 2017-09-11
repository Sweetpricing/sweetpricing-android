/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment, Inc.
 * Modified work Copyright (c) 2016 Sweet Pricing Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.sweetpricing.dynamicpricing;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.sweetpricing.dynamicpricing.integrations.BasePayload;
import com.sweetpricing.dynamicpricing.integrations.IdentifyPayload;
import com.sweetpricing.dynamicpricing.integrations.Integration;
import com.sweetpricing.dynamicpricing.integrations.Logger;
import com.sweetpricing.dynamicpricing.integrations.ScreenPayload;
import com.sweetpricing.dynamicpricing.integrations.TrackPayload;
import com.sweetpricing.dynamicpricing.internal.Utils;
import com.sweetpricing.dynamicpricing.internal.Utils.AnalyticsNetworkExecutorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.sweetpricing.dynamicpricing.internal.Utils.getResourceString;
import static com.sweetpricing.dynamicpricing.internal.Utils.getSweetpricingSharedPreferences;
import static com.sweetpricing.dynamicpricing.internal.Utils.hasPermission;

import static com.sweetpricing.dynamicpricing.internal.Utils.isNullOrEmpty;
/**
 * The entry point into the Segment for Android SDK.
 * <p/>
 * The idea is simple: one pipeline for all your data. Segment is the single hub to collect,
 * translate and route your data with the flip of a switch.
 * <p/>
 * DynamicPricing for Android will automatically batch events, queue them to disk, and upload it
 * periodically to Segment for you. It will also look up your project's settings (that you've
 * configured in the web interface), specifically looking up settings for bundled integrations, and
 * then initialize them for you on the user's phone, and mapping our standardized events to formats
 * they can all understand. You only need to instrument Segment once, then flip a switch to install
 * new tools.
 * <p/>
 * This class is the main entry point into the client API. Use {@link
 * #with(android.content.Context)} for the global singleton instance or construct your own instance
 * with {@link Builder}.
 *
 * @see <a href="https://Segment/">Segment</a>
 */
public class DynamicPricing {
  static final Handler HANDLER = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
      throw new AssertionError("Unknown handler message received: " + msg.what);
    }
  };
  private static final String OPT_OUT_PREFERENCE_KEY = "opt-out";
  static final String WRITE_KEY_RESOURCE_IDENTIFIER = "analytics_write_key";
  static final List<String> INSTANCES = new ArrayList<>(1);
  volatile static DynamicPricing singleton = null;
  private static final Properties EMPTY_PROPERTIES = new Properties();
  private static final String VERSION_KEY = "version";
  private static final String BUILD_KEY = "build";

  private final Application application;
  final ExecutorService networkExecutor;
  final Stats stats;
  private final Options defaultOptions;
  private final Traits.Cache traitsCache;
  private final AnalyticsContext analyticsContext;
  private final Logger logger;
  final String tag;
  final Client client;
  final Cartographer cartographer;
  private final ProjectSettings.Cache projectSettingsCache;

  ProjectSettings projectSettings; // todo: make final (non-final for testing).
  private final String writeKey;
  final int flushQueueSize;
  final long flushIntervalInMillis;
  // Retrieving the advertising ID is asynchronous. This latch helps us wait to ensure the
  // advertising ID is ready.
  final CountDownLatch advertisingIdLatch;
  final ExecutorService analyticsExecutor;
  final BooleanPreference optOut;

  final Map<String, Boolean> bundledIntegrations = new ConcurrentHashMap<>();
  private List<Integration.Factory> factories;
  // todo: use lightweight map implementation.
  private Map<String, Integration<?>> integrations;
  volatile boolean shutdown;

  /**
   * Return a reference to the global default {@link DynamicPricing} instance.
   * <p/>
   * This instance is automatically initialized with defaults that are suitable to most
   * implementations.
   * <p/>
   * If these settings do not meet the requirements of your application, you can override defaults
   * in {@code analytics.xml}, or you can construct your own instance with full control over the
   * configuration by using {@link Builder}.
   * <p/>
   * By default, events are uploaded every 30 seconds, or every 20 events (whichever occurs first),
   * and debugging is disabled.
   */
  public static DynamicPricing with(Context context) {
    if (singleton == null) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      synchronized (DynamicPricing.class) {
        if (singleton == null) {
          String writeKey = getResourceString(context, WRITE_KEY_RESOURCE_IDENTIFIER);
          Builder builder = new Builder(context, writeKey);

          try {
            String packageName = context.getPackageName();
            int flags = context.getPackageManager().getApplicationInfo(packageName, 0).flags;
            boolean debugging = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debugging) {
              builder.logLevel(LogLevel.INFO);
            }
          } catch (PackageManager.NameNotFoundException ignored) {
          }

          singleton = builder.build();
        }
      }
    }
    return singleton;
  }

  /**
   * Set the global instance returned from {@link #with}.
   * <p/>
   * This method must be called before any calls to {@link #with} and may only be called once.
   */
  public static void setSingletonInstance(DynamicPricing dynamicPricing) {
    synchronized (DynamicPricing.class) {
      if (singleton != null) {
        throw new IllegalStateException("Singleton instance already exists.");
      }
      singleton = dynamicPricing;
    }
  }

  DynamicPricing(Application application, ExecutorService networkExecutor, Stats stats,
                 Traits.Cache traitsCache, AnalyticsContext analyticsContext, Options defaultOptions,
                 Logger logger, String tag, final List<Integration.Factory> factories, Client client,
                 Cartographer cartographer, ProjectSettings.Cache projectSettingsCache, String writeKey,
                 int flushQueueSize, long flushIntervalInMillis, final ExecutorService analyticsExecutor,
                 final boolean shouldTrackApplicationLifecycleEvents, CountDownLatch advertisingIdLatch,
                 final boolean shouldRecordScreenViews, BooleanPreference optOut) {
    this.application = application;
    this.networkExecutor = networkExecutor;
    this.stats = stats;
    this.traitsCache = traitsCache;
    this.analyticsContext = analyticsContext;
    this.defaultOptions = defaultOptions;
    this.logger = logger;
    this.tag = tag;
    this.client = client;
    this.cartographer = cartographer;
    this.projectSettingsCache = projectSettingsCache;
    this.writeKey = writeKey;
    this.flushQueueSize = flushQueueSize;
    this.flushIntervalInMillis = flushIntervalInMillis;
    this.advertisingIdLatch = advertisingIdLatch;
    this.optOut = optOut;
    this.factories = Collections.unmodifiableList(factories);
    this.analyticsExecutor = analyticsExecutor;

    analyticsExecutor.submit(new Runnable() {
      @Override public void run() {
        projectSettings = getSettings();
        if (isNullOrEmpty(projectSettings)) {
          projectSettings = ProjectSettings.create(new ValueMap() //
                  .putValue("integrations", new ValueMap().putValue("Sweetpricing",
                          new ValueMap().putValue("apiKey", DynamicPricing.this.writeKey))));
        }


        HANDLER.post(new Runnable() {
          @Override public void run() {
            performInitializeIntegrations(projectSettings);
          }
        });
      }
    });

    logger.debug("Created analytics client for project with tag:%s.", tag);

    application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
      final AtomicBoolean trackedApplicationLifecycleEvents = new AtomicBoolean(false);

      @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (!trackedApplicationLifecycleEvents.getAndSet(true)
            && shouldTrackApplicationLifecycleEvents) {
          trackApplicationLifecycleEvents();
        }
        runOnMainThread(IntegrationOperation.onActivityCreated(activity, savedInstanceState));
      }

      @Override public void onActivityStarted(Activity activity) {
        if (shouldRecordScreenViews) {
          recordScreenViews(activity);
        }
        runOnMainThread(IntegrationOperation.onActivityStarted(activity));
      }

      @Override public void onActivityResumed(Activity activity) {
        runOnMainThread(IntegrationOperation.onActivityResumed(activity));
      }

      @Override public void onActivityPaused(Activity activity) {
        runOnMainThread(IntegrationOperation.onActivityPaused(activity));
      }

      @Override public void onActivityStopped(Activity activity) {
        runOnMainThread(IntegrationOperation.onActivityStopped(activity));
      }

      @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        runOnMainThread(IntegrationOperation.onActivitySaveInstanceState(activity, outState));
      }

      @Override public void onActivityDestroyed(Activity activity) {
        runOnMainThread(IntegrationOperation.onActivityDestroyed(activity));
      }
    });
  }

  private void trackApplicationLifecycleEvents() {
    // Get the current version.
    PackageInfo packageInfo = getPackageInfo(application);
    String currentVersion = packageInfo.versionName;
    int currentBuild = packageInfo.versionCode;

    // Get the previous recorded version.
    SharedPreferences sharedPreferences = getSweetpricingSharedPreferences(application);
    String previousVersion = sharedPreferences.getString(VERSION_KEY, null);
    int previousBuild = sharedPreferences.getInt(BUILD_KEY, -1);

    // Check and track Application Installed or Application Updated.
    if (previousBuild == -1) {
      track("Application Installed", new Properties() //
          .putValue(VERSION_KEY, currentVersion).putValue(BUILD_KEY, currentBuild));
    } else if (currentBuild != previousBuild) {
      track("Application Updated", new Properties() //
          .putValue(VERSION_KEY, currentVersion)
          .putValue(BUILD_KEY, currentBuild)
          .putValue("previous_" + VERSION_KEY, previousVersion)
          .putValue("previous_" + BUILD_KEY, previousBuild));
    }

    // Track Application Started.
    track("Application Started", new Properties() //
        .putValue("version", currentVersion) //
        .putValue("build", currentBuild));

    // Update the recorded version.
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putString(VERSION_KEY, currentVersion);
    editor.putInt(BUILD_KEY, currentBuild);
    editor.apply();
  }

  static PackageInfo getPackageInfo(Context context) {
    PackageManager packageManager = context.getPackageManager();
    try {
      return packageManager.getPackageInfo(context.getPackageName(), 0);
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError("Package not found: " + context.getPackageName());
    }
  }

  private void recordScreenViews(Activity activity) {
    PackageManager packageManager = activity.getPackageManager();
    try {
      ActivityInfo info =
          packageManager.getActivityInfo(activity.getComponentName(), PackageManager.GET_META_DATA);
      CharSequence activityLabel = info.loadLabel(packageManager);
      screen(null, activityLabel.toString());
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError("Activity Not Found: " + e.toString());
    }
  }

  private void runOnMainThread(final IntegrationOperation operation) {
    analyticsExecutor.submit(new Runnable() {
      @Override
      public void run() {
        HANDLER.post(new Runnable() {
          @Override
          public void run() {
            performRun(operation);
          }
        });
      }
    });
  }

  // DynamicPricing API

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   * This method will simply set the userId for the current user.
   *
   * @see #identify(String, Traits, Options)
   */
  public void identify(String userId) {
    identify(userId, null, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   * This method will simply add the given traits to the user profile.
   *
   * @see #identify(String, Traits, Options)
   */
  public void identify(Traits traits) {
    identify(null, traits, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   * <p/>
   * Traits and userId will be automatically cached and available on future sessions for the same
   * user. To update a trait on the server, call identify with the same user id (or null). You can
   * also use {@link #identify(Traits)} for this purpose.
   *
   * @param userId Unique identifier which you recognize a user by in your own database. If this
   * is null or empty, any previous id we have (could be the anonymous id) will be
   * used.
   * @param newTraits Traits about the user
   * @param options To configure the call
   * @throws IllegalArgumentException if both {@code userId} and {@code newTraits} are not provided
   * @see <a href="https://segment.com/docs/tracking-api/identify/">Identify Documentation</a>
   */
  public void identify(String userId, Traits newTraits, final Options options) {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
    if (isNullOrEmpty(userId) && isNullOrEmpty(newTraits)) {
      throw new IllegalArgumentException("Either userId or some traits must be provided.");
    }

    Traits traits = traitsCache.get();
    if (!isNullOrEmpty(userId)) {
      traits.putUserId(userId);
    }
    if (!isNullOrEmpty(newTraits)) {
      traits.putAll(newTraits);
    }

    traitsCache.set(traits); // Save the new traits
    analyticsContext.setTraits(traits); // Update the references

    analyticsExecutor.submit(new Runnable() {
      @Override public void run() {
        final Options finalOptions;
        if (options == null) {
          finalOptions = defaultOptions;
        } else {
          finalOptions = options;
        }

        waitForAdvertisingId();
        IdentifyPayload payload =
            new IdentifyPayload(analyticsContext, finalOptions, traitsCache.get());
        enqueue(payload);
      }
    });
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @see #track(String, Properties, Options)
   */
  public void track(String event) {
    track(event, null, null);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @see #track(String, Properties, Options)
   */
  public void track(String event, Properties properties) {
    track(event, properties, null);
  }

  /**
   * Track a purchase of a particular product ID that is setup with Sweet Pricing (this ID
   * comes from Google API).
   *
   * @param productId
   */
  public void trackPurchase(String productId) {
    Properties props = new Properties();
    props.putProductId(productId);

    track("Purchase", props);
  }

  /**
   * Track a purchase of a particular product ID, along with the purchase data and signature for
   * server-side verification.
   *
   * @param productId
   * @param inappPurchaseData The RESPONSE_INAPP_PURCHASE_DATA part of the response.
   * @param inappSignature The RESPONSE_INAPP_SIGNATURE part of the response.
     */
  public void trackPurchase(String productId, String inappPurchaseData, String inappSignature) {
    Properties props = new Properties();
    props.putProductId(productId);
    props.putValue("purchaseData", inappPurchaseData);
    props.putValue("signature", inappSignature);

    track("Purchase", props);
  }

  public void trackViewStore(Variant variant, List<Properties> products) {
    Properties props = new Properties();
    props.putVariantId(variant.getId());
    props.put("products", products);

    track("View Variant", props);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @param event Name of the event. Must not be null or empty.
   * @param properties {@link Properties} to add extra information to this call
   * @param options To configure the call
   * @throws IllegalArgumentException if event name is null or an empty string
   * @see <a href="https://segment.com/docs/tracking-api/track/">Track Documentation</a>
   */
  public void track(final String event, final Properties properties, final Options options) {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
    if (isNullOrEmpty(event)) {
      throw new IllegalArgumentException("event must not be null or empty.");
    }

    analyticsExecutor.submit(new Runnable() {
      @Override public void run() {
        final Options finalOptions;
        if (options == null) {
          finalOptions = defaultOptions;
        } else {
          finalOptions = options;
        }

        final Properties finalProperties;
        if (properties == null) {
          finalProperties = EMPTY_PROPERTIES;
        } else {
          finalProperties = properties;
        }

        waitForAdvertisingId();
        TrackPayload payload =
            new TrackPayload(analyticsContext, finalOptions, event, finalProperties);
        enqueue(payload);
      }
    });
  }

  /** @see #screen(String, String, Properties, Options) */
  public void screen(String category, String name) {
    screen(category, name, null, null);
  }

  /** @see #screen(String, String, Properties, Options) */
  public void screen(String category, String name, Properties properties) {
    screen(category, name, properties, null);
  }

  /**
   * The screen methods let your record whenever a user sees a screen of your mobile app, and
   * attach
   * a name, category or properties to the screen.
   * <p/>
   * Either category or name must be provided.
   *
   * @param category A category to describe the screen
   * @param name A name for the screen
   * @param properties {@link Properties} to add extra information to this call
   * @param options To configure the call
   * @see <a href="http://segment.com/docs/tracking-api/page-and-screen/">Screen Documentation</a>
   */
   public void screen(final String category, final String name, final Properties properties,
      final Options options) {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
    if (isNullOrEmpty(category) && isNullOrEmpty(name)) {
      throw new IllegalArgumentException("either category or name must be provided.");
    }

    analyticsExecutor.submit(new Runnable() {
      @Override public void run() {
        final Options finalOptions;
        if (options == null) {
          finalOptions = defaultOptions;
        } else {
          finalOptions = options;
        }

        final Properties finalProperties;
        if (properties == null) {
          finalProperties = EMPTY_PROPERTIES;
        } else {
          finalProperties = properties;
        }

        waitForAdvertisingId();
        ScreenPayload payload =
            new ScreenPayload(analyticsContext, finalOptions, category, name, finalProperties);
        enqueue(payload);
      }
    });
  }

  void waitForAdvertisingId() {
    try {
      advertisingIdLatch.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      logger.error(e, "Thread interrupted while waiting for advertising ID.");
    }
    if (advertisingIdLatch.getCount() == 1) {
      logger.debug("Advertising ID may not be collected because the Advertising ID API did not "
          + "respond within 15 seconds.");
    }
  }

  void enqueue(BasePayload payload) {
    if (optOut.get()) {
      return;
    }

    logger.verbose("Created payload %s.", payload);
    final IntegrationOperation operation;
    switch (payload.type()) {
      case identify:
        operation = IntegrationOperation.identify((IdentifyPayload) payload);
        break;
      case track:
        operation = IntegrationOperation.track((TrackPayload) payload);
        break;
      case screen:
        operation = IntegrationOperation.screen((ScreenPayload) payload);
        break;
      default:
        throw new AssertionError("unknown type " + payload.type());
    }
    HANDLER.post(new Runnable() {
      @Override public void run() {
        performRun(operation);
      }
    });
  }

  /**
   * Asynchronously flushes all messages in the queue to the server, and tells bundled integrations
   * to do the same.
   */
  public void flush() {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
    runOnMainThread(IntegrationOperation.FLUSH);
  }

  /** Get the {@link AnalyticsContext} used by this instance. */
  @SuppressWarnings("UnusedDeclaration") public AnalyticsContext getAnalyticsContext() {
    return analyticsContext;
  }

  public Options getDefaultOptions() {
    return defaultOptions;
  }

  public Client getClient() {
    return client;
  }

  /** Creates a {@link StatsSnapshot} of the current stats for this instance. */
  public StatsSnapshot getSnapshot() {
    return stats.createSnapshot();
  }

  /** Return the {@link Application} used to create this instance. */
  public Application getApplication() {
    return application;
  }

  /**
   * Return the {@link Logger} instance used by this client.
   *
   * @deprecated This will be removed in a future release.
   */
  public Logger getLogger() {
    return logger;
  }

  /** Return a new {@link Logger} with the given sub-tag. */
  public Logger logger(String tag) {
    return logger.subLog(tag);
  }

  /**
   * Resets the analytics client by clearing any stored information about the user. Events queued
   * on disk are not cleared, and will be uploaded at a later time.
   */
  public void reset() {
    traitsCache.delete();
    traitsCache.set(Traits.create());
    analyticsContext.setTraits(traitsCache.get());
    runOnMainThread(IntegrationOperation.RESET);
  }

  /**
   * Set the opt-out status for the current device and analytics client combination. This flag is
   * persisted across device reboots, so you can simply call this once during your application
   * (such as in a screen where a user can opt out of analytics tracking).
   */
  public void optOut(boolean optOut) {
    this.optOut.set(optOut);
  }

  /**
   * Register to be notified when a bundled integration is ready.
   * <p/>
   * In most cases, integrations would have already been initialized, and the callback will be
   * invoked fairly quickly. However there may be a latency the first time the app is launched, and
   * we don't have settings for bundled integrations yet. This is compounded if the user is offline
   * on the first run.
   * <p/>
   * You can only register for one callback per integration at a time, and passing in a {@code
   * callback} will remove the previous callback for that integration.
   * <p/>
   * Usage:
   * <pre> <code>
   *   analytics.onIntegrationReady("Amplitude", new Callback() {
   *     {@literal @}Override public void onIntegrationReady(Object instance) {
   *       Amplitude.enableLocationListening();
   *     }
   *   });
   *   analytics.onIntegrationReady("Mixpanel", new Callback() {
   *     {@literal @}Override public void onIntegrationReady(Object instance) {
   *       ((MixpanelAPI) instance).clearSuperProperties();
   *     }
   *   })*
   * </code> </pre>
   */
  public <T> void onIntegrationReady(final String key, final Callback<T> callback) {
    if (isNullOrEmpty(key)) {
      throw new IllegalArgumentException("key cannot be null or empty.");
    }

    analyticsExecutor.submit(new Runnable() {
      @Override public void run() {
        HANDLER.post(new Runnable() {
          @Override public void run() {
            performCallback(key, callback);
          }
        });
      }
    });
  }

  /** @deprecated  */
  public enum BundledIntegration {
    AMPLITUDE("Amplitude"),
    APPS_FLYER("AppsFlyer"),
    APPTIMIZE("Apptimize"),
    BUGSNAG("Bugsnag"),
    COUNTLY("Countly"),
    CRITTERCISM("Crittercism"),
    FLURRY("Flurry"),
    GOOGLE_ANALYTICS("Google Analytics"),
    KAHUNA("Kahuna"),
    LEANPLUM("Leanplum"),
    LOCALYTICS("Localytics"),
    MIXPANEL("Mixpanel"),
    QUANTCAST("Quantcast"),
    TAPLYTICS("Taplytics"),
    TAPSTREAM("Tapstream"),
    UXCAM("UXCam");

    /** The key that identifies this integration in our API. */
    final String key;

    BundledIntegration(String key) {
      this.key = key;
    }
  }

  /** Stops this instance from accepting further requests. */
  public void shutdown() {
    if (this == singleton) {
      throw new UnsupportedOperationException("Default singleton instance cannot be shutdown.");
    }
    if (shutdown) {
      return;
    }
    analyticsExecutor.shutdown();
    if (networkExecutor instanceof AnalyticsNetworkExecutorService) {
      networkExecutor.shutdown();
    }
    stats.shutdown();
    shutdown = true;
    synchronized (INSTANCES) {
      INSTANCES.remove(tag);
    }
  }

  /** Controls the level of logging. */
  public enum LogLevel {
    /** No logging. */
    NONE,
    /** Log exceptions only. */
    INFO,
    /** Log exceptions and print debug output. */
    DEBUG,
    /** Same as {@link LogLevel#DEBUG}, and log transformations in bundled integrations. */
    VERBOSE;

    public boolean log() {
      return this != NONE;
    }
  }

  /**
   * A callback interface that is invoked when the DynamicPricing client initializes bundled
   * integrations.
   */
  public interface Callback<T> {
    /**
     * This method will be invoked once for each callback.
     *
     * @param instance The underlying instance that has been initialized with the settings from
     * Segment.
     */
    void onReady(T instance);
  }

  /** Fluent API for creating {@link DynamicPricing} instances. */
  public static class Builder {
    private final Application application;
    private String writeKey;
    private boolean collectDeviceID = Utils.DEFAULT_COLLECT_DEVICE_ID;
    private int flushQueueSize = Utils.DEFAULT_FLUSH_QUEUE_SIZE;
    private long flushIntervalInMillis = Utils.DEFAULT_FLUSH_INTERVAL;
    private Options defaultOptions;
    private String tag;
    private LogLevel logLevel;
    private ExecutorService networkExecutor;
    private ConnectionFactory connectionFactory;
    private List<Integration.Factory> factories;
    private boolean trackApplicationLifecycleEvents = false;
    private boolean recordScreenViews = false;

    /** Start building a new {@link DynamicPricing} instance. */
    public Builder(Context context, String writeKey) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      if (!hasPermission(context, Manifest.permission.INTERNET)) {
        throw new IllegalArgumentException("INTERNET permission is required.");
      }
      application = (Application) context.getApplicationContext();
      if (application == null) {
        throw new IllegalArgumentException("Application context must not be null.");
      }

      if (isNullOrEmpty(writeKey)) {
        throw new IllegalArgumentException("writeKey must not be null or empty.");
      }
      this.writeKey = writeKey;

      factories = new ArrayList<>();
    }

    /**
     * Set the queue size at which the client should flush events. The client will automatically
     * flush events to Segment when the queue reaches {@code flushQueueSize}.
     *
     * @throws IllegalArgumentException if the flushQueueSize is less than or equal to zero.
     */
    public Builder flushQueueSize(int flushQueueSize) {
      if (flushQueueSize <= 0) {
        throw new IllegalArgumentException("flushQueueSize must be greater than or equal to zero.");
      }
      // 250 is a reasonably high number to trigger queue size flushes.
      // The queue may go over this size (as much as 1000), but you should flush much before then.
      if (flushQueueSize > 250) {
        throw new IllegalArgumentException("flushQueueSize must be less than or equal to 250.");
      }
      this.flushQueueSize = flushQueueSize;
      return this;
    }

    /**
     * Set the interval at which the client should flush events. The client will automatically
     * flush events to Segment every {@code flushInterval} duration, regardless of {@code
     * flushQueueSize}.
     *
     * @throws IllegalArgumentException if the flushInterval is less than or equal to zero.
     */
    public Builder flushInterval(long flushInterval, TimeUnit timeUnit) {
      if (timeUnit == null) {
        throw new IllegalArgumentException("timeUnit must not be null.");
      }
      if (flushInterval <= 0) {
        throw new IllegalArgumentException("flushInterval must be greater than zero.");
      }
      this.flushIntervalInMillis = timeUnit.toMillis(flushInterval);
      return this;
    }

    /**
     * Enable or disable collection of {@link android.provider.Settings.Secure#ANDROID_ID},
     * {@link android.os.Build#SERIAL} or the Telephony Identifier retrieved via
     * TelephonyManager as available. Collection of the device identifier is enabled by default.
     */
    public Builder collectDeviceId(boolean collect) {
      this.collectDeviceID = collect;
      return this;
    }

    /**
     * Set some default options for all calls. This will only be used to figure out which
     * integrations should be enabled or not for actions by default.
     *
     * @see {@link Options}
     */
    public Builder defaultOptions(Options defaultOptions) {
      if (defaultOptions == null) {
        throw new IllegalArgumentException("defaultOptions must not be null.");
      }
      // Make a defensive copy
      this.defaultOptions = new Options();
      for (Map.Entry<String, Object> entry : defaultOptions.integrations().entrySet()) {
        if (entry.getValue() instanceof Boolean) {
          this.defaultOptions.setIntegration(entry.getKey(), (Boolean) entry.getValue());
        } else {
          // A value is provided for an integration, and it is not a boolean. Assume it is enabled.
          this.defaultOptions.setIntegration(entry.getKey(), true);
        }
      }
      return this;
    }

    /**
     * Set a tag for this instance. The tag is used to generate keys for caching.
     * </p>
     * By default the writeKey is used. You may want to specify an alternative one, if you want
     * the instances with the same writeKey to share different caches (you probably do).
     *
     * @throws IllegalArgumentException if the tag is null or empty.
     */
    public Builder tag(String tag) {
      if (isNullOrEmpty(tag)) {
        throw new IllegalArgumentException("tag must not be null or empty.");
      }
      this.tag = tag;
      return this;
    }

    /** Set a {@link LogLevel} for this instance. */
    public Builder logLevel(LogLevel logLevel) {
      if (logLevel == null) {
        throw new IllegalArgumentException("LogLevel must not be null.");
      }
      this.logLevel = logLevel;
      return this;
    }

    /**
     * Specify the executor service for making network calls in the background.
     * <p/>
     * Note: Calling {@link DynamicPricing#shutdown()} will not shutdown supplied executors.
     * <p/>
     * Use it with care! http://bit.ly/1JVlA2e
     */
    public Builder networkExecutor(ExecutorService networkExecutor) {
      if (networkExecutor == null) {
        throw new IllegalArgumentException("Executor service must not be null.");
      }
      this.networkExecutor = networkExecutor;
      return this;
    }

    /**
     * Specify the connection factory for customizing how connections are created.
     * <p/>
     * This is a beta API, and might be changed in the future.
     * Use it with care! http://bit.ly/1JVlA2e
     */
    public Builder connectionFactory(ConnectionFactory connectionFactory) {
      if (connectionFactory == null) {
        throw new IllegalArgumentException("ConnectionFactory must not be null.");
      }
      this.connectionFactory = connectionFactory;
      return this;
    }

    /** TODO: docs */
    public Builder use(Integration.Factory factory) {
      if (factory == null) {
        throw new IllegalArgumentException("Factory must not be null.");
      }
      factories.add(factory);
      return this;
    }

    /**
     * Automatically track application lifecycle events, including "Application Installed",
     * "Application Updated" and "Application Opened".
     */
    public Builder trackApplicationLifecycleEvents() {
      this.trackApplicationLifecycleEvents = true;
      return this;
    }

    /** Automatically record screen calls when activities are created. */
    public Builder recordScreenViews() {
      this.recordScreenViews = true;
      return this;
    }

    /** Create a {@link DynamicPricing} client. */
    public DynamicPricing build() {
      if (isNullOrEmpty(tag)) {
        tag = writeKey;
      }
      synchronized (INSTANCES) {
        if (INSTANCES.contains(tag)) {
          throw new IllegalStateException("Duplicate dynamicPricing client created with tag: "
              + tag
              + ". If you want to use multiple DynamicPricing clients, use a different writeKey "
              + "or set a tag via the builder during construction.");
        }
        INSTANCES.add(tag);
      }

      if (defaultOptions == null) {
        defaultOptions = new Options();
      }
      if (logLevel == null) {
        logLevel = LogLevel.NONE;
      }
      if (networkExecutor == null) {
        networkExecutor = new AnalyticsNetworkExecutorService();
      }
      if (connectionFactory == null) {
        connectionFactory = new ConnectionFactory();
      }

      final Stats stats = new Stats();
      final Cartographer cartographer = Cartographer.INSTANCE;
      final Client client = new Client(writeKey, connectionFactory);

      ProjectSettings.Cache projectSettingsCache =
              new ProjectSettings.Cache(application, cartographer, tag);


      BooleanPreference optOut =
          new BooleanPreference(getSweetpricingSharedPreferences(application), OPT_OUT_PREFERENCE_KEY,
              false);

      Traits.Cache traitsCache = new Traits.Cache(application, cartographer, tag);
      if (!traitsCache.isSet() || traitsCache.get() == null) {
        Traits traits = Traits.create();
        traitsCache.set(traits);
      }

      Logger logger = Logger.with(logLevel);
      AnalyticsContext analyticsContext =
          AnalyticsContext.create(application, traitsCache.get(), collectDeviceID);
      CountDownLatch advertisingIdLatch = new CountDownLatch(1);
      analyticsContext.attachAdvertisingId(application, advertisingIdLatch, logger);

      List<Integration.Factory> factories = new ArrayList<>(1 + this.factories.size());
      factories.add(SweetpricingIntegration.FACTORY);
      factories.addAll(this.factories);

      return new DynamicPricing(application, networkExecutor, stats, traitsCache, analyticsContext,
          defaultOptions, logger, tag, factories, client, cartographer, projectSettingsCache,
          writeKey, flushQueueSize, flushIntervalInMillis, Executors.newSingleThreadExecutor(),
          trackApplicationLifecycleEvents, advertisingIdLatch, recordScreenViews, optOut);
    }
  }

  private static final long SETTINGS_REFRESH_INTERVAL = 1000 * 60 * 60 * 24; // 24 hours
  private static final long SETTINGS_RETRY_INTERVAL = 1000 * 60; // 1 minute


  /**
   * Retrieve settings from the cache or the network:
   * 1. If the cache is empty, fetch new settings.
   * 2. If the cache is not stale, use it.
   * 2. If the cache is stale, try to get new settings.
   */
  private ProjectSettings getSettings() {
    ProjectSettings settings = projectSettingsCache.get();
    if (isNullOrEmpty(settings)) {
      return null;
    }

    if (settings.timestamp() + SETTINGS_REFRESH_INTERVAL < System.currentTimeMillis()) {
      return settings;
    }

    return settings;
  }


  void performInitializeIntegrations(ProjectSettings projectSettings) {
    ValueMap integrationSettings = projectSettings.integrations();
    integrations = new LinkedHashMap<>(factories.size());
    for (int i = 0; i < factories.size(); i++) {
      Integration.Factory factory = factories.get(i);
      String key = factory.key();
      ValueMap settings = integrationSettings.getValueMap(key);
      if (isNullOrEmpty(settings)) {
        logger.debug("Integration %s is not enabled.", key);
        continue;
      }
      Integration integration = factory.create(settings, this);
      if (integration == null) {
        logger.info("Factory %s couldn't create integration.", factory);
      } else {
        integrations.put(key, integration);
        bundledIntegrations.put(key, false);
      }
    }
    factories = null;
  }

  /** Runs the given operation on all integrations. */
  void performRun(IntegrationOperation operation) {
    for (Map.Entry<String, Integration<?>> entry : integrations.entrySet()) {
      String key = entry.getKey();
      long startTime = System.nanoTime();
      operation.run(key, entry.getValue(), projectSettings);
      long endTime = System.nanoTime();
      long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      stats.dispatchIntegrationOperation(key, duration);
    }
  }

  private <T> void performCallback(String key, Callback<T> callback) {
    for (Map.Entry<String, Integration<?>> entry : integrations.entrySet()) {
      if (key.equals(entry.getKey())) {
        callback.onReady((T) entry.getValue().getUnderlyingInstance());
        return;
      }
    }
  }
}
