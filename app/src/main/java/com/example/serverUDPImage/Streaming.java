package com.example.serverUDPImage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

public class Streaming {

    public class CommunicationReceiveThread implements Runnable {
        public DatagramSocket phoneSocket;
        public int phonePort;
        byte headerLen = 15;
        int packetLen = 1000;
        private byte buf[] = new byte[packetLen+headerLen];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);

        int maxImageSize = 200000;
        byte[][] imageAll = new byte[2][maxImageSize];
        byte packetType;
        static final byte imageType = 0;
        static final byte audioType = 1;
        int packetSize;
        int frameId;
        int packetsNumber;
        int packetId;
        int frameSize;
        int[] prevPacketSize = {0,0};
        ImageView imageView;
        InetAddress esp32Ip;
        int esp32Port;



        public CommunicationReceiveThread(ImageView imageView){
            try {
                phonePort = 8081;
                phoneSocket =  new DatagramSocket(phonePort);
                esp32Port = 8000;
                esp32Ip = InetAddress.getByName("192.168.2.180");
            }catch (IOException e) {
                Log.e("Myti", "Exception in photoCallback", e);
            }
            this.imageView = imageView;

        }

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                if (this.imageView!= null){
                    continue;
                }

                Log.d("Myti", "Get data");
                try {
                    phoneSocket.receive(phoneDpReceive);
                    byte[] header = Arrays.copyOfRange(phoneDpReceive.getData(), 0, headerLen);
                    //For image we send 0b00000000 and for audio 0b11111111, because of the udp bits corruption
                    //we count the number of 1 in the byte. 0, 1, 2, 3 number of 1 means image type
                    //and 4, 5, 6, 7 ,8 number of 1 means audio type.
                    packetType = Integer.bitCount(header[0])>3?audioType:imageType;
                    packetSize = ((header[14] & 0xff) << 8) | (header[13] & 0xff);
                    packetSize = packetSize>0?packetSize: prevPacketSize[packetType];
                    frameId = ((header[4] & 0x00ff) << 24) | ((header[3] & 0x00ff) << 16) |
                            ((header[2] & 0x00ff) << 8) | (header[1] & 0xff);
                    frameSize = ((header[8] & 0x00ff) << 24) | ((header[7] & 0x00ff) << 16) |
                            ((header[6] & 0x00ff) << 8) | (header[5] & 0xff);
                    //frameSize = frameSize & 0x00ff;
                    packetsNumber = header[9]& 0xff;
                    packetId = (header[10]& 0xff) ;
                    int destPos = packetId* prevPacketSize[packetType];
                    int srcPos = headerLen;
                    int length = phoneDpReceive.getLength()-srcPos;
                    if(destPos+length<= maxImageSize)
                    {
                        System.arraycopy( phoneDpReceive.getData(), srcPos, imageAll[packetType], destPos, length);
                    }

                    //Save photo
                    if((packetId+1)== packetsNumber) {
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

                            Bitmap bitmap = BitmapFactory.decodeByteArray(imageAll[packetType], 0, frameSize);
                            Log.d("Myti", "frameSize "+ frameSize);
                            if (bitmap != null)
                            {
                                imageView.setImageBitmap(bitmap);
                            }
                        } catch (IOException e) {
                            Log.e("Myti", "Exception in photoCallback", e);
                        }
                    }

                    prevPacketSize[packetType] = packetSize;
                    String read = new String(phoneDpReceive.getData(),"UTF-8").replaceAll("^\\x00*", "");;
                    esp32Ip = phoneDpReceive.getAddress();
                    esp32Port = phoneDpReceive.getPort();
                    if (null == read || "Disconnect".contentEquals(read)) {
                        Thread.interrupted();
                        break;
                    }
                } catch (IOException e) {
                    Log.d("Myti", "Exception server");
                    e.printStackTrace();
                }

            }
        }



        public void sendMessage(String message) {
            try {
                if (null != phoneSocket) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            byte[] data = message.getBytes( );
                            try {
                                DatagramPacket dp = new DatagramPacket(data, data.length, esp32Ip, esp32Port);
                                phoneSocket.send(dp);
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

    }

    public class TestSendDataCommunicationThread implements Runnable {
        public DatagramSocket phoneSocketSendDataTest;
        public int phonePortSendDataTest;
        byte headerLen = 15;
        int packetLen = 1000;
        private byte buf[] = new byte[packetLen + headerLen];
        private DatagramPacket phoneDpReceive = new DatagramPacket(buf, buf.length);

        int maxImageSize = 200000;
        byte[][] imageAll = new byte[2][maxImageSize];
        byte packetType;
        static final byte imageType = 0;
        static final byte audioType = 1;
        int packetSize;
        int frameId;
        int packetsNumber;
        int packetId;
        int frameSize;
        int[] prevPacketSize = {0, 0};
        ImageView imageView;
        InetAddress phoneIpReceiveDataTest;
        int phonePortReceiveDataTest;


        public TestSendDataCommunicationThread(ImageView imageView, InetAddress localIP) {
            try {
                phonePortSendDataTest = 8000;
                phoneSocketSendDataTest = new DatagramSocket(phonePortSendDataTest);
                phoneIpReceiveDataTest = localIP;
                phonePortReceiveDataTest = 8081;

            } catch (IOException e) {
                Log.e("Myti", "Exception in photoCallback", e);
            }
            this.imageView = imageView;

        }

        public void run() {
            try {
                if (null != phoneSocketSendDataTest) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            byte data[] = new byte[2];
                            for (int i = 0; i < data.length; i++) {
                                data[i]=(byte)i;
                            }
                            try {
                                while(true){
                                    DatagramPacket dp = new DatagramPacket(data, data.length, phoneIpReceiveDataTest, phonePortReceiveDataTest);
                                    phoneSocketSendDataTest.send(dp);
                                }
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
    }
}
