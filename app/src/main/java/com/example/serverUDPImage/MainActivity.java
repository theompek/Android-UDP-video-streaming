package com.example.serverUDPImage;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    Streaming.CommunicationReceiveThread communicationReceiveThread;
    Streaming.TestSendDataCommunicationThread communicationSendThread;
    Thread communicationReceiveThreadHandler = null;
    Thread communicationSendThreadHandler = null;
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
        //msgList = findViewById(R.id.msgList);
        edMessage = findViewById(R.id.edMessage);
        Setup.verifyStoragePermissions(this);
        //setContentView(R.layout.activity_main);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        InetAddress localIPAddress = null;
        try {
            localIPAddress = InetAddress.getByName(Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress()));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        ArrayList<View> listViewObjects = new ArrayList<>();
        listViewObjects.add(findViewById(R.id.imageView));
        listViewObjects.add(findViewById(R.id.textViewFPS));
        listViewObjects.add(findViewById(R.id.textViewKbps));
        communicationReceiveThread = (new Streaming()).new CommunicationReceiveThread(this, listViewObjects);

        communicationSendThread = (new Streaming()).new TestSendDataCommunicationThread(findViewById(R.id.imageView), localIPAddress, this);
    }

    @Override
    public void onClick(View view) {

        if (view.getId() == R.id.start_server) {
            //msgList.removeAllViews();
            //Helpers.showMessage("Server Started.", Color.BLACK,msgList, this);
            this.communicationReceiveThreadHandler = new Thread(communicationReceiveThread);
            this.communicationSendThreadHandler = new Thread(communicationSendThread);
            this.communicationReceiveThreadHandler.start();
            this.communicationSendThreadHandler.start();
            return;
        }

        if (view.getId() == R.id.send_data) {
            String msg = edMessage.getText().toString().trim();
            //Helpers.showMessage("Server : " + msg, Color.BLUE, msgList, this);
            communicationReceiveThread.sendMessage(msg);
        }

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (null != communicationReceiveThreadHandler) {
            communicationReceiveThread.sendMessage("Disconnect");
            communicationReceiveThreadHandler.interrupt();
            communicationReceiveThreadHandler = null;
        }
    }
}