package com.example.serverUDPImage;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private DatagramSocket serverSocket;
    private DatagramSocket tempClientSocket;
    private InetAddress client_ip;
    private int client_port;
    Thread serverThread = null;
    public static final int SERVER_PORT = 8081;
    private LinearLayout msgList;
    private Handler handler;
    private int greenColor;
    private EditText edMessage;
    {
        try {
        tempClientSocket = new DatagramSocket(SERVER_PORT);
        } catch (
        SocketException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Server");
        greenColor = ContextCompat.getColor(this, R.color.green);
        handler = new Handler();
        msgList = findViewById(R.id.msgList);
        edMessage = findViewById(R.id.edMessage);
        verifyStoragePermissions(this);
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
            if (null != tempClientSocket) {
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
                            tempClientSocket.send(dp);
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

                serverSocket = new DatagramSocket(SERVER_PORT);
            } catch (IOException e) {
                e.printStackTrace();
                showMessage("Error Starting Server : " + e.getMessage(), Color.RED);
            }
            if (null != serverSocket) {
                CommunicationThread commThread = new CommunicationThread(serverSocket);
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

    class CommunicationThread implements Runnable {

        private DatagramSocket clientSocket;
        byte headerLen = 15;
        int packetLen = 1000;
        private byte buf[] = new byte[packetLen+headerLen];
        private DatagramPacket dp = new DatagramPacket(buf, buf.length);

        int max_image_size = 200000;
        byte[][] imageAll = new byte[2][max_image_size];
        byte packetType;
        byte imageType = 0;
        byte audioType = 1;
        int packetSize;
        int frame_id;
        int packetCount;
        int packetId;
        int frameSize;
        int[] prev_packet_size = {0,0};
        ImageView image_view = (ImageView)findViewById(R.id.imageView);



        public CommunicationThread(DatagramSocket clientSocket) {
            this.clientSocket = clientSocket;
            tempClientSocket = clientSocket;
        }

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Log.d("Myti", "Wait message");
                    clientSocket.receive(dp);
                    Log.d("Myti", "Receive message");
                    // Picture composition here
                    byte[] header = Arrays.copyOfRange(dp.getData(), 0, headerLen);
                    Log.d("Myti", "sdsdsdsdsdd ---- 1");
                    //For image we send 0b00000000 and for audio 0b11111111, because of the udp bits corruption
                    //we count the number of 1 in the byte. 0, 1, 2, 3 number of 1 means image type
                    //and 4, 5, 6, 7 ,8 number of 1 means audio type.
                    packetType = Integer.bitCount(header[0])>3?audioType:imageType;
                    packetSize = ((header[14] & 0xff) << 8) | (header[13] & 0xff);
                    packetSize = packetSize>0?packetSize:prev_packet_size[packetType];
                    frame_id = ((header[4] & 0x00ff) << 24) | ((header[3] & 0x00ff) << 16) |
                            ((header[2] & 0x00ff) << 8) | (header[1] & 0xff);
                    frameSize = ((header[8] & 0x00ff) << 24) | ((header[7] & 0x00ff) << 16) |
                            ((header[6] & 0x00ff) << 8) | (header[5] & 0xff);
                    //frameSize = frameSize & 0x00ff;
                    packetCount = header[9]& 0xff;
                    packetId = (header[10]& 0xff) ;
                    Log.d("Myti", "sdsdsdsdsdd ---- 2");
                    int destPos = packetId*prev_packet_size[packetType];
                    int srcPos = headerLen;
                    int length = dp.getLength()-srcPos;
                    Log.d("Myti", "sdsdsdsdsdd ---- 9"+" packet id "+packetId+" dest_Pos : "+destPos+"  final pose: " +(destPos+length));
                    if(destPos+length<=max_image_size)
                    {
                        System.arraycopy( dp.getData(), srcPos, imageAll[packetType], destPos, length);
                    }

                    Log.d("Myti", "sdsdsdsdsdd ---- 10");
                    Log.d("Myti", " Packet data position  ---------->: " + (packetCount & 0xff));

                    //Save photo
                    if((packetId+1)==packetCount) {
                        File photo = new File(Environment.getExternalStorageDirectory(),
                                "photo.jpeg");

                        if (photo.exists()) {
                            photo.delete();
                        }

                        try {
                            FileOutputStream fos = new FileOutputStream(photo.getPath());
                            imageAll[packetType][0]= (byte)0xFF;
                            imageAll[packetType][1]= (byte)0xD8;
                            imageAll[packetType][frameSize-2]= (byte)0xFF;
                            imageAll[packetType][frameSize-1]= (byte)0xD9;

                            fos.write(Arrays.copyOfRange(imageAll[packetType], 0, frameSize));
                            String s = String.valueOf(fos.getChannel().size());
                            fos.close();

                            Log.d("Myti", "frameSize "+ frameSize);

                            Bitmap bitmap = BitmapFactory.decodeByteArray(imageAll[packetType], 0, frameSize);
                            Log.d("Myti", "frameSize "+ frameSize);
                            if (bitmap != null)
                            {
                                image_view.setImageBitmap(bitmap);
                            }



                        } catch (java.io.IOException e) {
                            Log.e("Myti", "Exception in photoCallback", e);
                        }
                    }


                    prev_packet_size[packetType] = packetSize;
                    String read = new String(dp.getData(),"UTF-8").replaceAll("^\\x00*", "");;
                    client_ip = dp.getAddress();
                    client_port = dp.getPort();
                    Log.d("Myti", "Message: "+packetId+" From server :"+dp.getAddress().toString()+dp.getPort());
                    if (null == read || "Disconnect".contentEquals(read)) {
                        Log.d("Myti", "Disconnected");
                        read = "Client Disconnected";
                        showMessage("Client : " + read, greenColor);
                        Thread.interrupted();
                        break;
                    }
                    showMessage("Client : " + packetId, greenColor);
                } catch (IOException e) {
                    Log.d("Myti", "Exception server");
                    e.printStackTrace();
                }

            }
        }

    }

    String getTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new Date());
    }

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };


    public static void verifyStoragePermissions(Activity activity) {
        // Check if we have write permission
        int permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
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