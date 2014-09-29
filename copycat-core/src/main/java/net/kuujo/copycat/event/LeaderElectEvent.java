/*
 * Copyright 2014 the original author or authors.
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
package net.kuujo.copycat.event;

/**
 * Leader elcted event.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class LeaderElectEvent implements Event {
  private final long term;
  private final String leader;

  public LeaderElectEvent(long term, String leader) {
    this.term = term;
    this.leader = leader;
  }

  /**
   * Returns the leader's term.
   *
   * @return The leader's term.
   */
  public long term() {
    return term;
  }

  /**
   * Returns the leader URI.
   *
   * @return The leader URI.
   */
  public String leader() {
    return leader;
  }

}