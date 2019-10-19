/*
 * Copyright 2017 ~ 2025 the original author or authors. <wanglsir@gmail.com, 983708408@qq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wl4g.devops.support.task;

import com.wl4g.devops.support.task.GenericTaskRunner.RunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.util.Assert;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.util.CollectionUtils.isEmpty;

/**
 * Generic task schedule runner.
 * 
 * @author Wangl.sir <983708408@qq.com>
 * @version v1.0 2019年6月2日
 * @since
 */
public abstract class GenericTaskRunner<C extends RunProperties>
		implements DisposableBean, ApplicationRunner, Closeable, Runnable {
	final protected Logger log = LoggerFactory.getLogger(getClass());

	/** Boss running. */
	final private AtomicBoolean bossState = new AtomicBoolean(false);

	/** Runner task properties configuration. */
	final C config;

	/** Runner boss thread. */
	private Thread boss;

	/** Runner worker thread group pool. */
	private ThreadPoolExecutor worker;

	public GenericTaskRunner(C config) {
		Assert.notNull(config, "TaskHistory properties must not be null");
		this.config = config;
	}

	@Override
	public synchronized void run(ApplicationArguments args) throws Exception {
		// Call PreStartup
		preStartupProperties();

		// Create worker(if necessary)
		if (bossState.compareAndSet(false, true)) {
			if (config.getConcurrency() > 0) {
				// See:https://www.jianshu.com/p/e7ab1ac8eb4c
				worker = new ThreadPoolExecutor(config.getConcurrency(), config.getConcurrency(), config.getKeepAliveTime(),
						MICROSECONDS, new LinkedBlockingQueue<>(config.getAcceptQueue()),
						new NamedThreadFactory(getClass().getSimpleName()), config.getReject());
			} else {
				log.warn("No workthread pool for started, because the number of workthread is less than 0");
			}

			// Boss asynchronously execution.(if necessary)
			if (config.isStartup()) {
				String name = getClass().getSimpleName() + "-boss";
				boss = new Thread(this, name);
				boss.setDaemon(false);
				boss.start();
			} else {
				run(); // Sync execution.
			}
		} else {
			log.warn("Already runner!, already builders are read-only and do not allow task modification");
		}

		// Call post startup
		postStartupProperties();
	}

	@Override
	public void destroy() throws Exception {
		close();
	}

	@Override
	public void close() throws IOException {
		// Call pre close
		preCloseProperties();

		if (bossState.compareAndSet(true, false)) {
			if (worker != null) {
				try {
					worker.shutdown();
					worker = null;
				} catch (Exception e) {
					log.error("Runner worker shutdown failed!", e);
				}
			}
			try {
				if (boss != null) {
					boss.interrupt();
					boss = null;
				}
			} catch (Exception e) {
				log.error("Runner boss interrupt failed!", e);
			}
		}

		// Call post close
		postCloseProperties();
	}

	/**
	 * Pre startup properties
	 */
	protected void preStartupProperties() throws Exception {

	}

	/**
	 * Post startup properties
	 */
	protected void postStartupProperties() throws Exception {

	}

	/**
	 * Pre close properties
	 */
	protected void preCloseProperties() throws IOException {

	}

	/**
	 * Post close properties
	 */
	protected void postCloseProperties() throws IOException {

	}

	/**
	 * Is the current runner active.
	 * 
	 * @return
	 */
	protected boolean isActive() {
		return boss != null && !boss.isInterrupted() && bossState.get();
	}

	/**
	 * Get configuration properties.
	 * 
	 * @return
	 */
	public C getConfig() {
		return config;
	}

	@Override
	public void run() {
		// Ignore
	}

	/**
	 * Submitted job wait for completed.
	 * 
	 * @param jobs
	 * @param listener
	 * @param timeoutMs
	 * @throws InterruptedException
	 */
	public void submitForComplete(List<NamedIdJob> jobs, CompleteTaskListener listener, long timeoutMs)
			throws IllegalStateException {
		if (!isEmpty(jobs)) {
			int total = jobs.size();
			// Future jobs.
			Map<Future<?>, NamedIdJob> futureJob = new HashMap<Future<?>, NamedIdJob>(total);
			CountDownLatch latch = new CountDownLatch(total); // Submit.
			jobs.stream().forEach(job -> futureJob.put(getWorker().submit(new FutureDoneTaskWrapper(latch, job)), job));
			try {
				if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { // Timeout?
					Iterator<Entry<Future<?>, NamedIdJob>> it = futureJob.entrySet().iterator();
					while (it.hasNext()) {
						Entry<Future<?>, NamedIdJob> entry = it.next();
						if (!entry.getKey().isCancelled() && !entry.getKey().isDone()) {
							entry.getKey().cancel(true);
						} else {
							it.remove(); // Cleanup cancelled or isDone
						}
					}
					TimeoutException ex = new TimeoutException(
							String.format("Failed to job execution timeout, %s -> completed(%s)/total(%s)",
									jobs.get(0).getClass().getName(), (total - latch.getCount()), total));
					listener.onComplete(ex, (total - latch.getCount()), futureJob.values());
				} else {
					listener.onComplete(null, total, emptyList());
				}
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * Get thread worker.
	 * 
	 * @return
	 */
	protected ThreadPoolExecutor getWorker() {
		Assert.state(worker != null, "Worker thread group is not enabled and can be enabled with concurrency > 0");
		return worker;
	}

	/**
	 * The named thread factory
	 */
	private class NamedThreadFactory implements ThreadFactory {
		private final AtomicInteger threads = new AtomicInteger(1);
		private final ThreadGroup group;
		private final String prefix;

		NamedThreadFactory(String prefix) {
			SecurityManager s = System.getSecurityManager();
			this.group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
			if (isBlank(prefix)) {
				prefix = GenericTaskRunner.class.getSimpleName() + "-Default";
			}
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(group, r, prefix + "-" + threads.getAndIncrement(), 0);
			if (t.isDaemon())
				t.setDaemon(false);
			if (t.getPriority() != Thread.NORM_PRIORITY)
				t.setPriority(Thread.NORM_PRIORITY);
			return t;
		}
	}

	/**
	 * Wait future done runnable wrapper.
	 * 
	 * @author Wangl.sir <wanglsir@gmail.com, 983708408@qq.com>
	 * @version v1.0 2019年10月17日
	 * @since
	 */
	private class FutureDoneTaskWrapper implements Runnable {

		final private CountDownLatch latch;

		final private Runnable job;

		public FutureDoneTaskWrapper(CountDownLatch latch, Runnable job) {
			Assert.notNull(latch, "Job runable latch must not be null.");
			Assert.notNull(job, "Job runable must not be null.");
			this.latch = latch;
			this.job = job;
		}

		@Override
		public void run() {
			try {
				this.job.run();
			} finally {
				this.latch.countDown();
			}
		}

	}

	/**
	 * Named ID job runnable.
	 * 
	 * @author Wangl.sir <wanglsir@gmail.com, 983708408@qq.com>
	 * @version v1.0 2019年10月17日
	 * @since
	 */
	public static class NamedIdJob implements Runnable {

		final private String namedId;

		public NamedIdJob(String namedId) {
			Assert.hasText(namedId, "Named ID must not be empty.");
			this.namedId = namedId;
		}

		public String getNamedId() {
			return namedId;
		}

		@Override
		public void run() {
			// Ignore
		}

		@Override
		public String toString() {
			return "NamedIdJob@" + namedId;
		}

	}

	/**
	 * Wait completion task listener.
	 * 
	 * @author Wangl.sir <wanglsir@gmail.com, 983708408@qq.com>
	 * @version v1.0 2019年10月17日
	 * @since
	 */
	public static interface CompleteTaskListener {

		/**
		 * Call-back completion listener.
		 * 
		 * @param ex
		 * @param completed
		 * @param uncompleted
		 */
		void onComplete(TimeoutException ex, long completed, Collection<NamedIdJob> uncompleted);

	}

	/**
	 * Generic task runner properties
	 * 
	 * @author Wangl.sir <983708408@qq.com>
	 * @version v1.0 2019年6月8日
	 * @since
	 */
	public static class RunProperties implements Serializable {

		private static final long serialVersionUID = -1996272636830701232L;

		/** Whether to start the boss thread asynchronously. */
		private boolean startup = true;

		/**
		 * When the concurrency is less than 0, it means that the worker thread
		 * group is not enabled (only the boss asynchronous thread is started)
		 */
		private int concurrency = -1;

		/** Watch dog delay */
		private long keepAliveTime = 0L;

		/**
		 * Consumption receive queue size
		 */
		private int acceptQueue = 2;

		/** Rejected execution handler. */
		private RejectedExecutionHandler reject = new AbortPolicy();

		public RunProperties() {
			super();
		}

		public RunProperties(int concurrency) {
			this(concurrency, 0L, 32, null);
		}

		public RunProperties(int concurrency, long keepAliveTime, int acceptQueue) {
			this(concurrency, keepAliveTime, acceptQueue, null);
		}

		public RunProperties(int concurrency, long keepAliveTime, int acceptQueue, RejectedExecutionHandler reject) {
			this(true, concurrency, keepAliveTime, acceptQueue, reject);
		}

		public RunProperties(boolean startup, int concurrency, long keepAliveTime, int acceptQueue,
				RejectedExecutionHandler reject) {
			super();
			setStartup(startup);
			setConcurrency(concurrency);
			setKeepAliveTime(keepAliveTime);
			setAcceptQueue(acceptQueue);
			setReject(reject);
		}

		public boolean isStartup() {
			return startup;
		}

		public void setStartup(boolean async) {
			this.startup = async;
		}

		public int getConcurrency() {
			return concurrency;
		}

		public void setConcurrency(int concurrency) {
			this.concurrency = concurrency;
		}

		public long getKeepAliveTime() {
			return keepAliveTime;
		}

		public void setKeepAliveTime(long keepAliveTime) {
			if (getConcurrency() > 0) {
				Assert.isTrue(keepAliveTime >= 0, "keepAliveTime must be greater than or equal to 0");
			}
			this.keepAliveTime = keepAliveTime;
		}

		public int getAcceptQueue() {
			return acceptQueue;
		}

		public void setAcceptQueue(int acceptQueue) {
			if (getConcurrency() > 0) {
				Assert.isTrue(acceptQueue > 0, "acceptQueue must be greater than 0");
			}
			this.acceptQueue = acceptQueue;
		}

		public RejectedExecutionHandler getReject() {
			return reject;
		}

		public void setReject(RejectedExecutionHandler reject) {
			if (reject != null) {
				this.reject = reject;
			}
		}

		@Override
		public String toString() {
			return "TaskProperties [concurrency=" + concurrency + ", keepAliveTime=" + keepAliveTime + ", acceptQueue="
					+ acceptQueue + ", reject=" + reject + "]";
		}

	}

	@SuppressWarnings({ "resource", "unchecked", "rawtypes" })
	public static void main(String[] args) throws Exception {
		// Add testing jobs.
		List<NamedIdJob> jobs = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			jobs.add(new NamedIdJob("testjob-" + i) {
				@Override
				public void run() {
					try {
						System.out.println("Starting... testjob-" + getNamedId());
						Thread.sleep(3000L);
						System.out.println("Completed. testjob-" + getNamedId());
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			});
		}

		// Create runner.
		GenericTaskRunner runner = new GenericTaskRunner<RunProperties>(new RunProperties(2)) {
		};
		runner.run(null);

		// Submit jobs & listen job timeout.
		runner.submitForComplete(jobs, (ex, completed, uncompleted) -> {
			ex.printStackTrace();
			System.out.println(String.format("Completed: %s, uncompleted sets: %s", completed, uncompleted));
		}, 4 * 1000l); // > 3*3000
	}

}