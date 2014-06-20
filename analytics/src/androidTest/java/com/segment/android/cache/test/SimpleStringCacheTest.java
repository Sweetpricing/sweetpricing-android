package com.segment.android.cache.test;

import android.content.Context;
import android.test.AndroidTestCase;
import com.segment.android.cache.SimpleStringCache;
import junit.framework.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SimpleStringCacheTest extends AndroidTestCase {

  private SimpleStringCache noLoaderCache;
  private String val;

  @BeforeClass
  public void setup() {
    Context context = this.getContext();
    noLoaderCache = new SimpleStringCache(context, "cache.key");
    val = "cache value";
  }

  @Test
  public void getNull() {
    Assert.assertNull(noLoaderCache.get());
  }

  @Test
  public void set() {
    noLoaderCache.set(val);
    Assert.assertEquals(val, noLoaderCache.get());
  }
}
