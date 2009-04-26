/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2008 Martin Blom <martin@blom.org>

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

package org.esxx.request;

import com.sun.net.httpserver.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.net.URI;
import org.esxx.*;
import org.esxx.util.IO;
import org.esxx.util.XML;
import org.mozilla.javascript.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class HTTPRequest
  extends WebRequest {

  public HTTPRequest(HttpExchange he, URI root_uri, File canonical_script_file)
    throws IOException, java.net.URISyntaxException {
      super(canonical_script_file.toURI(), null,
	    createCGIEnvironment(he, root_uri, canonical_script_file),
	    he.getRequestBody(),
	    System.err,
	    null);
    httpExchange = he;
  }

  public Integer handleResponse(ESXX esxx, Context cx, Response response)
    throws Exception {
    try {
      // Do not call super method, since it has a null OutputStream.
      writeResponse(httpExchange, esxx, cx, response);

      return 0;
    }
    catch (IOException ex) {
      // If we fail to send response, it's probably just because
      // nobody is listening anyway.
      return 20;
    }
    catch (Exception ex) {
      ex.printStackTrace();
      throw ex;
    }
    finally {
      httpExchange.close();
    }
  }

  private static void writeResponse(HttpExchange he, ESXX esxx, Context cx, Response response)
    throws IOException {
    final Headers headers = he.getResponseHeaders();
    headers.set("Content-Type", response.getContentType(true));
    response.enumerateHeaders(new Response.HeaderEnumerator() {
	public void header(String name, String value) {
	  headers.set(name, value);
	}
      });

    int  status = response.getStatus();
    long content_length;

    if ((status >= 100 && status <= 199) ||
	status == 204 ||
	status == 205 ||
	status == 304) {
      content_length = -1;
    }
    else if (response.isBuffered()) {
      content_length = response.getContentLength(esxx, cx);
    }
    else {
      content_length = 0;
    }

    he.sendResponseHeaders(status, content_length);

    // Output body
    if (content_length != -1) {
      OutputStream os = he.getResponseBody();
      response.writeResult(esxx, cx, os);
      try { os.close(); } catch (Exception ex) {}
    }
  }

  private static void writeResponse(HttpExchange he, ESXX esxx, int status, Document doc)
    throws IOException {

    ByteArrayOutputStream os = new ByteArrayOutputStream();
    String ct;

    try {
      ct = renderHTML(doc, os);
    }
    catch (Exception ex) {
      ct = "text/plain";
      os.reset();
      new PrintWriter(os).println(ex.getMessage());
    }

    writeResponse(he, esxx, null, new Response(status, ct, os, null));
  }

  private static Properties createCGIEnvironment(HttpExchange he,
						 URI root_uri,
						 File canonical_script_file)
    throws java.net.URISyntaxException {

    URI full_request_uri = new URI("http",
				   he.getRequestHeaders().getFirst("Host"),
				   he.getRequestURI().getPath(),
				   he.getRequestURI().getQuery(),
				   null);

    Properties p = createCGIEnvironment(he.getRequestMethod(), he.getProtocol(),
					full_request_uri,
					he.getLocalAddress(), he.getRemoteAddress(),
					"/", root_uri, canonical_script_file);

    // Add request headers
    for (Map.Entry<String, List<String>> e : he.getRequestHeaders().entrySet()) {
      p.setProperty(ESXX.httpToCGI(e.getKey()), e.getValue().get(0));
    }

    return p;
  }

  public static void runServer(int http_port, String fs_root)
    throws IOException, java.net.URISyntaxException {
    final ESXX    esxx = ESXX.getInstance();
    final String  root = new File(fs_root).getCanonicalPath();
    final URI root_uri = new File(root).toURI();

    HttpServer  hs = HttpServer.create(new InetSocketAddress(http_port), 0);
    hs.createContext("/", new HttpHandler() {
	public void handle(HttpExchange he)
	  throws IOException {
	  String req_uri_raw = he.getRequestURI().getRawPath();
	  String req_uri     = he.getRequestURI().getPath();

	  try {
	    URI path_translated = root_uri.resolve(req_uri_raw.substring(1));
	    File file = new File(path_translated).getCanonicalFile();

	    Response embedded = esxx.getEmbeddedResource(he.getRequestURI().getQuery());

	    if (embedded != null) {
	      writeResponse(he, esxx, null, embedded);
	    }
	    else if (!file.getPath().startsWith(root)) {
	      // Deny access to files outside the root
	      throw new FileNotFoundException("Document is outside root");
	    }
	    else {
	      File app_file = null;

	      if (file.exists()) {
		if (file.isDirectory()) {
		  Document doc = org.esxx.js.protocol.FILEHandler.createDirectoryListing(file);
		  doc.getDocumentElement().setAttributeNS(null, "requestURI", req_uri);
		  writeResponse(he, esxx, 200, doc);
		}
		else {
		  if (ESXX.fileTypeMap.getContentType(file).equals("application/x-esxx+xml")) {
		    app_file = file;
		  }
		  else {
		    he.getResponseHeaders().set("Content-Type",
						ESXX.fileTypeMap.getContentType(file));
		    he.sendResponseHeaders(200, file.length());
		    IO.copyStream(new FileInputStream(file), he.getResponseBody());
		  }
		}
	      }
	      else {
		// Find a file that do exists
		app_file = file;
		while (app_file != null && !app_file.exists()) {
		  app_file = app_file.getParentFile();
		}

		if (app_file.isDirectory()) {
		  throw new FileNotFoundException("Not Found");
		}

		if (!ESXX.fileTypeMap.getContentType(app_file).equals("application/x-esxx+xml")) {
		  throw new FileNotFoundException("Only ESXX files are directories");
		}
	      }

	      if (app_file != null) {
		HTTPRequest hr = new HTTPRequest(he, root_uri, app_file);
		esxx.addRequest(hr, hr, 0);
		he = null;
	      }
	    }
	  }
	  catch (Exception ex) {
	    int code = 500;
	    String title = "Internal Server Error";

	    if (ex instanceof FileNotFoundException) {
	      code = 404;
	      title = "Not Found";
	    }
	    else {
	      ex.printStackTrace();
	    }

	    Document doc = esxx.createDocument("error");
	    Element root = doc.getDocumentElement();

	    XML.addChild(root, "title",    "ESXX Server Error");
	    XML.addChild(root, "subtitle", title);
	    XML.addChild(root, "message",
			 "The requested resource '" + req_uri + "' failed: " + ex.getMessage());

	    writeResponse(he, esxx, code, doc);
	  }
	  finally {
	    if (he != null) {
	      he.close();
	    }
	  }
	}
      });

    int http_threads = Integer.parseInt(esxx.getSettings().getProperty("esxx.http_threads", "0"));

    if (http_threads == 0) {
      // Use an unbounded thread pool
      hs.setExecutor(Executors.newCachedThreadPool());
    }
    else {
      hs.setExecutor(Executors.newFixedThreadPool(http_threads));
    }

    hs.start();

    while (true) {
      try {
	Thread.sleep(10000);
      }
      catch (InterruptedException ex) {
	Thread.currentThread().interrupt();
	break;
      }
    }
  }

  private HttpExchange httpExchange;
}
