/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2015 Martin Blom <martin@blom.org>

     This program is free software: you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation, either version 3
     of the License, or (at your option) any later version.

     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.

     You should have received a copy of the GNU General Public License
     along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.esxx;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.activation.FileTypeMap;
import javax.activation.MimetypesFileTypeMap;
import javax.mail.internet.ContentType;
import org.esxx.cache.*;
import org.esxx.jmx.WorkloadInfo;
import org.esxx.saxon.*;
import org.esxx.util.SingleThreadedExecutor;
import org.esxx.util.ThreadSafeExecutor;
import org.esxx.util.SyslogHandler;
import org.esxx.util.TrivialFormatter;
import org.esxx.util.URIResolver;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.WrapFactory;
import org.w3c.dom.*;
import org.w3c.dom.bootstrap.*;
import org.w3c.dom.ls.*;

import net.sf.saxon.s9api.*;
import net.sf.saxon.*;
import net.sf.saxon.functions.FunctionLibrary;
import net.sf.saxon.functions.FunctionLibraryList;


public class ESXX {
    /** A string that defines the ESXX XML namespace */
    public static final String NAMESPACE = "http://esxx.org/1.0/";

    public static int THREADS_PER_CORE = 8;

    private static ESXX esxx;

    public static ESXX getInstance() {
      return esxx;
    }

    public static ESXX initInstance(Properties p, Object h) {
      esxx = new ESXX(p, h);
      return esxx;
    }

    public static void destroyInstance() {
      if (esxx != null && esxx.shutdownHook != null) {
	Runtime.getRuntime().removeShutdownHook(esxx.shutdownHook);
	esxx.terminate();
	esxx = null;
      }
    }

    /** The constructor.
     *
     *  Will initialize the operating environment, start the worker
     *  threads and initialize the JavaScript contexts.
     *
     *  @param p A set of properties that can be used to tune the
     *  execution.
     *
     *  @param h A host object that can later be referenced by the
     *  JavaScript code as 'esxx.host'. May be null.
     *
     */

    private ESXX(Properties p, Object h) {
      settings = p;
      hostObject = h;

      defaultTimeout = (int) (Double.parseDouble(p.getProperty("esxx.app.timeout", "60"))
			      * 1000);
      addShutdownHook = Boolean.parseBoolean(p.getProperty("esxx.app.clean_shutdown", "true"));

      try {
	String[] path = p.getProperty("esxx.app.include_path", "").split(File.pathSeparator);
	includePath = new URI[path.length];

	for (int i = 0; i < path.length; ++i) {
	  includePath[i] = new File(path[i]).toURI();
	}
      }
      catch (Exception ex) {
	throw new ESXXException("Illegal esxx.app.include_path value: " + ex.getMessage(), ex);
      }

      applicationCache = new LRUCache<String, Application>(
	Integer.parseInt(p.getProperty("esxx.cache.apps.max_entries", "1024")),
	(long) (Double.parseDouble(p.getProperty("esxx.cache.apps.max_age", "3600")) * 1000));

      applicationCache.addListener(new ApplicationCacheListener());

      stylesheetCache = new LRUCache<String, Stylesheet>(
	Integer.parseInt(p.getProperty("esxx.cache.xslt.max_entries", "1024")),
	(long) (Double.parseDouble(p.getProperty("esxx.cache.xslt.max_age", "3600")) * 1000));

      stylesheetCache.addListener(new StylesheetCacheListener());

      schemaCache = new LRUCache<String, Schema>(
	Integer.parseInt(p.getProperty("esxx.cache.schema.max_entries", "1024")),
	(long) (Double.parseDouble(p.getProperty("esxx.cache.schema.max_age", "3600")) * 1000));

      schemaCache.addListener(new SchemaCacheListener());

      parsers = new Parsers();

      // Custom CGI-to-HTTP translations
      cgiToHTTPMap = new HashMap<String,String>();
      cgiToHTTPMap.put("HTTP_SOAPACTION", "SOAPAction");
      cgiToHTTPMap.put("CONTENT_TYPE", "Content-Type");
      cgiToHTTPMap.put("CONTENT_LENGTH", "Content-Length");
      cgiToHTTPMap.put("Authorization", "Authorization"); // For mod_fastcgi

      // ESXX is a singleton, so it's OK to call the static method
      // ContextFactory.initGlobal() here
      contextFactory = new ESXXContextFactory();
      ContextFactory.initGlobal(contextFactory);

      // Make sure all threads we create ourselves have a valid Context
      ThreadFactory tf = new ThreadFactory() {
	  public Thread newThread(final Runnable r) {
	    return new Thread("ESXX-Worker-" + cnt.incrementAndGet()) {
	      @Override public void run() {
		contextFactory.call(new ContextAction() {
		    @Override public Object run(Context cx) {
		      r.run();
		      return null;
		    }
		  });
	      }
	    };
	  }

	  private java.util.concurrent.atomic.AtomicInteger cnt = new java.util.concurrent.atomic.AtomicInteger();
	};

      int default_threads = THREADS_PER_CORE * Runtime.getRuntime().availableProcessors();
      int  worker_threads = Integer.parseInt(p.getProperty("esxx.worker_threads", Integer.toString(default_threads)));

       if (worker_threads == -1) {
	 // Use an unbounded thread pool
	 executorService = new ThreadSafeExecutor(tf);
      }
      else if (worker_threads == 0) {
	executorService = new SingleThreadedExecutor();
      }
      else {
	executorService = new ThreadSafeExecutor(worker_threads, tf);
      }

      workloadSet = new PriorityBlockingQueue<Workload>(16, new Comparator<Workload>() {
	  public int compare(Workload w1, Workload w2) {
	    return Long.signum(w1.getExpires() - w2.getExpires());
	  }
	});

      mxRegister("Workloads", null, new WorkloadJMXBean());

      // Add periodic Workload cancellation (if not single-threaded)
      if (worker_threads != 0) {
	executorService.scheduleAtFixedRate(new WorkloadCancellator(), 1, 1, TimeUnit.SECONDS);
      }

      // Add periodic check to expunge applications and xslt stylesheets
      executorService.scheduleWithFixedDelay(new CacheFilter(), 1, 1, TimeUnit.SECONDS);

//       org.mozilla.javascript.tools.debugger.Main main =
// 	new org.mozilla.javascript.tools.debugger.Main("ESXX Debugger");
//       main.doBreak();
//       main.attachTo(contextFactory);
//       main.pack();
//       main.setSize(800, 600);
//       main.setVisible(true);

      if (addShutdownHook) {
	try {
	  // Terminate all apps when the JVM exits
	  shutdownHook = new Thread() {
	      public void run() {
		terminate();
	      }
	    };

	  Runtime.getRuntime().addShutdownHook(shutdownHook);
	}
	catch(Exception ex) {
	  getLogger().logp(Level.WARNING, null, null, "Failed to add shutdown hook");
	}
      }
    }


    /** Terminate all apps and shut down worker threads */
    private void terminate() {
      // Workload w;

      // while ((w = workloadSet.poll()) != null) {
      // 	if (w.interruptable) {
      // 	  w.future.cancel(true /* may interrupt */);
      // 	}
      // }

      applicationCache.clear();
      stylesheetCache.clear();
      schemaCache.clear();
      shutdownAndAwaitTermination(executorService);
    }


    /** Returns the settings Properties object
     *
     *  @returns A Properties object.
     */

    public Properties getSettings() {
      return settings;
    }


    /** Returns the host object
     *
     *  @returns A host object (for instance, a Servlet).
     */

    public Object getHostObject() {
      return hostObject;
    }

    public void setNoHandlerMode(String match) {
      noHandlerMode = Pattern.compile(match == null ? ".*" : match);
    }

    public boolean isHandlerMode(String server_software) {
      if (server_software == null) {
	return true;
      }

      return ! noHandlerMode.matcher(server_software).matches();
    }

    /** Returns a global, non-application tied Logger.
     *
     *  @returns A Logger object (singleton).
     */

    public synchronized Logger getLogger() {
      if (logger == null) {
	logger = SyslogHandler.createLogger(ESXX.class.getName(), Level.CONFIG, "esxx", -1);
      }

      return logger;
    }

    /** Return the ScheduledExecutorService used to execute jobs. */
    public ScheduledExecutorService getExecutor() {
      return executorService;
    }

    /** Adds a Request to the work queue.
     *
     *  Once the request has been executed, Request.finished will be
     *  called with an ignorable returncode and a set of HTTP headers.
     *
     *  @param request  The Request object that is to be executed.
     *  @param timeout  The timeout in milliseconds. Note that this is
     *                  the time from the time of submission, not from the
     *                  time the request actually starts processing.
     */

    public Workload addRequest(final Request request, final ResponseHandler rh, int timeout) {
      return addContextAction(null, new ContextAction() {
	  public Object run(Context cx) {
	    try {
	      Response response = request.getQuickResponse();

	      if (response == null) {
		response = new Worker(ESXX.this).handleRequest(cx, request);
	      }

	      return rh.handleResponse(response);
	    }
	    catch (Throwable t) {
	      return rh.handleError(t);
	    }
	  }
	}, request.toString(), timeout);
    }

    public Workload addContextAction(Context old_cx, final ContextAction ca,
				     final String name, int timeout) {
      long expires;

      if (timeout == -1) {
	expires = Long.MAX_VALUE;
      }
      else if (timeout == 0) {
	expires = System.currentTimeMillis() + defaultTimeout;
      }
      else {
	expires = System.currentTimeMillis() + timeout;
      }

      if (old_cx != null) {
	Workload old_work = (Workload) old_cx.getThreadLocal(Workload.class);

	if (old_work != null && old_work.getExpires() < expires) {
	  // If we're already executing a workload, never extend the timeout
	  expires = old_work.getExpires();
	}
      }

      final Workload workload = new Workload(name, expires);

      workloadSet.add(workload);

      synchronized (workload) {
	workload.future = executorService.submit(new Callable<Object>() {
	    public Object call()
	      throws Exception {

	      return contextFactory.call(new ContextAction() {
		  @Override public Object run(Context new_cx) {
		    Object old_workload = new_cx.getThreadLocal(Workload.class);
		    // Thread thread       = Thread.currentThread();
		    // String old_name     = thread.getName();

		    workload.open();
		    new_cx.putThreadLocal(Workload.class, workload);
		    // thread.setName(old_name + " - " + name);

		    try {
		      return ca.run(new_cx);
		    }
		    finally {
		      if (old_workload != null) {
			new_cx.putThreadLocal(Workload.class, old_workload);
		      }
		      else {
			new_cx.removeThreadLocal(Workload.class);
		      }

		      workload.close();
		      workloadSet.remove(workload);
		      // thread.setName(old_name);
		    }
		  }
		});
	    }
	  });
      }

      return workload;
    }

    public static void checkTimeout(Context cx)
      throws ESXXException.TimeOut {
      Workload workload = (Workload) cx.getThreadLocal(Workload.class);

      if (workload == null) {
	return;
      }

      if (workload.isTimedOut()) {
	ESXX.getInstance().getLogger().logp(Level.FINE, null, null,
					    "Workload " + workload
					    + " timed out: throwing TimeOut");
	throw new ESXXException.TimeOut();
      }
    }


    /** Utility method that serializes a W3C DOM Node to a String.
     *
     *  @param node  The Node to be serialized.
     *
     *  @return A String containing the XML representation of the supplied Node.
     */


    public String serializeNode(org.w3c.dom.Node node) {
      try {
	LSSerializer ser = getDOMImplementationLS().createLSSerializer();

 	DOMConfiguration dc = ser.getDomConfig();
 	dc.setParameter("xml-declaration", false);

	return ser.writeToString(node);
      }
      catch (LSException ex) {
	// Should never happen
	ex.printStackTrace();
	throw new ESXXException("Unable to serialize DOM Node: " + ex.getMessage(), ex);
      }
    }

    /** Utility method that converts a W3C DOM Node into an E4X XML object.
     *
     *  @param node  The Node to be converted.
     *
     *  @param cx    The current JavaScript context.
     *
     *  @param scope The current JavaScript scope.
     *
     *  @return A Scriptable representing an E4X XML object.
     */

    public static Scriptable domToE4X(org.w3c.dom.Node node, Context cx, Scriptable scope) {
      if (node == null) {
	return null;
      }

      return cx.newObject(scope, "XML", new org.w3c.dom.Node[] { node });
    }


    /** Utility method that converts an E4X XML object into a W3C DOM Node.
     *
     *  @param node  The E4X XML node to be converted.
     *
     *  @return A W3C DOM Node.
     */

    public static org.w3c.dom.Node e4xToDOM(Scriptable node) {
      if ("XMLList".equals(node.getClassName())) {
	// If an XMLList object, return only the first member
	node = (Scriptable) node.get(0, node);
      }

      return org.mozilla.javascript.xmlimpl.XMLLibImpl.toDomNode(node);
    }


    /** Utility method to create a new W3C DOM document.
     *
     *  @param name  The name of the document element
     *
     *  @return A W3C DOM Document.
     */

    public Document createDocument(String name) {
      return getDOMImplementation().createDocument(null, name, null);
    }


    /** Utility method that translates the name of a CGI environment
     *  variable into it's original HTTP header name.
     *
     *  @param name The name of a CGI variable.
     *
     *  @return  The name of the original HTTP header, or null if this
     *  variable name is unknown.
     */

    public String cgiToHTTP(String name) {
      String h = cgiToHTTPMap.get(name);

      // If there was a mapping, use it

      if (h != null) {
	return h;
      }

      if (name.startsWith("HTTP_")) {
	// "Guess" the name by capitalizing the variable name

	StringBuilder str = new StringBuilder();

	boolean cap = true;
	for (int i = 5; i < name.length(); ++i) {
	  char c = name.charAt(i);

	  if (c == '_') {
	    str.append('-');
	    cap = true;
	  }
	  else if (cap) {
	    str.append(Character.toUpperCase(c));
	    cap = false;
	  }
	  else {
	    str.append(Character.toLowerCase(c));
	  }
	}

	return str.toString();
      }
      else {
	return null;
      }
    }

    /** Utility method that translates the name of an HTTP header into
     *  the CGI environment variable name.
     *
     *  @param name The name of an HTTP header.
     *
     *  @return  The name of the CGI variable.
     */

    public static String httpToCGI(String name) {
      if (name.equals("Content-Type")) {
	return "CONTENT_TYPE";
      }
      else if (name.equals("Content-Length")) {
	return "CONTENT_LENGTH";
      }
      else {
	return "HTTP_" + name.toUpperCase().replaceAll("-", "_");
      }
    }


  public static <T> T coalesce(T ...objects) {
    for (T obj : objects) {
      if (obj != null) {
	return obj;
      }
    }

    return null;
  }

    public URI[] getIncludePath() {
      return includePath;
    }

    /** Utility method that parses an InputStream into a W3C DOM
     *  Document.
     *
     *  @param is  The InputStream to be parsed.
     *
     *  @param is_uri  The location of the InputStream.
     *
     *  @param external_uris A Collection of URIs that will be
     *  populated with all URIs visited during the parsing. Can be
     *  'null'.
     *
     *  @param err A PrintWriter that will be used to report parser
     *  errors. Can be 'null'.
     *
     *  @return A W3C DOM Document.
     *
     *  @throws org.xml.sax.SAXException On parser errors.
     *
     *  @throws IOException On I/O errors.
     */

    public Document parseXML(InputStream is, URI is_uri,
			     final Collection<URI> external_uris,
			     final PrintWriter err)
      throws ESXXException {
      DOMImplementationLS di = getDOMImplementationLS();
      LSInput in = di.createLSInput();

      in.setSystemId(is_uri.toString());
      in.setByteStream(is);

      LSParser p = di.createLSParser(DOMImplementationLS.MODE_SYNCHRONOUS, null);

      DOMErrorHandler eh = new DOMErrorHandler() {
	    public boolean handleError(DOMError error) {
	      DOMLocator  dl = error.getLocation();
	      String     pos = (dl.getUri() + ", line " + dl.getLineNumber() +
				", column " + dl.getColumnNumber());
	      Throwable  rel = (Throwable) error.getRelatedException();

	      switch (error.getSeverity()) {
		case DOMError.SEVERITY_FATAL_ERROR:
		case DOMError.SEVERITY_ERROR:
		  if (rel instanceof ESXXException) {
		    throw (ESXXException) rel;
		  }
		  else {
		    throw new ESXXException(pos + ": " + error.getMessage(), rel);
		  }

		case DOMError.SEVERITY_WARNING:
		  err.println(pos + ": " + error.getMessage());
		  return true;
	      }

	      return false;
	    }
	};

      DOMConfiguration dc = p.getDomConfig();

      URIResolver ur = new URIResolver(this, is_uri, external_uris);

      try {
	dc.setParameter("comments", false);
	dc.setParameter("cdata-sections", false);
	dc.setParameter("entities", false);
	//      dc.setParameter("validate-if-schema", true);
	dc.setParameter("error-handler", eh);
	dc.setParameter("resource-resolver", ur);
	dc.setParameter("http://apache.org/xml/features/xinclude", true);

	return p.parse(in);
      }
      finally {
	ur.closeAllStreams();
      }
    }

    public InputStream openCachedURI(URI uri)
      throws IOException {

      if ("esxx-rsrc".equals(uri.getScheme())) {
	InputStream rsrc = getClass().getResourceAsStream("/rsrc/" + uri.getSchemeSpecificPart());

	if (rsrc == null) {
	  throw new FileNotFoundException(uri.toString());
	}

	return rsrc;
      }


      URLConnection uc = uri.toURL().openConnection();

      uc.setDoInput(true);
      uc.setDoOutput(false);
      uc.connect();

      return uc.getInputStream();
    }

    public File createTempFile(Context cx)
      throws IOException {
      File temp = File.createTempFile(getClass().getName(), null);
      temp.deleteOnExit();

      Workload workload = (Workload) cx.getThreadLocal(Workload.class);

      if (workload != null) {
	workload.addTempFile(temp);
      }

      return temp;
    }

    public Object parseStream(String mime_type, InputStream is, URI is_uri,
			      Collection<URI> external_uris,
			      PrintWriter err,
			      Context cx, Scriptable scope)
      throws Exception {
      try {
	return parseStream( new ContentType(mime_type), is, is_uri, external_uris, err, cx, scope);
      }
      catch (javax.mail.internet.ParseException ex) {
	throw new IOException("Invalid Content-Type: " + ex.getMessage(), ex);
      }
    }

    public Object parseStream(ContentType ct, InputStream is, URI is_uri,
			      Collection<URI> external_uris,
			      PrintWriter err,
			      Context cx, Scriptable scope)
      throws Exception {
      return parsers.parse(ct, is, is_uri, external_uris, err, cx, scope);
    }

    public ContentType serializeObject(Object data, ContentType ct,
				       OutputStream os, boolean close)
      throws IOException {
      try {
	Response response = new Response(0, ct != null ? ct.toString() : null, data, null);

	response.writeResult(os);

	if (ct == null) {
	  ct = new ContentType(response.getContentType(true));
	}

	return ct;
      }
      catch (javax.mail.internet.ParseException ex) {
	// (This should never happen, unless Response.getContentType() is broken)
	throw new ESXXException("Invalid Content-Type from Response.getContentType(): "
				+ ex.getMessage(), ex);
      }
      finally {
	try {
	  if (close) {
	    os.close();
	  }
	  else {
	    os.flush();
	  }
	}
	catch (IOException ignored) {}
      }
    }

    public Application getCachedApplication(final Context cx, final Request request)
      throws Exception {
      String url_string = request.getScriptFilename().toString();
      Application app;

      while (true) {
	app = applicationCache.add(url_string, new LRUCache.ValueFactory<String, Application>() {
	    public Application create(String key, long expires)
	    throws IOException {
	      // The application cache makes sure we are
	      // single-threaded (per application URL) here, so only
	      // one Application will ever be created, no matter how
	      // many concurrent requests there are.
	      return new Application(cx, request);
	    }
	}, 0);

	if (app.enter()) {
	  break;
	}

	// We could not "enter" the application, because it had been
	// marked for termination but not yet removed from the
	// cache. In this (rather unusual) situation, we let some
	// other thread execute for a while and then retry again.
	Thread.yield();
      }

      return app;
    }

    public void releaseApplication(Application app, long start_time) {
      app.logUsage(start_time);
      app.exit();
    }

    public void removeCachedApplication(Application app) {
      applicationCache.remove(app.getFilename());
    }

    public Stylesheet getCachedStylesheet(final URI uri, final InputStream is)
      throws IOException {
      try {
	return stylesheetCache.add(uri.toString(), new LRUCache.ValueFactory<String, Stylesheet>() {
	    public Stylesheet create(String key, long expires)
	      throws IOException {
	      return new Stylesheet(uri, is);
	    }
	  }, 0);
      }
      catch (IOException ex) {
	throw ex;
      }
      catch (ESXXException ex) {
	throw ex;
      }
      catch (Exception ex) {
	throw new ESXXException("Unexpected exception in getCachedStylesheet(): " + ex.toString(),
				ex);
      }
    }

    public void removeCachedStylesheet(Stylesheet xslt) {
      stylesheetCache.remove(xslt.getFilename());
    }

    public Schema getCachedSchema(final URI uri, final InputStream is, final String type)
      throws IOException {
      try {
	return schemaCache.add(uri.toString(), new LRUCache.ValueFactory<String, Schema>() {
	    public Schema create(String key, long expires)
	      throws IOException {
	      return new Schema(uri, is, type);
	    }
	  }, 0);
      }
      catch (IOException ex) {
	throw ex;
      }
      catch (ESXXException ex) {
	throw ex;
      }
      catch (Exception ex) {
	ex.printStackTrace();
	throw new ESXXException("Unexpected exception in getCachedSchema(): " + ex.toString(),
				ex);
      }
    }

    public void removeCachedSchema(Schema schema) {
      schemaCache.remove(schema.getFilename());
    }

    public DOMImplementationLS getDOMImplementationLS() {
      return (DOMImplementationLS) getDOMImplementation();
    }

    public synchronized DOMImplementation getDOMImplementation() {
      if (domImplementation == null) {
	try {
	  DOMImplementationRegistry reg  = DOMImplementationRegistry.newInstance();
	  domImplementation = reg.getDOMImplementation("XML 3.0");
	}
	catch (Exception ex) {
	  throw new ESXXException("Unable to get a DOM implementation object: "
				  + ex.getMessage(), ex);
	}
      }

      return domImplementation;
    }

    public synchronized Processor getSaxonProcessor() {
      if (saxonProcessor == null) {
	saxonProcessor = new Processor(false);

	// Hook in our own extension functions
	Configuration cfg = saxonProcessor.getUnderlyingConfiguration();
	FunctionLibrary java = cfg.getExtensionBinder("java");
	FunctionLibraryList fl = new FunctionLibraryList();
	fl.addFunctionLibrary(new ESXXFunctionLibrary());
	fl.addFunctionLibrary(java);
	cfg.setExtensionBinder("java", fl);
      }

      return saxonProcessor;
    }

  public synchronized DocumentBuilder getSaxonDocumentBuilder() {
    if (saxonDocumentBuilder == null) {
      saxonDocumentBuilder = getSaxonProcessor().newDocumentBuilder();
    }

    return saxonDocumentBuilder;
  }

  public void mxRegister(String type, String name, javax.management.StandardMBean object) {
    try {
      javax.management.MBeanServer mbs =
	java.lang.management.ManagementFactory.getPlatformMBeanServer();

      mbs.registerMBean(object, mxObjectName(type, name));
    }
    catch (Throwable ex) {
      // Probably a Google App Engine problem
      getLogger().logp(Level.WARNING, null, null,
		       "Failed to register " + type + " MXBean " + name);
    }
  }

  public void mxUnregister(String type, String name) {
    try {
      javax.management.MBeanServer mbs =
	java.lang.management.ManagementFactory.getPlatformMBeanServer();

      mbs.unregisterMBean(mxObjectName(type, name));
    }
    catch (Throwable ex) {
      // Probably a Google App Engine problem
      getLogger().logp(Level.WARNING, null, null,
		       "Failed to unregister " + type + " MXBean " + name);
    }
  }

  /** A pattern that matches the characters '"', '\', '?' and '*'. */
  private static java.util.regex.Pattern mxObjectNamePattern =
    java.util.regex.Pattern.compile("(\"\\\\\\?\\*)");

  private javax.management.ObjectName mxObjectName(String type, String name)
    throws javax.management.MalformedObjectNameException {
    String object_name = "org.esxx:type=" + type;

    if (name != null) {
      // Quote illegal characters
      name = mxObjectNamePattern.matcher(name).replaceAll("\\\\$1");
      object_name += ",name=\"" + name + "\"";
    }

    return new javax.management.ObjectName(object_name);
  }


    public ContextFactory getContextFactory() {
      return contextFactory;
    }


    public static String parseMIMEType(String ct, HashMap<String,String> params) {
      String[] parts = ct.split(";");
      String   type  = parts[0].trim();

      if (params != null) {
	params.clear();

	// Add all attributes
	for (int i = 1; i < parts.length; ++i) {
	  String[] attr = parts[i].split("=", 2);

	  if (attr.length == 2) {
	    params.put(attr[0].trim(), attr[1].trim());
	  }
	}
      }

      return type;
    }

    public static String combineMIMEType(String type, HashMap<String,String> params) {
      if (type == null) {
	return null;
      }

      try {
	javax.mail.internet.ContentType ct = new javax.mail.internet.ContentType(type);

	if (params != null) {
	  for (Map.Entry<String,String> e : params.entrySet()) {
	    ct.setParameter(e.getKey(), e.getValue());
	  }
	}

	return ct.toString();
      }
      catch (javax.mail.internet.ParseException ex) {
	throw new ESXXException("Failed to parse MIME type " + type + ": " + ex.getMessage(), ex);
      }
    }

    void shutdownAndAwaitTermination(ExecutorService pool) {
      pool.shutdown(); // Disable new tasks from being submitted
      try {
	// Wait a while for existing tasks to terminate
	if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
	  pool.shutdownNow(); // Cancel currently executing tasks
	  // Wait a while for tasks to respond to being cancelled
	  if (!pool.awaitTermination(60, TimeUnit.SECONDS))
	    getLogger().logp(Level.SEVERE, null, null, "Pool did not terminate");
	}
      } catch (InterruptedException ie) {
	// (Re-)Cancel if current thread also interrupted
	pool.shutdownNow();
	// Preserve interrupt status
	Thread.currentThread().interrupt();
      }
    }

    /** This class monitors all applications that are being loaded and
     *  unload, and handles MX registration and application exit
     *  handlers.
     */

    private class ApplicationCacheListener
      implements LRUCache.LRUListener<String, Application> {

      public void entryAdded(String key, Application app) {
	mxRegister("Applications", app.getFilename(), app.getJMXBean());
	getLogger().logp(Level.CONFIG, null, null, app + " loaded.");
      }

      public void entryRemoved(String key, final Application app) {
	getLogger().logp(Level.CONFIG, null, null, app + " unloading ...");

	// In this function, we're single-threaded (per application URI)
	app.terminate(defaultTimeout);

	// Execute the exit handler in one of the worker threads
	Workload workload = addContextAction(null, new ContextAction() {
	    public Object run(Context cx) {
	      app.executeExitHandler(cx);
	      app.clearPLS();
	      return null;
	    }
	  }, app + " exit handler", defaultTimeout);

	try {
	  workload.getResult();
	}
	catch (InterruptedException ex) {
	  Thread.currentThread().interrupt();
	  ex.printStackTrace();
	}
	catch (Exception ex) {
	  ex.printStackTrace();
	}
	finally {
	  mxUnregister("Applications", app.getFilename());
	  getLogger().logp(Level.CONFIG, null, null, app + " unloaded.");
	}
      }
    }


    /** This class monitors all stylesheets that are being loaded and
     *  unload and handles MX registration.
     */

    private class StylesheetCacheListener
      implements LRUCache.LRUListener<String, Stylesheet> {

      public void entryAdded(String key, Stylesheet xslt) {
	mxRegister("Stylesheets", xslt.getFilename(), xslt.getJMXBean());
	getLogger().logp(Level.CONFIG, null, null, xslt + " loaded.");
      }

      public void entryRemoved(String key, Stylesheet xslt) {
	mxUnregister("Stylesheets", xslt.getFilename());
	getLogger().logp(Level.CONFIG, null, null, xslt + " unloaded.");
      }
    }

    /** This class monitors all schemas that are being loaded and
     *  unload and handles MX registration.
     */

    private class SchemaCacheListener
      implements LRUCache.LRUListener<String, Schema> {

      public void entryAdded(String key, Schema sch) {
	mxRegister("Schemas", sch.getFilename(), sch.getJMXBean());
	getLogger().logp(Level.CONFIG, null, null, sch + " loaded.");
      }

      public void entryRemoved(String key, Schema sch) {
	mxUnregister("Schemas", sch.getFilename());
	getLogger().logp(Level.CONFIG, null, null, sch + " unloaded.");
      }
    }


     /** This class checks if any of an Application's, Stylesheet's or Schema's
     *   files have been modified since it was loaded, and if so,
     *   unloads the cached object.
     */

    private class CacheFilter
      implements Runnable {
      @Override public void run() {
	getLogger().logp(Level.FINEST, null, null, "Checking for modified applications");
	applicationCache.filterEntries(new LRUCache.EntryFilter<String, Application>() {
	    public boolean isStale(String key, Application app, long created) {
	      for (URI uri : app.getExternalURIs()) {
		if (checkTag(uri)) {
		  getLogger().logp(Level.FINE, null, null, uri + " modification detected");
		  return true;
		}
	      }

	      return false;
	    }
	  });

	getLogger().logp(Level.FINEST, null, null, "Checking for modified stylesheets");
	stylesheetCache.filterEntries(new LRUCache.EntryFilter<String, Stylesheet>() {
	    public boolean isStale(String key, Stylesheet xslt, long created) {
	      for (URI uri : xslt.getExternalURIs()) {
		if (checkTag(uri)) {
		  getLogger().logp(Level.FINE, null, null, uri + " modification detected");
		  return true;
		}
	      }

	      return false;
	    }
	  });

	getLogger().logp(Level.FINEST, null, null, "Checking for modified schemas");
	schemaCache.filterEntries(new LRUCache.EntryFilter<String, Schema>() {
	    public boolean isStale(String key, Schema sch, long created) {
	      for (URI uri : sch.getExternalURIs()) {
		if (checkTag(uri)) {
		  getLogger().logp(Level.FINE, null, null, uri + " modification detected");
		  return true;
		}
	      }

	      return false;
	    }
	  });

	getLogger().logp(Level.FINEST, null, null, "Finished checking for modified resources");
      }

      private boolean checkTag(URI uri) {
	URLConnection uc = null;

	try {
	  uc = uri.toURL().openConnection();
	  uc.setDoInput(true);
	  uc.setDoOutput(false);
	  uc.setUseCaches(false);
	  uc.setConnectTimeout(3000);
	  uc.setReadTimeout(3000);
	  if (uc instanceof HttpURLConnection) {
	    HttpURLConnection huc = (HttpURLConnection) uc;
	    huc.setRequestMethod("HEAD");
	    uc.getInputStream();
	  }
	  else {
	    uc.connect();
	  }

	  String new_tag = uc.getHeaderField("ETag");

	  if (new_tag == null) {
	    new_tag = Long.toString(uc.getLastModified());
	  }

	  String old_tag = tagMap.get(uri);

	  if (old_tag == null) {
	    // Not previously present
	    tagMap.put(uri, new_tag);
	    old_tag = new_tag;
	  }

	  if (old_tag.equals(new_tag)) {
	    // No modification
	    return false;
	  }
	  else {
	    // File updated
	    tagMap.put(uri, new_tag);
	    return true;
	  }
	}
	catch (IOException ex) {
	  // Assume nothing has changed
	  return false;
	}
	finally {
	  if (uc != null) {
	    try { uc.getInputStream().close(); } catch (IOException ignored) {}
	  }
	}
      }

      private Map<URI, String> tagMap = new HashMap<URI, String>();
    }


    /** Prepare a file path to be used as a root URI
     *
     *  This function makes the path absolute and ensures that it
     *  ends with a slash.
     *
     *  @param  fs_root A native file system path
     *  @return An absolute file URI than ends with a slash
     */

    public static URI createFSRootURI(String fs_root) {
      URI fs_root_uri = new File(fs_root).getAbsoluteFile().toURI();

      String fs_root_uri_str = fs_root_uri.toString();

      if (!fs_root_uri_str.endsWith("/")) {
	fs_root_uri = URI.create(fs_root_uri_str + "/");
      }

      return fs_root_uri;
    }

    /** This is ESXX ContextFactory, which makes sure all Rhino
     *  Context objects are set up with the desired properties and
     *  features.
     */

    private static class ESXXContextFactory
      extends ContextFactory {
      public ESXXContextFactory() {
	super();

	addListener(new ContextFactory.Listener() {
	    @Override public void contextCreated(Context cx) {
	      // Enable all optimizations, but do count instructions
	      cx.setOptimizationLevel(9);
	      cx.setInstructionObserverThreshold((int) 100e6);
	      cx.setLanguageVersion(Context.VERSION_1_7);

	      // Provide a better mapping for primitive types on this context
	      WrapFactory wf = new WrapFactory() {
		  @Override public Object wrap(Context cx, Scriptable scope,
					       Object obj, Class<?> static_type) {
		    if (obj instanceof char[]) {
		      return new String((char[]) obj);
		    }
		    else {
		      return super.wrap(cx, scope, obj, static_type);
		    }
		  }
		};
	      wf.setJavaPrimitiveWrap(false);
	      cx.setWrapFactory(wf);
	    }

	    @Override public void contextReleased(Context cx) {
	      // At this point, cx is un-associated, so we need to
	      // enter it again in order to call clearTLS(). And then
	      // we (naturally) have to exit it ... which will call
	      // contextReleased! Argh.

	      if (cx.getThreadLocal(ESXXContextFactory.class) != null) {
		// Prevent infinite recursion
		return;
	      }

	      cx.putThreadLocal(ESXXContextFactory.class, this);

	      enterContext(cx);
	      try {
		Application.clearTLS(cx);
	      }
	      finally {
		Context.exit();
	      }

	      cx.removeThreadLocal(ESXXContextFactory.class);
	    }
	  });
      }

      @Override	public boolean hasFeature(Context cx, int feature) {
	if (//feature == Context.FEATURE_DYNAMIC_SCOPE ||
	    feature == Context.FEATURE_LOCATION_INFORMATION_IN_ERROR ||
	    feature == Context.FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME ||
	    //feature == Context.FEATURE_WARNING_AS_ERROR ||
	    feature == Context.FEATURE_STRICT_MODE) {
	  return true;
	}
	else {
	  return super.hasFeature(cx, feature);
	}
      }

      @Override public void observeInstructionCount(Context cx, int instruction_count) {
	checkTimeout(cx);
      }
    }

    private class WorkloadCancellator
      implements Runnable {
      @Override public void run() {
	getLogger().logp(Level.FINEST, null, null, "Checking for workloads to cancel");

	while (true) {
	  Workload w = workloadSet.peek();

	  if (w == null) {
	    break;
	  }

	  if (w.shouldCancel()) {
	    getLogger().logp(Level.FINE, null, null, "Cancelling workload " + w);
	    w.cancel();
	    workloadSet.poll();
	  }
	  else {
	    // No need to look futher, since the workloads are
	    // sorted by expiration time
	    break;
	  }
	}

	getLogger().logp(Level.FINEST, null, null, "Finished checking for workloads to cancel");
      }
    }

    public static class Workload {
      public Workload(String name, long exp) {
	future    = null;
	this.name = name;
	expires   = exp;
      }

      public synchronized void addTempFile(File file) {
	tempFiles.add(file);
      }

      public synchronized void open() {
	thread = Thread.currentThread();
      }

      public synchronized void close() {
	for (File temp : tempFiles) {
	  try { temp.delete(); } catch (Exception ignored) {}
	}
	tempFiles.clear();

	thread = null;
      }

      public String getName() {
	return name;
      }

      public long getExpires() {
	return expires;
      }

      public synchronized Thread getThread() {
	return thread;
      }

      public Object getResult()
	throws InterruptedException, ExecutionException {
	return future.get();
      }

      public boolean isDone() {
	return future.isDone();
      }

      public boolean isTimedOut() {
	return System.currentTimeMillis() > expires;
      }

      public boolean shouldCancel() {
	// 10 seconds grace time. (Note that we cannot add to expires,
	// since it may be set to Long.MAX_VALUE.)
	return (System.currentTimeMillis() - 10000) > expires;
      }

      public void cancel() {
	future.cancel(true);
      }

      @Override public String toString() {
	return "[Workload: " + name + " (expires " + new java.util.Date(expires) + ")]";
      }

      @Override protected void finalize()
	throws Throwable {
	try {
	  close();
	}
	finally {
	  super.finalize();
	}
      }

      private Future<Object> future;
      private Thread thread;
      private String name;
      private long expires;
      private Collection<File> tempFiles = new ArrayList<File>();
    }


    private class WorkloadJMXBean
      extends javax.management.StandardEmitterMBean
      implements org.esxx.jmx.WorkloadMXBean {

      public WorkloadJMXBean() {
	super(org.esxx.jmx.WorkloadMXBean.class, true,
	      new javax.management.NotificationBroadcasterSupport());
      }

      @Override public int getWorkloadCount() {
	return ESXX.this.workloadSet.size();
      }

      @Override public List<WorkloadInfo> getWorkloads() {
	ArrayList<Workload> workloads = new ArrayList<Workload>(workloadSet);
	ArrayList<WorkloadInfo> infos = new ArrayList<WorkloadInfo>(workloads.size());

	for (Workload w : workloads) {
	  synchronized (w) {
	    infos.add(new WorkloadInfo(w.getName(), new Date(w.getExpires()),
				       w.getThread().getName(),
				       w.isTimedOut(), w.isDone()));
	  }
	}

	return infos;
      }
    }


    public interface ResponseHandler {
      Integer handleResponse(Response result)
	throws Exception;
      Integer handleError(Throwable error);
    }

    public static final FileTypeMap fileTypeMap = new ESXXFileTypeMap();


    private static class ESXXFileTypeMap
      extends MimetypesFileTypeMap {
      public ESXXFileTypeMap() {
	super();

	addIfMissing("css",   "text/css");
	addIfMissing("csv",   "text/csv");
	addIfMissing("eml",   "message/rfc822");
	addIfMissing("esxx",  "application/vnd.esxx.webapp+xml");
	addIfMissing("gif",   "image/gif");
	addIfMissing("html",  "text/html");
	addIfMissing("jpg",   "image/jpeg");
	addIfMissing("js",    "text/javascript");
	addIfMissing("json",  "application/json");
	addIfMissing("nrl",   "application/x-nrl+xml");
	addIfMissing("nvdl",  "application/x-nvdl+xml");
	addIfMissing("pdf",   "application/pdf");
	addIfMissing("png",   "image/png");
	addIfMissing("rnc",   "application/relax-ng-compact-syntax");
	addIfMissing("rng",   "application/x-rng+xml");
	addIfMissing("sch",   "application/x-schematron+xml");
	addIfMissing("txt",   "text/plain");
	addIfMissing("xhtml", "application/xhtml+xml");
	addIfMissing("xml",   "application/xml");
	addIfMissing("xsd",   "application/x-xsd+xml");
	addIfMissing("xsl",   "text/xsl");
	addIfMissing("xslt",  "text/xsl");
      }

      @Override public String getContentType(File file) {
	if (file.isDirectory()) {
	  return "application/vnd.esxx.directory+xml";
	}
	else if (!file.isFile()) {
	  return "application/vnd.esxx.object";
	}
	else {
	  return super.getContentType(file);
	}
      }

      private void addIfMissing(String ext, String type) {
	if (getContentType("file." + ext).equals("application/octet-stream")) {
	  addMimeTypes(type + " " + ext + " " + ext.toUpperCase());
	}
      }
    }

    private Pattern noHandlerMode = Pattern.compile("");

    private int defaultTimeout;
    private boolean addShutdownHook;
    private URI[] includePath;

    private LRUCache<String, Application> applicationCache;
    private LRUCache<String, Stylesheet> stylesheetCache;
    private LRUCache<String, Schema> schemaCache;

    private Parsers parsers;
    private Properties settings;
    private Object hostObject;
    private HashMap<String,String> cgiToHTTPMap;

    private DOMImplementation domImplementation;
    private Processor saxonProcessor;
    private DocumentBuilder saxonDocumentBuilder;

    private ContextFactory contextFactory;
    private ScheduledExecutorService executorService;
    private PriorityBlockingQueue<Workload> workloadSet;
    private WorkloadJMXBean workloadJMXBean;
    private Logger logger;

    private Thread shutdownHook;
}
