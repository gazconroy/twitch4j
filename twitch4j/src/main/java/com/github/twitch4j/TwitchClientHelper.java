package com.github.twitch4j;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.philippheuer.events4j.core.domain.Event;
import com.github.twitch4j.chat.events.channel.FollowEvent;
import com.github.twitch4j.common.events.domain.EventChannel;
import com.github.twitch4j.common.events.domain.EventUser;
import com.github.twitch4j.common.util.CollectionUtils;
import com.github.twitch4j.common.util.ExponentialBackoffStrategy;
import com.github.twitch4j.domain.ChannelCache;
import com.github.twitch4j.events.ChannelChangeGameEvent;
import com.github.twitch4j.events.ChannelChangeTitleEvent;
import com.github.twitch4j.events.ChannelFollowCountUpdateEvent;
import com.github.twitch4j.events.ChannelGoLiveEvent;
import com.github.twitch4j.events.ChannelGoOfflineEvent;
import com.github.twitch4j.helix.domain.*;
import com.netflix.hystrix.HystrixCommand;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A helper class that covers a few basic use cases of most library users
 */
@Slf4j
public class TwitchClientHelper implements AutoCloseable {

    public static final int REQUIRED_THREAD_COUNT = 2;

    /**
     * The greatest number of streams or followers that can be requested in a single API call
     */
    private static final int MAX_LIMIT = 100;

    /**
     * Holds the channels that are checked for live/offline state changes
     */
    private final Set<String> listenForGoLive = ConcurrentHashMap.newKeySet();

    /**
     * Holds the channels that are checked for new followers
     */
    private final Set<String> listenForFollow = ConcurrentHashMap.newKeySet();

    /**
     * TwitchClient
     */
    private final TwitchClient twitchClient;

    /**
     * Event Task - Stream Status
     * <p>
     * Accepts a list of channel ids not exceeding {@link TwitchClientHelper#MAX_LIMIT} in size as the input
     */
    private final Consumer<List<String>> streamStatusEventTask;

    /**
     * The {@link Future} associated with streamStatusEventTask, in an atomic wrapper
     */
    private final AtomicReference<Future<?>> streamStatusEventFuture = new AtomicReference<>();

    /**
     * Event Task - Followers
     * <p>
     * Accepts a channel id as the input; Yields true if the next call should not be delayed
     */
    private final Function<String, Boolean> followerEventTask;

    /**
     * The {@link Future} associated with followerEventTask, in an atomic wrapper
     */
    private final AtomicReference<Future<?>> followerEventFuture = new AtomicReference<>();

    /**
     * Channel Information Cache
     */
    private final Cache<String, ChannelCache> channelInformation = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    /**
     * Scheduled Thread Pool Executor
     */
    private final ScheduledThreadPoolExecutor executor;

    /**
     * Holds the {@link ExponentialBackoffStrategy} used for the stream status listener
     */
    private final AtomicReference<ExponentialBackoffStrategy> liveBackoff;

    /**
     * Holds the {@link ExponentialBackoffStrategy} used for the follow listener
     */
    private final AtomicReference<ExponentialBackoffStrategy> followBackoff;

    /**
     * Constructor
     *
     * @param twitchClient TwitchClient
     * @param executor     ScheduledThreadPoolExecutor
     */
    public TwitchClientHelper(TwitchClient twitchClient, ScheduledThreadPoolExecutor executor) {
        this.twitchClient = twitchClient;
        this.executor = executor;

        final ExponentialBackoffStrategy defaultBackoff = ExponentialBackoffStrategy.builder().immediateFirst(false).baseMillis(1000L).jitter(false).build();
        liveBackoff = new AtomicReference<>(defaultBackoff);
        followBackoff = new AtomicReference<>(defaultBackoff.copy());

        // Threads
        this.streamStatusEventTask = channels -> {
            // check go live / stream events
            HystrixCommand<StreamList> hystrixGetAllStreams = twitchClient.getHelix().getStreams(null, null, null, channels.size(), null, null, channels, null);
            try {
                Map<String, Stream> streams = new HashMap<>();
                channels.forEach(id -> streams.put(id, null));
                hystrixGetAllStreams.execute().getStreams().forEach(s -> streams.put(s.getUserId(), s));
                liveBackoff.get().reset(); // API call was successful

                streams.forEach((userId, stream) -> {
                    // Check if the channel's live status is still desired to be tracked
                    if (!listenForGoLive.contains(userId))
                        return;

                    ChannelCache currentChannelCache = channelInformation.get(userId, s -> new ChannelCache());
                    // Disabled name updates while Helix returns display name https://github.com/twitchdev/issues/issues/3
                    if (stream != null && currentChannelCache.getUserName() == null)
                        currentChannelCache.setUserName(stream.getUserName());
                    final EventChannel channel = new EventChannel(userId, currentChannelCache.getUserName());

                    boolean dispatchGoLiveEvent = false;
                    boolean dispatchGoOfflineEvent = false;
                    boolean dispatchTitleChangedEvent = false;
                    boolean dispatchGameChangedEvent = false;

                    if (stream != null && stream.getType().equalsIgnoreCase("live")) {
                        // is live
                        // - live status
                        if (currentChannelCache.getIsLive() != null && currentChannelCache.getIsLive() == false) {
                            dispatchGoLiveEvent = true;
                        }
                        currentChannelCache.setIsLive(true);
                        boolean wasAlreadyLive = dispatchGoLiveEvent != true && currentChannelCache.getIsLive() == true;

                        // - change stream title event
                        if (wasAlreadyLive && currentChannelCache.getTitle() != null && !currentChannelCache.getTitle().equalsIgnoreCase(stream.getTitle())) {
                            dispatchTitleChangedEvent = true;
                        }
                        currentChannelCache.setTitle(stream.getTitle());

                        // - change game event
                        if (wasAlreadyLive && currentChannelCache.getGameId() != null && !currentChannelCache.getGameId().equals(stream.getGameId())) {
                            dispatchGameChangedEvent = true;
                        }
                        currentChannelCache.setGameId(stream.getGameId());
                    } else {
                        // was online previously?
                        if (currentChannelCache.getIsLive() != null && currentChannelCache.getIsLive() == true) {
                            dispatchGoOfflineEvent = true;
                        }

                        // is offline
                        currentChannelCache.setIsLive(false);
                        currentChannelCache.setTitle(null);
                        currentChannelCache.setGameId(null);
                    }

                    // dispatch events
                    // - go live event
                    if (dispatchGoLiveEvent) {
                        Event event = new com.github.twitch4j.common.events.channel.ChannelGoLiveEvent(channel, currentChannelCache.getTitle(), currentChannelCache.getGameId());
                        twitchClient.getEventManager().publish(event);
                        twitchClient.getEventManager().publish(new ChannelGoLiveEvent(channel, stream));
                    }
                    // - go offline event
                    if (dispatchGoOfflineEvent) {
                        Event event = new com.github.twitch4j.common.events.channel.ChannelGoOfflineEvent(channel);
                        twitchClient.getEventManager().publish(event);
                        twitchClient.getEventManager().publish(new ChannelGoOfflineEvent(channel));
                    }
                    // - title changed event
                    if (dispatchTitleChangedEvent) {
                        Event event = new com.github.twitch4j.common.events.channel.ChannelChangeTitleEvent(channel, currentChannelCache.getTitle());
                        twitchClient.getEventManager().publish(event);
                        twitchClient.getEventManager().publish(new ChannelChangeTitleEvent(channel, stream));
                    }
                    // - game changed event
                    if (dispatchGameChangedEvent) {
                        Event event = new com.github.twitch4j.common.events.channel.ChannelChangeGameEvent(channel, currentChannelCache.getGameId());
                        twitchClient.getEventManager().publish(event);
                        twitchClient.getEventManager().publish(new ChannelChangeGameEvent(channel, stream));
                    }
                });
            } catch (Exception ex) {
                if (hystrixGetAllStreams != null && hystrixGetAllStreams.isFailedExecution()) {
                    log.trace(hystrixGetAllStreams.getFailedExecutionException().getMessage(), hystrixGetAllStreams.getFailedExecutionException());
                }

                log.error("Failed to check for Stream Events (Live/Offline/...): " + ex.getMessage());
            }
        };
        this.followerEventTask = channelId -> {
            // check follow events
            HystrixCommand<FollowList> commandGetFollowers = twitchClient.getHelix().getFollowers(null, null, channelId, null, MAX_LIMIT);
            try {
                ChannelCache currentChannelCache = channelInformation.get(channelId, s -> new ChannelCache());
                Instant lastFollowDate = null;

                boolean nextRequestCanBeImmediate = false;

                if (currentChannelCache.getLastFollowCheck() != null) {
                    FollowList executionResult = commandGetFollowers.execute();
                    List<Follow> followList = executionResult.getFollows();
                    followBackoff.get().reset(); // API call was successful

                    // Prepare EventChannel
                    String channelName = currentChannelCache.getUserName(); // Prefer login (even if old) to display_name https://github.com/twitchdev/issues/issues/3#issuecomment-562713594
                    if (channelName == null && !followList.isEmpty()) {
                        channelName = followList.get(0).getToName();
                        currentChannelCache.setUserName(channelName);
                    }
                    EventChannel channel = new EventChannel(channelId, channelName);

                    // Follow Count Event
                    Integer followCount = executionResult.getTotal();
                    Integer oldTotal = currentChannelCache.getFollowers().getAndSet(followCount);
                    if (oldTotal != null && followCount != null && !followCount.equals(oldTotal)) {
                        twitchClient.getEventManager().publish(new ChannelFollowCountUpdateEvent(channel, followCount, oldTotal));
                    }

                    // Individual Follow Events
                    for (Follow follow : followList) {
                        // update lastFollowDate
                        if (lastFollowDate == null || follow.getFollowedAtInstant().isAfter(lastFollowDate)) {
                            lastFollowDate = follow.getFollowedAtInstant();
                        }

                        // is new follower?
                        if (follow.getFollowedAtInstant().isAfter(currentChannelCache.getLastFollowCheck())) {
                            // dispatch event
                            FollowEvent event = new FollowEvent(channel, new EventUser(follow.getFromId(), follow.getFromName()));
                            twitchClient.getEventManager().publish(event);
                        }
                    }
                } else {
                    nextRequestCanBeImmediate = true; // No API call was made
                }

                if (currentChannelCache.getLastFollowCheck() == null) {
                    // only happens if the user doesn't have any followers at all
                    currentChannelCache.setLastFollowCheck(Instant.now());
                } else {
                    // tracks the date of the latest follow to identify new ones later on
                    currentChannelCache.setLastFollowCheck(lastFollowDate);
                }

                return nextRequestCanBeImmediate;
            } catch (Exception ex) {
                if (commandGetFollowers != null && commandGetFollowers.isFailedExecution()) {
                    log.trace(ex.getMessage(), ex);
                }

                log.error("Failed to check for Follow Events: " + ex.getMessage());
                return false;
            }
        };
    }

    /**
     * Enable StreamEvent Listener
     *
     * @param channelName Channel Name
     */
    public void enableStreamEventListener(String channelName) {
        UserList users = twitchClient.getHelix().getUsers(null, null, Collections.singletonList(channelName)).execute();

        if (users.getUsers().size() == 1) {
            users.getUsers().forEach(user -> enableStreamEventListener(user.getId(), user.getLogin()));
        } else {
            log.error("Failed to add channel {} to stream event listener!", channelName);
        }
    }

    /**
     * Enable StreamEvent Listener for the given channel names
     *
     * @param channelNames the channel names to be added
     */
    public void enableStreamEventListener(Iterable<String> channelNames) {
        CollectionUtils.chunked(channelNames, MAX_LIMIT).forEach(channels -> {
            UserList users = twitchClient.getHelix().getUsers(null, null, channels).execute();
            users.getUsers().forEach(user -> enableStreamEventListener(user.getId(), user.getLogin()));
        });
    }

    /**
     * Enable StreamEvent Listener, without invoking a Helix API call
     *
     * @param channelId   Channel Id
     * @param channelName Channel Name
     * @return true if the channel was added, false otherwise
     */
    public boolean enableStreamEventListener(String channelId, String channelName) {
        // add to set
        final boolean add = listenForGoLive.add(channelId);
        if (!add) {
            log.info("Channel {} already added for Stream Events", channelName);
        } else {
            // initialize cache
            channelInformation.get(channelId, s -> new ChannelCache(channelName));
        }
        startOrStopEventGenerationThread();
        return add;
    }

    /**
     * Disable StreamEvent Listener
     *
     * @param channelName Channel Name
     */
    public void disableStreamEventListener(String channelName) {
        UserList users = twitchClient.getHelix().getUsers(null, null, Collections.singletonList(channelName)).execute();

        if (users.getUsers().size() == 1) {
            users.getUsers().forEach(user -> disableStreamEventListenerForId(user.getId()));
        } else {
            log.error("Failed to remove channel " + channelName + " from stream event listener!");
        }
    }

    /**
     * Disable StreamEvent Listener for the given channel names
     *
     * @param channelNames the channel names to be removed
     */
    public void disableStreamEventListener(Iterable<String> channelNames) {
        CollectionUtils.chunked(channelNames, MAX_LIMIT).forEach(channels -> {
            UserList users = twitchClient.getHelix().getUsers(null, null, channels).execute();
            users.getUsers().forEach(user -> disableStreamEventListenerForId(user.getId()));
        });
    }

    /**
     * Disable StreamEventListener, without invoking a Helix API call
     *
     * @param channelId Channel Id
     * @return true if the channel was removed, false otherwise
     */
    public boolean disableStreamEventListenerForId(String channelId) {
        // remove from set
        boolean remove = listenForGoLive.remove(channelId);

        // invalidate cache
        channelInformation.invalidate(channelId);

        startOrStopEventGenerationThread();
        return remove;
    }

    /**
     * Follow Listener
     *
     * @param channelName Channel Name
     */
    public void enableFollowEventListener(String channelName) {
        UserList users = twitchClient.getHelix().getUsers(null, null, Collections.singletonList(channelName)).execute();

        if (users.getUsers().size() == 1) {
            users.getUsers().forEach(user -> enableFollowEventListener(user.getId(), user.getLogin()));
        } else {
            log.error("Failed to add channel " + channelName + " to Follow Listener, maybe it doesn't exist!");
        }
    }

    /**
     * Enable Follow Listener for the given channel names
     *
     * @param channelNames the channel names to be added
     */
    public void enableFollowEventListener(Iterable<String> channelNames) {
        CollectionUtils.chunked(channelNames, MAX_LIMIT).forEach(channels -> {
            UserList users = twitchClient.getHelix().getUsers(null, null, channels).execute();
            users.getUsers().forEach(user -> enableFollowEventListener(user.getId(), user.getLogin()));
        });
    }

    /**
     * Enable Follow Listener, without invoking a Helix API call
     *
     * @param channelId   Channel Id
     * @param channelName Channel Name
     * @return true if the channel was added, false otherwise
     */
    public boolean enableFollowEventListener(String channelId, String channelName) {
        // add to list
        final boolean add = listenForFollow.add(channelId);
        if (!add) {
            log.info("Channel {} already added for Follow Events", channelName);
        } else {
            // initialize cache
            channelInformation.get(channelId, s -> new ChannelCache(channelName));
        }
        startOrStopEventGenerationThread();
        return add;
    }

    /**
     * Disable Follow Listener
     *
     * @param channelName Channel Name
     */
    public void disableFollowEventListener(String channelName) {
        UserList users = twitchClient.getHelix().getUsers(null, null, Collections.singletonList(channelName)).execute();

        if (users.getUsers().size() == 1) {
            users.getUsers().forEach(user -> disableFollowEventListenerForId(user.getId()));
        } else {
            log.error("Failed to remove channel " + channelName + " from follow listener!");
        }
    }

    /**
     * Disable Follow Listener for the given channel names
     *
     * @param channelNames the channel names to be removed
     */
    public void disableFollowEventListener(Iterable<String> channelNames) {
        CollectionUtils.chunked(channelNames, MAX_LIMIT).forEach(channels -> {
            UserList users = twitchClient.getHelix().getUsers(null, null, channels).execute();
            users.getUsers().forEach(user -> disableFollowEventListenerForId(user.getId()));
        });
    }

    /**
     * Disable Follow Listener, without invoking a Helix API call
     *
     * @param channelId Channel Id
     * @return true when a previously-tracked channel was removed, false otherwise
     */
    public boolean disableFollowEventListenerForId(String channelId) {
        // remove from set
        boolean remove = listenForFollow.remove(channelId);

        // invalidate cache
        channelInformation.invalidate(channelId);

        startOrStopEventGenerationThread();
        return remove;
    }

    /**
     * Start or quit the thread, depending on usage
     */
    private void startOrStopEventGenerationThread() {
        // stream status event thread
        updateListener(listenForGoLive::isEmpty, streamStatusEventFuture, this::runRecursiveStreamStatusCheck, liveBackoff);

        // follower event thread
        updateListener(listenForFollow::isEmpty, followerEventFuture, this::runRecursiveFollowerCheck, followBackoff);
    }

    /**
     * Performs the "heavy lifting" of starting or stopping a listener
     *
     * @param stopCondition   yields whether or not the listener should be running
     * @param futureReference the current listener in an atomic wrapper
     * @param startCommand    the command to start the listener
     * @param backoff         the {@link ExponentialBackoffStrategy} for the listener
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter") // Acceptable as futureReference is only streamStatusEventFuture or followerEventFuture
    private void updateListener(BooleanSupplier stopCondition, AtomicReference<Future<?>> futureReference, Runnable startCommand, AtomicReference<ExponentialBackoffStrategy> backoff) {
        if (stopCondition.getAsBoolean()) {
            // Optimization to avoid obtaining an unnecessary lock
            if (futureReference.get() != null) {
                Future<?> future = null;
                synchronized (futureReference) {
                    if (stopCondition.getAsBoolean()) // Ensure conditions haven't changed in the time it took to acquire this lock
                        future = futureReference.getAndSet(null); // Clear out the listener future
                }

                // Cancel the future
                if (future != null) {
                    future.cancel(false);
                    backoff.get().reset(); // Ideally we would decrement to zero over time rather than instantly resetting
                }
            }
        } else {
            // Optimization to avoid obtaining an unnecessary lock
            if (futureReference.get() == null) {
                // Must synchronize to prevent race condition where multiple threads could be created
                synchronized (futureReference) {
                    // Start if not already started
                    if (!stopCondition.getAsBoolean() && futureReference.get() == null)
                        futureReference.set(executor.schedule(startCommand, backoff.get().get(), TimeUnit.MILLISECONDS));
                }
            }
        }
    }

    /**
     * Initiates the stream status listener execution
     */
    private void runRecursiveStreamStatusCheck() {
        if (streamStatusEventFuture.get() != null)
            synchronized (streamStatusEventFuture) {
                if (cancel(streamStatusEventFuture))
                    streamStatusEventFuture.set(
                        executor.submit(
                            new ListenerRunnable<>(
                                executor,
                                CollectionUtils.chunked(listenForGoLive, MAX_LIMIT),
                                streamStatusEventFuture,
                                liveBackoff,
                                this::runRecursiveStreamStatusCheck,
                                chunk -> {
                                    streamStatusEventTask.accept(chunk);
                                    return false; // treat as always consuming from the api rate-limit
                                }
                            )
                        )
                    );
            }
    }

    /**
     * Initiates the follower listener execution
     */
    private void runRecursiveFollowerCheck() {
        if (followerEventFuture.get() != null)
            synchronized (followerEventFuture) {
                if (cancel(followerEventFuture))
                    followerEventFuture.set(
                        executor.submit(
                            new ListenerRunnable<>(
                                executor,
                                new ArrayList<>(listenForFollow),
                                followerEventFuture,
                                followBackoff,
                                this::runRecursiveFollowerCheck,
                                followerEventTask
                            )
                        )
                    );
            }
    }

    /**
     * Updates {@link ExponentialBackoffStrategy#getBaseMillis()} for each of the independent listeners (i.e. stream status and followers)
     *
     * @param threadRate the maximum <i>rate</i> of api calls per second
     */
    public void setThreadRate(long threadRate) {
        this.setThreadDelay(1000 / threadRate);
    }

    /**
     * Updates {@link ExponentialBackoffStrategy#getBaseMillis()} for each of the independent listeners (i.e. stream status and followers)
     *
     * @param threadDelay the minimum milliseconds <i>delay</i> between each api call
     */
    public void setThreadDelay(long threadDelay) {
        final UnaryOperator<ExponentialBackoffStrategy> updateBackoff = old -> {
            ExponentialBackoffStrategy next = old.toBuilder().baseMillis(threadDelay).build();
            next.setFailures(old.getFailures());
            return next;
        };

        this.liveBackoff.getAndUpdate(updateBackoff);
        this.followBackoff.getAndUpdate(updateBackoff);
    }

    /**
     * Close
     */
    public void close() {
        final Future<?> streamStatusFuture = this.streamStatusEventFuture.getAndSet(null);
        if (streamStatusFuture != null)
            streamStatusFuture.cancel(false);

        final Future<?> followerFuture = this.followerEventFuture.getAndSet(null);
        if (followerFuture != null)
            followerFuture.cancel(false);

        listenForGoLive.clear();
        listenForFollow.clear();
    }

    @Value
    private static class ListenerRunnable<T> implements Runnable {
        ScheduledExecutorService executor;
        List<T> channels;
        AtomicReference<Future<?>> futureReference;
        AtomicReference<ExponentialBackoffStrategy> backoff;
        Runnable startCommand;
        Function<T, Boolean> executeSingle;

        @Override
        public void run() {
            if (channels.isEmpty()) {
                // Try again later if the task wasn't cancelled
                if (futureReference.get() != null)
                    synchronized (futureReference) {
                        if (cancel(futureReference)) {
                            backoff.get().reset();
                            futureReference.set(executor.schedule(startCommand, backoff.get().get(), TimeUnit.MILLISECONDS));
                        }
                    }
            } else {
                // Start execution from the first element
                run(0);
            }
        }

        private void run(final int index) {
            // If no api call was made by executeSingle, it will return true. Then, we do not need to add any delay before checking the next channel.
            Boolean skipDelay = executeSingle.apply(channels.get(index));

            // Queue up the next check (if the task hasn't been cancelled)
            if (futureReference.get() != null)
                synchronized (futureReference) {
                    if (cancel(futureReference))
                        futureReference.set(
                            executor.schedule(
                                index + 1 < channels.size() ? () -> run(index + 1) : startCommand,
                                skipDelay ? 0 : backoff.get().get(),
                                TimeUnit.MILLISECONDS
                            )
                        );
                }
        }
    }

    private static boolean cancel(AtomicReference<Future<?>> futureRef) {
        Future<?> future = futureRef.get();
        return future != null && future.cancel(false);
    }

}
