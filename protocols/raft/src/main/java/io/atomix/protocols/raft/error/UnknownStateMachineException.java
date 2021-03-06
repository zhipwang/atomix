/*
 * Copyright 2017-present Open Networking Laboratory
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
package io.atomix.protocols.raft.error;

/**
 * Indicates that an operation or other request for an unknown state machine was received.
 */
public class UnknownStateMachineException extends RaftException {
  private static final RaftError.Type TYPE = RaftError.Type.UNKNOWN_STATE_MACHINE_ERROR;

  public UnknownStateMachineException(String message, Object... args) {
    super(TYPE, message, args);
  }

  public UnknownStateMachineException(Throwable cause, String message, Object... args) {
    super(TYPE, cause, message, args);
  }

  public UnknownStateMachineException(Throwable cause) {
    super(TYPE, cause);
  }

}
