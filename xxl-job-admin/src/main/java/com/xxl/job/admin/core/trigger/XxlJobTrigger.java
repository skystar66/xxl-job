package com.xxl.job.admin.core.trigger;

import com.xxl.job.admin.core.model.XxlJobGroup;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.model.XxlJobLog;
import com.xxl.job.admin.core.route.ExecutorRouteStrategyEnum;
import com.xxl.job.admin.core.schedule.XxlJobDynamicScheduler;
import com.xxl.job.admin.core.thread.JobFailMonitorHelper;
import com.xxl.job.admin.core.util.I18nUtil;
import com.xxl.job.core.biz.ExecutorBiz;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.biz.model.TriggerParam;
import com.xxl.job.core.enums.ExecutorBlockStrategyEnum;
import com.xxl.job.core.util.IpUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * xxl-job trigger
 * Created by xuxueli on 17/7/13.
 */
public class XxlJobTrigger {
    private static Logger logger = LoggerFactory.getLogger(XxlJobTrigger.class);

    /**
     * trigger job
     *
     * @param jobId 任务ID
     * @param triggerType 触发类型 Cron
     * @param failRetryCount 重试次数
     * 			>=0: use this param 使用参数
     * 			<0: use param from job info config 实用配置文件参数
     * @param executorShardingParam 执行分片参数
     * @param executorParam 执行任务参数
     *          null: use job param
     *          not null: cover job param
     */
    public static void trigger(int jobId, TriggerTypeEnum triggerType, int failRetryCount, String executorShardingParam, String executorParam) {
        // load data
        // 通过JobId从数据库中查询该任务的具体信息
        XxlJobInfo jobInfo = XxlJobDynamicScheduler.xxlJobInfoDao.loadById(jobId);
        if (jobInfo == null) {
            logger.warn(">>>>>>>>>>>> trigger fail, jobId invalid，jobId={}", jobId);
            return;
        }
        if (executorParam != null) {
            jobInfo.setExecutorParam(executorParam);
        }
        //重试次数 当重试次数为小于0时，取页面配置的该任务重试次数
        int finalFailRetryCount = failRetryCount>=0?failRetryCount:jobInfo.getExecutorFailRetryCount();
        // 获取该类型的执行器信息
        XxlJobGroup group = XxlJobDynamicScheduler.xxlJobGroupDao.load(jobInfo.getJobGroup());

        // sharding param
        int[] shardingParam = null;
        if (executorShardingParam!=null){
            String[] shardingArr = executorShardingParam.split("/");
            if (shardingArr.length==2 && StringUtils.isNumeric(shardingArr[0]) && StringUtils.isNumeric(shardingArr[1])) {
                shardingParam = new int[2];
                shardingParam[0] = Integer.valueOf(shardingArr[0]);
                shardingParam[1] = Integer.valueOf(shardingArr[1]);
            }
        }
        // 判断路由策略  是否为  分片广播模式
        if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST==ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null)
                && CollectionUtils.isNotEmpty(group.getRegistryList()) && shardingParam==null) {
            for (int i = 0; i < group.getRegistryList().size(); i++) {
                processTrigger(group, jobInfo, finalFailRetryCount, triggerType, i, group.getRegistryList().size());
            }
        } else {
            // 出分片模式外，其他的路由策略均走这里
            if (shardingParam == null) {
                shardingParam = new int[]{0, 1};
            }
            // 默认分片标记为0
            // 默认分片总数为1
            processTrigger(group, jobInfo, finalFailRetryCount, triggerType, shardingParam[0], shardingParam[1]);
        }

    }

    /**
     * @param group                     job group, registry list may be empty 执行器信息
     * @param jobInfo                   任务信息
     * @param finalFailRetryCount       失败重试次数
     * @param triggerType               触发类型Cron
     * @param index                     sharding index 分片索引
     * @param total                     sharding index 分片总数
     */
    private static void processTrigger(XxlJobGroup group, XxlJobInfo jobInfo, int finalFailRetryCount, TriggerTypeEnum triggerType, int index, int total){

        // param
        // 匹配运行模式
        ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), ExecutorBlockStrategyEnum.SERIAL_EXECUTION);  // block strategy
        //  获取路由策略
        ExecutorRouteStrategyEnum executorRouteStrategyEnum = ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null);    // route strategy
        String shardingParam = (ExecutorRouteStrategyEnum.SHARDING_BROADCAST==executorRouteStrategyEnum)?String.valueOf(index).concat("/").concat(String.valueOf(total)):null;

        //定义日志信息
        // 1、save log-id
        XxlJobLog jobLog = new XxlJobLog();
        jobLog.setJobGroup(jobInfo.getJobGroup());
        jobLog.setJobId(jobInfo.getId());//任务ID
        jobLog.setTriggerTime(new Date());//调度时间
        XxlJobDynamicScheduler.xxlJobLogDao.save(jobLog);
        logger.debug(">>>>>>>>>>> xxl-job trigger start, jobId:{}", jobLog.getId());

        //初始化触发参数信息
        // 2、init trigger-param
        TriggerParam triggerParam = new TriggerParam();
        triggerParam.setJobId(jobInfo.getId());//任务ID
        triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());//Bean模式的spring容器服务
        triggerParam.setExecutorParams(jobInfo.getExecutorParam());//执行参数（页面配置）
        triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());//运行模式
        triggerParam.setExecutorTimeout(jobInfo.getExecutorTimeout());//执行超时时间
        triggerParam.setLogId(jobLog.getId());//日志ID
        triggerParam.setLogDateTim(jobLog.getTriggerTime().getTime());//日志触发时间
        triggerParam.setGlueType(jobInfo.getGlueType());//
        triggerParam.setGlueSource(jobInfo.getGlueSource());
        triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
        triggerParam.setBroadcastIndex(index);// 设置分片标记
        triggerParam.setBroadcastTotal(total);// 设置分片总数

        //初始化执行器地址(选取路由地址)
        // 3、init address
        String address = null;
        ReturnT<String> routeAddressResult = null;
        if (CollectionUtils.isNotEmpty(group.getRegistryList())) {
            if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == executorRouteStrategyEnum) {
                if (index < group.getRegistryList().size()) {
                    address = group.getRegistryList().get(index);
                } else {
                    address = group.getRegistryList().get(0);
                }
            } else {
                routeAddressResult = executorRouteStrategyEnum.getRouter().route(triggerParam, group.getRegistryList());
                if (routeAddressResult.getCode() == ReturnT.SUCCESS_CODE) {
                    address = routeAddressResult.getContent();
                }
            }
        } else {
            routeAddressResult = new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobconf_trigger_address_empty"));
        }

        //远程执行
        // 4、trigger remote executor
        ReturnT<String> triggerResult = null;
        if (address != null) {
            triggerResult = runExecutor(triggerParam, address);
        } else {
            triggerResult = new ReturnT<String>(ReturnT.FAIL_CODE, null);
        }

        // 5、collection trigger info
        StringBuffer triggerMsgSb = new StringBuffer();
        triggerMsgSb.append(I18nUtil.getString("jobconf_trigger_type")).append("：").append(triggerType.getTitle());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_admin_adress")).append("：").append(IpUtil.getIp());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_exe_regtype")).append("：")
                .append( (group.getAddressType() == 0)?I18nUtil.getString("jobgroup_field_addressType_0"):I18nUtil.getString("jobgroup_field_addressType_1") );
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_exe_regaddress")).append("：").append(group.getRegistryList());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorRouteStrategy")).append("：").append(executorRouteStrategyEnum.getTitle());
        if (shardingParam != null) {
            triggerMsgSb.append("("+shardingParam+")");
        }
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorBlockStrategy")).append("：").append(blockStrategy.getTitle());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_timeout")).append("：").append(jobInfo.getExecutorTimeout());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorFailRetryCount")).append("：").append(finalFailRetryCount);

        triggerMsgSb.append("<br><br><span style=\"color:#00c0ef;\" > >>>>>>>>>>>"+ I18nUtil.getString("jobconf_trigger_run") +"<<<<<<<<<<< </span><br>")
                .append((routeAddressResult!=null&&routeAddressResult.getMsg()!=null)?routeAddressResult.getMsg()+"<br><br>":"").append(triggerResult.getMsg()!=null?triggerResult.getMsg():"");

        //远程执行完毕 更新log日志
        // 6、save log trigger-info
        jobLog.setExecutorAddress(address);//执行器地址
        jobLog.setExecutorHandler(jobInfo.getExecutorHandler()); //执行器 handler
        jobLog.setExecutorParam(jobInfo.getExecutorParam()); // 执行参数
        jobLog.setExecutorShardingParam(shardingParam); // 执行分片参数
        jobLog.setExecutorFailRetryCount(finalFailRetryCount); //失败重试次数
        //jobLog.setTriggerTime();
        jobLog.setTriggerCode(triggerResult.getCode()); // 调度返回结果 触发是否成功
        jobLog.setTriggerMsg(triggerMsgSb.toString()); //调度返回结果
        XxlJobDynamicScheduler.xxlJobLogDao.updateTriggerInfo(jobLog);


        // 将日志ID，放入队列， 日志监控线程来监控任务的执行状态

        // 7、monitor trigger
        JobFailMonitorHelper.monitor(jobLog.getId());
        logger.debug(">>>>>>>>>>> xxl-job trigger end, jobId:{}", jobLog.getId());
    }

    /**
     * run executor
     * @param triggerParam 执行参数
     * @param address 执行器地址
     * @return
     */
    public static ReturnT<String> runExecutor(TriggerParam triggerParam, String address){
        ReturnT<String> runResult = null;
        try {
            //创建一个ExcutorBiz的对象，重点在这个方法里面
            ExecutorBiz executorBiz = XxlJobDynamicScheduler.getExecutorBiz(address);
            // 这个run 方法不会最终执行，仅仅只是为了触发 proxy object 的 invoke方法，同时将目标的类型传送给服务端， 因为在代理对象的invoke的方法里面没有执行目标对象的方法
            runResult = executorBiz.run(triggerParam);
        } catch (Exception e) {
            logger.error(">>>>>>>>>>> xxl-job trigger error, please check if the executor[{}] is running.", address, e);
            runResult = new ReturnT<String>(ReturnT.FAIL_CODE, ""+e );
        }

        StringBuffer runResultSB = new StringBuffer(I18nUtil.getString("jobconf_trigger_run") + "：");
        runResultSB.append("<br>address：").append(address);
        runResultSB.append("<br>code：").append(runResult.getCode());
        runResultSB.append("<br>msg：").append(runResult.getMsg());

        runResult.setMsg(runResultSB.toString());
        logger.info("调用执行器方法，执行器返回结果：【{}】=============================================================",runResult);
        return runResult;
    }

}
