package com.sweetpricing.dynamicpricing;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import com.sweetpricing.dynamicpricing.core.tests.BuildConfig;
import com.sweetpricing.dynamicpricing.integrations.Logger;
import com.sweetpricing.dynamicpricing.internal.Utils;
import com.sweetpricing.dynamicpricing.integrations.TrackPayload;
import com.sweetpricing.dynamicpricing.test.TrackPayloadBuilder;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static com.sweetpricing.dynamicpricing.DynamicPricing.LogLevel.NONE;
import static com.sweetpricing.dynamicpricing.SweetpricingIntegration.MAX_QUEUE_SIZE;
import static com.sweetpricing.dynamicpricing.TestUtils.SynchronousExecutor;
import static com.sweetpricing.dynamicpricing.TestUtils.TRACK_PAYLOAD;
import static com.sweetpricing.dynamicpricing.TestUtils.TRACK_PAYLOAD_JSON;
import static com.sweetpricing.dynamicpricing.TestUtils.mockApplication;
import static com.sweetpricing.dynamicpricing.Utils.createContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, emulateSdk = 18, manifest = Config.NONE)
public class SweetpricingIntegrationTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  private static Client.Connection mockConnection() {
    return mockConnection(mock(HttpURLConnection.class));
  }

  private static Client.Connection mockConnection(HttpURLConnection connection) {
    return new Client.Connection(connection, mock(InputStream.class), mock(OutputStream.class)) {
      @Override public void close() throws IOException {
        super.close();
      }
    };
  }

  @After public void tearDown() {
    assertThat(ShadowLog.getLogs()).isEmpty();
  }

  @Test public void enqueueAddsToQueueFile() throws IOException {
    QueueFile queueFile = mock(QueueFile.class);
    SweetpricingIntegration sweetpricingIntegration = new SweetpricingBuilder().queueFile(queueFile).build();

    sweetpricingIntegration.performEnqueue(TRACK_PAYLOAD);

    verify(queueFile).add(TRACK_PAYLOAD_JSON.getBytes());
  }

  @Test public void enqueueWritesIntegrations() throws IOException {
    final HashMap<String, Boolean> integrations = new LinkedHashMap<>();
    integrations.put("All", false); // should overwrite existing values in the map.
    integrations.put("foo", true); // should add new values.
    QueueFile queueFile = mock(QueueFile.class);
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().queueFile(queueFile).integrations(integrations).build();

    AnalyticsContext analyticsContext = createContext(new Traits());
    TrackPayload trackPayload =
        new TrackPayload(analyticsContext, new Options(), "foo", new Properties());
    // put some predictable values for data that is automatically generated
    trackPayload.put("messageId", "a161304c-498c-4830-9291-fcfb8498877b");
    trackPayload.put("timestamp", "2014-12-15T13:32:44-0700");

    sweetpricingIntegration.performEnqueue(trackPayload);

    String expected = "{\""
        + "messageId\":\"a161304c-498c-4830-9291-fcfb8498877b\","
        + "\"type\":\"track\","
        + "\"channel\":\"mobile\","
        + "\"context\":{\"traits\":{}},"
        + "\"anonymousId\":null,"
        + "\"timestamp\":\"2014-12-15T13:32:44-0700\","
        + "\"integrations\":"
        + "{\"All\":false,\"foo\":true},"
        + "\"event\":\"foo\","
        + "\"properties\":{}"
        + "}";
    verify(queueFile).add(expected.getBytes());
  }

  @Test public void enqueueLimitsQueueSize() throws IOException {
    QueueFile queueFile = mock(QueueFile.class);
    // we want to trigger a remove, but not a flush
    when(queueFile.size()).thenReturn(0, MAX_QUEUE_SIZE, MAX_QUEUE_SIZE, 0);
    SweetpricingIntegration sweetpricingIntegration = new SweetpricingBuilder().queueFile(queueFile).build();

    sweetpricingIntegration.performEnqueue(TRACK_PAYLOAD);

    verify(queueFile).remove(); // oldest entry is removed
    verify(queueFile).add(TRACK_PAYLOAD_JSON.getBytes()); // newest entry is added
  }

  @Test public void exceptionThrownIfFailedToRemove() throws IOException {
    QueueFile queueFile = mock(QueueFile.class);
    doThrow(new IOException("no remove for you.")).when(queueFile).remove();
    when(queueFile.size()).thenReturn(MAX_QUEUE_SIZE); // trigger a remove
    SweetpricingIntegration sweetpricingIntegration = new SweetpricingBuilder().queueFile(queueFile).build();

    try {
      sweetpricingIntegration.performEnqueue(TRACK_PAYLOAD);
      fail("expected QueueFile to throw an error.");
    } catch (IOError expected) {
      assertThat(expected).hasMessage("java.io.IOException: no remove for you.");
      assertThat(expected.getCause()).hasMessage("no remove for you.")
          .isInstanceOf(IOException.class);
    }
  }

  @Test public void enqueueMaxTriggersFlush() throws IOException {
    QueueFile queueFile = new QueueFile(new File(folder.getRoot(), "queue-file"));
    Client client = mock(Client.class);
    Client.Connection connection = mockConnection();
    when(client.upload()).thenReturn(connection);
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().client(client).flushSize(5).queueFile(queueFile).build();

    for (int i = 0; i < 4; i++) {
      sweetpricingIntegration.performEnqueue(TRACK_PAYLOAD);
    }
    verifyZeroInteractions(client);
    // Only the last enqueue should trigger an upload.
    sweetpricingIntegration.performEnqueue(TRACK_PAYLOAD);

    verify(client).upload();
  }

  @Test public void flushRemovesItemsFromQueue() throws IOException {
    QueueFile queueFile = new QueueFile(new File(folder.getRoot(), "queue-file"));
    Client client = mock(Client.class);
    when(client.upload()).thenReturn(mockConnection());
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().client(client).queueFile(queueFile).build();
    byte[] bytes = TRACK_PAYLOAD_JSON.getBytes();
    for (int i = 0; i < 4; i++) {
      queueFile.add(bytes);
    }

    sweetpricingIntegration.submitFlush();

    assertThat(queueFile.size()).isEqualTo(0);
  }

  @Test public void flushSubmitsToExecutor() throws IOException {
    ExecutorService executor = spy(new SynchronousExecutor());
    QueueFile queueFile = mock(QueueFile.class);
    when(queueFile.size()).thenReturn(1);
    SweetpricingIntegration dispatcher =
        new SweetpricingBuilder().queueFile(queueFile).networkExecutor(executor).build();

    dispatcher.submitFlush();

    verify(executor).submit(any(Runnable.class));
  }

  @Test public void flushWhenDisconnectedSkipsUpload() throws IOException {
    NetworkInfo networkInfo = mock(NetworkInfo.class);
    when(networkInfo.isConnectedOrConnecting()).thenReturn(false);
    ConnectivityManager connectivityManager = mock(ConnectivityManager.class);
    when(connectivityManager.getActiveNetworkInfo()).thenReturn(networkInfo);
    Context context = mockApplication();
    when(context.getSystemService(CONNECTIVITY_SERVICE)).thenReturn(connectivityManager);
    Client client = mock(Client.class);
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().context(context).client(client).build();

    sweetpricingIntegration.submitFlush();

    verify(client, never()).upload();
  }

  @Test public void flushWhenQueueSizeIsLessThanOneSkipsUpload() throws IOException {
    QueueFile queueFile = mock(QueueFile.class);
    when(queueFile.size()).thenReturn(0);
    Context context = mockApplication();
    Client client = mock(Client.class);
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().queueFile(queueFile).context(context).client(client).build();

    sweetpricingIntegration.submitFlush();

    verifyZeroInteractions(context);
    verify(client, never()).upload();
  }

  @Test public void flushDisconnectsConnection() throws IOException {
    Client client = mock(Client.class);
    QueueFile queueFile = new QueueFile(new File(folder.getRoot(), "queue-file"));
    queueFile.add(TRACK_PAYLOAD_JSON.getBytes());
    HttpURLConnection urlConnection = mock(HttpURLConnection.class);
    Client.Connection connection = mockConnection(urlConnection);
    when(client.upload()).thenReturn(connection);
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().client(client).queueFile(queueFile).build();

    sweetpricingIntegration.submitFlush();

    verify(urlConnection, times(2)).disconnect();
  }

  @Test public void serializationErrorSkipsAddingPayload() throws IOException {
    QueueFile queueFile = mock(QueueFile.class);
    Cartographer cartographer = mock(Cartographer.class);
    TrackPayload payload = new TrackPayloadBuilder().build();
    SweetpricingIntegration sweetpricingIntegration =
        new SweetpricingBuilder().cartographer(cartographer).queueFile(queueFile).build();

    // Serialized json is null.
    when(cartographer.toJson(anyMap())).thenReturn(null);
    sweetpricingIntegration.performEnqueue(payload);
    verify(queueFile, never()).add((byte[]) any());

    // Serialized json is empty.
    when(cartographer.toJson(anyMap())).thenReturn("");
    sweetpricingIntegration.performEnqueue(payload);
    verify(queueFile, never()).add((byte[]) any());

    // Serialized json is too large (> 15kb).
    StringBuilder stringBuilder = new StringBuilder();
    for (int i = 0; i < SweetpricingIntegration.MAX_PAYLOAD_SIZE + 1; i++) {
      stringBuilder.append('a');
    }
    when(cartographer.toJson(anyMap())).thenReturn(stringBuilder.toString());
    sweetpricingIntegration.performEnqueue(payload);
    verify(queueFile, never()).add((byte[]) any());

    // Serializing json throws exception.
    doThrow(new IOException("mock")).when(cartographer).toJson(anyMap());
    sweetpricingIntegration.performEnqueue(payload);
    verify(queueFile, never()).add((byte[]) any());
  }

  @Test public void shutdown() throws IOException {
    QueueFile queueFile = mock(QueueFile.class);
    SweetpricingIntegration sweetpricingIntegration = new SweetpricingBuilder().queueFile(queueFile).build();

    sweetpricingIntegration.shutdown();

    verify(queueFile).close();
  }

  @Test public void payloadVisitorReadsOnly475KB() throws IOException {
    SweetpricingIntegration.PayloadWriter payloadWriter =
        new SweetpricingIntegration.PayloadWriter(mock(SweetpricingIntegration.BatchPayloadWriter.class));
    byte[] bytes = ("{\n"
        + "        'context': {\n"
        + "          'library': 'sweetpricing-android',\n"
        + "          'libraryVersion': '0.4.4',\n"
        + "          'telephony': {\n"
        + "            'radio': 'gsm',\n"
        + "            'carrier': 'FI elisa'\n"
        + "          },\n"
        + "          'wifi': {\n"
        + "            'connected': false,\n"
        + "            'available': false\n"
        + "          },\n"
        + "          'providers': {\n"
        + "            'Tapstream': false,\n"
        + "            'Amplitude': false,\n"
        + "            'Localytics': false,\n"
        + "            'Flurry': false,\n"
        + "            'Countly': false,\n"
        + "            'Bugsnag': false,\n"
        + "            'Quantcast': false,\n"
        + "            'Crittercism': false,\n"
        + "            'Google Analytics': false,\n"
        + "            'Omniture': false,\n"
        + "            'Mixpanel': false\n"
        + "          },\n"
        + "          'location': {\n"
        + "            'speed': 0,\n"
        + "            'longitude': 24.937207,\n"
        + "            'latitude': 60.2495497\n"
        + "          },\n"
        + "          'locale': {\n"
        + "            'carrier': 'FI elisa',\n"
        + "            'language': 'English',\n"
        + "            'country': 'United States'\n"
        + "          },\n"
        + "          'device': {\n"
        + "            'userId': '123',\n"
        + "            'brand': 'samsung',\n"
        + "            'release': '4.2.2',\n"
        + "            'manufacturer': 'samsung',\n"
        + "            'sdk': 17\n"
        + "          },\n"
        + "          'display': {\n"
        + "            'density': 1.5,\n"
        + "            'width': 800,\n"
        + "            'height': 480\n"
        + "          },\n"
        + "          'build': {\n"
        + "            'name': '1.0',\n"
        + "            'code': 1\n"
        + "          },\n"
        + "          'ip': '80.186.195.102',\n"
        + "          'inferredIp': true\n"
        + "        }\n"
        + "      }").getBytes(); // length 1432
    QueueFile queueFile = new QueueFile(new File(folder.getRoot(), "queue-file"));
    // Fill the payload with (1432 * 500) = ~716kb of data
    for (int i = 0; i < 500; i++) {
      queueFile.add(bytes);
    }

    queueFile.forEach(payloadWriter);

    // Verify only (331 * 1432) = 473992 < 475KB bytes are read
    assertThat(payloadWriter.payloadCount).isEqualTo(331);
  }

  private static class SweetpricingBuilder {
    Client client;
    Stats stats;
    QueueFile queueFile;
    Context context;
    Cartographer cartographer;
    Map<String, Boolean> integrations;
    int flushInterval = Utils.DEFAULT_FLUSH_INTERVAL;
    int flushSize = Utils.DEFAULT_FLUSH_QUEUE_SIZE;
    Logger logger = Logger.with(NONE);
    ExecutorService networkExecutor;

    SweetpricingBuilder() {
      initMocks(this);
      context = mockApplication();
      when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)) //
          .thenReturn(PERMISSION_DENIED);
      cartographer = Cartographer.INSTANCE;
    }

    public SweetpricingBuilder client(Client client) {
      this.client = client;
      return this;
    }

    public SweetpricingBuilder stats(Stats stats) {
      this.stats = stats;
      return this;
    }

    public SweetpricingBuilder queueFile(QueueFile queueFile) {
      this.queueFile = queueFile;
      return this;
    }

    public SweetpricingBuilder context(Context context) {
      this.context = context;
      return this;
    }

    public SweetpricingBuilder cartographer(Cartographer cartographer) {
      this.cartographer = cartographer;
      return this;
    }

    public SweetpricingBuilder integrations(Map<String, Boolean> integrations) {
      this.integrations = integrations;
      return this;
    }

    public SweetpricingBuilder flushInterval(int flushInterval) {
      this.flushInterval = flushInterval;
      return this;
    }

    public SweetpricingBuilder flushSize(int flushSize) {
      this.flushSize = flushSize;
      return this;
    }

    public SweetpricingBuilder log(Logger logger) {
      this.logger = logger;
      return this;
    }

    public SweetpricingBuilder networkExecutor(ExecutorService networkExecutor) {
      this.networkExecutor = networkExecutor;
      return this;
    }

    SweetpricingIntegration build() {
      if (context == null) {
        context = mockApplication();
        when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)) //
            .thenReturn(PERMISSION_DENIED);
      }
      if (client == null) client = mock(Client.class);
      if (cartographer == null) cartographer = Cartographer.INSTANCE;
      if (queueFile == null) queueFile = mock(QueueFile.class);
      if (stats == null) stats = mock(Stats.class);
      if (integrations == null) integrations = Collections.emptyMap();
      if (networkExecutor == null) networkExecutor = new SynchronousExecutor();
      return new SweetpricingIntegration(context, client, cartographer, networkExecutor, queueFile, stats,
          integrations, flushInterval, flushSize, logger);
    }
  }
}
