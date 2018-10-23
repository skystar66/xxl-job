package com.xxl.job.core.executor;

import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.biz.ExecutorBiz;
import com.xxl.job.core.biz.impl.ExecutorBizImpl;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.annotation.JobHandler;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.core.rpc.netcom.NetComClientProxy;
import com.xxl.job.core.rpc.netcom.NetComServerFactory;
import com.xxl.job.core.thread.JobLogFileCleanThread;
import com.xxl.job.core.thread.JobThread;
import com.xxl.job.core.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by xuxueli on 2016/3/2 21:14.
 */
public class XxlJobExecutor implements ApplicationContextAware {
    private static final Logger logger = LoggerFactory.getLogger(XxlJobExecutor.class);

    // ---------------------- param ----------------------
    private String adminAddresses;
    private String appName;
    private String ip;
    private int port;
    private String accessToken;
    private String logPath;
    private int logRetentionDays;

    public void setAdminAddresses(String adminAddresses) {
        this.adminAddresses = adminAddresses;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    // ---------------------- applicationContext ----------------------
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }


    // ---------------------- start + stop ----------------------
    public void start() throws Exception {
        // 初始化调度中心的地址列表， 通过NetComClientProxy创建好adminBiz实例
        // init admin-client
        initAdminBizList(adminAddresses, accessToken);
        // 初始化所有带有@JobHandler的handle， 根据name , 放入一个ConcurrentHashMap 中
        // init executor-jobHandlerRepository
        initJobHandlerRepository(applicationContext);
        // 初始化本地日志路径
        // init logpath
        XxlJobFileAppender.initLogPath(logPath);
        // 初始化本地jetty服务器
        // init executor-server
        initExecutorServer(port, ip, appName, accessToken);
        // 启动一个线程，用来清理本地日志， 默认保留最近一天的日志
        // init JobLogFileCleanThread
        JobLogFileCleanThread.getInstance().start(logRetentionDays);
    }

    public void destroy() {
        // destory JobThreadRepository
        if (JobThreadRepository.size() > 0) {
            for (Map.Entry<Integer, JobThread> item : JobThreadRepository.entrySet()) {
                removeJobThread(item.getKey(), "web container destroy and kill the job.");
            }
            JobThreadRepository.clear();
        }

        // destory executor-server
        stopExecutorServer();

        // destory JobLogFileCleanThread
        JobLogFileCleanThread.getInstance().toStop();
    }


    // ---------------------- admin-client ----------------------
    private static List<AdminBiz> adminBizList;

    private static void initAdminBizList(String adminAddresses, String accessToken) throws Exception {

       logger.info("开始执行初始化initAdminBizList。。。。参数  adminAddresses:" + adminAddresses + ",accessToken:" + accessToken);
        if (adminAddresses != null && adminAddresses.trim().length() > 0) {
            for (String address : adminAddresses.trim().split(",")) {
                if (address != null && address.trim().length() > 0) {
                    String addressUrl = address.concat(AdminBiz.MAPPING);
                    AdminBiz adminBiz = (AdminBiz) new NetComClientProxy(AdminBiz.class, addressUrl, accessToken).getObject();
                    if (adminBizList == null) {
                        adminBizList = new ArrayList<AdminBiz>();
                    }
                    adminBizList.add(adminBiz);
                }
            }
        }
    }

    public static List<AdminBiz> getAdminBizList() {
        return adminBizList;
    }


    // ---------------------- executor-server(jetty) ----------------------
    private NetComServerFactory serverFactory = new NetComServerFactory();

    private void initExecutorServer(int port, String ip, String appName, String accessToken) throws Exception {

        logger.info("开始执行初始化jetty 执行器 服务端。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。");
        // 如果port为空，则默认9999为他的jetty服务器端口
        // valid param
        port = port > 0 ? port : NetUtil.findAvailablePort(9999);
        // 创建一个ExecutorService 实例，放入Map中，后面会通过class获取到他的实例执行run方法
        // start server
        NetComServerFactory.putService(ExecutorBiz.class, new ExecutorBizImpl());   // rpc-service, base on jetty
        NetComServerFactory.setAccessToken(accessToken);
        // 启动jetty 服务器
        serverFactory.start(port, ip, appName); // jetty + registry
    }

    private void stopExecutorServer() {
        serverFactory.destroy();    // jetty + registry + callback
    }


    // ---------------------- job handler repository ----------------------
    private static ConcurrentHashMap<String, IJobHandler> jobHandlerRepository = new ConcurrentHashMap<String, IJobHandler>();

    public static IJobHandler registJobHandler(String name, IJobHandler jobHandler) {
        logger.info(">>>>>>>>>>> xxl-job register jobhandler success, name:{}, jobHandler:{}", name, jobHandler);
        return jobHandlerRepository.put(name, jobHandler);
    }

    public static IJobHandler loadJobHandler(String name) {
        return jobHandlerRepository.get(name);
    }

    private static void initJobHandlerRepository(ApplicationContext applicationContext) {

        logger.info("开始执行初始化initJobHandlerRepository。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。。");
        if (applicationContext == null) {
            return;
        }

        // init job handler action
        Map<String, Object> serviceBeanMap = applicationContext.getBeansWithAnnotation(JobHandler.class);

        if (serviceBeanMap != null && serviceBeanMap.size() > 0) {
            for (Object serviceBean : serviceBeanMap.values()) {
                if (serviceBean instanceof IJobHandler) {
                    String name = serviceBean.getClass().getAnnotation(JobHandler.class).value();
                    IJobHandler handler = (IJobHandler) serviceBean;
                    if (loadJobHandler(name) != null) {
                        throw new RuntimeException("xxl-job jobhandler naming conflicts.");
                    }
                    registJobHandler(name, handler);
                }
            }
        }
    }


    // ---------------------- job thread repository ----------------------
    private static ConcurrentHashMap<Integer, JobThread> JobThreadRepository = new ConcurrentHashMap<Integer, JobThread>();

    public static JobThread registJobThread(int jobId, IJobHandler handler, String removeOldReason) {
        JobThread newJobThread = new JobThread(jobId, handler);
        newJobThread.start();
        logger.info(">>>>>>>>>>> xxl-job 注册 JobThread 成功, jobId:{}, handler:{}", new Object[]{jobId, handler});

        JobThread oldJobThread = JobThreadRepository.put(jobId, newJobThread);    // putIfAbsent | oh my god, map's put method return the old value!!!
        if (oldJobThread != null) {
            oldJobThread.toStop(removeOldReason);//终止掉老线程
            oldJobThread.interrupt(); //终止掉老线程
        }

        return newJobThread;
    }

    public static void removeJobThread(int jobId, String removeOldReason) {
        JobThread oldJobThread = JobThreadRepository.remove(jobId);
        if (oldJobThread != null) {
            oldJobThread.toStop(removeOldReason);//终止正在运行的线程 标记法
            oldJobThread.interrupt();//终止阻塞状态的线程
        }
    }

    public static JobThread loadJobThread(int jobId) {
        JobThread jobThread = JobThreadRepository.get(jobId);
        return jobThread;
    }

}
