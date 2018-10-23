package com.xxl.job.core.rpc.netcom.jetty.server;

import com.xxl.job.core.thread.ExecutorRegistryThread;
import com.xxl.job.core.thread.TriggerCallbackThread;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * rpc jetty server
 * @author xuxueli 2015-11-19 22:29:03
 */
public class JettyServer {
	private static final Logger logger = LoggerFactory.getLogger(JettyServer.class);

	private Server server;
	private Thread thread;
	public void start(final int port, final String ip, final String appName) throws Exception {
		thread = new Thread(new Runnable() {
			@Override
			public void run() {

				// The Server  // 非阻塞
				server = new Server(new ExecutorThreadPool(1000));

				// HTTP connector
				ServerConnector connector = new ServerConnector(server);
				if (ip!=null && ip.trim().length()>0) {
					//connector.setHost(ip);	// The network interface this connector binds to as an IP address or a hostname.  If null or 0.0.0.0, then bind to all interfaces.
				}
				connector.setPort(port);
                // 设置连接器
				server.setConnectors(new Connector[]{connector});

                // 设置一个连接处理的handler
				// Set a handler
				HandlerCollection handlerc =new HandlerCollection();
				handlerc.setHandlers(new Handler[]{new JettyServerHandler()});
				server.setHandler(handlerc);
                logger.info("开始启动jetty服务器 服务IP：{}，服务端口：{}=======================================",ip,port);
				try {
					// Start server
					server.start();
					logger.info("jetty服务器启动成功  端口号:{}==================================================", port);


                    // 此处是启动一个执行器注册的线程， 该线程第一次执行的时候，将该执行器的信息注册到数据库， xxl_job_qrtz_trigger_registry 这张表中  ，
                    // 此后，每过30秒， 执行器就会去数据库更新数据，表示自己还在存活中
                    // 调度中心那边会有一个线程定期的去数据库扫描，会自动的将30秒之内未更新信息的机器剔除， 同时将新加入的服务载入到集群列表中

					// Start Registry-Server
					ExecutorRegistryThread.getInstance().start(port, ip, appName);


                    // 启动一个日志监控的线程，里面设置了一个队列，每次有任务结束后，都会把任务的日志ID和处理结果放入队列，
                    // 线程从队列里面拿到日志ID和处理结果，通过调用adminBiz的callback方法来回调给调度中心执行结果
					// Start Callback-Server
					TriggerCallbackThread.getInstance().start();

					server.join();	// block until thread stopped
					logger.info(">>>>>>>>>>> xxl-rpc server join success, netcon={}, port={}", JettyServer.class.getName(), port);
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				} finally {
					//destroy();
				}
			}
		});
		thread.setDaemon(true);	// daemon, service jvm, user thread leave >>> daemon leave >>> jvm leave
		thread.start();
	}

	public void destroy() {

		// destroy Registry-Server
		ExecutorRegistryThread.getInstance().toStop();

		// destroy Callback-Server
		TriggerCallbackThread.getInstance().toStop();

		// destroy server
		if (server != null) {
			try {
				server.stop();
				server.destroy();
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
		if (thread.isAlive()) {
			thread.interrupt();
		}

		logger.info(">>>>>>>>>>> xxl-rpc server destroy success, netcon={}", JettyServer.class.getName());
	}

}
