package com.xxl.job.admin.core.jobbean;

import com.xxl.job.admin.core.thread.JobTriggerPoolHelper;
import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import com.xxl.job.admin.core.util.I18nUtil;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * http job bean
 * “@DisallowConcurrentExecution” diable concurrent, thread size can not be only one, better given more
 * 当quartz监听到有任务需要触发是，会调用 JobRunShell 的run方法， 在该类的run方法中，会调用当前任务的JOB_CLASS 的excute方法，
 *
 * 调用链最终会调用到remoteHttpJobBean 的 executeInternal()
 * @author xuxueli 2015-12-17 18:20:34
 */
//@DisallowConcurrentExecution
public class RemoteHttpJobBean extends QuartzJobBean {
	private static Logger logger = LoggerFactory.getLogger(RemoteHttpJobBean.class);

	@Override
	protected void executeInternal(JobExecutionContext context)
			throws JobExecutionException {

		// load jobId
		JobKey jobKey = context.getTrigger().getJobKey();
		Integer jobId = Integer.valueOf(jobKey.getName());

		logger.info("监听quartz有任务需要执行========================================================jobKey：{},jobid :{}",jobKey,jobId);

		// trigger
		//XxlJobTrigger.trigger(jobId);
		JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null);
	}

}