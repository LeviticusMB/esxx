
package org.blom.martin.esxx;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import org.blom.martin.esxx.js.JSESXX;
import org.mozilla.javascript.*;
import org.mozilla.javascript.xml.XMLObject;
import org.apache.xmlbeans.XmlObject;

import javax.xml.transform.*;
import javax.xml.transform.stream.*;

public class ESXX {
    public static final String NAMESPACE = "http://martin.blom.org/esxx/1.0/";

    public class ESXXException 
      extends Exception {
	public ESXXException(String why) { super(why); }
    }

    public ESXX(Properties p) {
      settings = p;

      workerThreads = new ThreadGroup("ESXX worker threads");
      workloadQueue = new LinkedBlockingQueue<Workload>(MAX_WORKLOADS);

      // Create worker threads

      int threads = Integer.parseInt(
	settings.getProperty("esxx.worker_threads", 
			     "" + Runtime.getRuntime().availableProcessors() * 2));

      for (int i = 0; i < threads; ++i) {
	Thread t = new Thread(
	  workerThreads, 
	  new Runnable() {
	      public void run() {
		// Create the JavaScript thread context and invoke workerThread()
		Context.call(new ContextAction() {
		      public Object run(Context cx) {
			workerThread(cx);
			return null;
		      }
		  });
	      }
	  },
	  "ESXX worker thread " + i);

	t.start();
      }
    }

    public void addWorkload(Workload w) {
      while (true) {
	try {
	  workloadQueue.put(w);
	  break;
	}
	catch (InterruptedException ex) {
	  // Retry
	}
      }
    }

    private void workerThread(Context cx) {
      // Provide a better mapping for primitive types on this context
      cx.getWrapFactory().setJavaPrimitiveWrap(false);

//       cx.setWrapFactory(new WrapFactory() {
// 	    public Object wrap(Context cx, Scriptable scope, Object obj, Class staticType) {
// 	      if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
// 		return obj;
// 	      } 
// 	      else if (obj instanceof Character) {
// 		char[] a = { ((Character) obj).charValue() };
// 		return new String(a);
// 	      }
// 	      else {
// 		return super.wrap(cx, scope, obj, staticType);
// 	      }
// 	    }	    
// 	});


      // Now wait for workloads and execute them

      while (true) {
	try {
	  Workload workload = workloadQueue.take();
	  String method = "GET";
	  
	  try {
	    ESXXParser parser = new ESXXParser(workload.getInputStream(), workload.getURL());

	    Scriptable scope   = new ImporterTopLevel(cx, false);
	    JSESXX     js_esxx = new JSESXX(cx, scope, workload, 
					    parser.getXML(), parser.getStylesheet());
	    Object     esxx    = Context.javaToJS(js_esxx, scope);
	    ScriptableObject.putProperty(scope, "esxx", esxx);
	    
	    Object result = null;
	    Exception error = null;

	    try {
	      for (ESXXParser.Code c : parser.getCodeList()) {
		cx.evaluateString(scope, c.code, c.url.toString(), c.line, null);
	      }

	      if (parser.hasHandlers()) {
		String handler = parser.getHandlerFunction(method);
		Object fobj = cx.evaluateString(scope, handler,
						"<handler/>", 1, null);
		if (fobj == null || 
		    fobj == ScriptableObject.NOT_FOUND) {
		  throw new ESXXException("'" + method + "' handler '" + handler + 
					  "' not found.");
		}
		else if (!(fobj instanceof Function)) {
		  // Error handler is not a function
		  throw new ESXXException("'" + method + "' handler '" + handler + 
					  "' is not a valid function.");
		} 
		else {
//		  Object args[] = { cx.javaToJS(ex, scope) };
		  Function f = (Function) fobj;
		  result = f.call(cx, scope, scope, null);
		}
	      }
	      else {
		// No handlers; the document is the result
		result = js_esxx.document;
	      }
	    }
	    catch (org.mozilla.javascript.RhinoException ex) {
	      error = ex;
	    }

	    // On errors, invoke error handler
	    if (error != null) {
	      if (parser.hasHandlers()) {
		String handler = parser.getErrorHandlerFunction();

		try {
		  Object fobj = cx.evaluateString(scope, handler, "<error-handler/>", 1, null);

		  if (fobj == null || 
		      fobj == ScriptableObject.NOT_FOUND ||
		      !(fobj instanceof Function)) {
		    // Error handler is not a function
		    throw new ESXXException("Error handler '" + handler + 
					    "' is not a valid function.");
		  } 
		  else {
		    Object args[] = { cx.javaToJS(error, scope) };
		    Function f = (Function) fobj;
		    result = f.call(cx, scope, scope, args);
		  }
		}	
		catch (Exception errex) {
		  throw new ESXXException("Failed to handle error '" + error.toString() + 
					  "': Error handler '" + handler + 
					  "' failed with message '" + 
					  errex.getMessage() + "'");
		}
	      }
	      else {
		// No error handler installed: throw away
		throw error;
	      }
	    }

	    // No error or error handled: Did we get a valid result?
	    if (result == null) {
	      throw new ESXXException("No result from '" + workload.getURL() + "'");
	    }
	    
	    workload.getErrorWriter().write(result.toString());

// 	    Wrapper wrap = (Wrapper) 
// 	      ScriptableObject.callMethod((XMLObject) result, "getXmlObject",  new Object[0]);
// 	    XmlObject xmlObj = (XmlObject) wrap.unwrap();

// 	    org.w3c.dom.Node foo = xmlObj.getDomNode(); 
// 	    System.out.println(foo);

	    try {
	      TransformerFactory tf = TransformerFactory.newInstance();
	      Transformer        tr = tf.newTransformer();

	      StreamSource src = new StreamSource(new StringReader(result.toString()));
	      StreamResult dst = new StreamResult(workload.getErrorWriter());

	      tr.transform(src, dst);
	    }
	    catch (TransformerException ex) {
	      ex.printStackTrace();
	    }
	  }
	  catch (Exception ex) {
	    ex.printStackTrace();
	  }


	  Properties p = new Properties();
// 	  p.setProperty("Status", "200 OK");
// 	  p.setProperty("Content-Type",  "text/plain");
	  workload.finished(0, p);
	}
	catch (InterruptedException ex) {
	  // Don't know what to do here ... die?
	  ex.printStackTrace();
	  return;
	}
      }
    }
    


    private static final int MAX_WORKLOADS = 16;

    private Properties settings;
    private ThreadGroup workerThreads;
    private LinkedBlockingQueue<Workload> workloadQueue;
};
