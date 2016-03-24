/*
 * Copyright 2014 Prateek Srivastava
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

package com.sweetpricing.analytics.internal;

import android.content.Context;
import com.sweetpricing.analytics.core.tests.BuildConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static com.sweetpricing.analytics.internal.Utils.isConnected;
import static com.sweetpricing.analytics.internal.Utils.isNullOrEmpty;
import static com.sweetpricing.analytics.internal.Utils.transform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, emulateSdk = 18, manifest = Config.NONE)
public class UtilsTest {

  @Mock Context context;

  @Before public void setUp() {
    initMocks(this);
  }

  @Test public void emptyString() {
    assertThat(isNullOrEmpty((String) null)).isTrue();
    assertThat(isNullOrEmpty("")).isTrue();
    assertThat(isNullOrEmpty("    ")).isTrue();
    assertThat(isNullOrEmpty("  a  ")).isFalse();
    assertThat(isNullOrEmpty("a")).isFalse();
  }

  @Test public void emptyMap() {
    assertThat(isNullOrEmpty((Map) null)).isTrue();
    Map<String, Object> map = new LinkedHashMap<>(20);
    assertThat(isNullOrEmpty(map)).isTrue();
    map.put("foo", "bar");
    assertThat(isNullOrEmpty(map)).isFalse();
    map.clear();
    assertThat(isNullOrEmpty(map)).isTrue();
  }

  @Test public void emptyCollections() {
    assertThat(isNullOrEmpty((Collection) null)).isTrue();
    Collection<String> collection = new ArrayList<>();
    assertThat(isNullOrEmpty(collection)).isTrue();
    collection.add("foo");
    assertThat(isNullOrEmpty(collection)).isFalse();
    collection.clear();
    assertThat(isNullOrEmpty(collection)).isTrue();
  }

  @Test public void testTransform() {
    Map<String, Integer> in = new LinkedHashMap<>();
    in.put("a", 1);
    in.put("b", 2);
    in.put("c", 3);
    in.put("d", 4);

    Map<String, String> mapper = new LinkedHashMap<>();
    mapper.put("a", "$a");
    mapper.put("c", "");
    mapper.put("d", null);

    assertThat(transform(in, mapper)).containsExactly(MapEntry.entry("$a", 1),
        MapEntry.entry("b", 2));
  }

  @Test public void returnsConnectedIfMissingPermission() throws Exception {
    when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)).thenReturn(PERMISSION_DENIED);
    assertThat(isConnected(context)).isTrue();
  }
}
