/*
 * Copyright 2015-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.impl;

import io.atomix.logging.Logger;
import io.atomix.logging.LoggerFactory;
import io.atomix.protocols.raft.RaftServer;
import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.cluster.RaftCluster;
import io.atomix.protocols.raft.cluster.RaftMember;
import io.atomix.protocols.raft.cluster.impl.DefaultRaftMember;
import io.atomix.protocols.raft.cluster.impl.RaftClusterContext;
import io.atomix.protocols.raft.protocol.RaftRequest;
import io.atomix.protocols.raft.protocol.RaftResponse;
import io.atomix.protocols.raft.protocol.RaftServerProtocol;
import io.atomix.protocols.raft.roles.AbstractRole;
import io.atomix.protocols.raft.roles.ActiveRole;
import io.atomix.protocols.raft.roles.CandidateRole;
import io.atomix.protocols.raft.roles.FollowerRole;
import io.atomix.protocols.raft.roles.InactiveRole;
import io.atomix.protocols.raft.roles.LeaderRole;
import io.atomix.protocols.raft.roles.PassiveRole;
import io.atomix.protocols.raft.roles.RaftRole;
import io.atomix.protocols.raft.roles.ReserveRole;
import io.atomix.protocols.raft.storage.RaftStorage;
import io.atomix.protocols.raft.storage.log.RaftLog;
import io.atomix.protocols.raft.storage.log.RaftLogReader;
import io.atomix.protocols.raft.storage.log.RaftLogWriter;
import io.atomix.protocols.raft.storage.snapshot.SnapshotStore;
import io.atomix.protocols.raft.storage.system.MetaStore;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.atomix.utils.concurrent.ThreadContext;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.atomix.utils.concurrent.Threads.namedThreads;

/**
 * Manages the volatile state and state transitions of a Raft server.
 * <p>
 * This class is the primary vehicle for managing the state of a server. All state that is shared across roles (i.e. follower, candidate, leader)
 * is stored in the cluster state. This includes Raft-specific state like the current leader and term, the log, and the cluster configuration.
 */
public class RaftServerContext implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(RaftServerContext.class);
  private final Set<Consumer<RaftServer.Role>> stateChangeListeners = new CopyOnWriteArraySet<>();
  private final Set<Consumer<RaftMember>> electionListeners = new CopyOnWriteArraySet<>();
  protected final String name;
  protected final ThreadContext threadContext;
  protected final RaftStateMachineRegistry registry;
  protected final RaftClusterContext cluster;
  protected final RaftServerProtocol protocol;
  protected final RaftStorage storage;
  private MetaStore meta;
  private RaftLog log;
  private RaftLogWriter writer;
  private RaftLogReader reader;
  private SnapshotStore snapshot;
  private RaftServerStateMachineManager stateMachine;
  protected final ScheduledExecutorService threadPool;
  protected final ThreadContext stateContext;
  protected RaftRole role = new InactiveRole(this);
  private Duration electionTimeout = Duration.ofMillis(500);
  private Duration sessionTimeout = Duration.ofMillis(5000);
  private Duration heartbeatInterval = Duration.ofMillis(150);
  private volatile MemberId leader;
  private volatile long term;
  private MemberId lastVotedFor;
  private long commitIndex;

  @SuppressWarnings("unchecked")
  public RaftServerContext(String name, RaftMember.Type type, MemberId localMemberId, RaftServerProtocol protocol, RaftStorage storage, RaftStateMachineRegistry registry, int threadPoolSize) {
    this.name = checkNotNull(name, "name cannot be null");
    this.protocol = checkNotNull(protocol, "protocol cannot be null");
    this.storage = checkNotNull(storage, "storage cannot be null");
    this.registry = checkNotNull(registry, "registry cannot be null");

    String baseThreadName = String.format("raft-server-%s-%s", localMemberId, name);
    this.threadContext = new SingleThreadContext(namedThreads(baseThreadName, LOGGER));
    this.stateContext = new SingleThreadContext(namedThreads(baseThreadName + "-state", LOGGER));
    this.threadPool = Executors.newScheduledThreadPool(threadPoolSize, namedThreads(baseThreadName + "-%d", LOGGER));

    // Open the meta store.
    CountDownLatch metaLatch = new CountDownLatch(1);
    threadContext.execute(() -> {
      this.meta = storage.openMetaStore();
      metaLatch.countDown();
    });

    try {
      metaLatch.await();
    } catch (InterruptedException e) {
    }

    // Load the current term and last vote from disk.
    this.term = meta.loadTerm();
    this.lastVotedFor = meta.loadVote();

    // Reset the state machine.
    CountDownLatch resetLatch = new CountDownLatch(1);
    threadContext.execute(() -> {
      reset();
      resetLatch.countDown();
    });

    try {
      resetLatch.await();
    } catch (InterruptedException e) {
    }

    this.cluster = new RaftClusterContext(type, localMemberId, this);

    // Register protocol listeners.
    registerHandlers(protocol);
  }

  /**
   * Adds a state change listener.
   *
   * @param listener The state change listener.
   */
  public void addStateChangeListener(Consumer<RaftServer.Role> listener) {
    stateChangeListeners.add(listener);
  }

  /**
   * Removes a state change listener.
   *
   * @param listener The state change listener.
   */
  public void removeStateChangeListener(Consumer<RaftServer.Role> listener) {
    stateChangeListeners.remove(listener);
  }

  /**
   * Adds a leader election listener.
   *
   * @param listener The leader election listener.
   */
  public void addLeaderElectionListener(Consumer<RaftMember> listener) {
    electionListeners.add(listener);
  }

  /**
   * Removes a leader election listener.
   *
   * @param listener The leader election listener.
   */
  public void removeLeaderElectionListener(Consumer<RaftMember> listener) {
    electionListeners.remove(listener);
  }

  /**
   * Returns the execution context.
   *
   * @return The execution context.
   */
  public ThreadContext getThreadContext() {
    return threadContext;
  }

  /**
   * Returns the server protocol.
   *
   * @return The server protocol.
   */
  public RaftServerProtocol getProtocol() {
    return protocol;
  }

  /**
   * Returns the server storage.
   *
   * @return The server storage.
   */
  public RaftStorage getStorage() {
    return storage;
  }

  /**
   * Sets the election timeout.
   *
   * @param electionTimeout The election timeout.
   * @return The Raft context.
   */
  public RaftServerContext setElectionTimeout(Duration electionTimeout) {
    this.electionTimeout = electionTimeout;
    return this;
  }

  /**
   * Returns the election timeout.
   *
   * @return The election timeout.
   */
  public Duration getElectionTimeout() {
    return electionTimeout;
  }

  /**
   * Sets the heartbeat interval.
   *
   * @param heartbeatInterval The Raft heartbeat interval.
   * @return The Raft context.
   */
  public RaftServerContext setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = checkNotNull(heartbeatInterval, "heartbeatInterval cannot be null");
    return this;
  }

  /**
   * Returns the heartbeat interval.
   *
   * @return The heartbeat interval.
   */
  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public Duration getSessionTimeout() {
    return sessionTimeout;
  }

  /**
   * Sets the session timeout.
   *
   * @param sessionTimeout The session timeout.
   * @return The Raft state machine.
   */
  public RaftServerContext setSessionTimeout(Duration sessionTimeout) {
    this.sessionTimeout = checkNotNull(sessionTimeout, "sessionTimeout cannot be null");
    return this;
  }

  /**
   * Sets the state leader.
   *
   * @param leader The state leader.
   * @return The Raft context.
   */
  public RaftServerContext setLeader(MemberId leader) {
    if (!Objects.equals(this.leader, leader)) {
      // 0 indicates no leader.
      if (leader == null) {
        this.leader = null;
      } else {
        // If a valid leader ID was specified, it must be a member that's currently a member of the
        // ACTIVE members configuration. Note that we don't throw exceptions for unknown members. It's
        // possible that a failure following a configuration change could result in an unknown leader
        // sending AppendRequest to this server. Simply configure the leader if it's known.
        DefaultRaftMember member = cluster.getMember(leader);
        if (member != null) {
          this.leader = leader;
          LOGGER.info("{} - Found leader {}", cluster.getMember().memberId(), member.memberId());
          electionListeners.forEach(l -> l.accept(member));
        }
      }

      this.lastVotedFor = null;
      meta.storeVote(null);
    }
    return this;
  }

  /**
   * Returns the cluster state.
   *
   * @return The cluster state.
   */
  public RaftCluster getCluster() {
    return cluster;
  }

  /**
   * Returns the cluster state.
   *
   * @return The cluster state.
   */
  public RaftClusterContext getClusterState() {
    return cluster;
  }

  /**
   * Returns the state leader.
   *
   * @return The state leader.
   */
  public DefaultRaftMember getLeader() {
    // Store in a local variable to prevent race conditions and/or multiple volatile lookups.
    MemberId leader = this.leader;
    return leader != null ? cluster.getMember(leader) : null;
  }

  /**
   * Returns a boolean indicating whether this server is the current leader.
   *
   * @return Indicates whether this server is the leader.
   */
  public boolean isLeader() {
    MemberId leader = this.leader;
    return leader != null && leader.equals(cluster.getMember().memberId());
  }

  /**
   * Sets the state term.
   *
   * @param term The state term.
   * @return The Raft context.
   */
  public RaftServerContext setTerm(long term) {
    if (term > this.term) {
      this.term = term;
      this.leader = null;
      this.lastVotedFor = null;
      meta.storeTerm(this.term);
      meta.storeVote(this.lastVotedFor);
      LOGGER.debug("{} - Set term {}", cluster.getMember().memberId(), term);
    }
    return this;
  }

  /**
   * Returns the state term.
   *
   * @return The state term.
   */
  public long getTerm() {
    return term;
  }

  /**
   * Sets the state last voted for candidate.
   *
   * @param candidate The candidate that was voted for.
   * @return The Raft context.
   */
  public RaftServerContext setLastVotedFor(MemberId candidate) {
    // If we've already voted for another candidate in this term then the last voted for candidate cannot be overridden.
    checkState(!(lastVotedFor != null && candidate != null), "Already voted for another candidate");
    DefaultRaftMember member = cluster.getMember(candidate);
    checkState(member != null, "Unknown candidate: %d", candidate);
    this.lastVotedFor = candidate;
    meta.storeVote(this.lastVotedFor);

    if (candidate != null) {
      LOGGER.debug("{} - Voted for {}", cluster.getMember().memberId(), member.memberId());
    } else {
      LOGGER.trace("{} - Reset last voted for", cluster.getMember().memberId());
    }
    return this;
  }

  /**
   * Returns the state last voted for candidate.
   *
   * @return The state last voted for candidate.
   */
  public MemberId getLastVotedFor() {
    return lastVotedFor;
  }

  /**
   * Sets the commit index.
   *
   * @param commitIndex The commit index.
   * @return The Raft context.
   */
  public RaftServerContext setCommitIndex(long commitIndex) {
    checkArgument(commitIndex >= 0, "commitIndex must be positive");
    long previousCommitIndex = this.commitIndex;
    if (commitIndex > previousCommitIndex) {
      this.commitIndex = commitIndex;
      writer.commit(Math.min(commitIndex, writer.getLastIndex()));
      long configurationIndex = cluster.getConfiguration().index();
      if (configurationIndex > previousCommitIndex && configurationIndex <= commitIndex) {
        cluster.commit();
      }
    }
    return this;
  }

  /**
   * Returns the commit index.
   *
   * @return The commit index.
   */
  public long getCommitIndex() {
    return commitIndex;
  }

  /**
   * Returns the server state machine.
   *
   * @return The server state machine.
   */
  public RaftServerStateMachineManager getStateMachine() {
    return stateMachine;
  }

  /**
   * Returns the server state machine registry.
   *
   * @return The server state machine registry.
   */
  public RaftStateMachineRegistry getStateMachineRegistry() {
    return registry;
  }

  /**
   * Returns the current server role.
   *
   * @return The current server role.
   */
  public RaftServer.Role getRole() {
    return role.role();
  }

  /**
   * Returns the current server state.
   *
   * @return The current server state.
   */
  public RaftRole getRaftRole() {
    return role;
  }

  /**
   * Returns the server metadata store.
   *
   * @return The server metadata store.
   */
  public MetaStore getMetaStore() {
    return meta;
  }

  /**
   * Returns the server log.
   *
   * @return The server log.
   */
  public RaftLog getLog() {
    return log;
  }

  /**
   * Returns the server log writer.
   *
   * @return The log writer.
   */
  public RaftLogWriter getLogWriter() {
    return writer;
  }

  /**
   * Returns the server log reader.
   *
   * @return The log reader.
   */
  public RaftLogReader getLogReader() {
    return reader;
  }

  /**
   * Resets the state log.
   *
   * @return The server context.
   */
  public RaftServerContext reset() {
    // Delete the existing log.
    if (log != null) {
      log.close();
      storage.deleteLog();
    }

    // Delete the existing snapshot store.
    if (snapshot != null) {
      snapshot.close();
      storage.deleteSnapshotStore();
    }

    // Open the log.
    log = storage.openLog();
    writer = log.writer();
    reader = log.openReader(1, RaftLogReader.Mode.ALL);

    // Open the snapshot store.
    snapshot = storage.openSnapshotStore();

    // Create a new internal server state machine.
    this.stateMachine = new RaftServerStateMachineManager(this, threadPool, stateContext);
    return this;
  }

  /**
   * Returns the server snapshot store.
   *
   * @return The server snapshot store.
   */
  public SnapshotStore getSnapshotStore() {
    return snapshot;
  }

  /**
   * Checks that the current thread is the state context thread.
   */
  public void checkThread() {
    threadContext.checkThread();
  }

  /**
   * Registers server handlers on the configured protocol.
   */
  private void registerHandlers(RaftServerProtocol protocol) {
    protocol.registerOpenSessionHandler(request -> runOnContext(() -> role.onOpenSession(request)));
    protocol.registerCloseSessionHandler(request -> runOnContext(() -> role.onCloseSession(request)));
    protocol.registerKeepAliveHandler(request -> runOnContext(() -> role.onKeepAlive(request)));
    protocol.registerMetadataHandler(request -> runOnContext(() -> role.onMetadata(request)));
    protocol.registerConfigureHandler(request -> runOnContext(() -> role.onConfigure(request)));
    protocol.registerInstallHandler(request -> runOnContext(() -> role.onInstall(request)));
    protocol.registerJoinHandler(request -> runOnContext(() -> role.onJoin(request)));
    protocol.registerReconfigureHandler(request -> runOnContext(() -> role.onReconfigure(request)));
    protocol.registerLeaveHandler(request -> runOnContext(() -> role.onLeave(request)));
    protocol.registerAppendHandler(request -> runOnContext(() -> role.onAppend(request)));
    protocol.registerPollHandler(request -> runOnContext(() -> role.onPoll(request)));
    protocol.registerVoteHandler(request -> runOnContext(() -> role.onVote(request)));
    protocol.registerCommandHandler(request -> runOnContext(() -> role.onCommand(request)));
    protocol.registerQueryHandler(request -> runOnContext(() -> role.onQuery(request)));
  }

  private <T extends RaftRequest, U extends RaftResponse> CompletableFuture<U> runOnContext(Supplier<CompletableFuture<U>> function) {
    CompletableFuture<U> future = new CompletableFuture<U>();
    threadContext.execute(() -> {
      function.get().whenComplete((response, error) -> {
        if (error == null) {
          future.complete(response);
        } else {
          future.completeExceptionally(error);
        }
      });
    });
    return future;
  }

  /**
   * Unregisters server handlers on the configured protocol.
   */
  private void unregisterHandlers(RaftServerProtocol protocol) {
    protocol.unregisterOpenSessionHandler();
    protocol.unregisterCloseSessionHandler();
    protocol.unregisterKeepAliveHandler();
    protocol.unregisterMetadataHandler();
    protocol.unregisterConfigureHandler();
    protocol.unregisterInstallHandler();
    protocol.unregisterJoinHandler();
    protocol.unregisterReconfigureHandler();
    protocol.unregisterLeaveHandler();
    protocol.unregisterAppendHandler();
    protocol.unregisterPollHandler();
    protocol.unregisterVoteHandler();
    protocol.unregisterCommandHandler();
    protocol.unregisterQueryHandler();
  }

  /**
   * Transitions the server to the base state for the given member type.
   */
  public void transition(RaftMember.Type type) {
    switch (type) {
      case ACTIVE:
        if (!(role instanceof ActiveRole)) {
          transition(RaftServer.Role.FOLLOWER);
        }
        break;
      case PASSIVE:
        if (this.role.role() != RaftServer.Role.PASSIVE) {
          transition(RaftServer.Role.PASSIVE);
        }
        break;
      case RESERVE:
        if (this.role.role() != RaftServer.Role.RESERVE) {
          transition(RaftServer.Role.RESERVE);
        }
        break;
      default:
        if (this.role.role() != RaftServer.Role.INACTIVE) {
          transition(RaftServer.Role.INACTIVE);
        }
        break;
    }
  }

  /**
   * Transition handler.
   */
  public void transition(RaftServer.Role role) {
    checkThread();

    if (this.role != null && role == this.role.role()) {
      return;
    }

    LOGGER.info("{} - Transitioning to {}", cluster.getMember().memberId(), role);

    // Close the old state.
    try {
      this.role.close().get();
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to close Raft state", e);
    }

    // Force state transitions to occur synchronously in order to prevent race conditions.
    try {
      this.role = createState(role);
      this.role.open().get();
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to initialize Raft state", e);
    }

    stateChangeListeners.forEach(l -> l.accept(this.role.role()));
  }

  /**
   * Creates an internal state for the given state type.
   */
  private AbstractRole createState(RaftServer.Role role) {
    switch (role) {
      case INACTIVE:
        return new InactiveRole(this);
      case RESERVE:
        return new ReserveRole(this);
      case PASSIVE:
        return new PassiveRole(this);
      case FOLLOWER:
        return new FollowerRole(this);
      case CANDIDATE:
        return new CandidateRole(this);
      case LEADER:
        return new LeaderRole(this);
      default:
        throw new AssertionError();
    }
  }

  @Override
  public void close() {
    // Unregister protocol listeners.
    unregisterHandlers(protocol);

    // Close the log.
    try {
      log.close();
    } catch (Exception e) {
    }

    // Close the metastore.
    try {
      meta.close();
    } catch (Exception e) {
    }

    // Close the snapshot store.
    try {
      snapshot.close();
    } catch (Exception e) {
    }

    // Close the state machine and thread context.
    stateMachine.close();
    threadContext.close();
    stateContext.close();
    threadPool.shutdownNow();

    try {
      threadPool.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
    }
  }

  /**
   * Deletes the server context.
   */
  public void delete() {
    // Delete the log.
    storage.deleteLog();

    // Delete the snapshot store.
    storage.deleteSnapshotStore();

    // Delete the metadata store.
    storage.deleteMetaStore();
  }

  @Override
  public String toString() {
    return getClass().getCanonicalName();
  }

}
