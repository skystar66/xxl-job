package com.xxl.job.core.thread;

import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.enums.RegistryConfig;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.core.util.FileUtil;
import com.xxl.job.core.util.JacksonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by xuxueli on 16/7/22.
 */
public class TriggerCallbackThread {
    private static Logger logger = LoggerFactory.getLogger(TriggerCallbackThread.class);

    private static TriggerCallbackThread instance = new TriggerCallbackThread();
    public static TriggerCallbackThread getInstance(){
        return instance;
    }

    /**
     * job results callback queue
     */
    private LinkedBlockingQueue<HandleCallbackParam> callBackQueue = new LinkedBlockingQueue<HandleCallbackParam>();
    public static void pushCallBack(HandleCallbackParam callback){
        getInstance().callBackQueue.add(callback);
        logger.debug(">>>>>>>>>>> xxl-job, push callback request, logId:{}", callback.getLogId());
    }

    /**
     * callback thread
     */
    private Thread triggerCallbackThread;
    private Thread triggerRetryCallbackThread;
    private volatile boolean toStop = false;
    public void start() {

        // valid
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor callback config fail, adminAddresses is null.");
            return;
        }

        // callback
        triggerCallbackThread = new Thread(new Runnable() {

            @Override
            public void run() {

                // normal callback
                while(!toStop){
                    try {
                        HandleCallbackParam callback = getInstance().callBackQueue.take();
                        if (callback != null) {

                            // callback list param
                            List<HandleCallbackParam> callbackParamList = new ArrayList<HandleCallbackParam>();
                            int drainToNum = getInstance().callBackQueue.drainTo(callbackParamList);
                            callbackParamList.add(callback);

                            // callback, will retry if error
                            if (callbackParamList!=null && callbackParamList.size()>0) {
                                doCallback(callbackParamList);
                            }
                        }
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }

                // last callback
                try {
                    List<HandleCallbackParam> callbackParamList = new ArrayList<HandleCallbackParam>();
                    int drainToNum = getInstance().callBackQueue.drainTo(callbackParamList);
                    if (callbackParamList!=null && callbackParamList.size()>0) {
                        doCallback(callbackParamList);
                    }
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                logger.info(">>>>>>>>>>> xxl-job, executor callback thread destory.");

            }
        });
        triggerCallbackThread.setDaemon(true);
        triggerCallbackThread.start();


        // retry
        triggerRetryCallbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(!toStop){
                    try {
                        retryFailCallbackFile();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                    try {
                        TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
                    } catch (InterruptedException e) {
                        logger.warn(">>>>>>>>>>> xxl-job, executor retry callback thread interrupted, error msg:{}", e.getMessage());
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, executor retry callback thread destory.");
            }
        });
        triggerRetryCallbackThread.setDaemon(true);
        triggerRetryCallbackThread.start();

    }
    public void toStop(){
        toStop = true;
        // stop callback, interrupt and wait
        triggerCallbackThread.interrupt();
        try {
            triggerCallbackThread.join();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }

        // stop retry, interrupt and wait
        triggerRetryCallbackThread.interrupt();
        try {
            triggerRetryCallbackThread.join();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * do callback, will retry if error
     * @param callbackParamList
     */
    private void doCallback(List<HandleCallbackParam> callbackParamList){
        // 获取调度中心的adminBiz列表，在执行器启动的时候，初始化的，
        boolean callbackRet = false;
        // callback, will retry if error
        for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
            try {
                // 这里的adminBiz 调用的callback方法，因为是通过NetComClientProxy 这个factoryBean创建的代理对象，
                 // 在getObject方法中，最终是没有调用的目标类方法的invoke的。  只是将目标类的方法名，参数，类名，等信息发送给调度中心了
                // 发送的地址调度中心的接口地址是 ：“调度中心IP/api” 这个接口 。 这个是在执行器启动的时候初始化设置好的。
                // 调度中心的API接口拿到请求之后，通过参数里面的类名，方法，参数，反射出来一个对象，然后invoke， 最终将结果写入数据库
                ReturnT<String> callbackResult = adminBiz.callback(callbackParamList);
                if (callbackResult!=null && ReturnT.SUCCESS_CODE == callbackResult.getCode()) {
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback finish.");
                    callbackRet = true;
                    break;
                } else {
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback fail, callbackResult:" + callbackResult);
                }
            } catch (Exception e) {
                callbackLog(callbackParamList, "<br>----------- xxl-job job callback error, errorMsg:" + e.getMessage());
            }
        }
        if (!callbackRet) {
            appendFailCallbackFile(callbackParamList);
        }
    }

    /**
     * callback log
     */
    private void callbackLog(List<HandleCallbackParam> callbackParamList, String logContent){
        for (HandleCallbackParam callbackParam: callbackParamList) {
            String logFileName = XxlJobFileAppender.makeLogFileName(new Date(callbackParam.getLogDateTim()), callbackParam.getLogId());
            XxlJobFileAppender.contextHolder.set(logFileName);
            XxlJobLogger.log(logContent);
        }
    }


    // ---------------------- fail-callback file ----------------------

    private static String failCallbackFileName = XxlJobFileAppender.getLogPath().concat(File.separator).concat("xxl-job-callback").concat(".log");

    private void appendFailCallbackFile(List<HandleCallbackParam> callbackParamList){
        // append file
        String content = JacksonUtil.writeValueAsString(callbackParamList);
        FileUtil.appendFileLine(failCallbackFileName, content);
    }

    private void retryFailCallbackFile(){

        // load and clear file
        List<String> fileLines = FileUtil.loadFileLines(failCallbackFileName);
        FileUtil.deleteFile(failCallbackFileName);

        // parse
        List<HandleCallbackParam> failCallbackParamList = new ArrayList<>();
        if (fileLines!=null && fileLines.size()>0) {
            for (String line: fileLines) {
                List<HandleCallbackParam> failCallbackParamListTmp = JacksonUtil.readValue(line, List.class, HandleCallbackParam.class);
                if (failCallbackParamListTmp!=null && failCallbackParamListTmp.size()>0) {
                    failCallbackParamList.addAll(failCallbackParamListTmp);
                }
            }
        }

        // retry callback, 100 lines per page
        if (failCallbackParamList!=null && failCallbackParamList.size()>0) {
            int pagesize = 100;
            List<HandleCallbackParam> pageData = new ArrayList<>();
            for (int i = 0; i < failCallbackParamList.size(); i++) {
                pageData.add(failCallbackParamList.get(i));
                if (i>0 && i%pagesize == 0) {
                    doCallback(pageData);
                    pageData.clear();
                }
            }
            if (pageData.size() > 0) {
                doCallback(pageData);
            }
        }
    }

}
