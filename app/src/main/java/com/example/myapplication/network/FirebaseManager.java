package com.example.myapplication.network;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.auth.FirebaseAuth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FirebaseManager {

    private static FirebaseManager instance;
    private final DatabaseReference database;
    private String currentSessionId;

    private FirebaseManager() {
        database = FirebaseDatabase.getInstance().getReference();
    }

    public static synchronized FirebaseManager getInstance() {
        if (instance == null) {
            instance = new FirebaseManager();
        }
        return instance;
    }

    public String startTrackingSession(String username) {
        currentSessionId = UUID.randomUUID().toString();
        DatabaseReference sessionRef = database.child("sessions").child(currentSessionId);

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userName", username);
        sessionData.put("createdAt", System.currentTimeMillis());
        sessionData.put("active", true);
        // Expire in 2 hours
        sessionData.put("expiresAt", System.currentTimeMillis() + (2 * 60 * 60 * 1000));

        sessionRef.setValue(sessionData);

        return "https://nirbhaya-70568.web.app/track.html?session=" + currentSessionId;
    }

    public void updateLocation(double lat, double lng) {
        if (currentSessionId != null) {
            Map<String, Object> locationData = new HashMap<>();
            locationData.put("lat", lat);
            locationData.put("lng", lng);
            locationData.put("timestamp", System.currentTimeMillis());
            
            database.child("sessions").child(currentSessionId).child("location").setValue(locationData);
        }
    }

    public void stopTrackingSession() {
        if (currentSessionId != null) {
            database.child("sessions").child(currentSessionId).child("active").setValue(false);
            currentSessionId = null;
        }
    }
    
    public String getCurrentSessionId() {
        return currentSessionId;
    }
}
