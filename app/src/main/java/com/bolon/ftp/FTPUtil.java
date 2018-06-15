package com.bolon.ftp;

import android.os.Handler;
import android.os.Message;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by Administrator on 2018/2/2.
 */

public class FTPUtil {
    public static final int CONNECT_START = 11;//连接开始
    public static final int CONNECT_ERROR = 12;//连接错误
    public static final int CONNECT_SUCCESS = 13;//连接成功

    public static final int DOWN_START = 1;// 开始下载
    public static final int DOWN_UPDATE = 2;// 进度更新
    public static final int DOWN_OVER = 3;// 结束下载
    public static final int DOWN_BREAK = 4;// 中断下载
    public static final int DOWN_ERROR = 5;//上传失败

    public static final int UP_START = 1;//上传开始
    public static final int UP_OVER = 2;//上传结束
    public static final int UP_ERROR = 3;//上传失败
    public static final int UP_BREAK = 4;//中断上传
    public static final int UP_UPDATE = 5;//上传进度更新

    /**
     * 本地字符编码
     */
    private static String LOCAL_CHARSET = "GBK";

    // FTP协议里面，规定文件名编码为iso-8859-1
    private static String SERVER_CHARSET = "ISO-8859-1";


    private static int queueDeep = 100;
    private ThreadPoolExecutor tpe;

    /**
     * ftp服务器地址
     */
    private String host;
    /**
     * ftp 端口号 默认21
     */
    private int port = 21;
    /**
     * ftp服务器用户名
     */
    private String username;
    /**
     * ftp服务器密码
     */
    private String password;


    public interface FtpListener {
        /**
         * 下载/上传任务开始
         */
        public void start();

        /**
         * 下载/上传成功
         */
        public void success();

        /**
         * 下载/上传错误
         *
         * @param msg 错误消息
         */
        public void error(String msg);

        /**
         * 进度更新
         *
         * @param progress      当前文件进度
         * @param totalProgress 总进度
         * @param path          当前文件
         * @param total         总文件个数
         * @param index         当前文件个数
         */
        public void progress(int progress, int totalProgress, String path, int total, int index);

        /**
         * 任务中断
         */
        public void breakTask();
    }

    public FTPUtil(String host, String username, String password) {
        this(host, 21, username, password);
    }

    public FTPUtil(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.tpe = new ThreadPoolExecutor(1, 3, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(queueDeep),
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * 下载文件
     *
     * @param remoteDir
     * @param localDir
     * @param ftpListener
     */
    public void addDownloadTask(String remoteDir, String localDir, FtpListener ftpListener) {
        DownloadTask task = new DownloadTask(remoteDir, localDir, ftpListener);
        tpe.execute(task);
    }

    class DownloadTask implements Runnable {
        private String remoteDir;
        private String localDir;
        private FtpListener ftpListener;
        private FTPClient client;
        private boolean interceptFlag;

        private int index = 0;
        private int ftpFileCount;
        private String currentPath = "";
        private int indexSize;
        private int ftpFileSize;

        private Handler handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case CONNECT_START:
                        if (ftpListener != null) {
                            ftpListener.start();
                        }
                        break;
                    case CONNECT_ERROR:
                        if (ftpListener != null) {
                            ftpListener.error("连接失败！");
                        }
                        break;
                    case CONNECT_SUCCESS:
                    case DOWN_START:
                        if (ftpListener != null) {
                            ftpListener.progress(0, 0, "", 0, 0);
                        }
                        break;
                    case DOWN_UPDATE:
                        if (ftpListener != null) {
                            int progress = msg.arg1;
                            int totalProgress = msg.arg2;
                            Map<String, Object> obj = (Map<String, Object>) msg.obj;
                            String path = (String) obj.get("path");
                            int total = (int) obj.get("total");
                            int index = (int) obj.get("index");
                            ftpListener.progress(progress, totalProgress, path, total, index);
                        }
                        break;
                    case DOWN_BREAK:
                        if (ftpListener != null) {
                            ftpListener.breakTask();
                        }
                        break;
                    case DOWN_OVER:
                        if (ftpListener != null) {
                            ftpListener.success();
                        }
                        break;
                    case DOWN_ERROR:
                        if (ftpListener != null) {
                            String text = (String) msg.obj;
                            ftpListener.error(text);
                        }
                        break;

                }
            }
        };

        public DownloadTask(String remoteDir, String localDir, FtpListener ftpListener) {
            this.remoteDir = remoteDir;
            this.localDir = localDir;
            this.ftpListener = ftpListener;
            this.client = new FTPClient();
            this.interceptFlag = false;
            client.setConnectTimeout(30000);
            this.index = 0;
            this.ftpFileCount = 0;
            this.ftpFileSize = 0;
            this.indexSize = 0;
            this.currentPath = "";
        }

        @Override
        public void run() {
            try {
                handler.sendEmptyMessage(CONNECT_START);
                // 1、连接服务器
                if (!client.isConnected()) {
                    // 如果采用默认端口，可以使用client.connect(host)的方式直接连接FTP服务器
                    client.connect(host, port);
                    // 登录
                    client.login(username, password);
                    // 获取ftp登录应答码
                    int reply = client.getReplyCode();
                    // 验证是否登陆成功
                    if (!FTPReply.isPositiveCompletion(reply)) {
                        client.disconnect();
                        Message msg = new Message();
                        msg.what = CONNECT_ERROR;
                        msg.obj = "用户名或密码错误";
                        handler.sendMessage(msg);
                    }
                    // 2、设置连接属性
                    if (FTPReply.isPositiveCompletion(client.sendCommand(
                            "OPTS UTF8", "ON"))) {// 开启服务器对UTF-8的支持，如果服务器支持就用UTF-8编码，否则就使用本地编码（GBK）.
                        LOCAL_CHARSET = "UTF-8";
                    }
                    client.setControlEncoding(LOCAL_CHARSET);
                    // 设置以二进制方式传输
                    client.setFileType(FTPClient.BINARY_FILE_TYPE);
                    client.enterLocalPassiveMode();
                }
                //连接成功
                Message msg = new Message();
                msg.what = CONNECT_SUCCESS;
                handler.sendMessage(msg);

            } catch (Exception e) {
                try {
                    client.disconnect();
                } catch (IOException e1) {
                }
                Message msg = new Message();
                msg.what = CONNECT_ERROR;
                msg.obj = "连接FTP服务器失败！";
                handler.sendMessage(msg);
            }

            if (client.isConnected()) {
                Message msg = new Message();
                msg.what = DOWN_START;
                handler.sendMessage(msg);

                //计算需要下载文件个数
                boolean state = calculateFtpFileCount(remoteDir);
                if (state) {
                    //下载文件
                    state = download(remoteDir, localDir);
                    if (state &&!interceptFlag){
                        handler.sendEmptyMessage(DOWN_OVER);
                    }
                }

            }
        }

        private boolean calculateFtpFileCount(String remoteDir) {
            try {
                // 1、设置远程FTP目录
                boolean state = client.changeWorkingDirectory(new String(remoteDir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                if (!state) {
                    Message msg = new Message();
                    msg.what = DOWN_ERROR;
                    msg.obj = "服务器路径错误！";
                    handler.sendMessage(msg);
                    return state;
                }
                // 2、读取远程文件
                FTPFile[] ftpFiles = client.listFiles();
                for (FTPFile file : ftpFiles) {
                    if (file.isDirectory()) {
                        state = calculateFtpFileCount(remoteDir + "/" + file.getName());
                    } else {
                        ftpFileCount++;
                        ftpFileSize += file.getSize();
                    }
                }
                return state;
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = DOWN_ERROR;
                msg.obj = "读取服务器文件数量异常！";
                handler.sendMessage(msg);
                e.printStackTrace();
            }

            return false;
        }

        private boolean download(String remoteDir, String localDir) {
            boolean state = false;
            try {
                // 1、设置远程FTP目录
                client.changeWorkingDirectory(new String(remoteDir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                // 2、读取远程文件

                FTPFile[] ftpFiles = client.listFiles(new String(remoteDir
                        .getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                for (FTPFile file : ftpFiles) {
                    if (file.isFile()) {
                        File localFile = new File(localDir + "/" + file.getName());
                        if (!localFile.getParentFile().exists()) {
                            localFile.getParentFile().mkdirs();
                        }
                        index++;
                        currentPath = remoteDir + "/" + file.getName();
                        state = copySingleFile(file, localFile);
                        if (!state) {
                            return state;
                        }
                    }
                }
                for (FTPFile file : ftpFiles) {
                    if (file.isDirectory()) {
                        state = download(remoteDir + "/" + file.getName(), localDir + "/" + file.getName());
                    }
                }
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = DOWN_ERROR;
                msg.obj = "下载文件异常！";
                handler.sendMessage(msg);
                e.printStackTrace();
                state = false;
            }
            return state;
        }

        private boolean copySingleFile(FTPFile remoteFile, File localFile) {
            try {
                long process = 0;
                long totalProcess = 0;
                long remoteSize = remoteFile.getSize();

                // 本地存在文件，进行断点下载
                if (localFile.exists()) {
                    long localSize = localFile.length();

                    indexSize += localSize;

                    // 判断本地文件大小是否大于远程文件大小
                    if (localSize >= remoteSize) {
                        process = 100;
                        totalProcess = indexSize * 100L / ftpFileSize;
                        Message msg = new Message();
                        msg.what = DOWN_UPDATE;
                        Map<String, Object> obj = new HashMap<>();
                        obj.put("path", currentPath);
                        obj.put("total", ftpFileCount);
                        obj.put("index", index);
                        msg.obj = obj;
                        msg.arg1 = (int) process;
                        msg.arg2 = (int) totalProcess;
                        handler.sendMessage(msg);
                        return true;
                    }
                    System.out.println("断点续传");

                    // 进行断点续传，并记录状态
                    FileOutputStream out = new FileOutputStream(localFile, true);
                    client.setRestartOffset(localSize);
                    InputStream in = client.retrieveFileStream(new String(remoteFile.getName().getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                    byte[] bytes = new byte[1024];
                    long step = remoteSize / 100;
                    process = localSize / step;
                    int c;
                    while ((c = in.read(bytes)) != -1) {
                        if (interceptFlag) {
                            break;
                        }
                        out.write(bytes, 0, c);
                        localSize += c;
                        indexSize += c;
                        long nowProcess = 0;
                        if(step!=0){
                            nowProcess=localSize/step;
                        }
                        totalProcess = indexSize * 100L / ftpFileSize;
                        if (nowProcess > process) {
                            process = nowProcess;
                            Message msg = new Message();
                            //更新文件下载进度,值存放在process变量中
                            msg.what = DOWN_UPDATE;
                            Map<String, Object> obj = new HashMap<>();
                            obj.put("path", currentPath);
                            obj.put("total", ftpFileCount);
                            obj.put("index", index);
                            msg.obj = obj;
                            msg.arg1 = (int) process;
                            msg.arg2 = (int) totalProcess;
                            handler.sendMessage(msg);

                        }
                    }
                    in.close();
                    out.close();


                    boolean isDo = client.completePendingCommand();
                    if (isDo) {
                        return true;
                    } else {
                        handler.sendEmptyMessage(DOWN_BREAK);
                        return false;
                    }
                } else {
                    OutputStream out = new FileOutputStream(localFile);
                    InputStream in = client.retrieveFileStream(new String(remoteFile.getName().getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                    byte[] bytes = new byte[1024];
                    long step = remoteSize / 100;
                    process = 0;
                    long localSize = 0L;
                    int c;
                    while ((c = in.read(bytes)) != -1) {
                        if (interceptFlag) {
                            break;
                        }
                        out.write(bytes, 0, c);
                        localSize += c;
                        indexSize += c;
                        long nowProcess = 0;
                        if(step!=0){
                            nowProcess=localSize/step;
                        }
                        totalProcess = indexSize * 100L / ftpFileSize;
                        //更新文件下载进度,值存放在process变量中
                        if (nowProcess > process) {
                            process = nowProcess;
                            Message msg = new Message();
                            msg.what = DOWN_UPDATE;
                            Map<String, Object> obj = new HashMap<>();
                            obj.put("path", currentPath);
                            obj.put("total", ftpFileCount);
                            obj.put("index", index);
                            msg.obj = obj;
                            msg.arg1 = (int) process;
                            msg.arg2 = (int) totalProcess;
                            handler.sendMessage(msg);

                        }
                    }
                    in.close();
                    out.close();

                    boolean isDo = client.completePendingCommand();
                    if (isDo) {
                        return true;
                    } else {
                        handler.sendEmptyMessage(DOWN_BREAK);
                        return false;
                    }
                }
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = DOWN_ERROR;
                msg.obj = "下载文件异常！";
                handler.sendMessage(msg);
                e.printStackTrace();
            }

            return false;
        }
    }


    /**
     * 上传文件
     *
     * @param remoteDir
     * @param localDir
     * @param ftpListener
     */
    public void addUploadTask(String localDir, String remoteDir, FtpListener ftpListener) {
        UploadTask uploadTask = new UploadTask(localDir, remoteDir, ftpListener);
        tpe.execute(uploadTask);
    }

    class UploadTask implements Runnable {
        private String localDir;
        private String remoteDir;
        private FtpListener ftpListener;

        private FTPClient client;
        private boolean interceptFlag;

        private int index = 0;
        private int localFileCount;
        private long indexSize;//已经上传字节数
        private long localFileSize;//总字节数
        private String currentPath = "";

        private Handler handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case CONNECT_START:
                        if (ftpListener != null) {
                            ftpListener.start();
                        }
                        break;
                    case CONNECT_ERROR:
                        if (ftpListener != null) {
                            ftpListener.error("连接失败！");
                        }
                        break;
                    case CONNECT_SUCCESS:
                    case UP_START:
                        if (ftpListener != null) {
                            ftpListener.progress(0, 0, "", 0, 0);
                        }
                        break;
                    case UP_UPDATE:
                        if (ftpListener != null) {
                            int progress = msg.arg1;
                            int totalProgress = msg.arg2;
                            Map<String, Object> obj = (Map<String, Object>) msg.obj;
                            String path = (String) obj.get("path");
                            int total = (int) obj.get("total");
                            int index = (int) obj.get("index");
                            ftpListener.progress(progress, totalProgress, path, total, index);
                        }
                        break;
                    case UP_BREAK:
                        if (ftpListener != null) {
                            ftpListener.breakTask();
                        }
                        break;
                    case UP_ERROR:
                        if (ftpListener != null) {
                            String text = (String) msg.obj;
                            ftpListener.error(text);
                        }
                        break;
                    case UP_OVER:
                        if (ftpListener != null) {
                            ftpListener.success();
                        }
                        break;
                }
            }
        };

        public UploadTask(String localDir, String remoteDir, FtpListener ftpListener) {
            this.localDir = localDir;
            this.remoteDir = remoteDir;
            this.ftpListener = ftpListener;
            this.client = new FTPClient();
            this.interceptFlag = false;
            client.setConnectTimeout(30000);
            this.indexSize = 0;
            this.localFileSize = 0;
            this.index = 0;
            this.localFileCount = 0;
            this.currentPath = "";
        }

        @Override
        public void run() {

            try {
                handler.sendEmptyMessage(CONNECT_START);
                // 1、连接服务器
                if (!client.isConnected()) {
                    // 如果采用默认端口，可以使用client.connect(host)的方式直接连接FTP服务器
                    client.connect(host, port);
                    // 登录
                    client.login(username, password);
                    // 获取ftp登录应答码
                    int reply = client.getReplyCode();
                    // 验证是否登陆成功
                    if (!FTPReply.isPositiveCompletion(reply)) {
                        client.disconnect();
                        Message msg = new Message();
                        msg.what = CONNECT_ERROR;
                        msg.obj = "用户名或密码错误";
                        handler.sendMessage(msg);
                    }
                    // 2、设置连接属性
                    if (FTPReply.isPositiveCompletion(client.sendCommand(
                            "OPTS UTF8", "ON"))) {// 开启服务器对UTF-8的支持，如果服务器支持就用UTF-8编码，否则就使用本地编码（GBK）.
                        LOCAL_CHARSET = "UTF-8";
                    }
                    client.setControlEncoding(LOCAL_CHARSET);
                    // 设置以二进制方式传输
                    client.setFileType(FTPClient.BINARY_FILE_TYPE);
                    client.enterLocalPassiveMode();
                }
                //连接成功
                Message msg = new Message();
                msg.what = CONNECT_SUCCESS;
                handler.sendMessage(msg);

            } catch (Exception e) {
                try {
                    client.disconnect();
                } catch (IOException e1) {
                }
                Message msg = new Message();
                msg.what = CONNECT_ERROR;
                msg.obj = "连接FTP服务器失败！";
                handler.sendMessage(msg);
            }
            try {
                if (client.isConnected()) {
                    Message msg = new Message();
                    msg.what = UP_START;
                    handler.sendMessage(msg);

                    File localFile = new File(localDir);
                    if (!localFile.exists()) {
                        msg = new Message();
                        msg.what = UP_ERROR;
                        msg.obj = "文件不存在！";
                        handler.sendMessage(msg);
                        return;
                    }

                    //计算需要上传文件个数
                    calculateUploadFileCount(localFile);

                    if (!CreateDirecroty(remoteDir)) {
                        msg = new Message();
                        msg.what = UP_ERROR;
                        msg.obj = "创建服务器远程目录失败";
                        handler.sendMessage(msg);
                        return;
                    }
                    //上传文件
                    upload(localFile, remoteDir);

                    if (!interceptFlag) {
                        handler.sendEmptyMessage(UP_OVER);
                    }
                }
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = UP_ERROR;
                msg.obj = "上传文件失败";
                handler.sendMessage(msg);
                e.printStackTrace();
            }

        }

        private void calculateUploadFileCount(File localFile) {
            try {
                File[] localFiles = localFile.listFiles();
                for (File file : localFiles) {
                    if (file.isDirectory()) {
                        calculateUploadFileCount(file);
                    } else {
                        localFileSize += file.length();
                        localFileCount++;
                    }
                }
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = UP_ERROR;
                msg.obj = "读取本地文件数量异常！";
                handler.sendMessage(msg);
                e.printStackTrace();
            }
        }

        private void upload(File localFile, String remoteDir) {
            try {
                File[] localFiles = localFile.listFiles();
                for (File file : localFiles) {
                    client.changeWorkingDirectory(remoteDir);
                    if (file.isFile()) {
                        index++;
                        boolean state = uploadSingleFile(file, remoteDir + "/" + file.getName());
                        if (!state) {
                            return;
                        }
                    } else {
                        client.makeDirectory(file.getName());
                        upload(file, remoteDir + "/" + file.getName());
                    }
                }


            } catch (Exception e) {
                Message msg = new Message();
                msg.what = UP_ERROR;
                msg.obj = "上传文件失败！";
                handler.sendMessage(msg);
                e.printStackTrace();
            }
        }

        /**
         * 上传单个文件
         *
         * @param file
         * @param remoteFilePath
         * @return
         * @throws IOException
         */
        private boolean uploadSingleFile(File file, String remoteFilePath) throws IOException {
            OutputStream out = null;
            FileInputStream in = null;
            try {
                long process = 0;

                // 对远程目录的处理
                String remoteFileName = remoteFilePath;
                if (remoteFilePath.contains("/")) {
                    remoteFileName = remoteFilePath.substring(remoteFilePath.lastIndexOf("/") + 1);
                }

                currentPath = file.getAbsolutePath().replace(localDir, "");

                // 检查远程是否存在文件
                FTPFile[] files = client.listFiles(new String(remoteFileName
                        .getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                if (files.length == 1) {
                    long remoteSize = files[0].getSize();
                    long localSize = file.length();
                    indexSize += remoteSize;
                    if (remoteSize == localSize) {
                        long totalProcess = indexSize * 100L / localFileSize;
                        Message msg = new Message();
                        //更新文件下载进度,值存放在process变量中
                        msg.what = UP_UPDATE;
                        Map<String, Object> obj = new HashMap<>();
                        obj.put("path", currentPath);
                        obj.put("total", localFileCount);
                        obj.put("index", index);
                        msg.obj = obj;
                        msg.arg1 = 100;
                        msg.arg2 = (int) totalProcess;
                        handler.sendMessage(msg);
                        return true;
                    } else if (remoteSize > localSize) {
                        Message msg = new Message();
                        msg.what = UP_ERROR;
                        msg.obj = "服务器远程文件错误";
                        handler.sendMessage(msg);
                        return false;
                    }

                    out = client.appendFileStream(new String(remoteFileName
                            .getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                    in = new FileInputStream(file);
                    in.skip(remoteSize);//跳过服务器已经存在的字节数

                    byte[] bytes = new byte[1024];
                    long step = localSize / 100;
                    process = remoteSize / step;
                    int c;
                    while ((c = in.read(bytes)) != -1) {
                        if (interceptFlag) {
                            break;
                        }
                        out.write(bytes, 0, c);
                        remoteSize += c;
                        indexSize += c;

                        long nowProcess = remoteSize / step;
                        long totalProcess = indexSize * 100L / localFileSize;
                        if (nowProcess > process) {
                            process = nowProcess;
                            Message msg = new Message();
                            //更新文件下载进度,值存放在process变量中
                            msg.what = UP_UPDATE;
                            Map<String, Object> obj = new HashMap<>();
                            obj.put("path", currentPath);
                            obj.put("total", localFileCount);
                            obj.put("index", index);
                            msg.obj = obj;
                            msg.arg1 = (int) process;
                            msg.arg2 = (int) totalProcess;
                            handler.sendMessage(msg);

                        }
                    }

                    out.flush();
                    out.close();
                    in.close();
                    boolean isDo = client.completePendingCommand();
                    if (isDo) {
                        return true;
                    } else {
                        handler.sendEmptyMessage(UP_BREAK);
                        return false;
                    }
                } else {
                    long remoteSize = 0;
                    long localSize = file.length();
                    out = client.storeFileStream(new String(remoteFileName
                            .getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                    in = new FileInputStream(file);

                    byte[] bytes = new byte[1024];
                    long step = localSize / 100;
                    process = remoteSize / step;
                    int c;
                    while ((c = in.read(bytes)) != -1) {
                        if (interceptFlag) {
                            break;
                        }
                        interceptFlag = true;
                        out.write(bytes, 0, c);
                        remoteSize += c;
                        indexSize += c;
                        long nowProcess = remoteSize / step;
                        long totalProcess = indexSize * 100L / localFileSize;
                        if (nowProcess > process) {
                            process = nowProcess;
                            Message msg = new Message();
                            //更新文件下载进度,值存放在process变量中
                            msg.what = UP_UPDATE;
                            Map<String, Object> obj = new HashMap<>();
                            obj.put("path", currentPath);
                            obj.put("total", localFileCount);
                            obj.put("index", index);
                            msg.obj = obj;
                            msg.arg1 = (int) process;
                            msg.arg2 = (int) totalProcess;
                            handler.sendMessage(msg);

                        }
                    }
                    out.flush();
                    out.close();
                    in.close();
                    boolean isDo = client.completePendingCommand();
                    if (isDo) {
                        return true;
                    } else {
                        handler.sendEmptyMessage(UP_BREAK);
                        return false;
                    }
                }
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = UP_ERROR;
                msg.obj = "上传文件失败！";
                handler.sendMessage(msg);
                e.printStackTrace();
            }

            return false;
        }

        /** */
        /**
         * 递归创建远程服务器目录
         *
         * @param directory 远程服务器文件绝对路径
         * @return 目录创建是否成功
         * @throws IOException
         */
        public boolean CreateDirecroty(String directory)
                throws Exception {
            if (!client.changeWorkingDirectory(new String(directory.getBytes(LOCAL_CHARSET), SERVER_CHARSET))) {
                // 如果远程目录不存在，则递归创建远程服务器目录
                int start = 0;
                int end = 0;
                if (directory.startsWith("/")) {
                    start = 1;
                } else {
                    start = 0;
                }
                end = directory.indexOf("/", start);
                if (end == -1) {
                    end = directory.length();
                }
                while (true) {
                    String subDirectory = new String(directory.substring(start, end)
                            .getBytes(LOCAL_CHARSET), SERVER_CHARSET);
                    if (!client.changeWorkingDirectory(subDirectory)) {
                        if (client.makeDirectory(subDirectory)) {
                            client.changeWorkingDirectory(subDirectory);
                        } else {
                            System.out.println("创建目录失败");
                            return false;
                        }
                    }

                    start = end + 1;
                    end = directory.indexOf("/", start);
                    if (end == -1) {
                        end = directory.length();
                    }
                    // 检查所有目录是否创建完毕
                    if (end <= start) {
                        break;
                    }
                }
            }
            return true;
        }
    }
}