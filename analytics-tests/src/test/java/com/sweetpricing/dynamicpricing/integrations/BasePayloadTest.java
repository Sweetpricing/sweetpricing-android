package com.sweetpricing.dynamicpricing.integrations;

import com.sweetpricing.dynamicpricing.AnalyticsContext;
import com.sweetpricing.dynamicpricing.Options;
import com.sweetpricing.dynamicpricing.Traits;
import com.sweetpricing.dynamicpricing.core.tests.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, emulateSdk = 18, manifest = Config.NONE)
public class BasePayloadTest {

  @Test public void newInvocationIsCreatedWithDefaults() {
    AnalyticsContext analyticsContext = mock(AnalyticsContext.class);
    when(analyticsContext.unmodifiableCopy()).thenReturn(analyticsContext);
    when(analyticsContext.traits()).thenReturn(mock(Traits.class));

    RealPayload realPayload =
        new RealPayload(BasePayload.Type.identify, analyticsContext, new Options());

    assertThat(realPayload).containsEntry("type", BasePayload.Type.identify);
    assertThat(realPayload).containsEntry("type", BasePayload.Type.identify);
    assertThat(realPayload).containsEntry("type", BasePayload.Type.identify);
  }

  static class RealPayload extends BasePayload {

    public RealPayload(Type type, AnalyticsContext context, Options options) {
      super(type, context, options);
    }
  }
}
