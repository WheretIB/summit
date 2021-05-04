package com.egoengine.summit;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.entry_splash);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(this::checkForPermissions, 1000);
    }

    private void checkForPermissions() {
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            loadDatabase();
        } else {
            showPermissionRequestView();
        }
    }

    private ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    loadDatabase();
                } else {
                    Toast.makeText(getApplicationContext(), "Cannot proceed", Toast.LENGTH_LONG).show();
                }
            });

    private void showPermissionRequestView() {
        setContentView(R.layout.permission_request);

        Button button = findViewById(R.id.buttonSmsRequest);
        button.setOnClickListener(view -> requestPermissionLauncher.launch(Manifest.permission.READ_SMS));
    }

    private class ProcessedData {
        String card;
        String amountCurr;
        int amountCents;
        String time;
        String fullPlace;
        String place;
        String topPlace;
    }

    private class Message {
        String id;
        String address;
        String date;
        java.sql.Date dateProcessed;
        String body;

        ProcessedData processed;
    }

    private class Group {
        Group parent = null;

        String name;
        boolean sortByCost = false;
        boolean showCount = false;

        TreeMap<String, Group> subgroups = new TreeMap<>();

        ArrayList<Message> messages = new ArrayList<>();

        int cost = 0;

        HashMap<String, Integer> totalAmountCentsInCurrency = new HashMap<>();
    }

    private ArrayList<Message> messages = new ArrayList<>();
    private TreeMap<String, Group> instruments = new TreeMap<>();

    private Group getOrAddGroup(Group parent, TreeMap<String, Group> map, String name, boolean sortByCost, boolean showCount) {
        if (!map.containsKey(name)) {
            Group group = new Group();

            group.parent = parent;
            group.name = name;
            group.sortByCost = sortByCost;
            group.showCount = showCount;

            map.put(name, group);
            return group;
        }

        return map.get(name);
    }

    private void appendMessage(Group group, Message message) {
        group.messages.add(message);

        Integer curr = group.totalAmountCentsInCurrency.getOrDefault(message.processed.amountCurr, 0);
        group.totalAmountCentsInCurrency.put(message.processed.amountCurr, curr + message.processed.amountCents);

        if (message.processed.amountCurr.equals("USD"))
            group.cost += message.processed.amountCents;
        else if (message.processed.amountCurr.equals("EUR"))
            group.cost += message.processed.amountCents;
        else if (message.processed.amountCurr.equals("RUB"))
            group.cost += message.processed.amountCents / 70;
    }

    private void loadDatabase() {
        setContentView(R.layout.loading);

        TextView textViewProgress = findViewById(R.id.textViewProgress);
        ProgressBar progressBar = findViewById(R.id.progressBar);

        String[] projection = {
                "_id",
                "address",
                "date",
                "body"
        };

        try {
            Cursor cursor = getContentResolver().query(Uri.parse("content://sms/inbox"), projection, "address = 'Luminor'", null, "date");

            if (cursor != null) {
                int count = cursor.getCount();

                progressBar.setProgress(0);
                textViewProgress.setText(String.format(Locale.US, "0/%d", count));

                if (cursor.moveToFirst()) {
                    do {
                        Message message = new Message();

                        for (int idx = 0; idx < cursor.getColumnCount(); idx++) {
                            String name = cursor.getColumnName(idx);
                            String value = cursor.getString(idx);

                            if (name.equals("_id"))
                                message.id = value;
                            else if (name.equals("address"))
                                message.address = value;
                            else if (name.equals("date"))
                                message.date = value;
                            else if (name.equals("body"))
                                message.body = value;
                        }

                        try {
                            message.dateProcessed = new java.sql.Date(Long.parseLong(message.date));

                            if (processMessage(message)) {
                                messages.add(message);
                            }
                        } catch (Exception e) {
                            Log.e("SUMMIT", e.getMessage());
                        }
                    } while (cursor.moveToNext());
                }

                cursor.close();

                progressBar.setProgress(100);
                textViewProgress.setText(String.format(Locale.US, "%d/%d", count, count));

                rebuildInstruments(1);

                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(this::showInstrumentList, 1000);
            } else {
                progressBar.setProgress(0);
                textViewProgress.setText("No SMS");
            }
        } catch (Exception ex) {
            Toast.makeText(getApplicationContext(), "Oops, failed to read SMS :(", Toast.LENGTH_LONG).show();
        }
    }

    private boolean processMessage(Message msg) {
        try {
            ProcessedData processed = new ProcessedData();

            if (msg.address.equals("Luminor")) {
                if (msg.body.startsWith("Purchase")) {
                    String[] split = msg.body.split("\r\n|\n");

                    int fields = 0;

                    for (String line : split) {
                        if (line.startsWith("Card: ")) {
                            processed.card = line.substring("Card: ".length());
                            fields++;
                        } else if (line.startsWith("Amount: ")) {
                            String amount = line.substring("Amount: ".length());

                            Pattern p = Pattern.compile("(\\w+) (\\d+)\\.(\\d+)");
                            Matcher m = p.matcher(amount);

                            if (m.find()) {
                                processed.amountCurr = m.group(1);
                                processed.amountCents = Integer.parseInt(m.group(2)) * 100 + Integer.parseInt(m.group(3));

                                if (processed.amountCents != 0)
                                    fields++;
                            }
                        } else if (line.startsWith("Time: ")) {
                            processed.time = line.substring("Time: ".length());
                            fields++;
                        } else if (line.startsWith("Place: ")) {
                            processed.fullPlace = line.substring("Place: ".length());

                            processed.place = processed.fullPlace;

                            int pos = processed.place.indexOf('>');

                            if (pos != -1)
                                processed.place = processed.place.substring(0, pos);

                            processed.place = processed.place.replaceAll("[0-9\\-#_]", "");

                            if (processed.place.endsWith(" "))
                                processed.place = processed.place.substring(0, processed.place.length() - 1);

                            processed.topPlace = processed.place;

                            pos = processed.topPlace.indexOf(' ');

                            if (pos != -1)
                                processed.topPlace = processed.topPlace.substring(0, pos);

                            fields++;
                        }
                    }

                    if (fields == 4) {
                        msg.processed = processed;
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e("SUMMIT", e.getMessage());
        }

        return false;
    }

    private void showInstrumentList() {
        setContentView(R.layout.instrument_list);

        LinearLayout linearLayout = findViewById(R.id.instrumentList);

        linearLayout.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(getApplicationContext());

        boolean primary = true;

        for (String name : instruments.keySet()) {
            Group instrument = instruments.get(name);

            if (instrument == null) {
                Log.w("SUMMIT", "showInstrumentList() For some reason, instrument is null");
                continue;
            }

            View v = inflater.inflate(R.layout.instrument_list_item, null);

            TextView viewName = v.findViewById(R.id.instrumentName);
            TextView viewUpdateDate = v.findViewById(R.id.instrumentUpdateDate);

            viewName.setText(instrument.name);
            viewUpdateDate.setText(instrument.messages.get(instrument.messages.size() - 1).dateProcessed.toString());

            v.setBackgroundColor(primary ? getResources().getColor(R.color.colorPrimary) : getResources().getColor(R.color.colorPrimaryDark));
            primary = !primary;

            v.setMinimumHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, getResources().getDisplayMetrics()));

            v.setOnClickListener(view -> selectInstrument(instrument));

            linearLayout.addView(v);
        }
    }

    private int activeLevel = 0;

    private void rebuildInstruments(int level) {
        activeLevel = level;

        instruments.clear();

        DateFormatSymbols dfs = new DateFormatSymbols();
        String[] months = dfs.getMonths();

        // All time
        for (Message message : messages) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(message.dateProcessed);

            Group instrument = getOrAddGroup(null, instruments, message.address + " " + message.processed.card, level == 0, false);
            appendMessage(instrument, message);

            Group folder = instrument;

            if (level >= 1) {
                Group year = getOrAddGroup(folder, folder.subgroups, String.valueOf(cal.get(Calendar.YEAR)), level == 1, false);
                appendMessage(year, message);

                folder = year;
            }

            if (level >= 2) {
                Group month = getOrAddGroup(folder, folder.subgroups, String.format(Locale.US, "%02d: %s", (cal.get(Calendar.MONTH) + 1), months[cal.get(Calendar.MONTH)]), level == 2, false);
                appendMessage(month, message);

                folder = month;
            }

            if (!message.processed.topPlace.equals(message.processed.place)) {
                Group topPlace = getOrAddGroup(folder, folder.subgroups, message.processed.topPlace, true, true);
                appendMessage(topPlace, message);

                folder = topPlace;
            }

            Group place = getOrAddGroup(folder, folder.subgroups, message.processed.place, false, false);
            appendMessage(place, message);

            Group element = getOrAddGroup(place, place.subgroups, message.dateProcessed.toString(), false, false);
            appendMessage(element, message);
        }
    }

    private String selectedInstrument = null;
    private Group selectedGroup = null;

    private int currentLevel = 0;

    private void selectInstrument(Group instrument) {
        selectedInstrument = instrument.name;
        selectedGroup = instrument;
        currentLevel = 0;

        setContentView(R.layout.source_list);

        TextView title = findViewById(R.id.instrumentTitleName);

        title.setText(selectedInstrument);

        updateSourceList();
    }

    private void selectGroup(Group group) {
        selectedGroup = group;

        currentLevel++;

        updateSourceList();
    }

    private void toggleLevel() {
        int desiredLevel = activeLevel + 1;

        if (desiredLevel > 2) {
            desiredLevel = 0;
        }

        currentLevel = 0;

        rebuildInstruments(desiredLevel);
        selectedGroup = instruments.get(selectedInstrument);

        updateSourceList();
    }

    private void updateSourceList() {
        Button level = findViewById(R.id.sourceTypeButton);

        if (activeLevel == 0)
            level.setText("All Time");
        else if (activeLevel == 1)
            level.setText("Year");
        else if (activeLevel == 2)
            level.setText("Month");

        level.setOnClickListener(view -> toggleLevel());

        LinearLayout linearLayout = findViewById(R.id.sourceList);

        linearLayout.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(getApplicationContext());

        boolean primary = true;

        ArrayList<Group> groups = new ArrayList<>(selectedGroup.subgroups.values());

        if (selectedGroup.sortByCost)
            groups.sort((a, b) -> a.cost == b.cost ? 0 : a.cost > b.cost ? -1 : 1);

        for (Group group : groups) {
            View v = inflater.inflate(R.layout.source_list_item, null);

            TextView viewName = v.findViewById(R.id.sourceName);
            TextView viewAmount = v.findViewById(R.id.sourceAmount);

            if (group.showCount)
                viewName.setText(String.format(Locale.US, "%s (%d)", group.name, group.subgroups.size()));
            else
                viewName.setText(group.name);

            String amount = "";

            for (String curr : group.totalAmountCentsInCurrency.keySet()) {
                String currName = curr;

                switch (currName) {
                    case "USD":
                        currName = "$";
                        break;
                    case "EUR":
                        currName = "€";
                        break;
                    case "RUB":
                        currName = "\u20BD";
                        break;
                    default:
                        currName += " ";
                        break;
                }

                int amountCents = group.totalAmountCentsInCurrency.getOrDefault(curr, 0);

                if (!amount.isEmpty())
                    amount += "\n";

                amount += currName + String.format(Locale.US, "%d.%02d", amountCents / 100, amountCents % 100);
            }

            viewAmount.setText(amount);

            v.setBackgroundColor(primary ? getResources().getColor(R.color.colorPrimary) : getResources().getColor(R.color.colorPrimaryDark));
            primary = !primary;

            v.setMinimumHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, getResources().getDisplayMetrics()));

            if (group.subgroups.isEmpty())
                v.setOnClickListener(null);
            else
                v.setOnClickListener(view -> selectGroup(group));

            linearLayout.addView(v);
        }
    }

    @Override
    public void onBackPressed() {
        if (currentLevel > 0) {
            currentLevel -= 1;

            selectedGroup = selectedGroup.parent;

            updateSourceList();
            return;
        } else if (currentLevel == 0) {
            if (selectedInstrument != null) {
                selectedInstrument = null;
                showInstrumentList();
                return;
            }

        }
        super.onBackPressed();
    }
}
