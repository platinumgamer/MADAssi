package com.mad.assignment.services;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.GeofencingEvent;
import com.mad.assignment.activity.MainActivity;
import com.mad.assignment.constants.Constants;
import com.mad.assignment.R;
import com.mad.assignment.database.GsonHelper;
import com.mad.assignment.model.WorkSite;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by Guan Du 98110291
 * <p>
 * This service handles the event when the user enters or leaves a geofence.
 * Start timer when user enters and record the hours worked and location when user leaves.
 */

public class GeofenceTransitionService extends IntentService {

    private static final String TAG = GeofenceTransitionService.class.getSimpleName();
    private static final int TIMER_INTERVAL = 2000;
    private static final double TIMER_INCREMENT = 0.5;


    // Controls the timer to record the hours worked.
    private Handler mLogTimerHandler = new Handler();
    private static double sHoursWorked = 0;
    private static boolean sRunnableTimerState = false;

    private GsonHelper mGsonHelper;

    /**
     * Generic constructor that is not explicitly called.
     */
    public GeofenceTransitionService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        GeofencingEvent geofencingEvent = GeofencingEvent.fromIntent(intent);
        // First check if there was any errors.
        if (geofencingEvent.hasError()) {
            String errorMsg = getErrorString(geofencingEvent.getErrorCode());
            Log.e(TAG, errorMsg);
            return;
        }

        mGsonHelper = new GsonHelper(this);

        int geoFenceTransition = geofencingEvent.getGeofenceTransition();

        // Check if the triggered transition is either entering or exiting.
        switch (geoFenceTransition) {
            case Geofence.GEOFENCE_TRANSITION_ENTER:
                // Set the work location to currently working.
                handleTransitionEvent(geofencingEvent, geoFenceTransition, true);
                break;
            case Geofence.GEOFENCE_TRANSITION_EXIT:
                // Set the currently worked location to false as user is leaving.
                handleTransitionEvent(geofencingEvent, geoFenceTransition, false);
                break;
            default:
                Log.d(TAG, "Transition not known");
                break;
        }
    }

    /**
     * Finds the work site object that has the same address as the triggered geofence.
     * Sets the work site to active or inactive depending on the transition state.
     */
    private void handleTransitionEvent(GeofencingEvent geofencingEvent, int geoFenceTransition,
                                       boolean activeState) {

        Log.d(TAG, "handleTransitionEvent()");

        // Retrieve triggered geofences.
        List<Geofence> triggeringGeofences = geofencingEvent.getTriggeringGeofences();

        // Find the work site object with the address of the firstGeofence.
        Geofence firstGeofence = triggeringGeofences.get(0);
        Log.d(TAG, "GEOID: " + firstGeofence.getRequestId());
        WorkSite activeWorkSite = findWorkSiteWithAddress(firstGeofence.getRequestId());

        if (activeWorkSite != null) {

            // Start or stop the timer recording the hours worked.
            handleTimerForHoursWorked(activeWorkSite, activeState);

            // Broadcast location worked so that MainActivity can see updated site in real time.
            BroadcastLocationWorked(activeWorkSite, activeState);

            // Save to shared prefs so that MainActivity can read the active site offline.
            saveActiveWorkSiteToSharedPrefs(activeWorkSite, activeState);

            String geofenceTransitionDetails =
                    getGeofenceTransitionDetails(geoFenceTransition, triggeringGeofences);

            // Send notification details as a String
            sendNotification(geofenceTransitionDetails);
            Log.d(TAG, "triggered firstGeofence");

        } else {
            Log.d(TAG, "Cannot find a work site on the firstGeofence");
        }

    }

    /**
     * Saves the work site corresponding to the triggered geofence to the shared prefs.
     */
    private void saveActiveWorkSiteToSharedPrefs(WorkSite activeWorkSite, boolean activeState) {

        // Retrieve all active work sites from shared preferences first.
        ArrayList<WorkSite> workSites = mGsonHelper.getWorkSitesFromPrefs();

        // Find the index of the work site with the address of triggered site.
        int indexOfTriggeredWorkSite = findIndexOfWorkSite(workSites, activeWorkSite);

        // Set it to currently working within the list.
        workSites.get(indexOfTriggeredWorkSite).setCurrentlyWorking(activeState);

        mGsonHelper.overwriteWorkSitesInPrefs(workSites);
    }

    /**
     * Increment hours worked when in a work site. Records this number when user leaves it.
     */
    private void handleTimerForHoursWorked(final WorkSite activeSite, final boolean activeState) {

        // Static boolean to actually stop the runnables once they run.
        sRunnableTimerState = activeState;

        // Simply increases hours worked every 'TIMER_INTERVAL'.
        Runnable logHoursRunnable = new Runnable() {
            @Override
            public void run() {
                if (sRunnableTimerState) {
                    sHoursWorked += TIMER_INCREMENT;
                    Log.d(TAG, "Hours worked: " + Double.toString(sHoursWorked));
                    //Log.d(TAG, new SimpleDateFormat("dd/MM/yy").format(new Date()));
                    BroadcastHoursWorked(sHoursWorked);
                    mLogTimerHandler.postDelayed(this, TIMER_INTERVAL);
                }
            }
        };

        if (sRunnableTimerState) {
            // If the user is at a work site, start a timer which stores the hours worked as field.
            logHoursRunnable.run();
        } else {
            // Stop the timer by removing all runnables in the handler.
            mLogTimerHandler.removeCallbacks(null);

            // Once the user leaves the site, update the hours worked.
            saveWorkSiteToDatabase(activeSite, sHoursWorked);

            // Reset hours worked.
            sHoursWorked = 0;
        }
    }

    /**
     * Saves the recently left work site to the DB with SugarORM.
     * Checks if there's already work done at the same location on the same day.
     * If there is, update the hours worked instead.
     */
    private void saveWorkSiteToDatabase(WorkSite recentlyLeftWorkSite, double hoursWorked) {

        // Retrieve all work entries.
        List<WorkSite> workSites = WorkSite.listAll(WorkSite.class);

        // Local variable to see if there is already a work site saved in the DB.
        WorkSite existingWorkSite = new WorkSite();

        String currentDate = new SimpleDateFormat(Constants.DATE_FORMAT).format(new Date());

        // The recently left work site could still be at the same place.
        // Check if it is already recorded in the DB save it to existingWorkSite
        if (workSites.size() > 0) {
            int indexOfExistingWorkSite = findIndexOfWorkSite(workSites, recentlyLeftWorkSite);

            // Try to find the existing work site if index is not -1.
            // NOTE: SugarORM starts its index at 1, not 0.
            if (indexOfExistingWorkSite != -1) {
                existingWorkSite = WorkSite.findById(WorkSite.class,
                        indexOfExistingWorkSite + 1);
            }
        }

        // Verify that the DB has retrieved an existingWorkSite
        if (existingWorkSite.getAddress() != null && !existingWorkSite.equals("")) {

            // Check if the date AND address match with the existing work log.
            if (existingWorkSite.getAddress().equals(recentlyLeftWorkSite.getAddress())) {
                if (existingWorkSite.getDateWorked().equals(currentDate)) {
                    // Increase hours worked in the existing entry as both date and location
                    // are the same.
                    existingWorkSite.incrementHoursWorked(hoursWorked);
                    existingWorkSite.save();
                } else {
                    // If the location is the same BUT the date is not the same,
                    // save as a new entry.
                    saveNewWorkSiteToDB(recentlyLeftWorkSite, hoursWorked, currentDate);
                }
            }

        } else {
            // The DB is completely new, so add this as the first entry.
            saveNewWorkSiteToDB(recentlyLeftWorkSite, hoursWorked, currentDate);
        }
    }

    /**
     * Saves a work entry as a new row in the SugarORM DB.
     */
    private void saveNewWorkSiteToDB(WorkSite recentlyLeftWorkSite, double hoursWorked,
                                     String currentDate) {

        // Limit the hours worked to a max of 24 hrs.
        double limitedHoursWorked = 0;

        if (hoursWorked > 24) {
            limitedHoursWorked = 24;
        } else {
            limitedHoursWorked = hoursWorked;
        }
        recentlyLeftWorkSite.setHoursWorked(limitedHoursWorked);
        recentlyLeftWorkSite.setDateWorked(currentDate);
        recentlyLeftWorkSite.save();
    }

    /**
     * Broadcasts the hours worked when the user enters a work site. Received by MainActivity.
     */
    private void BroadcastHoursWorked(double hoursWorked) {
        Intent intent = new Intent(Constants.INTENT_FILTER_HOURS_WORKED);

        intent.putExtra(Constants.EXTRA_HOURS_WORKED, hoursWorked);
        LocalBroadcastManager.getInstance(GeofenceTransitionService.this).sendBroadcast(intent);
    }

    /**
     * Broadcasts the address when the user enters a work site. Received by MainActivity.
     */
    private void BroadcastLocationWorked(WorkSite workSite, boolean activeState) {
        Intent intent = new Intent(Constants.INTENT_FILTER_ACTIVE_ADDRESS);

        if (activeState) {
            intent.putExtra(Constants.EXTRA_ACTIVE_ADDRESS, workSite.getAddress());
        } else {
            intent.putExtra(Constants.EXTRA_ACTIVE_ADDRESS,
                    getString(R.string.main_activity_not_at_worksite));
        }

        LocalBroadcastManager.getInstance(GeofenceTransitionService.this).sendBroadcast(intent);
    }

    /**
     * Finds the index of a work site within a list by matching their address.
     */
    private int findIndexOfWorkSite(List<WorkSite> sites, WorkSite activeSite) {

        for (int i = sites.size() - 1; i > -1; i--) {

            if (sites.get(i).getAddress().equals(activeSite.getAddress())) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Finds a work site object from the shared prefs with the given address.
     */
    private WorkSite findWorkSiteWithAddress(String address) {

        // Retrieve all active work sites from shared preferences first.
        ArrayList<WorkSite> workSites = mGsonHelper.getWorkSitesFromPrefs();

        // Look through all work sites and find the one with the same address.
        for (WorkSite workSite : workSites) {
            if (workSite.getAddress().equals(address)) {
                return workSite;
            }
        }
        return null;
    }

    /**
     * Returns a string containing the details of the triggering event.
     * Details include exiting or entering and the location of the site.
     */
    private String getGeofenceTransitionDetails(int geoFenceTransition,
                                                List<Geofence> triggeringGeofences) {
        // get the ID of each geofence triggered
        ArrayList<String> triggeringGeofencesList = new ArrayList<>();
        for (Geofence geofence : triggeringGeofences) {
            triggeringGeofencesList.add(geofence.getRequestId());
        }

        String status = null;
        if (geoFenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER)
            status = getString(R.string.notification_entering_prefix);
        else if (geoFenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT)
            status = getString(R.string.notification_exiting_prefix);
        return status + TextUtils.join(", ", triggeringGeofencesList);
    }

    /**
     * Sends a notification which has an audio cue.
     */
    private void sendNotification(String msg) {
        Log.i(TAG, "sendNotification: " + msg);

        // Intent to start the main Activity
        Intent notificationIntent =
                MainActivity.makeNotificationIntent(getApplicationContext(), msg);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(notificationIntent);
        PendingIntent notificationPendingIntent =
                stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

        // Creating and sending Notification
        NotificationManager notificationMng =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationMng.notify(
                Constants.GEOFENCE_NOTIFICATION_ID,
                createNotification(msg, notificationPendingIntent));

    }

    /**
     * Create the notification by giving it specific properties.
     */
    private Notification createNotification(String msg, PendingIntent notificationPendingIntent) {
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
        notificationBuilder
                .setSmallIcon(R.drawable.ic_action_location)
                .setColor(Color.RED)
                .setContentTitle(msg)
                .setContentText(getString(R.string.notification_click_instruction))
                .setContentIntent(notificationPendingIntent)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_VIBRATE |
                        Notification.DEFAULT_SOUND)
                .setAutoCancel(true);
        return notificationBuilder.build();
    }

    /**
     * Returns various error strings if there was an error during the trigger.
     * Not UI related, used for logcat.
     */
    private static String getErrorString(int errorCode) {
        switch (errorCode) {
            case GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE:
                return "GeoFence not available";
            case GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES:
                return "Too many GeoFences";
            case GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS:
                return "Too many pending intents";
            default:
                return "Unknown error.";
        }
    }
}