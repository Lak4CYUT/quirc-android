package com.lakxtab.android.quircdemo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class ScanActivity extends Activity
{

    // Used to load the 'native-lib' library on application startup.
    static
    {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        // Example of a call to a native method
        TextView tv = (TextView) findViewById(R.id.sample_text);
    }
}
