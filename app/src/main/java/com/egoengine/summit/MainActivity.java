package com.egoengine.summit;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;

import android.os.Handler;
import android.os.Looper;
import android.provider.UserDictionary;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;

import java.text.DateFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.entry_splash);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> checkForPermissions(), 500);
        /*
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);*/
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        //getMenuInflater().inflate(R.menu.menu_scrolling, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
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

    private class Source {
        String name;

        int cost = 0;

        HashMap<String, Integer> totalAmountCentsInCurrency = new HashMap<>();

        ArrayList<Message> messages = new ArrayList<>();
    }

    private class Group {
        Group parent = null;

        String name;

        ArrayList<Message> messages = new ArrayList<>();

        HashMap<String, Source> sources = new HashMap<>();

        TreeMap<String, Group> subgroups = new TreeMap<>();
    }

    private TreeMap<String, Group> instruments = new TreeMap<>();

    private Group getOrAddGroup(Group parent, TreeMap<String, Group> map, String name) {
        if (!map.containsKey(name)) {
            Group group = new Group();

            group.parent = parent;
            group.name = name;

            map.put(name, group);
            return group;
        }

        return map.get(name);
    }

    private Source getOrAddSource(HashMap<String, Source> map, String name) {
        if (!map.containsKey(name)) {
            Source source = new Source();
            source.name = name;
            map.put(name, source);
            return source;
        }

        return map.get(name);
    }

    private void appendMessage(Group group, Message message) {
        group.messages.add(message);

        Source source = getOrAddSource(group.sources, message.processed.place);

        source.messages.add(message);

        Integer curr = source.totalAmountCentsInCurrency.getOrDefault(message.processed.amountCurr, new Integer(0));
        source.totalAmountCentsInCurrency.put(message.processed.amountCurr, curr + message.processed.amountCents);

        if (message.processed.amountCurr.equals("USD"))
            source.cost += message.processed.amountCents;
        else if (message.processed.amountCurr.equals("EUR"))
            source.cost += message.processed.amountCents;
        else if (message.processed.amountCurr.equals("RUB"))
            source.cost += message.processed.amountCents / 70;
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
                textViewProgress.setText(String.format("0/%d", count));

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
                                Group instrument = getOrAddGroup(null, instruments, message.address + " " + message.processed.card);

                                appendMessage(instrument, message);

                                Calendar cal = Calendar.getInstance();
                                cal.setTime(message.dateProcessed);

                                Group year = getOrAddGroup(instrument, instrument.subgroups, String.valueOf(cal.get(Calendar.YEAR)));

                                appendMessage(year, message);

                                DateFormatSymbols dfs = new DateFormatSymbols();
                                String[] months = dfs.getMonths();

                                Group month = getOrAddGroup(year, year.subgroups, months[cal.get(Calendar.MONTH)]);

                                appendMessage(month, message);
                            }
                        } catch (Exception e) {
                            Log.e("SUMMIT", e.getMessage());
                        }
                    } while (cursor.moveToNext());
                }

                cursor.close();

                progressBar.setProgress(100);
                textViewProgress.setText(String.format("%d/%d", count, count));

                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> showInstrumentList(), 500);
            } else {
                progressBar.setProgress(0);
                textViewProgress.setText("No SMS");
            }
        } catch (Exception ex) {
            Toast.makeText(getApplicationContext(), "Oops, failed to read SMS :(", Toast.LENGTH_LONG).show();
        }
    }

    boolean processMessage(Message msg) {
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


    private Group selectedInstrument = null;
    private Group selectedGroup = null;
    private Source selectedSource = null;

    private int desiredLevel = 0;
    private int currentLevel = 0;

    private void selectInstrument(Group instrument) {
        selectedInstrument = instrument;
        selectedGroup = instrument;
        selectedSource = null;
        desiredLevel = 1; // Start with year
        currentLevel = 0;

        setContentView(R.layout.source_list);

        TextView title = findViewById(R.id.instrumentTitleName);

        title.setText(selectedInstrument.name);

        updateSourceList();
    }

    private void selectGroup(Group group) {
        selectedGroup = group;

        currentLevel++;

        updateSourceList();
    }

    private void selectSource(Source source) {
        selectedSource = source;

        currentLevel++;

        updateSourceList();
    }

    private void toggleLevel() {
        desiredLevel += 1;

        if (desiredLevel > 2) {
            desiredLevel = 0;
        }

        currentLevel = 0;
        selectedGroup = selectedInstrument;

        updateSourceList();
    }

    private void updateSourceList() {
        Button level = findViewById(R.id.sourceTypeButton);

        if (desiredLevel == 0)
            level.setText("All Time");
        else if (desiredLevel == 1)
            level.setText("Year");
        else if (desiredLevel == 2)
            level.setText("Month");

        level.setOnClickListener(view -> toggleLevel());

        LinearLayout linearLayout = findViewById(R.id.sourceList);

        linearLayout.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(getApplicationContext());

        boolean primary = true;

        if (currentLevel < desiredLevel) {
            for (String name : selectedGroup.subgroups.keySet()) {
                Group group = selectedGroup.subgroups.get(name);

                View v = inflater.inflate(R.layout.source_list_item, null);

                TextView viewName = v.findViewById(R.id.sourceName);
                TextView viewAmount = v.findViewById(R.id.sourceAmount);

                viewName.setText(group.name);
                viewAmount.setText("");

                v.setBackgroundColor(primary ? getResources().getColor(R.color.colorPrimary) : getResources().getColor(R.color.colorPrimaryDark));
                primary = !primary;

                v.setMinimumHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, getResources().getDisplayMetrics()));

                v.setOnClickListener(view -> selectGroup(group));

                linearLayout.addView(v);
            }
        } else if (currentLevel == desiredLevel) {
            ArrayList<Source> sources = new ArrayList<>();

            for (Source source : selectedGroup.sources.values()) {
                sources.add(source);
            }

            sources.sort((a, b) -> a.cost == b.cost ? 0 : a.cost > b.cost ? -1 : 1);

            for (Source source : sources) {
                View v = inflater.inflate(R.layout.source_list_item, null);

                TextView viewName = v.findViewById(R.id.sourceName);
                TextView viewAmount = v.findViewById(R.id.sourceAmount);

                viewName.setText(source.name);

                String amount = "";

                for (String curr : source.totalAmountCentsInCurrency.keySet()) {
                    String currName = curr;

                    if (currName.equals("USD"))
                        currName = "$";
                    else if (currName.equals("EUR"))
                        currName = "€";
                    else
                        currName += " ";

                    int amountCents = source.totalAmountCentsInCurrency.get(curr);

                    if (!amount.isEmpty())
                        amount += "\n";

                    amount += currName + String.format("%d.%02d", amountCents / 100, amountCents % 100);
                }

                viewAmount.setText(amount);

                v.setBackgroundColor(primary ? getResources().getColor(R.color.colorPrimary) : getResources().getColor(R.color.colorPrimaryDark));
                primary = !primary;

                v.setMinimumHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, getResources().getDisplayMetrics()));

                v.setOnClickListener(view -> selectSource(source));

                linearLayout.addView(v);
            }
        } else {
            for (Message message : selectedSource.messages) {
                View v = inflater.inflate(R.layout.source_list_item, null);

                TextView viewName = v.findViewById(R.id.sourceName);
                TextView viewAmount = v.findViewById(R.id.sourceAmount);

                viewName.setText(message.dateProcessed.toString());

                String amount = "";

                String currName = message.processed.amountCurr;

                if (currName.equals("USD"))
                    currName = "$";
                else if (currName.equals("EUR"))
                    currName = "€";
                else
                    currName += " ";

                int amountCents = message.processed.amountCents;

                if (!amount.isEmpty())
                    amount += "\n";

                amount += currName + String.format("%d.%02d", amountCents / 100, amountCents % 100);

                viewAmount.setText(amount);

                v.setBackgroundColor(primary ? getResources().getColor(R.color.colorPrimary) : getResources().getColor(R.color.colorPrimaryDark));
                primary = !primary;

                v.setMinimumHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70, getResources().getDisplayMetrics()));

                v.setOnClickListener(null);

                linearLayout.addView(v);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (currentLevel > 0) {
            currentLevel -= 1;

            if (selectedSource != null)
                selectedSource = null;
            else
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
