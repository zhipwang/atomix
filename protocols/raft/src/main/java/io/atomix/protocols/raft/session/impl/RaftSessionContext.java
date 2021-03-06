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
package io.atomix.protocols.raft.session.impl;

import io.atomix.event.Event;
import io.atomix.logging.Logger;
import io.atomix.logging.LoggerFactory;
import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.impl.RaftOperationResult;
import io.atomix.protocols.raft.impl.RaftServerContext;
import io.atomix.protocols.raft.impl.RaftServerStateMachineContext;
import io.atomix.protocols.raft.impl.RaftServerStateMachineExecutor;
import io.atomix.protocols.raft.protocol.PublishRequest;
import io.atomix.protocols.raft.protocol.RaftServerProtocol;
import io.atomix.protocols.raft.session.RaftSession;
import io.atomix.protocols.raft.session.RaftSessionEvent;
import io.atomix.protocols.raft.session.RaftSessionEventListener;
import io.atomix.protocols.raft.session.SessionId;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;

/**
 * Raft session.
 */
public class RaftSessionContext implements RaftSession {
  private static final Logger LOGGER = LoggerFactory.getLogger(RaftSessionContext.class);
  private final SessionId id;
  private final MemberId member;
  private final String name;
  private final String type;
  private final long timeout;
  private final RaftServerProtocol protocol;
  private final RaftServerStateMachineExecutor executor;
  private final RaftServerContext server;
  private volatile State state = State.OPEN;
  private long timestamp;
  private long requestSequence;
  private long commandSequence;
  private long lastApplied;
  private long commandLowWaterMark;
  private long eventIndex;
  private long completeIndex;
  private final Map<Long, List<Runnable>> sequenceQueries = new HashMap<>();
  private final Map<Long, List<Runnable>> indexQueries = new HashMap<>();
  private final Map<Long, RaftOperationResult> results = new HashMap<>();
  private final Queue<EventHolder> events = new LinkedList<>();
  private EventHolder currentEventList;
  private final Set<RaftSessionEventListener> eventListeners = new CopyOnWriteArraySet<>();

  public RaftSessionContext(SessionId id, MemberId member, String name, String type, long timeout, RaftServerStateMachineExecutor executor, RaftServerContext server) {
    this.id = id;
    this.member = member;
    this.name = name;
    this.type = type;
    this.timeout = timeout;
    this.eventIndex = id.id();
    this.completeIndex = id.id();
    this.lastApplied = id.id();
    this.protocol = server.getProtocol();
    this.executor = executor;
    this.server = server;
    protocol.registerResetListener(id, request -> resendEvents(request.index()), executor.executor());
  }

  @Override
  public SessionId sessionId() {
    return id;
  }

  /**
   * Returns the node to which the session belongs.
   *
   * @return The node to which the session belongs.
   */
  public MemberId getMemberId() {
    return member;
  }

  /**
   * Returns the session name.
   *
   * @return The session name.
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the session type.
   *
   * @return The session type.
   */
  public String getTypeName() {
    return type;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public long getTimeout() {
    return timeout;
  }

  /**
   * Returns the state machine executor associated with the session.
   *
   * @return The state machine executor associated with the session.
   */
  public RaftServerStateMachineExecutor getStateMachineExecutor() {
    return executor;
  }

  /**
   * Returns the session update timestamp.
   *
   * @return The session update timestamp.
   */
  public long getTimestamp() {
    return timestamp;
  }

  /**
   * Updates the session timestamp.
   *
   * @param timestamp The session timestamp.
   */
  public void setTimestamp(long timestamp) {
    this.timestamp = Math.max(this.timestamp, timestamp);
  }

  @Override
  public State getState() {
    return state;
  }

  /**
   * Updates the session state.
   *
   * @param state The session state.
   */
  private void setState(State state) {
    if (this.state != state) {
      this.state = state;
      LOGGER.debug("{} - State changed: {}", id, state);
      switch (state) {
        case OPEN:
          eventListeners.forEach(l -> l.onEvent(new RaftSessionEvent(RaftSessionEvent.Type.OPEN, this, getTimestamp())));
          break;
        case EXPIRED:
          eventListeners.forEach(l -> l.onEvent(new RaftSessionEvent(RaftSessionEvent.Type.EXPIRE, this, getTimestamp())));
          break;
        case CLOSED:
          eventListeners.forEach(l -> l.onEvent(new RaftSessionEvent(RaftSessionEvent.Type.CLOSE, this, getTimestamp())));
          break;
      }
    }
  }

  @Override
  public void addListener(RaftSessionEventListener listener) {
    eventListeners.add(listener);
  }

  @Override
  public void removeListener(RaftSessionEventListener listener) {
    eventListeners.remove(listener);
  }

  /**
   * Returns the session request number.
   *
   * @return The session request number.
   */
  public long getRequestSequence() {
    return requestSequence;
  }

  /**
   * Checks and sets the current request sequence number.
   *
   * @param requestSequence The request sequence number to set.
   * @return Indicates whether the given {@code requestSequence} number is the next sequence number.
   */
  public boolean setRequestSequence(long requestSequence) {
    if (requestSequence == this.requestSequence + 1) {
      this.requestSequence = requestSequence;
      return true;
    } else if (requestSequence <= this.requestSequence) {
      return true;
    }
    return false;
  }

  /**
   * Resets the current request sequence number.
   *
   * @param requestSequence The request sequence number.
   */
  public void resetRequestSequence(long requestSequence) {
    // If the request sequence number is less than the applied sequence number, update the request
    // sequence number. This is necessary to ensure that if the local server is a follower that is
    // later elected leader, its sequences are consistent for commands.
    if (requestSequence > this.requestSequence) {
      this.requestSequence = requestSequence;
    }
  }

  /**
   * Returns the session operation sequence number.
   *
   * @return The session operation sequence number.
   */
  public long getCommandSequence() {
    return commandSequence;
  }

  /**
   * Returns the next operation sequence number.
   *
   * @return The next operation sequence number.
   */
  public long nextCommandSequence() {
    return commandSequence + 1;
  }

  /**
   * Sets the session operation sequence number.
   *
   * @param sequence The session operation sequence number.
   */
  public void setCommandSequence(long sequence) {
    // For each increment of the sequence number, trigger query callbacks that are dependent on the specific sequence.
    for (long i = commandSequence + 1; i <= sequence; i++) {
      commandSequence = i;
      List<Runnable> queries = this.sequenceQueries.remove(commandSequence);
      if (queries != null) {
        for (Runnable query : queries) {
          query.run();
        }
      }
    }
  }

  /**
   * Returns the session index.
   *
   * @return The session index.
   */
  public long getLastApplied() {
    return lastApplied;
  }

  /**
   * Sets the session index.
   *
   * @param index The session index.
   */
  public void setLastApplied(long index) {
    // Query callbacks for this session are added to the indexQueries map to be executed once the required index
    // for the query is reached. For each increment of the index, trigger query callbacks that are dependent
    // on the specific index.
    for (long i = lastApplied + 1; i <= index; i++) {
      lastApplied = i;
      List<Runnable> queries = this.indexQueries.remove(lastApplied);
      if (queries != null) {
        for (Runnable query : queries) {
          query.run();
        }
      }
    }
  }

  /**
   * Registers a causal session query.
   *
   * @param sequence The session sequence number at which to execute the query.
   * @param query    The query to execute.
   */
  public void registerSequenceQuery(long sequence, Runnable query) {
    // Add a query to be run once the session's sequence number reaches the given sequence number.
    List<Runnable> queries = this.sequenceQueries.computeIfAbsent(sequence, v -> new LinkedList<Runnable>());
    queries.add(query);
  }

  /**
   * Registers a session index query.
   *
   * @param index The state machine index at which to execute the query.
   * @param query The query to execute.
   */
  public void registerIndexQuery(long index, Runnable query) {
    // Add a query to be run once the session's index reaches the given index.
    List<Runnable> queries = this.indexQueries.computeIfAbsent(index, v -> new LinkedList<>());
    queries.add(query);
  }

  /**
   * Registers a session result.
   * <p>
   * Results are stored in memory on all servers in order to provide linearizable semantics. When a command
   * is applied to the state machine, the command's return value is stored with the sequence number. Once the
   * client acknowledges receipt of the command output the result will be cleared from memory.
   *
   * @param sequence The result sequence number.
   * @param result   The result.
   */
  public void registerResult(long sequence, RaftOperationResult result) {
    results.put(sequence, result);
  }

  /**
   * Clears command results up to the given sequence number.
   * <p>
   * Command output is removed from memory up to the given sequence number. Additionally, since we know the
   * client received a response for all commands up to the given sequence number, command futures are removed
   * from memory as well.
   *
   * @param sequence The sequence to clear.
   */
  public void clearResults(long sequence) {
    if (sequence > commandLowWaterMark) {
      for (long i = commandLowWaterMark + 1; i <= sequence; i++) {
        results.remove(i);
        commandLowWaterMark = i;
      }
    }
  }

  /**
   * Returns the session response for the given sequence number.
   *
   * @param sequence The response sequence.
   * @return The response.
   */
  public RaftOperationResult getResult(long sequence) {
    return results.get(sequence);
  }

  /**
   * Returns the session event index.
   *
   * @return The session event index.
   */
  public long getEventIndex() {
    return eventIndex;
  }

  @Override
  public void publish(Event event) {
    // Store volatile state in a local variable.
    State state = this.state;
    checkState(state != State.EXPIRED, "session is expired");
    checkState(state != State.CLOSED, "session is closed");
    checkState(executor.getContext().context() == RaftServerStateMachineContext.Type.COMMAND, "session events can only be published during command execution");

    // If the client acked an index greater than the current event sequence number since we know the
    // client must have received it from another server.
    if (completeIndex > executor.getContext().currentIndex()) {
      return;
    }

    // If no event has been published for this index yet, create a new event holder.
    if (this.currentEventList == null || this.currentEventList.eventIndex != executor.getContext().currentIndex()) {
      long previousIndex = eventIndex;
      eventIndex = executor.getContext().currentIndex();
      this.currentEventList = new EventHolder(eventIndex, previousIndex);
    }

    // Add the event to the event holder.
    this.currentEventList.events.add(event);
  }

  /**
   * Commits events for the given index.
   */
  public void commit(long index) {
    if (currentEventList != null && currentEventList.eventIndex == index) {
      events.add(currentEventList);
      sendEvents(currentEventList);
    }
    setLastApplied(index);
  }

  /**
   * Returns the index of the highest event acked for the session.
   *
   * @return The index of the highest event acked for the session.
   */
  public long getLastCompleted() {
    // If there are any queued events, return the index prior to the first event in the queue.
    EventHolder event = events.peek();
    if (event != null && event.eventIndex > completeIndex) {
      return event.eventIndex - 1;
    }
    // If no events are queued, return the highest index applied to the session.
    return lastApplied;
  }

  /**
   * Clears events up to the given sequence.
   *
   * @param index The index to clear.
   * @return The server session.
   */
  private RaftSessionContext clearEvents(long index) {
    if (index > completeIndex) {
      EventHolder event = events.peek();
      while (event != null && event.eventIndex <= index) {
        events.remove();
        completeIndex = event.eventIndex;
        event = events.peek();
      }
      completeIndex = index;
    }
    return this;
  }

  /**
   * Resends events from the given sequence.
   *
   * @param index The index from which to resend events.
   * @return The server session.
   */
  public RaftSessionContext resendEvents(long index) {
    clearEvents(index);
    for (EventHolder event : events) {
      sendEvents(event);
    }
    return this;
  }

  /**
   * Sends an event to the session.
   */
  private void sendEvents(EventHolder event) {
    // Only send events to the client if this server is the leader.
    if (server.isLeader()) {
      PublishRequest request = PublishRequest.newBuilder()
          .withSession(sessionId().id())
          .withEventIndex(event.eventIndex)
          .withPreviousIndex(Math.max(event.previousIndex, completeIndex))
          .withEvents(event.events.stream()
              .map(e -> executor.serializer().encode(e))
              .collect(Collectors.toList()))
          .build();

      LOGGER.trace("{} - Sending {}", id, request);
      protocol.publish(member, request);
    }
  }

  /**
   * Expires the session.
   */
  public void expire() {
    setState(State.EXPIRED);
    protocol.unregisterResetListener(id);
  }

  /**
   * Closes the session.
   */
  public void close() {
    setState(State.CLOSED);
    protocol.unregisterResetListener(id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), id);
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof RaftSession && ((RaftSession) object).sessionId() == id;
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("id", id)
        .toString();
  }

  /**
   * Event holder.
   */
  private static class EventHolder {
    private final long eventIndex;
    private final long previousIndex;
    private final List<Object> events = new LinkedList<>();

    private EventHolder(long eventIndex, long previousIndex) {
      this.eventIndex = eventIndex;
      this.previousIndex = previousIndex;
    }
  }

}
