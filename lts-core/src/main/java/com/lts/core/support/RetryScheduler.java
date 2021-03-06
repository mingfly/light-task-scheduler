package com.lts.core.support;

import com.lts.core.Application;
import com.lts.core.commons.utils.CollectionUtils;
import com.lts.core.commons.utils.GenericsUtils;
import com.lts.core.commons.utils.JSONUtils;
import com.lts.core.domain.KVPair;
import com.lts.core.extension.ExtensionLoader;
import com.lts.core.failstore.FailStore;
import com.lts.core.failstore.FailStoreException;
import com.lts.core.failstore.FailStoreFactory;
import com.lts.core.logger.Logger;
import com.lts.core.logger.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Robert HG (254963746@qq.com) on 8/19/14.
 *         重试定时器 (用来发送 给 客户端的反馈信息等)
 */
public abstract class RetryScheduler<T> {

    public static final Logger LOGGER = LoggerFactory.getLogger(RetryScheduler.class);

    private Class<?> type = GenericsUtils.getSuperClassGenericType(this.getClass());

    // 定时检查是否有 师表的反馈任务信息(给客户端的)
    private ScheduledExecutorService RETRY_EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduledFuture;
    private AtomicBoolean start = new AtomicBoolean(false);
    private FailStore failStore;
    // 名称主要是用来记录日志
    private String name;

    // 批量发送的消息数
    private int batchSize = 5;

    public RetryScheduler(Application application) {
        this(application, application.getConfig().getFailStorePath());
    }

    public RetryScheduler(Application application, String storePath) {
        FailStoreFactory failStoreFactory = ExtensionLoader.getExtensionLoader(FailStoreFactory.class).getAdaptiveExtension();
        failStore = failStoreFactory.getFailStore(application.getConfig(), storePath);
    }

    public RetryScheduler(Application application, String storePath, int batchSize) {
        this(application, storePath);
        this.batchSize = batchSize;
    }

    protected RetryScheduler(Application application, int batchSize) {
        this(application);
        this.batchSize = batchSize;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void start() {
        try {
            if (start.compareAndSet(false, true)) {
                // 这个时间后面再去优化
                scheduledFuture = RETRY_EXECUTOR_SERVICE.scheduleWithFixedDelay(new CheckRunner(), 10, 30, TimeUnit.SECONDS);
                LOGGER.info("Start {} retry scheduler success!", name);
            }
        } catch (Throwable t) {
            LOGGER.error("Start {} retry scheduler failed!", name, t);
        }
    }

    public void stop() {
        try {
            if (start.compareAndSet(false, true)) {
                scheduledFuture.cancel(true);
                RETRY_EXECUTOR_SERVICE.shutdown();
                LOGGER.info("Stop {} retry scheduler success!", name);
            }
        } catch (Throwable t) {
            LOGGER.error("Stop {} retry scheduler failed!", name, t);
        }
    }

    /**
     * 定时检查 提交失败任务的Runnable
     */
    private class CheckRunner implements Runnable {

        @Override
        public void run() {
            try {
                // 1. 检测 远程连接 是否可用
                if (!isRemotingEnable()) {
                    return;
                }

                List<KVPair<String, T>> kvPairs = null;
                do {
                    try {
                        failStore.open();

                        kvPairs = failStore.fetchTop(batchSize, type);

                        if (CollectionUtils.isEmpty(kvPairs)) {
                            break;
                        }

                        List<T> values = new ArrayList<T>(kvPairs.size());
                        List<String> keys = new ArrayList<String>(kvPairs.size());
                        for (KVPair<String, T> kvPair : kvPairs) {
                            keys.add(kvPair.getKey());
                            values.add(kvPair.getValue());
                        }
                        if (retry(values)) {
                            LOGGER.info("{} local files send success, size: {}, {}", name, values.size(), JSONUtils.toJSONString(values));
                            failStore.delete(keys);
                        } else {
                            break;
                        }
                    } finally {
                        failStore.close();
                    }
                } while (CollectionUtils.isNotEmpty(kvPairs));

            } catch (Throwable e) {
                LOGGER.error("Run {} retry scheduler error.", name, e);
            }
        }

    }

    public void inSchedule(String key, T value) {
        try {
            try {
                failStore.open();
                failStore.put(key, value);
                LOGGER.info("{}  local files save success, {}", name, JSONUtils.toJSONString(value));
            } finally {
                failStore.close();
            }
        } catch (FailStoreException e) {
            LOGGER.error("{} in schedule error. ", e);
        }
    }

    /**
     * 远程连接是否可用
     *
     * @return
     */
    protected abstract boolean isRemotingEnable();

    /**
     * 重试
     *
     * @param list
     * @return
     */
    protected abstract boolean retry(List<T> list);

}
