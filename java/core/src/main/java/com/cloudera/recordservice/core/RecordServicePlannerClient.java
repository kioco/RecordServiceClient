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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.recordservice.thrift.RecordServicePlanner;
import com.cloudera.recordservice.thrift.TErrorCode;
import com.cloudera.recordservice.thrift.TGetSchemaResult;
import com.cloudera.recordservice.thrift.TPlanRequestParams;
import com.cloudera.recordservice.thrift.TPlanRequestResult;
import com.cloudera.recordservice.thrift.TProtocolVersion;
import com.cloudera.recordservice.thrift.TRecordServiceException;
/**
 * Java client for the RecordServicePlanner. This class is not thread safe.
 */
// TODO: This class should not expose the raw Thrift objects, should use proper logger.
// TODO: Make retry interval/attempts configurable.
public class RecordServicePlannerClient {
  private final static Logger LOG =
      LoggerFactory.getLogger(RecordServicePlannerClient.class);

  // Planner client connection. null if closed.
  private RecordServicePlanner.Client plannerClient_;
  private TProtocol protocol_;
  private ProtocolVersion protocolVersion_ = null;

  // Number of consecutive attempts before failing any request.
  private final int maxAttempts_ = 3;
  // Duration to sleep between retry attempts.
  private final int retrySleepMs_ = 1000;

  /**
   * Generates a plan for 'request', connecting to the planner service at
   * hostname/port.
   */
  public static TPlanRequestResult planRequest(String hostname, int port, Request request)
      throws IOException, TRecordServiceException {
    return planRequest(hostname, port, request, null);
  }

  public static TPlanRequestResult planRequest(
      String hostname, int port, Request request, String kerberosPrincipal)
      throws IOException, TRecordServiceException {
    RecordServicePlannerClient client = null;
    try {
      client = new RecordServicePlannerClient(hostname, port, kerberosPrincipal);
      return client.planRequest(request);
    } finally {
      if (client != null) client.close();
    }
  }

  /**
   * Gets the schema for 'request', connecting to the planner service at
   * hostname/port.
   */
  public static TGetSchemaResult getSchema(
      String hostname, int port, Request request)
      throws IOException, TRecordServiceException {
    return getSchema(hostname, port, request, null);
  }

  public static TGetSchemaResult getSchema(
      String hostname, int port, Request request, String kerberosPrincipal)
      throws IOException, TRecordServiceException {
    RecordServicePlannerClient client = null;
    try {
      client = new RecordServicePlannerClient(hostname, port, kerberosPrincipal);
      return client.getSchema(request);
    } finally {
      if (client != null) client.close();
    }
  }

  /**
   * Opens a connection to the RecordServicePlanner.
   */
  public RecordServicePlannerClient(String hostname, int port)
     throws IOException, TRecordServiceException {
    this(hostname, port, null);
  }

  public RecordServicePlannerClient(String hostname, int port, String kerberosPrincipal)
      throws IOException, TRecordServiceException {
    TTransport transport = ThriftUtils.createTransport(
        "RecordServicePlanner", hostname, port, kerberosPrincipal);
    protocol_ = new TBinaryProtocol(transport);
    plannerClient_ = new RecordServicePlanner.Client(protocol_);

    try {
      protocolVersion_ = ThriftUtils.fromThrift(plannerClient_.GetProtocolVersion());
      LOG.debug("Connected to planner service with version: " + protocolVersion_);
    } catch (TTransportException e) {
      LOG.warn("Could not connect to planner service. " + e);
      if (e.getType() == TTransportException.END_OF_FILE) {
        // TODO: this is basically a total hack. It looks like there is an issue in
        // thrift where the exception thrown by the server is swallowed.
        TRecordServiceException ex = new TRecordServiceException();
        ex.code = TErrorCode.SERVICE_BUSY;
        ex.message = "Server is likely busy. Try the request again.";
        throw ex;
      }
      throw new IOException("Could not get serivce protocol version.", e);
    } catch (TException e) {
      LOG.warn("Could not connection to planner service. " + e);
      throw new IOException("Could not get service protocol version. It's likely " +
          "the service at " + hostname + ":" + port + " is not running the " +
          "RecordServicePlanner.", e);
    }
  }

  /**
   * Closes a connection to the RecordServicePlanner.
   */
  public void close() {
    if (plannerClient_ != null) {
      LOG.info("Closing RecordServicePlanner connection.");
      protocol_.getTransport().close();
      plannerClient_ = null;
    }
  }

  /**
   * Returns the protocol version of the connected service.
   */
  public ProtocolVersion getProtocolVersion() throws RuntimeException {
    validateIsConnected();
    return protocolVersion_;
  }

  /**
   * Calls the RecordServicePlanner to generate a new plan - set of tasks that can be
   * executed using a RecordServiceWorker.
   */
  public TPlanRequestResult planRequest(Request request)
      throws IOException, TRecordServiceException {
    validateIsConnected();

    TPlanRequestResult planResult;
    TException firstException = null;
    boolean connected = true;
    for (int i = 0; i < maxAttempts_; ++i) {
      try {
        if (!connected) {
          connected = waitAndReconnect();
          if (!connected) continue;
        }
        LOG.info("Planning request: " + request + " with attempt " + (i + 1) + " out of "
            + maxAttempts_);
        TPlanRequestParams planParams = request.request_;
        planParams.client_version = TProtocolVersion.V1;
        planResult = plannerClient_.PlanRequest(planParams);
        LOG.debug("PlanRequest generated " + planResult.tasks.size() + " tasks.");
        return planResult;
      } catch (TRecordServiceException e) {
        switch (e.code) {
          case SERVICE_BUSY:
            if (firstException == null) firstException = e;
            LOG.warn("Failed to planRequest(): "  + e);
            sleepForRetry();
            break;
          default:
            throw e;
        }
      } catch (TException e) {
        connected = false;
        if (firstException == null) firstException = e;
        LOG.warn("Failed to planRequest(): "  + e);
      }
    }
    handleThriftException(firstException, "Could not plan request.");
    throw new RuntimeException(firstException);
  }

  /**
   * Calls the RecordServicePlanner to return the schema for a request.
   */
  public TGetSchemaResult getSchema(Request request)
    throws IOException, TRecordServiceException {
    validateIsConnected();
    TGetSchemaResult result;
    TException firstException = null;
    boolean connected = true;
    for (int i = 0; i < maxAttempts_; ++i) {
      try {
        if (!connected) {
          connected = waitAndReconnect();
          if (!connected) continue;
        }
        LOG.info("Getting schema for request: " + request + " with attempt " + (i + 1)
            + " out of " + maxAttempts_);
        TPlanRequestParams planParams = request.request_;
        planParams.client_version = TProtocolVersion.V1;
        result = plannerClient_.GetSchema(planParams);
        return result;
      } catch (TRecordServiceException e) {
        switch (e.code) {
          case SERVICE_BUSY:
            if (firstException == null) firstException = e;
            LOG.warn("Failed to getSchema(): "  + e);
            sleepForRetry();
            break;
          default:
            throw e;
        }
      } catch (TException e) {
        connected = false;
        if (firstException == null) firstException = e;
        LOG.warn("Failed to getSchema(): "  + e);
      }
    }
    handleThriftException(firstException, "Could not get schema.");
    throw new RuntimeException(firstException);
  }

  /**
   * Returns a delegation token for the current user. If renewer is set, this renewer
   * can renew the token.
   * TODO: better error messages, handling.
   */
  public ByteBuffer getDelegationToken(String renewer)
      throws TRecordServiceException, IOException {
    try {
      return plannerClient_.GetDelegationToken(System.getProperty("user.name"), renewer);
    } catch (TRecordServiceException e) {
      throw e;
    } catch (TException e) {
      throw new IOException("Could not get delegation token.", e);
    }
  }

  /**
   * Cancels the token.
   */
  public void cancelDelegationToken(ByteBuffer token)
      throws TRecordServiceException, IOException {
    try {
      plannerClient_.CancelDelegationToken(token);
    } catch (TRecordServiceException e) {
      throw e;
    } catch (TException e) {
      throw new IOException("Could not cancel delegation token.", e);
    }
  }

  /**
   * Renews the token.
   */
  public void renewDelegationToken(ByteBuffer token)
      throws TRecordServiceException, IOException {
    try {
      plannerClient_.RenewDelegationToken(token);
    } catch (TRecordServiceException e) {
      throw e;
    } catch (TException e) {
      throw new IOException("Could not renew delegation token.", e);
    }
  }

  /**
   * Closes the underlying transport, used to simulate an error with the service
   * connection.
   * @VisibleForTesting
   */
  void closeConnectionForTesting() {
    protocol_.getTransport().close();
    assert(!protocol_.getTransport().isOpen());
  }

  /**
   * Handles TException, throwing a more canonical exception.
   * generalMsg is thrown if we can't infer more information from e.
   */
  private void handleThriftException(TException e, String generalMsg)
      throws TRecordServiceException, IOException {
    // TODO: this should mark the connection as bad on some error codes.
    if (e instanceof TRecordServiceException) {
      throw (TRecordServiceException)e;
    } else if (e instanceof TTransportException) {
      LOG.warn("Could not reach planner serivce.");
      throw new IOException("Could not reach service.", e);
    } else {
      throw new IOException(generalMsg, e);
    }
  }

  private void validateIsConnected() throws RuntimeException {
    if (plannerClient_ == null) {
      throw new RuntimeException("Client not connected.");
    }
  }

  /**
   * Sleeps for retrySleepMs_ and reconnects to the planner. Returns
   * true if the connection was established.
   */
  private boolean waitAndReconnect() {
    sleepForRetry();
    try {
      protocol_.getTransport().open();
      plannerClient_ = new RecordServicePlanner.Client(protocol_);
      return true;
    } catch (TTransportException e) {
      return false;
    }
  }

  /**
   * Sleeps for retrySleepMs_.
   */
  private void sleepForRetry() {
    if (LOG.isInfoEnabled()) {
      LOG.info("Sleeping for " + retrySleepMs_ + "ms before retrying.");
    }
    try {
      Thread.sleep(retrySleepMs_);
    } catch (InterruptedException e) {
      LOG.error("Failed sleeping: " + e);
    }
  }
}