package com.example.downloadmanager;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private SharedPreferences sp;

    private Toolbar toolbar;

    private EditText linkInput;
    private Button beginDL;

    private ListView listView;
    private ArrayList<String> downloads;
    private ArrayAdapter<String> downloadAda;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sp = getApplicationContext().getSharedPreferences("DOWNLOAD_MANAGER", MODE_PRIVATE);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("Download Manager");

        beginDL = findViewById(R.id.beginDL);
        beginDL.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                linkInput = findViewById(R.id.linkInput);
                String link = linkInput.getText().toString();

                if (link.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter a valid URL.", Toast.LENGTH_SHORT).show();
                    return;
                }

                downloadFile(MainActivity.this, link);
            }
        });

        listView = findViewById(R.id.listView);

        loadDownloads();

        downloadAda = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                downloads
        );

        listView.setAdapter(downloadAda);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    /*
                    Because this app won't be released and will
                    likely only be run in the emulator, this code
                    will specifically only work on Android 13+
                     */
                    Uri downloadsUri = Uri.parse("content://com.android.providers.media.documents/root/downloads/" + downloads.get(position).toString());
                    i.setDataAndType(downloadsUri, "vnd.android.document/root");
                    i.addCategory(Intent.CATEGORY_DEFAULT);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    startActivity(i);
                } catch (Exception e) {
                    Log.e(TAG, "An error occurred: " + e);
                    Toast.makeText(MainActivity.this,
                            "Could not open Downloads folder.",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                PopupMenu popupMenu = new PopupMenu(MainActivity.this, view);
                popupMenu.getMenuInflater().inflate(R.menu.popup_menu, popupMenu.getMenu());
                popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == R.id.menu_delete) {
                            downloads.remove(position);
                            downloadAda.notifyDataSetChanged();
                            Toast.makeText(MainActivity.this, "Deleted item from list", Toast.LENGTH_SHORT).show();
                            saveDownloads();
                            return true;
                        }
                        return false;
                    }
                });
                popupMenu.show();
                return true;
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.settings) {
            Intent i = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(i);
            return true;
        }

        return false;
    }

    private void downloadFile(Context context, String link) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri downloadUri = Uri.parse(link);
            String fileName = downloadUri.getLastPathSegment();

            if (fileName == null || !fileName.contains(".")) {
                fileName = System.currentTimeMillis() + ".file";
                Log.d(TAG, "Forced to change fileName, as it did not appear to be a valid URL.");
            }

            DownloadManager.Request request = new DownloadManager.Request(downloadUri);
            request.setTitle(R.string.request_title + " " + fileName);
            request.setDescription(R.string.request_desc + "");

            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
            );

            if (dm != null) {
                long dlId = dm.enqueue(request);
                Toast.makeText(context, "Starting download...", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Download started with ID " + dlId);

                // Add to ListView
                if (!downloads.contains(fileName)) {
                    downloads.add(fileName);
                    downloadAda.notifyDataSetChanged();

                    saveDownloads();
                }

                monitorDownload(dm, dlId, fileName);
            } else {
                Toast.makeText(context, "Something has gone seriously wrong.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Download Manager is null.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Something went wrong: " + e);
            Toast.makeText(context, "Error occured during download.", Toast.LENGTH_SHORT).show();
        }
    }

    private void monitorDownload(DownloadManager dm, long dlId, String fileName) {
        new Thread(() -> {
            boolean downloading = true;

            while (downloading) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(dlId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        int bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                        if (bytesTotal > 0) {
                            int progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Downloading... " + progress + "%", Toast.LENGTH_SHORT).show());
                        }

                        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));

                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            downloading = false;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error trying to monitor download: " + e);
                    downloading = false;
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Monitoring interrupted: " + e);
                }
            }
        }).start();
    }

    /*
    Both functions here use Google's Gson library
    to keep a persistent ArrayList between sessions.
    */

    private void loadDownloads() {
        String json = sp.getString("DOWNLOADS", null);

        if (json != null) {
            Gson gson = new Gson();
            Type type = new TypeToken<ArrayList<String>>(){}.getType();
            downloads = gson.fromJson(json, type);
        } else {
            downloads = new ArrayList<String>();
        }
    }

    private void saveDownloads() {
        SharedPreferences.Editor editor = sp.edit();

        Gson gson = new Gson();
        String json = gson.toJson(downloads);

        editor.putString("DOWNLOADS", json);
        editor.commit();
    }
}