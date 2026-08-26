package com.example.myapplication.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.example.myapplication.models.Contact;
import com.example.myapplication.network.FirebaseManager;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps sending live location updates to Firebase even after the user
 * leaves the app or turns off the screen. Android requires a visible
 * notification while this runs — that's the "Nirbhaya SOS Active" alert
 * the user will see, with an "I'm Safe" button to stop it.
 */
public class LocationTrackingService extends Service {

    public static final String ACTION_STOP = "com.example.myapplication.STOP_SOS";

    private static final String CHANNEL_ID = "nirbhaya_sos_channel";
    private static final int NOTIFICATION_ID = 101;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private boolean firstFixHandled = false;

    private SharedPreferences sharedPreferences;
    private Gson gson;
    private String trackingLink;
    private List<Contact> contacts;
    private String username;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        sharedPreferences = getSharedPreferences("NIRBHAYA_PREFS", MODE_PRIVATE);
        gson = new Gson();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTrackingAndSelf();
            return START_NOT_STICKY;
        }

        username = sharedPreferences.getString("username", "User");
        contacts = loadContacts();

        // Must call startForeground() within seconds of the service starting,
        // or Android will kill it and throw an exception.
        startForeground(NOTIFICATION_ID, buildNotification());

        trackingLink = FirebaseManager.getInstance().startTrackingSession(username);
        startLocationUpdates();

        return START_STICKY;
    }

    private List<Contact> loadContacts() {
        String json = sharedPreferences.getString("contacts", null);
        Type type = new TypeToken<ArrayList<Contact>>() {}.getType();
        List<Contact> list = gson.fromJson(json, type);
        return (list != null) ? list : new ArrayList<>();
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // No permission — still alert contacts, just without a location.
            sendInitialAlert(null);
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                if (location == null) return;

                // Every fresh reading updates Firebase — this is what keeps the map live.
                FirebaseManager.getInstance().updateLocation(location.getLatitude(), location.getLongitude());

                if (!firstFixHandled) {
                    firstFixHandled = true;
                    sendInitialAlert(location);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void sendInitialAlert(Location location) {
        String alertHeader = "\uD83D\uDEA8 EMERGENCY SOS! \uD83D\uDEA8\nAlert: Manual Trigger\n";
        String message;
        if (location != null) {
            message = alertHeader + username + " needs help!\nLive Track: " + trackingLink
                    + "\nLocation: https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
        } else {
            message = alertHeader + username + " needs help!\nLive Track: " + trackingLink + "\n(Location unavailable)";
        }
        sendSMSAlerts(message);
        if (!contacts.isEmpty()) {
            makeEmergencyCall(contacts.get(0).getPhoneNumber());
        }
    }

    private void sendSMSAlerts(String message) {
        if (contacts.isEmpty()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }
            ArrayList<String> parts = smsManager.divideMessage(message);
            for (Contact contact : contacts) {
                String phone = contact.getPhoneNumber().replaceAll("[^0-9+]", "").trim();
                if (!phone.isEmpty()) {
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null);
                }
            }
        } catch (Exception e) {
            // Running inside a Service — no Toast here, but this is a safe place
            // to add Log.e(...) if you want to debug SMS failures later.
        }
    }

    private void makeEmergencyCall(String phoneNumber) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + phoneNumber));
        callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // required when starting an Activity from a Service
        startActivity(callIntent);
    }

    private void stopTrackingAndSelf() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        FirebaseManager.getInstance().stopTrackingSession();
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, LocationTrackingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nirbhaya SOS Active")
                .setContentText("Sharing your live location with emergency contacts")
                .setSmallIcon(android.R.drawable.ic_dialog_alert) // swap for a custom icon later
                .setOngoing(true) // user can't swipe it away by accident
                .addAction(0, "I'm Safe — Stop", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Nirbhaya SOS Tracking", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Shows when Nirbhaya is actively sharing your location during an SOS");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // we don't need two-way binding, just start/stop
    }
}