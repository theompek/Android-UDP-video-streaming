package com.example.serverUDPImage;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private DatagramSocket serverSocket;
    private InetAddress client_ip;
    private int client_port;
    Thread serverThread = null;
    public static final int SERVER_PORT = 8081; //SERVER_PORT = 8081;
    private LinearLayout msgList;
    private Handler handler;
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
        handler = new Handler();
        msgList = findViewById(R.id.msgList);
        edMessage = findViewById(R.id.edMessage);
        Setup.verifyStoragePermissions(this);

            }

    public TextView textView(String message, int color) {
        if (null == message || message.trim().isEmpty()) {
            message = "<Empty Message>";
        }
        TextView tv = new TextView(this);
        tv.setTextColor(color);
        tv.setText(message + " [" + getTime() + "]");
        tv.setTextSize(20);
        tv.setPadding(0, 5, 0, 0);
        return tv;
    }

    public void showMessage(final String message, final int color) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                msgList.addView(textView(message, color));
            }
        });
    }

    @Override
    public void onClick(View view) {

        if (view.getId() == R.id.start_server) {
            msgList.removeAllViews();
            showMessage("Server Started.", Color.BLACK);
            this.serverThread = new Thread(new ServerThread());
            this.serverThread.start();
            return;
        }
        if (view.getId() == R.id.send_data) {
            String msg = edMessage.getText().toString().trim();
            showMessage("Server : " + msg, Color.BLUE);
            sendMessage(msg);
        }
    }

    private void sendMessage(final String message) {
        try {

            Log.d("Myti", "send start");
            if(serverSocket==null)
            {
                serverSocket = new DatagramSocket(SERVER_PORT);
            }

            if (null != serverSocket) {
                Log.d("Myti", "send start2");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d("Myti", "send start3");
                        byte[] data = message.getBytes( );
                        try {
                            Log.d("Myti", "send start4");
                            Log.d("Myti", "Send message : "+message+" to server : "+ client_ip+client_port);
                            client_ip = InetAddress.getByName("192.168.2.180");
                            client_port = 8000;
                            DatagramPacket dp = new DatagramPacket(data, data.length, client_ip, client_port);
                            Log.d("Myti", "send start5");
                            serverSocket.send(dp);
                            Log.d("Myti", "send start6");
                        } catch (IOException e) {
                            Log.d("Myti", "Exception server not ip for client");
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
        } catch (Exception e) {
            Log.d("Myti", "Exception server not ip for client");
            e.printStackTrace();
        }
    }

    class ServerThread implements Runnable {

        public void run() {

            try {
                //serverSocket = new DatagramSocket(null);
                //InetSocketAddress address = new InetSocketAddress("127.0.0.3", SERVER_PORT);
                //serverSocket.bind(address);
                // Not recomended not findViewById(R.id.start_server).setVisibility(View.GONE);
                //InetAddress address= InetAddress.getByName("192.168.1.4");
                //URL url = new URL("https://checkip.amazonaws.com/");
                //BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
                //Log.d("Myti", "IP from internet : "+br.readLine());
                //InetAddress address = InetAddress.getByName(br.readLine());
                //InetAddress address = InetAddress.getByName("localhost");
                //InetAddress address = InetAddress.getLocalHost();
                //Log.d("Myti", "IP from local machine : "+address);
                if(serverSocket==null)
                {
                    serverSocket = new DatagramSocket(SERVER_PORT);
                }

            } catch (IOException e) {
                e.printStackTrace();
                showMessage("Error Starting Server : " + e.getMessage(), Color.RED);
            }
            if (null != serverSocket) {
                Streaming.CommunicationThread commThread = new Streaming.CommunicationThread(serverSocket, (ImageView)findViewById(R.id.imageView));
                new Thread(commThread).start();
                while (!Thread.currentThread().isInterrupted()) {
                    Log.d("Myti", "                         ");
                    Log.d("Myti", "Remote ip: "+String.valueOf(serverSocket.getRemoteSocketAddress()));
                    Log.d("Myti", "Remote port number: "+String.valueOf(serverSocket.getPort()));
                    Log.d("Myti", "Local ip: "+String.valueOf(serverSocket.getLocalAddress()));
                    Log.d("Myti", "Local port number: "+String.valueOf(serverSocket.getLocalPort()));
                    Log.d("Myti", "Address:  "+String.valueOf(serverSocket.getInetAddress()));
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    String getTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new Date());
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (null != serverThread) {
            sendMessage("Disconnect");
            serverThread.interrupt();
            serverThread = null;
        }
    }
}