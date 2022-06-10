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

    static class CommunicationThread implements Runnable {

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
        ImageView image_view;
        InetAddress client_ip;
        int client_port;



        public CommunicationThread(DatagramSocket clientSocket, ImageView image_view){
            this.clientSocket = clientSocket;
            this.image_view = image_view;
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



                        } catch (IOException e) {
                            Log.e("Myti", "Exception in photoCallback", e);
                        }
                    }


                    prev_packet_size[packetType] = packetSize;
                    String read = new String(dp.getData(),"UTF-8").replaceAll("^\\x00*", "");;
                    client_ip = dp.getAddress();
                    client_port = dp.getPort();
                    Log.d("Myti", "Message: "+packetId+" From server :"+dp.getAddress().toString()+dp.getPort());
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

    }


}
