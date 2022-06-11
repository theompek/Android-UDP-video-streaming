package com.example.serverUDPImage;

import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Helpers {

    public static TextView textView(String message, int color, MainActivity a) {
        if (null == message || message.trim().isEmpty()) {
            message = "<Empty Message>";
        }
        TextView tv = new TextView(a);
        tv.setTextColor(color);
        tv.setText(message + " [" + getTime() + "]");
        tv.setTextSize(20);
        tv.setPadding(0, 5, 0, 0);
        return tv;
    }

    public static void showMessage(final String message, final int color, LinearLayout msgList, MainActivity a) {
        Handler handler= new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                msgList.addView(textView(message, color, a));
            }
        });
    }

   public static String getTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new Date());
    }


}
