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
package io.atomix.protocols.raft.roles;

import io.atomix.protocols.raft.RaftServer;
import io.atomix.protocols.raft.cluster.impl.DefaultRaftMember;
import io.atomix.protocols.raft.cluster.impl.RaftMemberContext;
import io.atomix.protocols.raft.impl.RaftServerContext;
import io.atomix.protocols.raft.protocol.AppendRequest;
import io.atomix.protocols.raft.protocol.AppendResponse;
import io.atomix.protocols.raft.protocol.ConfigureRequest;
import io.atomix.protocols.raft.protocol.ConfigureResponse;
import io.atomix.protocols.raft.protocol.InstallRequest;
import io.atomix.protocols.raft.protocol.InstallResponse;
import io.atomix.protocols.raft.protocol.PollRequest;
import io.atomix.protocols.raft.protocol.VoteRequest;
import io.atomix.protocols.raft.protocol.VoteResponse;
import io.atomix.protocols.raft.storage.log.entry.RaftLogEntry;
import io.atomix.protocols.raft.utils.Quorum;
import io.atomix.storage.journal.Indexed;
import io.atomix.utils.concurrent.Scheduled;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Follower state.
 */
public final class FollowerRole extends ActiveRole {
  private final FollowerAppender appender;
  private final Random random = new Random();
  private Scheduled heartbeatTimer;

  public FollowerRole(RaftServerContext context) {
    super(context);
    this.appender = new FollowerAppender(context);
  }

  @Override
  public RaftServer.Role role() {
    return RaftServer.Role.FOLLOWER;
  }

  @Override
  public synchronized CompletableFuture<RaftRole> open() {
    return super.open().thenRun(this::startHeartbeatTimeout).thenApply(v -> this);
  }

  /**
   * Starts the heartbeat timer.
   */
  private void startHeartbeatTimeout() {
    LOGGER.trace("{} - Starting heartbeat timer", context.getCluster().getMember().memberId());
    resetHeartbeatTimeout();
  }

  /**
   * Resets the heartbeat timer.
   */
  private void resetHeartbeatTimeout() {
    context.checkThread();
    if (isClosed())
      return;

    // If a timer is already set, cancel the timer.
    if (heartbeatTimer != null) {
      heartbeatTimer.cancel();
    }

    // Set the election timeout in a semi-random fashion with the random range
    // being election timeout and 2 * election timeout.
    Duration delay = context.getElectionTimeout().plus(Duration.ofMillis(random.nextInt((int) context.getElectionTimeout().toMillis())));
    heartbeatTimer = context.getThreadContext().schedule(delay, () -> {
      heartbeatTimer = null;
      if (isOpen()) {
        context.setLeader(null);
        LOGGER.debug("{} - Heartbeat timed out in {}", context.getCluster().getMember().memberId(), delay);
        sendPollRequests();
      }
    });
  }

  /**
   * Polls all members of the cluster to determine whether this member should transition to the CANDIDATE state.
   */
  private void sendPollRequests() {
    // Set a new timer within which other nodes must respond in order for this node to transition to candidate.
    heartbeatTimer = context.getThreadContext().schedule(context.getElectionTimeout(), () -> {
      LOGGER.debug("{} - Failed to poll a majority of the cluster in {}", context.getCluster().getMember().memberId(), context.getElectionTimeout());
      resetHeartbeatTimeout();
    });

    // Create a quorum that will track the number of nodes that have responded to the poll request.
    final AtomicBoolean complete = new AtomicBoolean();
    final Set<DefaultRaftMember> votingMembers = new HashSet<>(context.getClusterState().getActiveMemberStates().stream().map(RaftMemberContext::getMember).collect(Collectors.toList()));

    // If there are no other members in the cluster, immediately transition to leader.
    if (votingMembers.isEmpty()) {
      context.transition(RaftServer.Role.CANDIDATE);
      return;
    }

    final Quorum quorum = new Quorum(context.getClusterState().getQuorum(), (elected) -> {
      // If a majority of the cluster indicated they would vote for us then transition to candidate.
      complete.set(true);
      if (elected) {
        context.transition(RaftServer.Role.CANDIDATE);
      } else {
        resetHeartbeatTimeout();
      }
    });

    // First, load the last log entry to get its term. We load the entry
    // by its index since the index is required by the protocol.
    final Indexed<RaftLogEntry> lastEntry = context.getLogWriter().getLastEntry();

    final long lastTerm;
    if (lastEntry != null) {
      lastTerm = lastEntry.entry().term();
    } else {
      lastTerm = 0;
    }

    LOGGER.info("{} - Polling members {}", context.getCluster().getMember().memberId(), votingMembers);

    // Once we got the last log term, iterate through each current member
    // of the cluster and vote each member for a vote.
    for (DefaultRaftMember member : votingMembers) {
      LOGGER.trace("{} - Polling {} for next term {}", context.getCluster().getMember().memberId(), member, context.getTerm() + 1);
      PollRequest request = PollRequest.newBuilder()
          .withTerm(context.getTerm())
          .withCandidate(context.getCluster().getMember().memberId())
          .withLastLogIndex(lastEntry != null ? lastEntry.index() : 0)
          .withLastLogTerm(lastTerm)
          .build();
      context.getProtocol().poll(member.memberId(), request).whenCompleteAsync((response, error) -> {
        context.checkThread();
        if (isOpen() && !complete.get()) {
          if (error != null) {
            LOGGER.warn("{} - {}", context.getCluster().getMember().memberId(), error.getMessage());
            quorum.fail();
          } else {
            if (response.term() > context.getTerm()) {
              context.setTerm(response.term());
            }

            if (!response.accepted()) {
              LOGGER.trace("{} - Received rejected poll from {}", context.getCluster().getMember().memberId(), member);
              quorum.fail();
            } else if (response.term() != context.getTerm()) {
              LOGGER.trace("{} - Received accepted poll for a different term from {}", context.getCluster().getMember().memberId(), member);
              quorum.fail();
            } else {
              LOGGER.trace("{} - Received accepted poll from {}", context.getCluster().getMember().memberId(), member);
              quorum.succeed();
            }
          }
        }
      }, context.getThreadContext());
    }
  }

  @Override
  public CompletableFuture<InstallResponse> onInstall(InstallRequest request) {
    CompletableFuture<InstallResponse> future = super.onInstall(request);
    resetHeartbeatTimeout();
    return future;
  }

  @Override
  public CompletableFuture<ConfigureResponse> onConfigure(ConfigureRequest request) {
    CompletableFuture<ConfigureResponse> future = super.onConfigure(request);
    resetHeartbeatTimeout();
    return future;
  }

  @Override
  public CompletableFuture<AppendResponse> onAppend(AppendRequest request) {
    CompletableFuture<AppendResponse> future = super.onAppend(request);

    // Reset the heartbeat timeout.
    resetHeartbeatTimeout();

    // Send AppendEntries requests to passive members if necessary.
    appender.appendEntries();
    return future;
  }

  @Override
  protected VoteResponse handleVote(VoteRequest request) {
    // Reset the heartbeat timeout if we voted for another candidate.
    VoteResponse response = super.handleVote(request);
    if (response.voted()) {
      resetHeartbeatTimeout();
    }
    return response;
  }

  /**
   * Cancels the heartbeat timeout.
   */
  private void cancelHeartbeatTimeout() {
    if (heartbeatTimer != null) {
      LOGGER.trace("{} - Cancelling heartbeat timer", context.getCluster().getMember().memberId());
      heartbeatTimer.cancel();
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close().thenRun(appender::close).thenRun(this::cancelHeartbeatTimeout);
  }

}
