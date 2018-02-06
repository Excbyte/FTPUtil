package com.bolon.ftp;

import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button ftp_download = findViewById(R.id.ftp_download);
        Button ftp_upload = findViewById(R.id.ftp_upload);
        ftp_download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FTPUtil ftpUtil = new FTPUtil("192.168.0.222", 21, "Administrator", "1");
                ftpUtil.addDownloadTask("/CheckPhoto", Environment.getExternalStorageDirectory().getAbsolutePath() + "/TestFtp", new FTPUtil.FtpListener() {
                    @Override
                    public void start() {
                        Log.d("bolon", "start");
                    }

                    @Override
                    public void success() {
                        Log.d("bolon", "success");
                    }

                    @Override
                    public void error(String msg) {
                        Log.d("bolon", "error " + msg);
                    }

                    @Override
                    public void progress(int progress, int totalProgress, String path, int total, int index) {
                        Log.d("bolon", path + "   " + progress + "   " + "(" + index + "/" + total + ")"+ totalProgress);
                    }


                    @Override
                    public void breakTask() {
                        Log.d("bolon", "breakTask");
                    }
                });
            }
        });



        ftp_upload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FTPUtil ftpUtil = new FTPUtil("192.168.0.222", 21, "Administrator", "1");
                ftpUtil.addUploadTask(Environment.getExternalStorageDirectory().getAbsolutePath() + "/TestFtp", "/testFTP1/23ss", new FTPUtil.FtpListener() {
                    @Override
                    public void start() {
                        Log.d("bolon", "start");
                    }

                    @Override
                    public void success() {
                        Log.d("bolon", "success");
                    }

                    @Override
                    public void error(String msg) {
                        Log.d("bolon", "error " + msg);
                    }

                    @Override
                    public void progress(int progress, int totalProgress,String path, int total, int index) {
                        Log.d("bolon", path + "   " + progress + "   " + "(" + index + "/" + total + ")"+totalProgress);
                    }


                    @Override
                    public void breakTask() {
                        Log.d("bolon", "breakTask");
                    }
                });
            }
        });
    }
}
