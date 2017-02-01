/*
 * Copyright 2016 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.protocol.http.response;

import io.atomix.copycat.protocol.http.response.HttpResponse;

/**
 * Error response.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class HttpErrorResponse extends HttpResponse {
  private String error;

  public String getError() {
    return error;
  }

  public io.atomix.copycat.protocol.http.response.HttpErrorResponse setError(String error) {
    this.error = error;
    return this;
  }
}
