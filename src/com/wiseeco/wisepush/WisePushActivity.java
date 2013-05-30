package com.wiseeco.wisepush;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.provider.Settings.Secure;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class WisePushActivity extends Activity 
{
	private String mDeviceID;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        setTitle(R.string.app_name);
        
        
        /** topic 정보를 화면에 표시해 줌. */ 
        mDeviceID = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);         
  	  	((TextView) findViewById(R.id.target_text)).setText("/" + 
  	  			WisePushService.MQTT_CLIENT_ID + "/" + mDeviceID);

  	  	final Button startButton = (Button) findViewById(R.id.start_button);
  		final Button stopButton = (Button) findViewById(R.id.stop_button);
		startButton.setOnClickListener(new OnClickListener() {			
			@Override
			public void onClick(View v) {
		    	Editor editor = getSharedPreferences(WisePushService.TAG, MODE_PRIVATE).edit();
		    	editor.putString(WisePushService.PREF_DEVICE_ID, mDeviceID);
		    	editor.commit();
				WisePushService.actionStart(getApplicationContext());		        
		  	  	
		  		startButton.setEnabled(false);
		  		stopButton.setEnabled(true);
			}
		});
  	  	stopButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				WisePushService.actionStop(getApplicationContext());
		  		startButton.setEnabled(true);
		  		stopButton.setEnabled(false);
			}
		});
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
  	  	
  	  	SharedPreferences p = getSharedPreferences(WisePushService.TAG, MODE_PRIVATE);
  	  	boolean started = p.getBoolean(WisePushService.PREF_STARTED, false);
  	  	
  	  	if (started)
  	  		started = false;
  		((Button) findViewById(R.id.start_button)).setEnabled(!started);
  		((Button) findViewById(R.id.stop_button)).setEnabled(started);

    }
}