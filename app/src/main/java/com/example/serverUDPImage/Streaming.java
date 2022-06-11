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

    public class CommunicationThread implements Runnable {
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
        int packetCount;
        int packetId;
        int frameSize;
        int[] prevPacketSize = {0,0};
        ImageView imageView;
        InetAddress esp32Ip;
        int esp32Port;



        public CommunicationThread(ImageView imageView){
            try {
                phoneSocket =  new DatagramSocket(phonePort);
                phonePort = 8081;
                esp32Ip = InetAddress.getByName("192.168.2.180");
                esp32Port = 8000;
            }catch (IOException e) {
                Log.e("Myti", "Exception in photoCallback", e);
            }
            this.imageView = imageView;

        }

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
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
                    packetCount = header[9]& 0xff;
                    packetId = (header[10]& 0xff) ;
                    int destPos = packetId* prevPacketSize[packetType];
                    int srcPos = headerLen;
                    int length = phoneDpReceive.getLength()-srcPos;
                    if(destPos+length<= maxImageSize)
                    {
                        System.arraycopy( phoneDpReceive.getData(), srcPos, imageAll[packetType], destPos, length);
                    }

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
                    byte[] data = message.getBytes( );
                    DatagramPacket dp = new DatagramPacket(data, data.length, esp32Ip, esp32Port);
                    phoneSocket.send(dp);
                }
            } catch (Exception e) {
                Log.d("Myti", "Exception server not ip for client");
                e.printStackTrace();
            }
        }

    }


}
