package com.sweetpricing.dynamicpricing;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.JsonWriter;
import com.sweetpricing.dynamicpricing.integrations.BasePayload;
import com.sweetpricing.dynamicpricing.integrations.IdentifyPayload;
import com.sweetpricing.dynamicpricing.integrations.Integration;
import com.sweetpricing.dynamicpricing.integrations.Logger;
import com.sweetpricing.dynamicpricing.integrations.ScreenPayload;
import com.sweetpricing.dynamicpricing.integrations.TrackPayload;
import com.sweetpricing.dynamicpricing.internal.Utils.AnalyticsThreadFactory;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.sweetpricing.dynamicpricing.internal.Utils.THREAD_PREFIX;
import static com.sweetpricing.dynamicpricing.internal.Utils.closeQuietly;
import static com.sweetpricing.dynamicpricing.internal.Utils.createDirectory;
import static com.sweetpricing.dynamicpricing.internal.Utils.isConnected;
import static com.sweetpricing.dynamicpricing.internal.Utils.isNullOrEmpty;
import static com.sweetpricing.dynamicpricing.internal.Utils.toISO8601Date;

/** Entity that queues payloads on disks and uploads them periodically. */
class SweetpricingIntegration extends Integration<Void> {
  static final Integration.Factory FACTORY = new Integration.Factory() {
    @Override public Integration<?> create(ValueMap settings, DynamicPricing dynamicPricing) {
      return SweetpricingIntegration.create(dynamicPricing.getApplication(), dynamicPricing.client,
          dynamicPricing.cartographer, dynamicPricing.networkExecutor, dynamicPricing.stats,
          Collections.unmodifiableMap(dynamicPricing.bundledIntegrations), dynamicPricing.tag,
          dynamicPricing.flushIntervalInMillis, dynamicPricing.flushQueueSize, dynamicPricing.getLogger());
    }

    @Override public String key() {
      return SWEETPRICING_KEY;
    }
  };

  /**
   * Drop old payloads if queue contains more than 1000 items. Since each item can be at most
   * 15KB, this bounds the queue size to ~15MB (ignoring headers), which also leaves room for
   * QueueFile's 2GB limit.
   */
  static final int MAX_QUEUE_SIZE = 1000;
  /** Our servers only accept payloads < 15KB. */
  static final int MAX_PAYLOAD_SIZE = 15000; // 15KB.
  /**
   * Our servers only accept batches < 500KB. This limit is 475KB to account for extra data
   * that is not present in payloads themselves, but is added later, such as {@code sentAt},
   * {@code integrations} and other json tokens.
   */
  private static final int MAX_BATCH_SIZE = 475000; // 475KB.
  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final String SWEETPRICING_THREAD_NAME = THREAD_PREFIX + "SweetpricingDispatcher";
  static final String SWEETPRICING_KEY = "Sweetpricing";
  private final Context context;
  private final PayloadQueue payloadQueue;
  private final Client client;
  private final int flushQueueSize;
  private final Stats stats;
  private final Handler handler;
  private final HandlerThread sweetpricingThread;
  private final Logger logger;
  private final Map<String, Boolean> bundledIntegrations;
  private final Cartographer cartographer;
  private final ExecutorService networkExecutor;
  private final ScheduledExecutorService flushScheduler;
  /**
   * We don't want to stop adding payloads to our disk queue when we're uploading payloads. So we
   * upload payloads on a network executor instead.
   * <p/>
   * Given:
   * 1. Peek returns the oldest elements
   * 2. Writes append to the tail of the queue
   * 3. Methods on QueueFile are synchronized (so only thread can access it at a time)
   * <p/>
   * We offload flushes to the network executor, read the QueueFile and remove entries on it, while
   * we continue to add payloads to the QueueFile on the default Dispatcher thread.
   * <p/>
   * We could end up in a case where (assuming MAX_QUEUE_SIZE is 10):
   * 1. Executor reads 10 payloads from the QueueFile
   * 2. Dispatcher is told to add an payloads (the 11th) to the queue.
   * 3. Dispatcher sees that the queue size is at it's limit (10).
   * 4. Dispatcher removes an payloads.
   * 5. Dispatcher adds a payload.
   * 6. Executor finishes uploading 10 payloads and proceeds to remove 10 elements from the file.
   * Since the dispatcher already removed the 10th element and added a 11th, this would actually
   * delete the 11th payload that will never get uploaded.
   * <p/>
   * This lock is used ensure that the Dispatcher thread doesn't remove payloads when we're
   * uploading.
   */
  private final Object flushLock = new Object();

  /**
   * Create a {@link QueueFile} in the given folder with the given name. If the underlying file is
   * somehow corrupted, we'll delete it, and try to recreate the file. This method will throw an
   * {@link IOException} if the directory doesn't exist and could not be created.
   */
  private static QueueFile createQueueFile(File folder, String name) throws IOException {
    createDirectory(folder);
    File file = new File(folder, name);
    try {
      return new QueueFile(file);
    } catch (IOException e) {
      //noinspection ResultOfMethodCallIgnored
      if (file.delete()) {
        return new QueueFile(file);
      } else {
        throw new IOException("Could not create queue file (" + name + ") in " + folder + ".");
      }
    }
  }

  static synchronized SweetpricingIntegration create(Context context, Client client,
      Cartographer cartographer, ExecutorService networkExecutor, Stats stats,
      Map<String, Boolean> bundledIntegrations, String tag, long flushIntervalInMillis,
      int flushQueueSize, Logger logger) {
    PayloadQueue payloadQueue;
    try {
      File folder = context.getDir("sweetpricing-disk-queue", Context.MODE_PRIVATE);
      QueueFile queueFile = createQueueFile(folder, tag);
      payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    } catch (IOException e) {
      logger.error(e, "Falling back to memory queue.");
      payloadQueue = new PayloadQueue.MemoryQueue(new ArrayList<byte[]>());
    }
    return new SweetpricingIntegration(context, client, cartographer, networkExecutor, payloadQueue,
            stats, bundledIntegrations, flushIntervalInMillis, flushQueueSize, logger);
  }

  SweetpricingIntegration(Context context, Client client, Cartographer cartographer,
      ExecutorService networkExecutor, PayloadQueue payloadQueue, Stats stats,
      Map<String, Boolean> bundledIntegrations, long flushIntervalInMillis, int flushQueueSize,
      Logger logger) {
    this.context = context;
    this.client = client;
    this.networkExecutor = networkExecutor;
    this.payloadQueue = payloadQueue;
    this.stats = stats;
    this.logger = logger;
    this.bundledIntegrations = bundledIntegrations;
    this.cartographer = cartographer;
    this.flushQueueSize = flushQueueSize;
    this.flushScheduler = Executors.newScheduledThreadPool(1, new AnalyticsThreadFactory());

    sweetpricingThread = new HandlerThread(SWEETPRICING_THREAD_NAME, THREAD_PRIORITY_BACKGROUND);
    sweetpricingThread.start();
    handler = new SweetpricingDispatcherHandler(sweetpricingThread.getLooper(), this);

    long initialDelay = payloadQueue.size() >= flushQueueSize ? 0L : flushIntervalInMillis;
    flushScheduler.scheduleAtFixedRate(new Runnable() {
      @Override public void run() {
        flush();
      }
    }, initialDelay, flushIntervalInMillis, TimeUnit.MILLISECONDS);
  }

  @Override public void identify(IdentifyPayload identify) {
    dispatchEnqueue(identify);
  }

  @Override public void track(TrackPayload track) {
    dispatchEnqueue(track);
  }

  @Override public void screen(ScreenPayload screen) {
    dispatchEnqueue(screen);
  }

  private void dispatchEnqueue(BasePayload payload) {
    handler.sendMessage(handler.obtainMessage(SweetpricingDispatcherHandler.REQUEST_ENQUEUE,
            payload));
  }

  void performEnqueue(BasePayload original) {
    // Override any user provided values with anything that was bundled.
    // e.g. If user did Mixpanel: true and it was bundled, this would correctly override it with
    // false so that the server doesn't send that event as well.
    ValueMap providedIntegrations = original.integrations();
    LinkedHashMap<String, Object> combinedIntegrations =
        new LinkedHashMap<>(providedIntegrations.size() + bundledIntegrations.size());
    combinedIntegrations.putAll(providedIntegrations);
    combinedIntegrations.putAll(bundledIntegrations);
    combinedIntegrations.remove("Sweetpricing"); // don't include the Segment integration.
    // Make a copy of the payload so we don't mutate the original.
    ValueMap payload = new ValueMap();
    payload.putAll(original);
    payload.put("integrations", combinedIntegrations);

    if (payloadQueue.size() >= MAX_QUEUE_SIZE) {
      synchronized (flushLock) {
        // Double checked locking, the network executor could have removed payload from the queue
        // to bring it below our capacity while we were waiting.
        if (payloadQueue.size() >= MAX_QUEUE_SIZE) {
          logger.info("Queue is at max capacity (%s), removing oldest payload.",
              payloadQueue.size());
          try {
            payloadQueue.remove(1);
          } catch (IOException e) {
            logger.error(e, "Unable to remove oldest payload from queue.");
            return;
          }
        }
      }
    }

    try {
      String payloadJson = cartographer.toJson(payload);
      if (isNullOrEmpty(payloadJson) || payloadJson.length() > MAX_PAYLOAD_SIZE) {
        throw new IOException("Could not serialize payload " + payload);
      }
      payloadQueue.add(payloadJson.getBytes(UTF_8));
    } catch (IOException e) {
      logger.error(e, "Could not add payload %s to queue: %s.", payload, payloadQueue);
      return;
    }

    logger.verbose("Enqueued %s payload. %s elements in the queue.", payload, payloadQueue.size());
    if (payloadQueue.size() >= flushQueueSize) {
      submitFlush();
    }
  }

  /** Enqueues a flush message to the handler. */
  @Override public void flush() {
    handler.sendMessage(handler.obtainMessage(SweetpricingDispatcherHandler.REQUEST_FLUSH));
  }

  /** Submits a flush message to the network executor. */
  void submitFlush() {
    if (!shouldFlush()) {
      return;
    }

    networkExecutor.submit(new Runnable() {
      @Override public void run() {
        synchronized (flushLock) {
          performFlush();
        }
      }
    });
  }

  private boolean shouldFlush() {
    return payloadQueue.size() > 0 && isConnected(context);
  }

  /** Upload payloads to our servers and remove them from the queue file. */
  private void performFlush() {
    // Conditions could have changed between enqueuing the task and when it is run.
    if (!shouldFlush()) {
      return;
    }

    logger.verbose("Uploading payloads in queue to Sweetpricing.");
    int payloadsUploaded;

    Client.Connection connection = null;
    try {
      // Open a connection.
      connection = client.upload();

      // Write the payloads into the OutputStream.
      BatchPayloadWriter writer = new BatchPayloadWriter(connection.os) //
          .beginObject() //
          .beginBatchArray();
      PayloadWriter payloadWriter = new PayloadWriter(writer);
      payloadQueue.forEach(payloadWriter);
      writer.endBatchArray().endObject().close();
      // Don't use the result of QueueFiles#forEach, since we may not read the last element.
      payloadsUploaded = payloadWriter.payloadCount;

      try {
        // Upload the payloads.
        connection.close();
      } catch (Client.UploadException e) {
        // Simply log and proceed to remove the rejected payloads from the queue.
        logger.error(e, "Payloads were rejected by server. Marked for removal.");
      }
    } catch (IOException e) {
      logger.error(e, "Error while uploading payloads");
      return;
    } finally {
      closeQuietly(connection);
    }

    try {
      payloadQueue.remove(payloadsUploaded);
    } catch (IOException e) {
      logger.error(e, "Unable to remove " + payloadsUploaded + " payload(s) from queue.");
      return;
    }

    logger.verbose("Uploaded %s payloads. %s remain in the queue.", payloadsUploaded,
        payloadQueue.size());
    stats.dispatchFlush(payloadsUploaded);
    if (payloadQueue.size() > 0) {
      performFlush(); // Flush any remaining items.
    }
  }

  void shutdown() {
    flushScheduler.shutdownNow();
    sweetpricingThread.quit();
    closeQuietly(payloadQueue);
  }

  static class PayloadWriter implements PayloadQueue.ElementVisitor {

    final BatchPayloadWriter writer;
    int size;
    int payloadCount;

    PayloadWriter(BatchPayloadWriter writer) {
      this.writer = writer;
    }

    @Override public boolean read(InputStream in, int length) throws IOException {
      final int newSize = size + length;
      if (newSize > MAX_BATCH_SIZE) return false;
      size = newSize;
      byte[] data = new byte[length];
      //noinspection ResultOfMethodCallIgnored
      in.read(data, 0, length);
      writer.emitPayloadObject(new String(data, UTF_8));
      payloadCount++;
      return true;
    }
  }

  /** A wrapper that emits a JSON formatted batch payload to the underlying writer. */
  static class BatchPayloadWriter implements Closeable {

    private final JsonWriter jsonWriter;
    /** Keep around for writing payloads as Strings. */
    private final BufferedWriter bufferedWriter;
    private boolean needsComma = false;

    BatchPayloadWriter(OutputStream stream) {
      bufferedWriter = new BufferedWriter(new OutputStreamWriter(stream));
      jsonWriter = new JsonWriter(bufferedWriter);
    }

    BatchPayloadWriter beginObject() throws IOException {
      jsonWriter.beginObject();
      return this;
    }

    BatchPayloadWriter beginBatchArray() throws IOException {
      jsonWriter.name("batch").beginArray();
      needsComma = false;
      return this;
    }

    BatchPayloadWriter emitPayloadObject(String payload) throws IOException {
      // Payloads already serialized into json when storing on disk. No need to waste cycles
      // deserializing them.
      if (needsComma) {
        bufferedWriter.write(',');
      } else {
        needsComma = true;
      }
      bufferedWriter.write(payload);
      return this;
    }

    BatchPayloadWriter endBatchArray() throws IOException {
      if (!needsComma) {
        throw new IOException("At least one payload must be provided.");
      }
      jsonWriter.endArray();
      return this;
    }

    BatchPayloadWriter endObject() throws IOException {
      /**
       * The sent timestamp is an ISO-8601-formatted string that, if present on a message, can be
       * used to correct the original timestamp in situations where the local clock cannot be
       * trusted, for example in our mobile libraries. The sentAt and receivedAt timestamps will be
       * assumed to have occurred at the same time, and therefore the difference is the local clock
       * skew.
       */
      jsonWriter.name("sentAt").value(toISO8601Date(new Date())).endObject();
      return this;
    }

    @Override public void close() throws IOException {
      jsonWriter.close();
    }
  }

  static class SweetpricingDispatcherHandler extends Handler {

    static final int REQUEST_FLUSH = 1;
    private static final int REQUEST_ENQUEUE = 0;
    private final SweetpricingIntegration sweetpricingIntegration;

    SweetpricingDispatcherHandler(Looper looper, SweetpricingIntegration sweetpricingIntegration) {
      super(looper);
      this.sweetpricingIntegration = sweetpricingIntegration;
    }

    @Override public void handleMessage(final Message msg) {
      switch (msg.what) {
        case REQUEST_ENQUEUE:
          BasePayload payload = (BasePayload) msg.obj;
          sweetpricingIntegration.performEnqueue(payload);
          break;
        case REQUEST_FLUSH:
          sweetpricingIntegration.submitFlush();
          break;
        default:
          throw new AssertionError("Unknown dispatcher message: " + msg.what);
      }
    }
  }
}
