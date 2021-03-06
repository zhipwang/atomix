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
package io.atomix.logging.slf4j;

import io.atomix.logging.Logger;
import io.atomix.logging.LoggingService;

/**
 * SLF4J logging service.
 */
public class Slf4jLoggingService implements LoggingService {

  @Override
  public Logger getLogger(String name) {
    return new Slf4jLogger(name);
  }

  @Override
  public Logger getLogger(Class<?> clazz) {
    return new Slf4jLogger(clazz);
  }

}
