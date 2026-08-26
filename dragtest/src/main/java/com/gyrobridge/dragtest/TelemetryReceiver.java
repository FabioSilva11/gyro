package com.gyrobridge.dragtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

final class TelemetryReceiver extends BroadcastReceiver {
    interface Listener {
        void onTelemetry(String json);
    }

    private final Listener listener;

    TelemetryReceiver(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!MainActivity.ACTION_TELEMETRY.equals(intent.getAction())) return;
        JSONObject value = new JSONObject();
        try {
            value.put("sensor", intent.getStringExtra("sensor"));
            value.put("rotation", intent.getIntExtra("rotation", 0));
            value.put("yaw", intent.getFloatExtra("yaw", 0f));
            value.put("pitch", intent.getFloatExtra("pitch", 0f));
            value.put("status", intent.getStringExtra("status"));
            value.put("accessibility", intent.getBooleanExtra("accessibility", false));
            value.put("movement", intent.getStringExtra("movement"));
            value.put("forwardAcceleration", intent.getFloatExtra("forwardAcceleration", 0f));
            value.put("pdrPositionMeters", intent.getFloatExtra("pdrPositionMeters", 0f));
            value.put("stepCount", intent.getIntExtra("stepCount", 0));
            listener.onTelemetry(value.toString());
        } catch (Exception ignored) {
            listener.onTelemetry("{}");
        }
    }
}
