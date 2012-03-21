// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import it.sauronsoftware.cron4j.InvalidPatternException;
import it.sauronsoftware.cron4j.Scheduler;

import org.opensaml.DefaultBootstrap;
import org.opensaml.xml.ConfigurationException;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** This class handles the communications with GSA. */
public class GsaCommunicationHandler {
  private static final Logger log
      = Logger.getLogger(GsaCommunicationHandler.class.getName());

  private final Adaptor adaptor;
  private final Config config;
  private final Journal journal;
  /**
   * Generic scheduler. Available for other uses, but necessary for running
   * {@link docIdFullPusher}
   */
  private Scheduler scheduler = new Scheduler();
  /**
   * Runnable to be called for doing a full push of {@code DocId}s. It only
   * permits one invocation at a time. If multiple simultaneous invocations
   * occur, all but the first will log a warning and return immediately.
   */
  private final OneAtATimeRunnable docIdFullPusher = new OneAtATimeRunnable(
      new PushRunnable(), new AlreadyRunningRunnable());
  /**
   * Schedule identifier for {@link #sendDocIds}.
   */
  private String sendDocIdsSchedId;
  private HttpServer server;
  private Thread shutdownHook;
  private IncrementalAdaptorPoller incrementalAdaptorPoller;
  private final DocIdCodec docIdCodec;
  private final DocIdSender docIdSender;
  private final Dashboard dashboard;

  public GsaCommunicationHandler(Adaptor adaptor, Config config) {
    this.adaptor = adaptor;
    this.config = config;

    journal = new Journal(config.isJournalReducedMem());
    dashboard = new Dashboard(config, this, journal);
    docIdCodec = new DocIdCodec(config);
    GsaFeedFileSender fileSender = new GsaFeedFileSender(config);
    GsaFeedFileMaker fileMaker = new GsaFeedFileMaker(docIdCodec);
    docIdSender
        = new DocIdSender(fileMaker, fileSender, journal, config, adaptor);
  }

  /** Starts listening for communications from GSA. */
  public synchronized void start() throws Exception {
    if (server != null) {
      throw new IllegalStateException("Already listening");
    }

    int port = config.getServerPort();
    boolean secure = config.isServerSecure();
    InetSocketAddress addr = new InetSocketAddress(port);
    if (!secure) {
      server = HttpServer.create(addr, 0);
    } else {
      server = HttpsServer.create(addr, 0);
      try {
        HttpsConfigurator httpsConf
            = new HttpsConfigurator(SSLContext.getDefault()) {
              public void configure(HttpsParameters params) {
                SSLParameters sslParams
                    = getSSLContext().getDefaultSSLParameters();
                // Allow verifying the GSA and other trusted computers.
                sslParams.setWantClientAuth(true);
                params.setSSLParameters(sslParams);
              }
            };
        ((HttpsServer) server).setHttpsConfigurator(httpsConf);
      } catch (java.security.NoSuchAlgorithmException ex) {
        throw new RuntimeException(ex);
      }
    }
    if (port == 0) {
        // If the port is zero, then the OS chose a port for us. This is mainly
        // useful during testing.
        port = server.getAddress().getPort();
        config.setValue("server.port", "" + port);
    }
    int maxThreads = config.getServerMaxWorkerThreads();
    int queueCapacity = config.getServerQueueCapacity();
    BlockingQueue<Runnable> blockingQueue
        = new ArrayBlockingQueue<Runnable>(queueCapacity);
    // The Executor can't reject jobs directly, because HttpServer does not
    // appear to handle that case.
    RejectedExecutionHandler policy
        = new SuggestHandlerAbortPolicy(AbstractHandler.abortImmediately);
    Executor executor = new ThreadPoolExecutor(maxThreads, maxThreads,
        1, TimeUnit.MINUTES, blockingQueue, policy);
    server.setExecutor(executor);

    SessionManager<HttpExchange> sessionManager
        = new SessionManager<HttpExchange>(
          new SessionManager.HttpExchangeClientStore("sessid_" + port, secure),
          30 * 60 * 1000 /* session lifetime: 30 minutes */,
          5 * 60 * 1000 /* max cleanup frequency: 5 minutes */);
    AuthnHandler authnHandler = null;
    if (secure) {
      bootstrapOpenSaml();
      SamlMetadata metadata = new SamlMetadata(config.getServerHostname(),
          config.getServerPort(), config.getGsaHostname());

      server.createContext("/samlassertionconsumer",
          new SamlAssertionConsumerHandler(config.getServerHostname(),
            config.getGsaCharacterEncoding(), sessionManager));
      authnHandler = new AuthnHandler(config.getServerHostname(),
          config.getGsaCharacterEncoding(), sessionManager,
          config.getServerKeyAlias(), metadata);
      server.createContext("/saml-authz", new SamlBatchAuthzHandler(
          config.getServerHostname(), config.getGsaCharacterEncoding(),
          adaptor, docIdCodec, metadata));
    }
    server.createContext(config.getServerBaseUri().getPath()
        + config.getServerDocIdPath(),
        new DocumentHandler(config.getServerHostname(),
                            config.getGsaCharacterEncoding(), docIdCodec,
                            docIdCodec, journal, adaptor,
                            config.getGsaHostname(),
                            config.getServerFullAccessHosts(),
                            authnHandler, sessionManager,
                            createTransformPipeline(),
                            config.getTransformMaxDocumentBytes(),
                            config.isTransformRequired(),
                            config.isServerToUseCompression()));
    server.start();
    log.info("GSA host name: " + config.getGsaHostname());
    log.info("server is listening on port #" + port);

    dashboard.start(sessionManager);
    shutdownHook = new Thread(new ShutdownHook(), "gsacomm-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    config.addConfigModificationListener(new GsaConfigModListener());

    long sleepDurationMillis = 1000;
    // An hour.
    long maxSleepDurationMillis = 60 * 60 * 1000;
    while (true) {
      try {
        adaptor.init(new AdaptorContextImpl());
        break;
      } catch (Exception ex) {
        log.log(Level.WARNING, "Failed to initialize adaptor", ex);
        Thread.sleep(sleepDurationMillis);
        sleepDurationMillis
            = Math.min(sleepDurationMillis * 2, maxSleepDurationMillis);
        ensureLatestConfigLoaded();
      }
    }

    // Since we are white-listing particular keys for auto-update, things aren't
    // ready enough to expose to adaptors.
    /*if (adaptor instanceof ConfigModificationListener) {
      config.addConfigModificationListener(
          (ConfigModificationListener) adaptor);
    }*/

    if (adaptor instanceof PollingIncrementalAdaptor) {
      incrementalAdaptorPoller = new IncrementalAdaptorPoller(
          (PollingIncrementalAdaptor) adaptor, docIdSender);
      incrementalAdaptorPoller.start(
          config.getAdaptorIncrementalPollPeriodMillis());
    }

    scheduler.start();
    sendDocIdsSchedId = scheduler.schedule(
        config.getAdaptorFullListingSchedule(), docIdFullPusher);
  }

  private TransformPipeline createTransformPipeline() {
    return createTransformPipeline(config.getTransformPipelineSpec());
  }

  static TransformPipeline createTransformPipeline(
      List<Map<String, String>> pipelineConfig) {
    List<DocumentTransform> elements = new LinkedList<DocumentTransform>();
    for (Map<String, String> element : pipelineConfig) {
      final String name = element.get("name");
      final String confPrefix = "transform.pipeline." + name + ".";
      String factoryMethodName = element.get("factoryMethod");
      if (factoryMethodName == null) {
        throw new RuntimeException(
            "Missing " + confPrefix + "factoryMethod configuration setting");
      }
      int sepIndex = factoryMethodName.lastIndexOf(".");
      if (sepIndex == -1) {
        throw new RuntimeException("Could not separate method name from class "
            + "name");
      }
      String className = factoryMethodName.substring(0, sepIndex);
      String methodName = factoryMethodName.substring(sepIndex + 1);
      log.log(Level.FINE, "Split {0} into class {1} and method {2}",
          new Object[] {factoryMethodName, className, methodName});
      Class<?> klass;
      try {
        klass = Class.forName(className);
      } catch (ClassNotFoundException ex) {
        throw new RuntimeException(
            "Could not load class for transform " + name, ex);
      }
      Method method;
      try {
        method = klass.getDeclaredMethod(methodName, Map.class);
      } catch (NoSuchMethodException ex) {
        throw new RuntimeException("Could not find method " + methodName
            + " on class " + className, ex);
      }
      log.log(Level.FINE, "Found method {0}", new Object[] {method});
      Object o;
      try {
        o = method.invoke(null, Collections.unmodifiableMap(element));
      } catch (Exception ex) {
        throw new RuntimeException("Failure while running factory method "
            + factoryMethodName, ex);
      }
      if (!(o instanceof DocumentTransform)) {
        throw new ClassCastException(o.getClass().getName()
            + " is not an instance of DocumentTransform");
      }
      DocumentTransform transform = (DocumentTransform) o;
      elements.add(transform);
    }
    // If we created an empty pipeline, then we don't need the pipeline at all.
    return elements.size() > 0 ? new TransformPipeline(elements) : null;
  }

  // Useful as a separate method during testing.
  static void bootstrapOpenSaml() {
    try {
      DefaultBootstrap.bootstrap();
    } catch (ConfigurationException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Stop the current services, allowing up to {@code maxDelay} seconds for
   * things to shutdown.
   */
  public synchronized void stop(int maxDelay) {
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ex) {
        // Already executing hook.
      }
      shutdownHook = null;
    }
    scheduler.deschedule(sendDocIdsSchedId);
    sendDocIdsSchedId = null;
    // Stop sendDocIds before scheduler, because scheduler blocks until all
    // tasks are completed. We want to interrupt sendDocIds so that the
    // scheduler stops within a reasonable amount of time.
    docIdFullPusher.stop();
    if (scheduler.isStarted()) {
      scheduler.stop();
    }
    if (incrementalAdaptorPoller != null) {
      incrementalAdaptorPoller.cancel();
    }
    if (server != null) {
      server.stop(maxDelay);
      server = null;
    }
    dashboard.stop();
    adaptor.destroy();
  }

  /**
   * Ensure there is a push running right now. This schedules a new push if one
   * is not already running. Returns {@code true} if it starts a new push, and
   * false otherwise.
   */
  public boolean checkAndScheduleImmediatePushOfDocIds() {
    return docIdFullPusher.runInNewThread() != null;
  }

  boolean ensureLatestConfigLoaded() {
    try {
      return config.ensureLatestConfigLoaded();
    } catch (Exception ex) {
      log.log(Level.WARNING, "Error while trying to reload configuration",
              ex);
      return false;
    }
  }

  /**
   * Runnable that calls {@link DocIdSender#pushDocIds}.
   */
  private class PushRunnable implements Runnable {
    private volatile GetDocIdsErrorHandler handler
        = new DefaultGetDocIdsErrorHandler();

    @Override
    public void run() {
      try {
        docIdSender.pushDocIdsFromAdaptor(handler);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }

    public void setGetDocIdsErrorHandler(GetDocIdsErrorHandler handler) {
      if (handler == null) {
        throw new NullPointerException();
      }
      this.handler = handler;
    }

    public GetDocIdsErrorHandler getGetDocIdsErrorHandler() {
      return handler;
    }
  }

  /**
   * Runnable that logs an error that {@link PushRunnable} is already executing.
   */
  private class AlreadyRunningRunnable implements Runnable {
    @Override
    public void run() {
      log.warning("Skipping scheduled push of docIds. The previous invocation "
                  + "is still running.");
    }
  }

  private class ShutdownHook implements Runnable {
    @Override
    public void run() {
      // Allow three seconds for things to stop.
      stop(3);
    }
  }

  private class GsaConfigModListener implements ConfigModificationListener {
    @Override
    public void configModified(ConfigModificationEvent ev) {
      Set<String> modifiedKeys = ev.getModifiedKeys();
      synchronized (GsaCommunicationHandler.this) {
        if (modifiedKeys.contains("adaptor.fullListingSchedule")
            && sendDocIdsSchedId != null) {
          String schedule = ev.getNewConfig().getAdaptorFullListingSchedule();
          try {
            scheduler.reschedule(sendDocIdsSchedId, schedule);
          } catch (InvalidPatternException ex) {
            log.log(Level.WARNING, "Invalid schedule pattern", ex);
          }
        }
      }

      // List of "safe" keys that can be updated without a restart.
      List<String> safeKeys = Arrays.asList("adaptor.fullListingSchedule");
      // Set of "unsafe" keys that have been modified.
      Set<String> modifiedKeysRequiringRestart
          = new HashSet<String>(modifiedKeys);
      modifiedKeysRequiringRestart.removeAll(safeKeys);
      // If there are modified "unsafe" keys, then we restart things to make
      // sure all the code is up-to-date with the new values.
      if (!modifiedKeysRequiringRestart.isEmpty()) {
        log.warning("Unsafe configuration keys modified. To ensure a sane "
                    + "state, the adaptor is restarting.");
        stop(3);
        try {
          start();
        } catch (Exception ex) {
          log.log(Level.SEVERE, "Automatic restart failed", ex);
          throw new RuntimeException(ex);
        }
      }
    }
  }

  /**
   * This class is thread-safe.
   */
  private class AdaptorContextImpl implements AdaptorContext {
    @Override
    public Config getConfig() {
      return config;
    }

    @Override
    public DocIdPusher getDocIdPusher() {
      return docIdSender;
    }

    @Override
    public DocIdEncoder getDocIdEncoder() {
      return docIdCodec;
    }

    @Override
    public void addStatusSource(StatusSource source) {
      dashboard.addStatusSource(source);
    }

    @Override
    public void removeStatusSource(StatusSource source) {
      dashboard.removeStatusSource(source);
    }

    @Override
    public void setGetDocIdsErrorHandler(GetDocIdsErrorHandler handler) {
      ((PushRunnable) docIdFullPusher.getRunnable())
          .setGetDocIdsErrorHandler(handler);
    }

    @Override
    public GetDocIdsErrorHandler getGetDocIdsErrorHandler() {
      return ((PushRunnable) docIdFullPusher.getRunnable())
          .getGetDocIdsErrorHandler();
    }
  }

  /**
   * Executes Runnable in current thread, but only after setting a thread-local
   * object. The code that will be run, is expected to take notice of the set
   * variable and abort immediately. This is a hack.
   */
  private static class SuggestHandlerAbortPolicy
      implements RejectedExecutionHandler {
    private final ThreadLocal<Object> abortImmediately;
    private final Object signal = new Object();

    public SuggestHandlerAbortPolicy(ThreadLocal<Object> abortImmediately) {
      this.abortImmediately = abortImmediately;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
      abortImmediately.set(signal);
      try {
        r.run();
      } finally {
        abortImmediately.set(null);
      }
    }
  }
}