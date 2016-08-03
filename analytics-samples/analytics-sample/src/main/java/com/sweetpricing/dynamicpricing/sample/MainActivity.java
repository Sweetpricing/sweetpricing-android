/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment, Inc.
 * Modified work Copyright (c) 2016 Sweet Pricing Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.sweetpricing.dynamicpricing.sample;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.sweetpricing.dynamicpricing.DynamicPricing;
import com.sweetpricing.dynamicpricing.FetchVariantTask;
import com.sweetpricing.dynamicpricing.Properties;
import com.sweetpricing.dynamicpricing.Variant;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
  /**
   * The following IDs are the product group ID and product ID configured
   * within Sweet Pricing.
   */
  private static final int SP_STORE_ID = 3;
  private static final int SP_1MONTH_ID = 7;
  private static final int SP_1YEAR_ID = 8;
  private static final String DEFAULT_1MONTH = "com.sweetpricing.default.1month";
  private static final String DEFAULT_1YEAR = "com.sweetpricing.default.1year";

  /** Returns true if the string is null, or empty (when trimmed). */
  public static boolean isNullOrEmpty(String text) {
    return TextUtils.isEmpty(text) || text.trim().length() == 0;
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);
    initViews();
  }

  private void initViews() {
    findViewById(R.id.action_get_sku).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        updateProductSku();
      }
    });

    findViewById(R.id.action_track_purchase).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        String productId =
                ((EditText) findViewById(R.id.action_track_purchase_product_id)).getText().toString();

        if (isNullOrEmpty(productId)) {
          Toast.makeText(MainActivity.this, R.string.product_id_required, Toast.LENGTH_LONG).show();
        } else {
          DynamicPricing.with(MainActivity.this).trackPurchase(productId);
        }
      }
    });

    findViewById(R.id.action_track_event).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        DynamicPricing.with(MainActivity.this).track("Logged In");
      }
    });
  }

  private void updateProductSku() {
    // When you need to obtain a pricepoint SKU for a particular product, you must create a
    // new FetchVariantTask...
    FetchVariantTask fetchVariantTask = new FetchVariantTask(DynamicPricing.with(MainActivity.this)) {
      @Override
      protected void onPostExecute(Variant variant) {
        // if there was an error, e will be an Exception
        if (e != null) {
          Log.e("MainActivity", "Error getting variant", e);
        }

        // even if there is an error, a variant object is returned
        // if there was a problem obtaining the data, you should specify a default SKU
        // to fall back on...
        String google1MonthId = variant.getProductSku(SP_1MONTH_ID, DEFAULT_1MONTH);
        String google1YearId = variant.getProductSku(SP_1YEAR_ID, DEFAULT_1YEAR);
        ((TextView) findViewById(R.id.current_sku_1month)).setText(google1MonthId);
        ((TextView) findViewById(R.id.current_sku_1year)).setText(google1YearId);

        List<Properties> productsList = new ArrayList<>();
        productsList.add(new Properties()
                .putValue("price", 7.99)
                .putValue("currencyCode", "USD")
                .putValue("productId", google1MonthId));
        productsList.add(new Properties()
                .putValue("price", 29.99)
                .putValue("currencyCode", "USD")
                .putValue("productId", google1YearId));

        DynamicPricing.with(MainActivity.this).trackViewStore(
                variant,
                productsList
        );
      }
    };

    // ... Execute this task with the product **GROUP** you are displaying. This will fetch
    // all the pricepoint SKUs within this group for the current user's segment.
    fetchVariantTask.execute(SP_STORE_ID);
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_view_docs) {
      Intent intent = new Intent(Intent.ACTION_VIEW,
          Uri.parse("https://sweetpricing.com/docs"));
      try {
        startActivity(intent);
      } catch (ActivityNotFoundException e) {
        Toast.makeText(this, R.string.no_browser_available, Toast.LENGTH_LONG).show();
      }
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
