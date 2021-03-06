/**
 * Copyright 2018 Comcast Cable Communications Management, LLC
 * Licensed under the Apache License, Version 2.0 (the "License"); * you may not use this file except in compliance with the License. * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.kafka.connect.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.connector.ConnectorContext;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.common.config.ConfigException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class PartitionMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionMonitor.class);

    // No need to make these options configurable.

    private AtomicBoolean shutdown = new AtomicBoolean(false);
    private AdminClient partitionMonitorClient;
    private Set<String> topicsWhitelist;
    private Pattern topicsRegexPattern;
    private volatile Set<LeaderTopicPartition> currentLeaderTopicPartitions = new HashSet<>();

    private int maxShutdownWaitMs;
    private int topicRequestTimeoutMs;
    private boolean reconfigureTasksOnLeaderChange;
    private Runnable pollThread;
    private int topicPollIntervalMs;
    private ScheduledExecutorService pollExecutorService;
    private ScheduledFuture<?> pollHandle;

    // Constructor with topics list
    PartitionMonitor(ConnectorContext connectorContext, Properties adminClientConfig, List<String> topicsList,
                     boolean reconfigureOnLeaderChange, int pollIntervalMs, int topicListTimeout, int shutdownTimeout)
            throws ConfigException {
        this(connectorContext, adminClientConfig, topicsList, null, reconfigureOnLeaderChange, pollIntervalMs, topicListTimeout, shutdownTimeout);
    }

    // Constructor with topics regex
    PartitionMonitor(ConnectorContext connectorContext, Properties adminClientConfig, String topicsRegexStr,
                     boolean reconfigureOnLeaderChange, int pollIntervalMs, int topicListTimeout, int shutdownTimeout)
            throws ConfigException {
        this(connectorContext, adminClientConfig, null, topicsRegexStr, reconfigureOnLeaderChange, pollIntervalMs, topicListTimeout, shutdownTimeout);
    }


    PartitionMonitor(ConnectorContext connectorContext, Properties adminClientConfig, List<String> topicsList,
                     String topicsRegexStr, boolean reconfigureOnLeaderChange, int pollIntervalMs,
                     int topicListTimeout, int shutdownTimeout) throws ConfigException {
        if (topicsList != null) {
            topicsWhitelist = new HashSet<>(topicsList);
        } else {
            topicsRegexPattern = Pattern.compile(topicsRegexStr);
        }
        reconfigureTasksOnLeaderChange = reconfigureOnLeaderChange;
        topicPollIntervalMs = pollIntervalMs;
        maxShutdownWaitMs = shutdownTimeout;
        topicRequestTimeoutMs = topicListTimeout;
        partitionMonitorClient = AdminClient.create(adminClientConfig);
        // Thread to periodically poll the kafka cluster for changes in topics or partititons
        pollThread = new Runnable() {
            @Override
            public void run() {
                if (!shutdown.get()) {
                    LOG.info("Fetching latest topic partitions.");
                    try {
                        Set<LeaderTopicPartition> retrievedLeaderTopicPartitions = retrieveLeaderTopicPartitions(topicRequestTimeoutMs);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("retrievedLeaderTopicPartitions: {}", retrievedLeaderTopicPartitions);
                            LOG.debug("currentLeaderTopicPartitions: {}", getCurrentLeaderTopicPartitions());
                        }
                        boolean requestTaskReconfiguration = false;
                        if (reconfigureTasksOnLeaderChange) {
                            if (!retrievedLeaderTopicPartitions.equals(getCurrentLeaderTopicPartitions())) {
                                LOG.info("Retrieved leaders and topic partitions do not match currently stored leaders and topic partitions, will request task reconfiguration");
                                requestTaskReconfiguration = true;
                            }
                        } else {
                            Set<TopicPartition> retrievedTopicPartitions = retrievedLeaderTopicPartitions.stream()
                                    .map(LeaderTopicPartition::toTopicPartition)
                                    .collect(Collectors.toSet());
                            if (LOG.isDebugEnabled())
                                LOG.debug("retrievedTopicPartitions: {}", retrievedTopicPartitions);
                            Set<TopicPartition> currentTopicPartitions = getCurrentLeaderTopicPartitions().stream()
                                    .map(LeaderTopicPartition::toTopicPartition)
                                    .collect(Collectors.toSet());
                            if (LOG.isDebugEnabled())
                                LOG.debug("currentTopicPartitions: {}", currentTopicPartitions);
                            if (!retrievedTopicPartitions.equals(currentTopicPartitions)) {
                                LOG.info("Retrieved topic partitions do not match currently stored topic partitions, will request task reconfiguration");
                                requestTaskReconfiguration = true;
                            }
                        }
                        setCurrentLeaderTopicPartitions(retrievedLeaderTopicPartitions);
                        if (requestTaskReconfiguration)
                            connectorContext.requestTaskReconfiguration();
                        else
                            LOG.info("No partition changes which require reconfiguration have been detected.");
                    } catch (TimeoutException e) {
                        LOG.error("Timeout while waiting for AdminClient to return topic list. This likely indicates a (possibly transient) connection issue, but could be an indicator that the timeout is set too low. {}", e);
                    } catch (ExecutionException e) {
                        LOG.error("Unexpected ExecutionException. {}", e);
                    } catch (InterruptedException e) {
                        LOG.error("InterruptedException. Probably shutting down. {}, e");
                    }
                }
            }
        };
    }

    public void start() {
        // On start, block until we retrieve the initial list of topic partitions (or at least until timeout)
        try {
            // This will block while waiting to retrieve data form kafka. Timeout is set so that we don't hang the kafka connect herder if an invalid configuration causes us to retry infinitely.
            LOG.info("Retrieving initial topic list from kafka.");
            setCurrentLeaderTopicPartitions(retrieveLeaderTopicPartitions(topicRequestTimeoutMs));
        } catch (TimeoutException e) {
            LOG.error("Timeout while waiting for AdminClient to return topic list. This likely indicates a (possibly transient) connection issue, but could be an indicator that the timeout is set too low. {}", e);
            throw new ConnectException("Timeout while waiting for AdminClient to return topic list. This likely indicates a (possibly transient) connection issue, but could be an indicator that the timeout is set too low.");
        } catch (ExecutionException e) {
            LOG.error("Unexpected ExecutionException. {}", e);
            throw new ConnectException("Unexpected  while starting PartitionMonitor.");
        } catch (InterruptedException e) {
            LOG.error("InterruptedException. {}, e");
            throw new ConnectException("Unexpected InterruptedException while starting PartitionMonitor.");
        }
        // Schedule a task to periodically run to poll for new data
        pollExecutorService = Executors.newSingleThreadScheduledExecutor();
        pollHandle = pollExecutorService.scheduleWithFixedDelay(pollThread, topicPollIntervalMs, topicPollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean matchedTopicFilter(String topic) {
        if (topicsWhitelist != null) {
            return topicsWhitelist.contains(topic);
        } else {
            return topicsRegexPattern.matcher(topic).matches();
        }
    }

    private synchronized void setCurrentLeaderTopicPartitions(Set<LeaderTopicPartition> leaderTopicPartitions) {
        currentLeaderTopicPartitions = leaderTopicPartitions;
    }

    public synchronized Set<LeaderTopicPartition> getCurrentLeaderTopicPartitions() {
        return currentLeaderTopicPartitions;
    }

    // Allow the main thread a chance to shut down gracefully
    public synchronized void shutdown() {
        LOG.info("Shutdown called.");
        long startWait = System.currentTimeMillis();
        shutdown.set(true);
        partitionMonitorClient.close( maxShutdownWaitMs - (System.currentTimeMillis() - startWait), TimeUnit.MILLISECONDS);
        // Cancel our scheduled task, but wait for an existing task to complete if running
        pollHandle.cancel(false);
        // Ask nicely to shut down the executor service if it hasnt already
        if (!pollExecutorService.isShutdown()) {
            try {
                pollExecutorService.awaitTermination(maxShutdownWaitMs - (System.currentTimeMillis() - startWait), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.warn("Got InterruptedException while waiting for pollExecutorService to shutdown, shutdown will be forced.");
            }
        }
        if (!pollExecutorService.isShutdown()) {
            pollExecutorService.shutdownNow();
        }
        LOG.info("Shutdown Complete.");
    }


    // Retrieve a list of LeaderTopicPartitions that match our topic filter
    private synchronized Set<LeaderTopicPartition> retrieveLeaderTopicPartitions(int requestTimeoutMs) throws InterruptedException, ExecutionException, TimeoutException {
        long startWait = System.currentTimeMillis();

        ListTopicsOptions listTopicsOptions = new ListTopicsOptions().listInternal(false).timeoutMs((int) (requestTimeoutMs - (System.currentTimeMillis() - startWait)));
        Set<String> retrievedTopicSet = partitionMonitorClient.listTopics(listTopicsOptions).names().get(requestTimeoutMs - (System.currentTimeMillis() - startWait), TimeUnit.MILLISECONDS);
        LOG.debug("Server topic list: {}", retrievedTopicSet);
        Set<String> matchedTopicSet = retrievedTopicSet.stream()
            .filter(topic -> matchedTopicFilter(topic))
            .collect(Collectors.toSet());
        LOG.debug("Matched topic list: {}", matchedTopicSet);

        DescribeTopicsOptions describeTopicsOptions = new DescribeTopicsOptions().timeoutMs((int) (requestTimeoutMs - (System.currentTimeMillis() - startWait)));
        Map<String, TopicDescription> retrievedTopicDescriptions = partitionMonitorClient.describeTopics(matchedTopicSet, describeTopicsOptions).all().get(requestTimeoutMs - (System.currentTimeMillis() - startWait), TimeUnit.MILLISECONDS);
        return retrievedTopicDescriptions.values().stream()
            .map(topicDescription ->
                topicDescription.partitions().stream()
                .map(partitionInfo -> new LeaderTopicPartition(partitionInfo.leader().id(), topicDescription.name(), partitionInfo.partition()))
            )
            .flatMap(Function.identity())
            .collect(Collectors.toSet());
    }

}
