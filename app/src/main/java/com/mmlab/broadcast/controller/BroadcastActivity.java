package com.mmlab.broadcast.controller;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import com.mmlab.broadcast.R;

public class BroadcastActivity extends AppCompatActivity {

    BroadcastService broadcastService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast);

        broadcastService = new BroadcastService(getApplicationContext());

        Button button_send = (Button) findViewById(R.id.button_send);
        button_send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                broadcastService.send(BroadcastService.TYPE_URL, "http://deh.csie.ncku.edu.tw/deh/functions/pic_add_watermark.php?src=player_pictures/20150305182420_455_.jpg", true, 2);
            }
        });

        Button button_receive = (Button) findViewById(R.id.button_receive);
        button_receive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                broadcastService.receive(true);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        broadcastService.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        broadcastService.stop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_broadcast, menu);
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
}
