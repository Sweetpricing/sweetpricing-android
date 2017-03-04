package com.sweetpricing.dynamicpricing;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import com.sweetpricing.dynamicpricing.TestUtils.NoDescriptionMatcher;
import com.sweetpricing.dynamicpricing.core.tests.BuildConfig;
import com.sweetpricing.dynamicpricing.integrations.IdentifyPayload;
import com.sweetpricing.dynamicpricing.integrations.Integration;
import com.sweetpricing.dynamicpricing.integrations.Logger;
import com.sweetpricing.dynamicpricing.integrations.ScreenPayload;
import com.sweetpricing.dynamicpricing.integrations.TrackPayload;
import com.sweetpricing.dynamicpricing.internal.Utils.AnalyticsNetworkExecutorService;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.data.MapEntry;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLog;

import static android.content.Context.MODE_PRIVATE;
import static com.sweetpricing.dynamicpricing.DynamicPricing.LogLevel.NONE;
import static com.sweetpricing.dynamicpricing.TestUtils.SynchronousExecutor;
import static com.sweetpricing.dynamicpricing.TestUtils.mockApplication;
import static com.sweetpricing.dynamicpricing.Utils.createContext;
import static com.sweetpricing.dynamicpricing.internal.Utils.DEFAULT_FLUSH_INTERVAL;
import static com.sweetpricing.dynamicpricing.internal.Utils.DEFAULT_FLUSH_QUEUE_SIZE;
import static com.sweetpricing.dynamicpricing.internal.Utils.isNullOrEmpty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, emulateSdk = 18, manifest = Config.NONE)
public class DynamicPricingTest {
  private static final String SETTINGS = "{\n"
      + "  \"integrations\": {\n"
      + "    \"test\": {\n"
      + "      \"foo\": \"bar\"\n"
      + "    }\n"
      + "  },\n"
      + "  \"plan\": {\n"
      + "    \n"
      + "  }\n"
      + "}";

  @Mock Traits.Cache traitsCache;
  @Mock Options defaultOptions;
  @Spy AnalyticsNetworkExecutorService networkExecutor;
  @Spy ExecutorService analyticsExecutor = new SynchronousExecutor();
  @Mock Client client;
  @Mock Stats stats;
  @Mock ProjectSettings.Cache projectSettingsCache;
  @Mock Integration integration;
  Integration.Factory factory;
  BooleanPreference optOut;
  Application application;
  Traits traits;
  AnalyticsContext analyticsContext;

  private DynamicPricing dynamicPricing;

  public static void grantPermission(final Application app, final String permission) {
    ShadowApplication shadowApp = Shadows.shadowOf(app);
    shadowApp.grantPermissions(permission);
  }

  @Before public void setUp() throws IOException {
    DynamicPricing.INSTANCES.clear();

    initMocks(this);
    application = mockApplication();
    traits = Traits.create();
    when(traitsCache.get()).thenReturn(traits);
    analyticsContext = createContext(traits);
    factory = new Integration.Factory() {
      @Override public Integration<?> create(ValueMap settings, DynamicPricing dynamicPricing) {
        return integration;
      }

      @Override public String key() {
        return "test";
      }
    };
    when(projectSettingsCache.get()) //
        .thenReturn(ProjectSettings.create(Cartographer.INSTANCE.fromJson(SETTINGS)));

    SharedPreferences sharedPreferences =
        RuntimeEnvironment.application.getSharedPreferences("analytics-test", MODE_PRIVATE);
    optOut = new BooleanPreference(sharedPreferences, "opt-out-test", false);

    dynamicPricing = new DynamicPricing(application, networkExecutor, stats, traitsCache, analyticsContext,
        defaultOptions, Logger.with(NONE), "qaz", Collections.singletonList(factory), client,
        Cartographer.INSTANCE, projectSettingsCache, "foo", DEFAULT_FLUSH_QUEUE_SIZE,
        DEFAULT_FLUSH_INTERVAL, analyticsExecutor, false, new CountDownLatch(0), false, optOut);

    // Used by singleton tests.
    grantPermission(RuntimeEnvironment.application, Manifest.permission.INTERNET);
  }

  @After public void tearDown() {
    RuntimeEnvironment.application.getSharedPreferences("sweetpricing-android", MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    assertThat(ShadowLog.getLogs()).isEmpty();
  }

  @Test public void invalidIdentify() {
    try {
      dynamicPricing.identify(null, null, null);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Either userId or some traits must be provided.");
    }
  }

  @Test public void identify() {
    dynamicPricing.identify("prateek", new Traits().putUsername("f2prateek"), null);

    verify(integration).identify(argThat(new NoDescriptionMatcher<IdentifyPayload>() {
      @Override protected boolean matchesSafely(IdentifyPayload item) {
        return item.userId().equals("prateek") && item.traits().username().equals("f2prateek");
      }
    }));
  }

  @Test public void identifyUpdatesCache() {
    dynamicPricing.identify("foo", new Traits().putValue("bar", "qaz"), null);

    assertThat(traits).contains(MapEntry.entry("userId", "foo"))
        .contains(MapEntry.entry("bar", "qaz"));
    assertThat(analyticsContext.traits()).contains(MapEntry.entry("userId", "foo"))
        .contains(MapEntry.entry("bar", "qaz"));
    verify(traitsCache).set(traits);
    verify(integration).identify(argThat(new NoDescriptionMatcher<IdentifyPayload>() {
      @Override protected boolean matchesSafely(IdentifyPayload item) {
        // Exercises a bug where payloads didn't pick up userId in identify correctly.
        // https://github.com/segmentio/analytics-android/issues/169
        return item.userId().equals("foo");
      }
    }));
  }

  @Test public void invalidTrack() {
    try {
      dynamicPricing.track(null);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("event must not be null or empty.");
    }
    try {
      dynamicPricing.track("   ");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("event must not be null or empty.");
    }
  }

  @Test public void track() {
    dynamicPricing.track("wrote tests", new Properties().putProductId("100coins"));
    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("wrote tests") && //
            payload.properties().productId().equals("100coins");
      }
    }));
  }

  @Test public void invalidScreen() throws Exception {
    try {
      dynamicPricing.screen(null, null);
      fail("null category and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("either category or name must be provided.");
    }

    try {
      dynamicPricing.screen("", "");
      fail("empty category and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("either category or name must be provided.");
    }
  }

  @Test public void screen() {
    dynamicPricing.screen("android", "saw tests", new Properties().putValue("url", "github.com"));
    verify(integration).screen(argThat(new NoDescriptionMatcher<ScreenPayload>() {
      @Override protected boolean matchesSafely(ScreenPayload payload) {
        return payload.name().equals("saw tests") && //
            payload.category().equals("android") && //
            payload.properties().getString("url").equals("github.com");
      }
    }));
  }

  @Test public void optionsDisableIntegrations() {
    dynamicPricing.screen("foo", "bar", null, new Options().setIntegration("test", false));
    dynamicPricing.track("foo", null, new Options().setIntegration("test", false));
    dynamicPricing.identify("foo", null, new Options().setIntegration("test", false));

    dynamicPricing.screen("foo", "bar", null,
        new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));
    dynamicPricing.track("foo", null, new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));
    dynamicPricing.identify("foo", null, new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));

    verifyNoMoreInteractions(integration);
  }

  @Test public void optOutDisablesEvents() throws IOException {
    dynamicPricing.optOut(true);
    dynamicPricing.track("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test public void emptyTrackingPlan() throws IOException {
    dynamicPricing.projectSettings = ProjectSettings.create(Cartographer.INSTANCE.fromJson("{\n"
        + "  \"integrations\": {\n"
        + "    \"test\": {\n"
        + "      \"foo\": \"bar\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"plan\": {\n"
        + "  }\n"
        + "}"));

    dynamicPricing.track("foo");
    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("foo");
      }
    }));
    verifyNoMoreInteractions(integration);
  }

  @Test public void emptyEventPlan() throws IOException {
    dynamicPricing.projectSettings = ProjectSettings.create(Cartographer.INSTANCE.fromJson("{\n"
        + "  \"integrations\": {\n"
        + "    \"test\": {\n"
        + "      \"foo\": \"bar\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "    }\n"
        + "  }\n"
        + "}"));

    dynamicPricing.track("foo");
    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
        @Override
        protected boolean matchesSafely(TrackPayload payload) {
            return payload.event().equals("foo");
        }
    }));
    verifyNoMoreInteractions(integration);
  }

  @Test public void trackingPlanDisablesEvent() throws IOException {
    dynamicPricing.projectSettings = ProjectSettings.create(Cartographer.INSTANCE.fromJson("{\n"
        + "  \"integrations\": {\n"
        + "    \"test\": {\n"
        + "      \"foo\": \"bar\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"foo\": {\n"
        + "        \"enabled\": false\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}"));

    dynamicPricing.track("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test public void trackingPlanDisablesEventForSingleIntegration() throws IOException {
    dynamicPricing.projectSettings = ProjectSettings.create(Cartographer.INSTANCE.fromJson("{\n"
        + "  \"integrations\": {\n"
        + "    \"test\": {\n"
        + "      \"foo\": \"bar\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"foo\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "          \"test\": false\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}"));

    dynamicPricing.track("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test public void trackingPlanDisabledEventCannotBeOverriddenByOptions() throws IOException {
    dynamicPricing.projectSettings = ProjectSettings.create(Cartographer.INSTANCE.fromJson("{\n"
        + "  \"integrations\": {\n"
        + "    \"test\": {\n"
        + "      \"foo\": \"bar\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"foo\": {\n"
        + "        \"enabled\": false\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}"));

    dynamicPricing.track("foo", null, new Options().setIntegration("test", true));
    verifyNoMoreInteractions(integration);
  }

  @Test public void trackingPlanDisabledEventForIntegrationOverriddenByOptions()
      throws IOException {
    dynamicPricing.projectSettings = ProjectSettings.create(Cartographer.INSTANCE.fromJson("{\n"
        + "  \"integrations\": {\n"
        + "    \"test\": {\n"
        + "      \"foo\": \"bar\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"foo\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "          \"test\": false\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}"));

    dynamicPricing.track("foo", null, new Options().setIntegration("test", true));
    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("foo");
      }
    }));
    verifyNoMoreInteractions(integration);
  }

  @Test public void flush() throws Exception {
    dynamicPricing.flush();

    verify(integration).flush();
  }

  @Test public void getSnapshot() throws Exception {
    dynamicPricing.getSnapshot();

    verify(stats).createSnapshot();
  }

  @Test public void onIntegrationReadyShouldFailForNullKey() {
    try {
      dynamicPricing.onIntegrationReady((String) null, mock(DynamicPricing.Callback.class));
      fail("registering for null integration should fail");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("key cannot be null or empty.");
    }
  }

  @Test public void onIntegrationReady() {
    DynamicPricing.Callback<Void> callback = mock(DynamicPricing.Callback.class);
    dynamicPricing.onIntegrationReady("test", callback);
    verify(callback).onReady(null);
  }

  @Test public void shutdown() {
    assertThat(dynamicPricing.shutdown).isFalse();
    dynamicPricing.shutdown();
    verify(stats).shutdown();
    verify(networkExecutor).shutdown();
    assertThat(dynamicPricing.shutdown).isTrue();

    try {
      dynamicPricing.track("foo");
      fail("Enqueuing a message after shutdown should throw.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.");
    }

    try {
      dynamicPricing.flush();
      fail("Enqueuing a message after shutdown should throw.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.");
    }
  }

  @Test public void shutdownTwice() {
    assertThat(dynamicPricing.shutdown).isFalse();
    dynamicPricing.shutdown();
    dynamicPricing.shutdown();
    verify(stats).shutdown();
    assertThat(dynamicPricing.shutdown).isTrue();
  }

  @Test public void shutdownDisallowedOnCustomSingletonInstance() throws Exception {
    DynamicPricing.singleton = null;
    try {
      DynamicPricing dynamicPricing = new DynamicPricing.Builder(RuntimeEnvironment.application, "foo").build();
      DynamicPricing.setSingletonInstance(dynamicPricing);
      dynamicPricing.shutdown();
      fail("Calling shutdown() on static singleton instance should throw");
    } catch (UnsupportedOperationException ignored) {
    }
  }

  @Test public void setSingletonInstanceMayOnlyBeCalledOnce() {
    DynamicPricing.singleton = null;

    DynamicPricing dynamicPricing = new DynamicPricing.Builder(RuntimeEnvironment.application, "foo").build();
    DynamicPricing.setSingletonInstance(dynamicPricing);

    try {
      DynamicPricing.setSingletonInstance(dynamicPricing);
      fail("Can't set singleton instance twice.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Singleton instance already exists.");
    }
  }

  @Test public void setSingletonInstanceAfterWithFails() {
    DynamicPricing.singleton = null;

    DynamicPricing.setSingletonInstance(new DynamicPricing.Builder(RuntimeEnvironment.application, "foo") //
            .build());

    DynamicPricing dynamicPricing = new DynamicPricing.Builder(RuntimeEnvironment.application, "bar").build();
    try {
      DynamicPricing.setSingletonInstance(dynamicPricing);
      fail("Can't set singleton instance after with().");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Singleton instance already exists.");
    }
  }

  @Test public void setSingleInstanceReturnedFromWith() {
    DynamicPricing.singleton = null;
    DynamicPricing dynamicPricing = new DynamicPricing.Builder(RuntimeEnvironment.application, "foo").build();
    DynamicPricing.setSingletonInstance(dynamicPricing);
    assertThat(DynamicPricing.with(RuntimeEnvironment.application)).isSameAs(dynamicPricing);
  }

  @Test public void multipleInstancesWithSameTagThrows() throws Exception {
    new DynamicPricing.Builder(RuntimeEnvironment.application, "foo").build();
    try {
      new DynamicPricing.Builder(RuntimeEnvironment.application, "bar").tag("foo").build();
      fail("Creating client with duplicate should throw.");
    } catch (IllegalStateException expected) {
      assertThat(expected) //
          .hasMessageContaining("Duplicate dynamicPricing client created with tag: foo.");
    }
  }

  @Test public void multipleInstancesWithSameTagIsAllowedAfterShutdown() throws Exception {
    new DynamicPricing.Builder(RuntimeEnvironment.application, "foo").build().shutdown();
    new DynamicPricing.Builder(RuntimeEnvironment.application, "bar").tag("foo").build();
  }

  @Test public void getSnapshotInvokesStats() throws Exception {
    dynamicPricing.getSnapshot();
    verify(stats).createSnapshot();
  }

  @Test public void trackApplicationLifecycleEventsInstalled() throws NameNotFoundException {
    DynamicPricing.INSTANCES.clear();

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionCode = 100;
    packageInfo.versionName = "1.0.0";

    PackageManager packageManager = mock(PackageManager.class);
    when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
    when(application.getPackageName()).thenReturn("com.foo");
    when(application.getPackageManager()).thenReturn(packageManager);

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing().when(application)
        .registerActivityLifecycleCallbacks(
            argThat(new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
              @Override
              protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                callback.set(item);
                return true;
              }
            }));

    dynamicPricing = new DynamicPricing(application, networkExecutor, stats, traitsCache, analyticsContext,
        defaultOptions, Logger.with(NONE), "qaz", Collections.singletonList(factory), client,
        Cartographer.INSTANCE, projectSettingsCache, "foo", DEFAULT_FLUSH_QUEUE_SIZE,
        DEFAULT_FLUSH_INTERVAL, analyticsExecutor, true, new CountDownLatch(0), false, optOut);

    callback.get().onActivityCreated(null, null);

    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("Application Installed") && //
            payload.properties().getString("version").equals("1.0.0") && //
            payload.properties().getInt("build", -1) == 100;
      }
    }));
    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("Application Started") && //
            payload.properties().getString("version").equals("1.0.0") && //
            payload.properties().getInt("build", -1) == 100;
      }
    }));

    callback.get().onActivityCreated(null, null);
    verify(integration, times(2)).onActivityCreated(null, null);
    verifyNoMoreInteractions(integration);
  }

  @Test public void trackApplicationLifecycleEventsUpdated() throws NameNotFoundException {
    DynamicPricing.INSTANCES.clear();

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionCode = 101;
    packageInfo.versionName = "1.0.1";

    SharedPreferences sharedPreferences =
        RuntimeEnvironment.application.getSharedPreferences("sweetpricing-android", MODE_PRIVATE);
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putInt("build", 100);
    editor.putString("version", "1.0.0");
    editor.apply();

    PackageManager packageManager = mock(PackageManager.class);
    when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
    when(application.getPackageName()).thenReturn("com.foo");
    when(application.getPackageManager()).thenReturn(packageManager);

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing().when(application)
        .registerActivityLifecycleCallbacks(
            argThat(new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
              @Override
              protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                callback.set(item);
                return true;
              }
            }));

    dynamicPricing = new DynamicPricing(application, networkExecutor, stats, traitsCache, analyticsContext,
        defaultOptions, Logger.with(NONE), "qaz", Collections.singletonList(factory), client,
        Cartographer.INSTANCE, projectSettingsCache, "foo", DEFAULT_FLUSH_QUEUE_SIZE,
        DEFAULT_FLUSH_INTERVAL, analyticsExecutor, true, new CountDownLatch(0), false, optOut);

    callback.get().onActivityCreated(null, null);

    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("Application Updated") && //
            payload.properties().getString("previous_version").equals("1.0.0") && //
            payload.properties().getInt("previous_build", -1) == 100 && //
            payload.properties().getString("version").equals("1.0.1") && //
            payload.properties().getInt("build", -1) == 101;
      }
    }));
    verify(integration).track(argThat(new NoDescriptionMatcher<TrackPayload>() {
      @Override protected boolean matchesSafely(TrackPayload payload) {
        return payload.event().equals("Application Started") && //
            payload.properties().getString("version").equals("1.0.1") && //
            payload.properties().getInt("build", -1) == 101;
      }
    }));
  }

  @Test public void recordScreenViews() throws NameNotFoundException {
    DynamicPricing.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing().when(application)
        .registerActivityLifecycleCallbacks(
            argThat(new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
              @Override
              protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                callback.set(item);
                return true;
              }
            }));

    dynamicPricing = new DynamicPricing(application, networkExecutor, stats, traitsCache, analyticsContext,
        defaultOptions, Logger.with(NONE), "qaz", Collections.singletonList(factory), client,
        Cartographer.INSTANCE, projectSettingsCache, "foo", DEFAULT_FLUSH_QUEUE_SIZE,
        DEFAULT_FLUSH_INTERVAL, analyticsExecutor, false, new CountDownLatch(0), true, optOut);

    Activity activity = mock(Activity.class);
    PackageManager packageManager = mock(PackageManager.class);
    ActivityInfo info = mock(ActivityInfo.class);

    when(activity.getPackageManager()).thenReturn(packageManager);
    //noinspection WrongConstant
    when(packageManager.getActivityInfo(any(ComponentName.class), eq(PackageManager.GET_META_DATA)))
        .thenReturn(info);
    when(info.loadLabel(packageManager)).thenReturn("Foo");

    callback.get().onActivityStarted(activity);

    verify(integration).screen(argThat(new NoDescriptionMatcher<ScreenPayload>() {
      @Override protected boolean matchesSafely(ScreenPayload payload) {
        return payload.name().equals("Foo");
      }
    }));
  }

  @Test public void registerActivityLifecycleCallbacks() throws NameNotFoundException {
    DynamicPricing.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing().when(application)
        .registerActivityLifecycleCallbacks(
            argThat(new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
              @Override
              protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                callback.set(item);
                return true;
              }
            }));

    dynamicPricing = new DynamicPricing(application, networkExecutor, stats, traitsCache, analyticsContext,
        defaultOptions, Logger.with(NONE), "qaz", Collections.singletonList(factory), client,
        Cartographer.INSTANCE, projectSettingsCache, "foo", DEFAULT_FLUSH_QUEUE_SIZE,
        DEFAULT_FLUSH_INTERVAL, analyticsExecutor, false, new CountDownLatch(0), false, optOut);

    Activity activity = mock(Activity.class);
    Bundle bundle = new Bundle();

    callback.get().onActivityCreated(activity, bundle);
    verify(integration).onActivityCreated(activity, bundle);

    callback.get().onActivityStarted(activity);
    verify(integration).onActivityStarted(activity);

    callback.get().onActivityResumed(activity);
    verify(integration).onActivityResumed(activity);

    callback.get().onActivityPaused(activity);
    verify(integration).onActivityPaused(activity);

    callback.get().onActivityStopped(activity);
    verify(integration).onActivityStopped(activity);

    callback.get().onActivitySaveInstanceState(activity, bundle);
    verify(integration).onActivitySaveInstanceState(activity, bundle);

    callback.get().onActivityDestroyed(activity);
    verify(integration).onActivityDestroyed(activity);

    verifyNoMoreInteractions(integration);
  }
}
