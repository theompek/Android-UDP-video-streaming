package com.example.serverUDPImage;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    Streaming.CommunicationThread communicationThread;
    Thread communicationThreadHandler = null;
    private LinearLayout msgList;
    private int greenColor;
    private EditText edMessage;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2296) {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // perform action when allow permission success
                } else {
                    Toast.makeText(this, "Allow permission for storage access!", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Setup.requestPermission(MainActivity.this);
        setContentView(R.layout.activity_main);
        setTitle("Server");
        greenColor = ContextCompat.getColor(this, R.color.green);
        msgList = findViewById(R.id.msgList);
        edMessage = findViewById(R.id.edMessage);
        Setup.verifyStoragePermissions(this);
        communicationThread = (new Streaming()).new CommunicationThread(findViewById(R.id.imageView));
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.start_server) {
            msgList.removeAllViews();
            Helpers.showMessage("Server Started.", Color.BLACK,msgList, this);
            this.communicationThreadHandler = new Thread(communicationThread);
            this.communicationThreadHandler.start();
            return;
        }

        if (view.getId() == R.id.send_data) {
            String msg = edMessage.getText().toString().trim();
            Helpers.showMessage("Server : " + msg, Color.BLUE, msgList, this);
            communicationThread.sendMessage(msg);
        }

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (null != communicationThreadHandler) {
            communicationThread.sendMessage("Disconnect");
            communicationThreadHandler.interrupt();
            communicationThreadHandler = null;
        }
    }
}