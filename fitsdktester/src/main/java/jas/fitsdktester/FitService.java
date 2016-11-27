package jas.fitsdktester;

import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResult;

import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;



public class FitService extends Service {
    private static final String TAG = "Fit Data";
    private GoogleApiClient googleApiClient;
    private Map<String, Map<String, Integer>> finalData = new HashMap<>();
    private int noOfDays = 7;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("Starting fit", "now");
        if (intent != null) {
            try {
                Bundle extras = intent.getExtras();
                if (extras != null && extras.containsKey("count"))
                    noOfDays = extras.getInt("count");
                prepareReceiversAndClients();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        Log.d("Creating fit", "now");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        JSONObject toSend = new JSONObject();
        try {
            toSend.put("source", "google fit");
            JSONArray tempArray = new JSONArray();

            for (String date : finalData.keySet()) {
                JSONObject temp = new JSONObject();
                temp.put("date", date);
                temp.put("data", new JSONObject(finalData.get(date)));
                tempArray.put(temp);
            }

            toSend.put("data", tempArray);
            Log.d("Final activity Data", toSend.toString());
            sendFitnessData(toSend);
        } catch (JSONException e) {
            e.printStackTrace();
        }
//        googleApiClient.disconnect();
        super.onDestroy();
    }

    private void prepareReceiversAndClients() {
        //register network level
        //build google api client
        googleApiClient = new GoogleApiClient.Builder(getApplicationContext()).
                addApi(Fitness.HISTORY_API)
                .addScope(new Scope(Scopes.FITNESS_ACTIVITY_READ))
                .addConnectionCallbacks(
                        new GoogleApiClient.ConnectionCallbacks() {
                            @Override
                            public void onConnected(Bundle bundle) {
                                Log.d("Device", "Connected!!!");
                                new GetFitnessDataTask().execute();
                            }

                            @Override
                            public void onConnectionSuspended(int i) {
                                if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST) {
                                    Log.d("device", "Connection lost.  Cause: Network Lost.");
                                } else if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED) {
                                    Log.d("device", "Connection lost.  Reason: Service Disconnected");
                                }
                            }
                        }
                )
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult connectionResult) {
                        Log.d("Google fit", "Failed");
                    }
                })
                .build();
        googleApiClient.connect();
    }

    private class Query {
        private long startTime;
        private long endTime;
        private String date;

        public Query(long startTime, long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.date = new DateTime(startTime).toString("yyyy-MM-dd");
        }

    }

    private class GetFitnessDataTask extends AsyncTask<Void, String, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            ArrayList<Query> queries = getDailyQueryBounds(noOfDays);
            for (Query query : queries) {
                try {
                    readDailyStepsData(query);
                    readActivity(query);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            stopSelf();
            return null;
        }
    }

    private ArrayList<Query> getDailyQueryBounds(int noOfDays) {
        long now = System.currentTimeMillis();
        DateTime start = new DateTime().withTimeAtStartOfDay().minusDays(noOfDays - 1);
        ArrayList<Query> queries = new ArrayList<>();

        for (int i = 0; i < noOfDays; i++) {
            long begin = start.getMillis();
            long end = begin + 24 * 60 * 60 * 1000;
            if (end > now)
                end = now;
            queries.add(new Query(begin, end));
            start = start.plusDays(1);
        }
        return queries;
    }

    private void readActivity(Query query) {
        try {
            DataReadRequest readRequest = new DataReadRequest.Builder()
                    .read(DataType.TYPE_ACTIVITY_SEGMENT)
                    .setTimeRange(query.startTime, query.endTime, TimeUnit.MILLISECONDS)
                    .build();

            PendingResult<DataReadResult> dataReadResultPendingResult = Fitness.HistoryApi.readData(googleApiClient, readRequest);

            DataReadResult result = dataReadResultPendingResult.await(30, TimeUnit.SECONDS);

            for (DataSet dataSet : result.getDataSets()) {
                processActivities(dataSet, query.date);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processActivities(DataSet dataSet, String date) {
        Log.d(TAG, "Data returned for Data type: " + dataSet.getDataType().getName());
        Map<String, Integer> activities = new HashMap<>();

        for (DataPoint dp : dataSet.getDataPoints()) {
            int dur = (int) (dp.getEndTime(TimeUnit.MILLISECONDS) - dp.getStartTime(TimeUnit.MILLISECONDS));
            String activity = dp.getValue(Field.FIELD_ACTIVITY).asActivity().replaceAll("\\.", "_");
            if (activities.containsKey(activity)) {
                int old = activities.get(activity);
                old += dur;
                activities.put(activity, old);
            } else activities.put(activity, dur);
        }

        for (String act : activities.keySet()) {
            activities.put(act, activities.get(act) / (1000 * 60));
        }
        addToActivity(date, activities);
    }


    private void readDailyStepsData(Query query) {
        try {
            PendingResult<DataReadResult> pendingResult = Fitness.HistoryApi.readData(
                    googleApiClient,
                    new DataReadRequest.Builder()
                            .aggregate(DataType.TYPE_STEP_COUNT_DELTA, DataType.AGGREGATE_STEP_COUNT_DELTA)
                            .setTimeRange(query.startTime, query.endTime, TimeUnit.MILLISECONDS)
                            .bucketByTime(1, TimeUnit.DAYS)
                            .build());

            DataReadResult readDataResult = pendingResult.await(1, TimeUnit.MINUTES);
            List<Bucket> buckets = readDataResult.getBuckets();
            if (buckets != null && buckets.size() > 0) {
                Log.d("Buckets found", "" + buckets.size());
                for (Bucket bucket : buckets) {
                    if (bucket.getDataSets() != null) {
                        for (DataSet set : bucket.getDataSets()) {
                            processStepData(set, query.date);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processStepData(DataSet dataSet, String date) {
        for (DataPoint dp : dataSet.getDataPoints()) {
            if (dp.getDataType().equals(DataType.TYPE_STEP_COUNT_DELTA)) {
                int steps = dp.getValue(Field.FIELD_STEPS).asInt();
                Map<String, Integer> temp = new HashMap<>();
                temp.put("steps", steps);

                addToActivity(date, temp);
            }
        }
    }


    synchronized private void addToActivity(String date, Map<String, Integer> toAdd) {
        if (finalData.containsKey(date)) {
            Map<String, Integer> temp = finalData.get(date);
            temp.putAll(toAdd);
            finalData.put(date, temp);
        } else {
            finalData.put(date, toAdd);
        }
    }

    private void sendFitnessData(JSONObject params) throws JSONException {
        Log.d("final data",""+params.toString());

    }
}
