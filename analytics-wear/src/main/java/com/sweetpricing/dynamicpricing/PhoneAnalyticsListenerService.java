/*
 * Copyright 2014 Prateek Srivastava
 * Modified work Copyright (c) 2016 Sweet Pricing Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sweetpricing.dynamicpricing;

import android.annotation.SuppressLint;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.io.IOException;

/**
 * A {@link WearableListenerService} that listens for analytics events from a wear device.
 * <p/>
 * Clients may subclass this and override {@link #getAnalytics()} to provide custom instances of
 * {@link DynamicPricing} client. Ideally, it should be the same instance as the client you're using to
 * track events on the host Android device.
 */
@SuppressLint("Registered")
public class PhoneAnalyticsListenerService extends WearableListenerService {

  final Cartographer cartographer = Cartographer.INSTANCE;

  @Override public void onMessageReceived(MessageEvent messageEvent) {
    super.onMessageReceived(messageEvent);

    if (WearAnalytics.ANALYTICS_PATH.equals(messageEvent.getPath())) {
      WearPayload wearPayload;
      try {
        wearPayload = new WearPayload(cartographer.fromJson(new String(messageEvent.getData())));
      } catch (IOException e) {
        getAnalytics().getLogger()
            .error(e, "Could not deserialize event %s", new String(messageEvent.getData()));
        return;
      }
      switch (wearPayload.type()) {
        case track:
          WearTrackPayload wearTrackPayload = wearPayload.payload(WearTrackPayload.class);
          getAnalytics().track(wearTrackPayload.getEvent(), wearTrackPayload.getProperties(), null);
          break;
        default:
          throw new UnsupportedOperationException("Only track/screen calls may be sent from Wear.");
      }
    }
  }

  public DynamicPricing getAnalytics() {
    return DynamicPricing.with(this);
  }
}
