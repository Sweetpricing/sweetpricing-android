package com.sweetpricing.dynamicpricing.integrations;

import android.app.Activity;
import android.os.Bundle;
import com.sweetpricing.dynamicpricing.DynamicPricing;
import com.sweetpricing.dynamicpricing.ValueMap;

/**
 * Converts Segment messages to a format a bundled integration understands, and calls those
 * methods.
 *
 * @param <T> The type of the backing instance. This isn't strictly necessary (since we return an
 * object), but serves as documentation for what type to expect with
 * {@link #getUnderlyingInstance()}.
 */
public abstract class Integration<T> {

  public interface Factory {
    /**
     * Attempts to create an adapter for with {@code settings}. This
     * returns the adapter if one was created, or null if this factory isn't capable of creating
     * such an adapter.
     */
    Integration<?> create(ValueMap settings, DynamicPricing dynamicPricing);

    /** The key for which this factory can create an {@link Integration}. */
    String key();
  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivityStarted(Activity activity) {

  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivityResumed(Activity activity) {
  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivityPaused(Activity activity) {
  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivityStopped(Activity activity) {
  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
  }

  /** @see {@link android.app.Application.ActivityLifecycleCallbacks} */
  public void onActivityDestroyed(Activity activity) {
  }

  /**
   * @see {@link DynamicPricing#identify(String, com.sweetpricing.dynamicpricing.Traits,
   * com.sweetpricing.dynamicpricing.Options)}
   */
  public void identify(IdentifyPayload identify) {
  }

  /**
   * @see {@link DynamicPricing#track(String, com.sweetpricing.dynamicpricing.Properties,
   * com.sweetpricing.dynamicpricing.Options)}
   */
  public void track(TrackPayload track) {
  }

  /**
   * @see {@link DynamicPricing#screen(String, String, com.sweetpricing.dynamicpricing.Properties,
   * com.sweetpricing.dynamicpricing.Options)}		
   */
  public void screen(ScreenPayload screen) {
  }

  /** @see {@link DynamicPricing#flush()} */
  public void flush() {
  }

  /** @see {@link DynamicPricing#reset()} */
  public void reset() {
  }

  /**
   * The underlying instance for this provider - used for integration specific actions. This will
   * return {@code null} for SDK's that only provide interactions with static methods
   * (e.g. Localytics).
   */
  public T getUnderlyingInstance() {
    return null;
  }
}
