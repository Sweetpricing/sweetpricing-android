package com.sweetpricing.dynamicpricing;

import com.sweetpricing.dynamicpricing.core.tests.BuildConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, emulateSdk = 18, manifest = Config.NONE)
public class OptionsTest {

  Options options;

  @Before public void setUp() {
    options = new Options();
  }

  @Test public void disallowsDisablingSweetpricingIntegration() throws Exception {
    try {
      options.setIntegration("Sweetpricing", Randoms.nextBoolean());
      fail("shouldn't be able to set option for Sweetpricing integration.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("Sweetpricing integration cannot be enabled or disabled.");
    }
  }
}
