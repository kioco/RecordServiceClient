// Confidential Cloudera Information: Covered by NDA.
// Copyright 2014 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.recordservice.core;

import java.util.List;

import com.cloudera.recordservice.thrift.TPathRequest;
import com.cloudera.recordservice.thrift.TPlanRequestParams;
import com.cloudera.recordservice.thrift.TRequestType;

/**
 * Abstraction over different requests and utilities to build common request
 * types.
 */
public class Request {
  /**
   * Creates a request that is a SQL query.
   */
  public static Request createSqlRequest(String query) {
    TPlanRequestParams request = new TPlanRequestParams();
    request.request_type = TRequestType.Sql;
    request.sql_stmt = query;
    return new Request(request);
  }

  /**
   * Creates a request to read an entire table.
   */
  public static Request createTableScanRequest(String table) {
    TPlanRequestParams request = new TPlanRequestParams();
    request.request_type = TRequestType.Sql;
    request.sql_stmt = "SELECT * FROM " + table;
    return new Request(request);
  }

  /**
   * Creates a request to read a projection of a table. An empty or null
   * projection returns the number of rows in the table (as a BIGINT).
   */
  public static Request createProjectionRequest(String table, List<String> cols) {
    TPlanRequestParams request = new TPlanRequestParams();
    request.request_type = TRequestType.Sql;
    if (cols == null || cols.size() == 0) {
      request.sql_stmt = "SELECT count(*) FROM " + table;
    } else {
      StringBuilder sb = new StringBuilder("SELECT ");
      for (int i = 0; i < cols.size(); ++i) {
        if (i != 0) sb.append(", ");
        sb.append(cols.get(i));
      }
      sb.append(" FROM " + table);
      request.sql_stmt = sb.toString();
    }
    return new Request(request);
  }

  /**
   * Creates a request that is a PATH query. This does a full scan of the
   * data files in 'uri'.
   */
  public static Request createPathRequest(String uri) {
    TPlanRequestParams request = new TPlanRequestParams();
    request.request_type = TRequestType.Path;
    request.path = new TPathRequest(uri);
    return new Request(request);
  }

  /**
   * Creates a request that is a PATH query with filtering
   */
  public static Request createPathRequest(String uri, String query) {
    Request request = createPathRequest(uri);
    request.request_.path.setQuery(query);
    return request;
  }

  @Override
  // TODO: better string?
  public String toString() {
    return request_.toString();
  }

  TPlanRequestParams request_;

  private Request(TPlanRequestParams request) {
    request_ = request;
  }
}