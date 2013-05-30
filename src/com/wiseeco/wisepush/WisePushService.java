package com.wiseeco.wisepush;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.util.Log;

import com.ibm.mqtt.IMqttClient;
import com.ibm.mqtt.MqttClient;
import com.ibm.mqtt.MqttException;
import com.ibm.mqtt.MqttPersistence;
import com.ibm.mqtt.MqttSimpleCallback;

public class WisePushService extends Service
{
	// 로깅 태그
	public static final String		TAG = "WisePushDemo";
	// MQTT broker 서버 DNS/IP 정보를 기술한다.
	private static final String		MQTT_HOST = "www.wiseeco.com";
	// MQTT broker 서버 포트 정보를 기술한다. 
	private static int				MQTT_BROKER_PORT_NUM      = 1883;
	// Let's not use the MQTT persistence.
	private static MqttPersistence	MQTT_PERSISTENCE          = null;
	// We don't need to remember any state between the connections, so we use a clean start. 
	private static boolean			MQTT_CLEAN_START          = true;
	// keep alive for MQTT to 15분 설정.
	private static short			MQTT_KEEP_ALIVE           = 60 * 15;
	// QOS 0으로 설정
	private static int[]			MQTT_QUALITIES_OF_SERVICE = { 0 } ;
	private static int				MQTT_QUALITY_OF_SERVICE   = 0;
	// The broker should not retain any messages.
	private static boolean			MQTT_RETAINED_PUBLISH     = false;
		
	// MQTT client ID, MQTT broker와 통신하기 위해 유일환 값이 되어야 함. 
	public static String			MQTT_CLIENT_ID = "wise";

	private static final String		ACTION_START = MQTT_CLIENT_ID + ".START";
	private static final String		ACTION_STOP = MQTT_CLIENT_ID + ".STOP";
	private static final String		ACTION_KEEPALIVE = MQTT_CLIENT_ID + ".KEEP_ALIVE";
	private static final String		ACTION_RECONNECT = MQTT_CLIENT_ID + ".RECONNECT";
	
	// Connectivity manager to determining, when the phone loses connection
	private ConnectivityManager		mConnMan;
	// Notification manager to displaying arrived push notifications 
	private NotificationManager		mNotifMan;

	// Whether or not the service has been started.	
	private boolean 				mStarted;

	// This the application level keep-alive interval, that is used by the AlarmManager
	// to keep the connection active, even when the device goes to sleep.
	private static final long		KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

	// Retry intervals, when the connection is lost.
	private static final long		INITIAL_RETRY_INTERVAL = 1000 * 10;
	private static final long		MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 30;

	// Preferences instance 
	private SharedPreferences 		mPrefs;
	// We store in the preferences, whether or not the service has been started
	public static final String		PREF_STARTED = "isStarted";
	// We also store the deviceID (target)
	public static final String		PREF_DEVICE_ID = "deviceID";
	// We store the last retry interval
	public static final String		PREF_RETRY = "retryInterval";

	// Notification title
	public static String			NOTIF_TITLE = "Wiseeco"; 	

	// Notification id
    public static final int 		NOTIF_UPDATE  = 2;
    
	// This is the instance of an MQTT connection.
	private MQTTConnection			mConnection;
	private long					mStartTime;
	
	//Message ID for Broadcast
	public static final String		MQTT_CONNECT = "com.wiseeco.wisepush.mqtt_connect";
	public static final String		MQTT_DISCONNECT = "com.wiseeco.wisepush.mqtt_disconnect";

	// Static method to start the service
	public static void actionStart(Context ctx) {
		Intent i = new Intent(ctx, WisePushService.class);
		i.setAction(ACTION_START);
		ctx.startService(i);
	}

	// Static method to stop the service
	public static void actionStop(Context ctx) {
		Intent i = new Intent(ctx, WisePushService.class);
		i.setAction(ACTION_STOP);
		ctx.startService(i);
	}
	
	// Static method to send a keep alive message
	public static void actionPing(Context ctx) {
		Intent i = new Intent(ctx, WisePushService.class);
		i.setAction(ACTION_KEEPALIVE);
		ctx.startService(i);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		log("Creating service");
		mStartTime = System.currentTimeMillis();

		// Get instances of preferences, connectivity manager and notification manager
		mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
		mConnMan = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
		mNotifMan = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
	
		/* If our process was reaped by the system for any reason we need
		 * to restore our state with merely a call to onCreate.  We record
		 * the last "started" value and restore it here if necessary. */
		handleCrashedService();
	}
	
	// This method does any necessary clean-up need in case the server has been destroyed by the system
	// and then restarted
	private void handleCrashedService() {
		if (wasStarted() == true) {
			log("Handling crashed service...");
			 // stop the keep alives
			stopKeepAlives(); 
			start();
		}
	}
	
	@Override
	public void onDestroy() {
		log("Service destroyed (started=" + mStarted + ")");
		// Stop the services, if it has been started
		if (mStarted == true) {
			stop();
		}
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		log("Service started with intent=" + intent);

		if (intent.getAction().equals(ACTION_STOP) == true) {
			stop();
			stopSelf();
		} else if (intent.getAction().equals(ACTION_START) == true) {
			start();
		} else if (intent.getAction().equals(ACTION_KEEPALIVE) == true) {
			keepAlive();
		} else if (intent.getAction().equals(ACTION_RECONNECT) == true) {
			if (isNetworkAvailable()) {
				reconnectIfNecessary();
			}
		}
	}
	
	public int onStartCommand(Intent intent, int flags, int startId) {
		onStart(intent, startId);
		return START_STICKY;
	}
	 
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	// log helper function
	private void log(String message) {
		log(message, null);
	}
	
	private void log(String message, Throwable e) {
		if (e != null) {
			Log.e(TAG, message, e);
			
		} else {
			Log.i(TAG, message);			
		}
	
	}
	
	// Reads whether or not the service has been started from the preferences
	private boolean wasStarted() {
		return mPrefs.getBoolean(PREF_STARTED, false);
	}

	// Sets whether or not the services has been started in the preferences.
	private void setStarted(boolean started) {
		mPrefs.edit().putBoolean(PREF_STARTED, started).commit();		
		mStarted = started;
	}

	private synchronized void start() {
		try {
			log("Starting service..."); // 3
			
			// Do nothing, if the service is already running.
			if (mStarted == true) {
				Log.w(TAG, "Attempt to start connection that is already active");
				return;
			}
			// MQTT 연결
			connect();
	
			registerReceiver(mConnectivityChanged, 
					new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}

	private synchronized void stop() {
		try {
			// Do nothing, if the service is not running.
			if (mStarted == false) {
				Log.w(TAG, "Attempt to stop connection not active.");
				return;
			}
	
			// Save stopped state in the preferences
			setStarted(false);
	
			// Remove the connectivity receiver
			unregisterReceiver(mConnectivityChanged);
			// Any existing reconnect timers should be removed, since we explicitly stopping the service.
			cancelReconnect();
	
			// MQTT 연결 해제.
			if (mConnection != null) {
				mConnection.disconnect();
				mConnection = null;
			}
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}
	
	// 
	private synchronized void connect() {
		log("Connecting...");
		// device ID 획득.
		String deviceID = mPrefs.getString(PREF_DEVICE_ID, null);
		if (deviceID == null) {
			log("Device ID not found.");
		} else {
			try {
				mConnection = new MQTTConnection(MQTT_HOST, deviceID);
			} catch (MqttException e) {
				log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"));
	        	if (isNetworkAvailable()) {
	        		scheduleReconnect(mStartTime);
	        	}
			}
			setStarted(true);
		}
	}

	private synchronized void keepAlive() {
		try {
			if (mStarted == true && mConnection != null) {
				mConnection.sendKeepAlive();
			}
		} catch (MqttException e) {
			log("MqttException: " + (e.getMessage() != null? e.getMessage(): "NULL"), e);
			
			mConnection.disconnect();
			mConnection = null;
			cancelReconnect();
		}
	}

	// Schedule application level keep-alives using the AlarmManager
	private void startKeepAlives() {
		try {
			Intent i = new Intent();
			i.setClass(this, WisePushService.class);
			i.setAction(ACTION_KEEPALIVE);
			PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
			AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
			alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP,
			  System.currentTimeMillis() + KEEP_ALIVE_INTERVAL,
			  KEEP_ALIVE_INTERVAL, pi);
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}

	// Remove all scheduled keep alives
	private void stopKeepAlives() {
		try {
			Intent i = new Intent();
			i.setClass(this, WisePushService.class);
			i.setAction(ACTION_KEEPALIVE);
			PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
			AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
			alarmMgr.cancel(pi);
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}

	// We schedule a reconnect based on the starttime of the service
	public void scheduleReconnect(long startTime) {
		try {
			// the last keep-alive interval
			long interval = mPrefs.getLong(PREF_RETRY, INITIAL_RETRY_INTERVAL);
	
			// Calculate the elapsed time since the start
			long now = System.currentTimeMillis();
			long elapsed = now - startTime;
	
	
			// Set an appropriate interval based on the elapsed time since start 
			if (elapsed < interval) {
				interval = Math.min(interval * 4, MAXIMUM_RETRY_INTERVAL);
			} else {
				interval = INITIAL_RETRY_INTERVAL;
			}
			
			mPrefs.edit().putLong(PREF_RETRY, interval).commit();
	
			// Schedule a reconnect using the alarm manager.
			Intent i = new Intent();
			i.setClass(this, WisePushService.class);
			i.setAction(ACTION_RECONNECT);
			PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
			AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
			alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}
	
	// Remove the scheduled reconnect
	public void cancelReconnect() {
		try {
			Intent i = new Intent();
			i.setClass(this, WisePushService.class);
			i.setAction(ACTION_RECONNECT);
			PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
			AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
			alarmMgr.cancel(pi);
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}
	
	private synchronized void reconnectIfNecessary() {
		try {
			if (mStarted == true && mConnection == null) {
				log("Reconnecting...");
				connect();
			}
		} catch (Exception e) {
			log("error", e.getCause());
		}
	}

	// This receiver listeners for network changes and updates the MQTT connection
	// accordingly
	private BroadcastReceiver mConnectivityChanged = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			boolean hasConnectivity = false;
			NetworkInfo networkInfo = null;
			try {
				networkInfo = (NetworkInfo)intent.getParcelableExtra (ConnectivityManager.EXTRA_NETWORK_INFO);
				hasConnectivity = (networkInfo != null && networkInfo.isConnected()) ? true : false;
				log("Connectivity changed: connected=" + hasConnectivity);
	
				if (hasConnectivity) {
					reconnectIfNecessary();
				} else if (mConnection != null) {
					mConnection.disconnect();
					cancelReconnect();
					mConnection = null;
				}
			} catch (Exception e) {
				log("error", e.getCause());
			}
		}
	};
	
	// Display the topbar notification
	private void showNotification(String text) 
	{
		Notification notification = null;
		String title = "wisepush 알림";
        try {
    		PendingIntent pi = PendingIntent.getActivity(this, 0,
    				  new Intent(this, WisePushActivity.class), 0);
    		
        	notification = new Notification();			
        	notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        	//notification.flags |= Notification.FLAG_ONGOING_EVENT;
        	notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.defaults |= Notification.DEFAULT_LIGHTS;
            notification.defaults |= Notification.DEFAULT_VIBRATE;
            notification.ledARGB = Color.MAGENTA;
        	notification.when = System.currentTimeMillis();
        	notification.icon = com.wiseeco.wisepush.R.drawable.icon;
        	notification.setLatestEventInfo(this, title, text, pi);
        	mNotifMan.notify(NOTIF_UPDATE, notification);
        } catch (Exception e) {
        	log("error", e.getCause());
        }
	}
	
	// Check if we are online
	private boolean isNetworkAvailable() {
		NetworkInfo info = mConnMan.getActiveNetworkInfo();
		if (info == null) {
			return false;
		}
		return info.isConnected();
	}
	
	// This inner class is a wrapper on top of MQTT client.
	private class MQTTConnection implements MqttSimpleCallback 
	{
		private IMqttClient mqttClient = null;
		
		// Creates a new connection given the broker address and initial topic
		public MQTTConnection(String brokerHostName, String topic) throws MqttException 
		{
	    	String mqttConnSpec = "tcp://" + brokerHostName + "@" + MQTT_BROKER_PORT_NUM;
	       	mqttClient = MqttClient.createMqttClient(mqttConnSpec, MQTT_PERSISTENCE);
	       	String clientID = MQTT_CLIENT_ID + "/" + mPrefs.getString(PREF_DEVICE_ID, "");
	       	mqttClient.connect(clientID, MQTT_CLEAN_START, MQTT_KEEP_ALIVE);
			mqttClient.registerSimpleHandler(this);
			
			// 구독할 토픽 정의.
			String initTopic = "/" + MQTT_CLIENT_ID + "/" + topic;
			subscribe(initTopic);
	
			log("Connection established to " + brokerHostName + " on topic " + initTopic);
			mStartTime = System.currentTimeMillis();
			startKeepAlives();				        
		}
		
		// MQTT 연결 해제.
		public void disconnect() {
			try {			
				stopKeepAlives();
				mqttClient.disconnect();
			} catch (Exception e) {
				log("Exception" + (e.getMessage() != null? e.getMessage():" NULL"), e);
			}
		}
		
		/*
		 * Send a request to the message broker to be sent messages published with 
		 *  the specified topic name. Wildcards are allowed.	
		 */
		private void subscribe(String topic) throws MqttException 
		{
			if ((mqttClient == null) || (mqttClient.isConnected() == false)) {
				log("Connection error" + "No connection");	
			} else {									
				String[] topics = {topic};
				mqttClient.subscribe(topics, MQTT_QUALITIES_OF_SERVICE);
			}
		}	
		/*
		 * Sends a message to the message broker, requesting that it be published
		 *  to the specified topic.
		 */
		private void publish(String topic, String message) throws MqttException {		
			if ((mqttClient == null) || (mqttClient.isConnected() == false)) {		
				log("No connection to public to");		
			} else {
				mqttClient.publish(topic, 
					message.getBytes(), MQTT_QUALITY_OF_SERVICE, MQTT_RETAINED_PUBLISH);
			}
		}		
		
		/*
		 * Called if the application loses it's connection to the message broker.
		 */
		public void connectionLost() throws Exception {
			log("Loss of connection" + "connection downed");
			stopKeepAlives();
			mConnection = null;
			if (isNetworkAvailable() == true) {
				reconnectIfNecessary();	
			}
		}		
		
		/*
		 * Called when we receive a message from the message broker. 
		 */
		public void publishArrived(String topicName, byte[] payload, int qos, boolean retained) {
			try {
				showNotification(new String(payload));	
			} catch (Exception e) {
				log("error", e.getCause());
			}
		}   
		
		public void sendKeepAlive() throws MqttException {
			log("Sending keep alive");
			// publish to a keep-alive topic
			publish(MQTT_CLIENT_ID + "/keepalive", mPrefs.getString(PREF_DEVICE_ID, ""));
		}		
	}
}