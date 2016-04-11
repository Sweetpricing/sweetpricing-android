package com.sweetpricing.dynamicpricing;

import android.app.Activity;
import android.os.Bundle;

import com.sweetpricing.dynamicpricing.integrations.IdentifyPayload;
import com.sweetpricing.dynamicpricing.integrations.Integration;
import com.sweetpricing.dynamicpricing.integrations.TrackPayload;

import static com.sweetpricing.dynamicpricing.Options.ALL_INTEGRATIONS_KEY;
import static com.sweetpricing.dynamicpricing.internal.Utils.isNullOrEmpty;

/** Abstraction for a task that a {@link Integration <?>} can execute. */
abstract class IntegrationOperation {
  static boolean isIntegrationEnabled(ValueMap integrations, String key) {
    if (isNullOrEmpty(integrations)) {
      return true;
    }
    if (SweetpricingIntegration.SWEETPRICING_KEY.equals(key)) {
      return true; // Leave Segment integration enabled.
    }
    boolean enabled = true;
    if (integrations.containsKey(key)) {
      enabled = integrations.getBoolean(key, true);
    } else if (integrations.containsKey(ALL_INTEGRATIONS_KEY)) {
      enabled = integrations.getBoolean(ALL_INTEGRATIONS_KEY, true);
    }
    return enabled;
  }

  static boolean isIntegrationEnabledInPlan(ValueMap plan, String key) {
    boolean eventEnabled = plan.getBoolean("enabled", true);
    if (eventEnabled) {
      // Check if there is an integration specific setting.
      ValueMap integrationPlan = plan.getValueMap("integrations");
      eventEnabled = isIntegrationEnabled(integrationPlan, key);
    }
    return eventEnabled;
  }

  static IntegrationOperation onActivityCreated(final Activity activity, final Bundle bundle) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivityCreated(activity, bundle);
      }

      @Override public String toString() {
        return "Activity Created";
      }
    };
  }

  static IntegrationOperation onActivityStarted(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivityStarted(activity);
      }

      @Override public String toString() {
        return "Activity Started";
      }
    };
  }

  static IntegrationOperation onActivityResumed(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivityResumed(activity);
      }

      @Override public String toString() {
        return "Activity Resumed";
      }
    };
  }

  static IntegrationOperation onActivityPaused(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivityPaused(activity);
      }

      @Override public String toString() {
        return "Activity Paused";
      }
    };
  }

  static IntegrationOperation onActivityStopped(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivityStopped(activity);
      }

      @Override public String toString() {
        return "Activity Stopped";
      }
    };
  }

  static IntegrationOperation onActivitySaveInstanceState(final Activity activity,
      final Bundle bundle) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivitySaveInstanceState(activity, bundle);
      }

      @Override public String toString() {
        return "Activity Save Instance";
      }
    };
  }

  static IntegrationOperation onActivityDestroyed(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        integration.onActivityDestroyed(activity);
      }

      @Override public String toString() {
        return "Activity Destroyed";
      }
    };
  }

  static IntegrationOperation identify(final IdentifyPayload identifyPayload) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        if (isIntegrationEnabled(identifyPayload.integrations(), key)) {
          integration.identify(identifyPayload);
        }
      }

      @Override public String toString() {
        return identifyPayload.toString();
      }
    };
  }

  static IntegrationOperation track(final TrackPayload trackPayload) {
    return new IntegrationOperation() {
      @Override
      public void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
        ValueMap integrationOptions = trackPayload.integrations();

        ValueMap trackingPlan = projectSettings.trackingPlan();
        if (isNullOrEmpty(trackingPlan)) {
          // No tracking plan, use options provided.
          if (isIntegrationEnabled(integrationOptions, key)) {
            integration.track(trackPayload);
          }
          return;
        }

        String event = trackPayload.event();
        ValueMap eventPlan = trackingPlan.getValueMap(event);
        if (isNullOrEmpty(eventPlan)) {
          // No event plan, use options provided.
          if (isIntegrationEnabled(integrationOptions, key)) {
            integration.track(trackPayload);
          }
          return;
        }

        // We have a tracking plan for the event.
        boolean isEnabled = eventPlan.getBoolean("enabled", true);
        if (!isEnabled) {
          // If event is disabled in the tracking plan, don't send it to any
          // integrations (Segment included), regardless of options in code.
          return;
        }

        ValueMap integrations = new ValueMap();
        ValueMap eventIntegrations = eventPlan.getValueMap("integrations");
        if (!isNullOrEmpty(eventIntegrations)) {
          integrations.putAll(eventIntegrations);
        }
        integrations.putAll(integrationOptions);
        if (isIntegrationEnabled(integrations, key)) {
          integration.track(trackPayload);
        }
      }

      @Override public String toString() {
        return trackPayload.toString();
      }
    };
  }

  static final IntegrationOperation FLUSH = new IntegrationOperation() {
    @Override void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
      integration.flush();
    }

    @Override public String toString() {
      return "Flush";
    }
  };

  static final IntegrationOperation RESET = new IntegrationOperation() {
    @Override void run(String key, Integration<?> integration, ProjectSettings projectSettings) {
      integration.reset();
    }

    @Override public String toString() {
      return "Reset";
    }
  };

  private IntegrationOperation() {
  }

  /** Run this operation on the given integration. */
  abstract void run(String key, Integration<?> integration, ProjectSettings projectSettings);
}
