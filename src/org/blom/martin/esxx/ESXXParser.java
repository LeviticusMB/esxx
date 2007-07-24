/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007 Martin Blom <martin@blom.org>
     
     This program is free software; you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation; either version 2
     of the License, or (at your option) any later version.
     
     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.
     
     You should have received a copy of the GNU General Public License
     along with this program; if not, write to the Free Software
     Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.blom.martin.esxx;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.*;
import javax.xml.xpath.*;
import org.blom.martin.esxx.js.JSGlobal;
import org.blom.martin.esxx.js.JSRequest;
import org.blom.martin.esxx.js.JSURI;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.w3c.dom.*;

public class ESXXParser {

    public static class Code {
	public Code(URL u, int l, String s) {
	  url = u;
	  line = l;
	  source = s;
	  code = null;
	}

	public String toString() {
	  return url.toString() + "::" + line + ": " + code;
	}

	public URL url;
	public int line;
	public String source;
	public Script code;
    };

    public ESXXParser(ESXX esxx, URL url)
      throws IOException, XMLStreamException {
      
      esxxObject = esxx;
      baseURL = url;
      xmlInputFactory = XMLInputFactory.newInstance();

      // Load and parse the document

      try {
	xml = esxxObject.parseXML(esxxObject.openCachedURL(url), url, externalURLs, null);

	// Extract ESXX information, if any

	try { 
	  XPath xpath = XPathFactory.newInstance().newXPath();
	  xpath.setNamespaceContext(new javax.xml.namespace.NamespaceContext() {
		public String getNamespaceURI(String prefix) {
		  if (prefix.equals("esxx")) {
		    return ESXX.NAMESPACE;
		  }
		  else {
		    return javax.xml.XMLConstants.NULL_NS_URI;
		  }
		}

		public String getPrefix(String uri) {
		  throw new UnsupportedOperationException();
		}

		public java.util.Iterator getPrefixes(String uri) {
		  throw new UnsupportedOperationException();
		}
	    });

	  NodeList r = (NodeList) xpath.evaluate("processing-instruction() | " +
						 "esxx:esxx/esxx:handlers/esxx:*", 
						 xml, XPathConstants.NODESET);
	  
	  for (int i = 0; i < r.getLength(); ++i) {
	    Node n = r.item(i);
	    
	    if (n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
	      String name = n.getNodeName();
	      if (name.equals("esxx-stylesheet")) {
		handleStylesheet(n.getNodeValue());
	      }
	      else if (name.equals("esxx-import")) {
		handleImport(n.getNodeValue());
	      }
	      else if (name.equals("esxx")) {
		handleCode(url, 0, n.getNodeValue());
	      }
	    }
	    else if (n.getNodeType() == Node.ELEMENT_NODE) {
	      Element e = (Element) n;
	      String name = e.getLocalName();

	      // esxx/handlers/* matched.
	      gotESXX = true;

	      if (name.equals("http")) {
		handleHTTP(e);
	      }
	      else if (name.equals("soap")) {
		handleSOAP(e);
	      }
	      else if (name.equals("stylesheet")) {
		handleStylesheet(e);
	      }
	      else if (name.equals("error")) {
		handleErrorHandler(e);
	      }
	    }
	  }
	}
	catch(XPathExpressionException ex) {
	  // Should never happen
	  ex.printStackTrace();
	  throw new XMLStreamException(ex.getMessage());
	}
      }
      catch (org.xml.sax.SAXException ex) {
	throw new XMLStreamException(ex.getMessage());
      }
    }

    public Collection<URL> getExternalURLs() {
      return externalURLs;
    }

    public Document getXML() {
      return xml;
    }

    public Collection<Code> getCodeList() {
      return codeList;
    }

    public URL getStylesheet(String media_type) {
      return stylesheets.get(media_type);
    }

    public boolean hasHandlers() {
      return gotESXX;
    }

    public String getHandlerFunction(String http_method, String path_info) {
      String key = http_method + path_info;

      Matcher m = uriPrefixPattern.matcher(key);

      if (m.matches()) {
	for (int i = 1; i <= httpFunctions.length; ++i) {
	  if (m.start(i) != -1) {
	    return httpFunctions[i];
	  }
	}
      }

      return null;
    }

    public String getSOAPAction(String action) {
      return soapActions.get(action);
    }

    public String getErrorHandlerFunction() {
      return errorHandler;
    }

    public synchronized Scriptable compile(Context cx)
      throws IllegalAccessException, InstantiationException, 
      java.lang.reflect.InvocationTargetException {
      if (applicationScope != null) {
	return applicationScope;
      }

      // Compile uri-matching regex pattern
      compileRegEx();

      // Create per-application top-level and global scopes
      applicationScope = new JSGlobal(cx);

      for (Code c : codeList) {
	c.code = cx.compileString(c.source, c.url.toString(), c.line, null);
      }

      return applicationScope;
    }

    public synchronized void execute(Context cx, Scriptable scope) {
      if (!hasExecuted) {
	for (Code c : codeList) {
	  c.code.exec(cx, scope);
	}

	hasExecuted = true;
      }
    }

    private void compileRegEx() {
      StringBuilder                  regex   = new StringBuilder();
      Set<Map.Entry<String, String>> reverse = httpHandlers.descendingMap().entrySet();

      httpFunctions = new String[reverse.size() + 1];

      int i = 1;

      for (Map.Entry<String, String> e : reverse) {
	if (i != 1) {
 	  regex.append("|");
	}
 	
	regex.append("^(");
 	regex.append(e.getKey());
 	regex.append(").*");

	httpFunctions[i] = e.getValue();

	++i;
      }

      uriPrefixPattern = Pattern.compile(regex.toString());
    }

    private void handleStylesheet(String data)
      throws XMLStreamException {

      XMLStreamReader xsr = xmlInputFactory.createXMLStreamReader(
	new StringReader("<esxx-stylesheet " + data + "/>"));

      while (xsr.hasNext()) {
	if (xsr.next() == XMLStreamConstants.START_ELEMENT) {
	  String type = xsr.getAttributeValue(null, "type");
	  if (type == null || !type.equals("text/xsl")) {
	    throw new XMLStreamException("<?esxx-stylesheet?> attribute 'type' " +
					 "must be set to 'text/xsl'");
	  }

	  String href = xsr.getAttributeValue(null, "href");

	  if (href == null) {
	    throw new XMLStreamException("<?esxx-stylesheet?> attribute 'href' " +
					 "must be specified");
	  }

	  try {
	    stylesheets.put("", new URL(baseURL, href));
	  }
	  catch (MalformedURLException ex) {
	    throw new XMLStreamException("<?esxx-stylesheet?> attribute 'href' is invalid: " +
					 ex.getMessage());
	  }
	}
      }

      xsr.close();
    }

    private void handleImport(String data)
      throws XMLStreamException {

      XMLStreamReader xsr = xmlInputFactory.createXMLStreamReader(
	new StringReader("<esxx-import " + data + "/>"));

      while (xsr.hasNext()) {
	if (xsr.next() == XMLStreamConstants.START_ELEMENT) {
	  String href = xsr.getAttributeValue(null, "href");

	  if (href == null) {
	    throw new XMLStreamException("<?esxx-import?> attribute 'href' " +
					 "must be specified");
	  }

	  try {
	    URL url = new URL(baseURL, href);
	    BufferedReader br = new BufferedReader(new InputStreamReader(
						     esxxObject.openCachedURL(url)));
	    StringBuilder code = new StringBuilder();
	    String line;

	    while ((line = br.readLine()) != null) {
	      code.append(line);
	      code.append("\n");
	    }

	    handleCode(url, 1, code.toString());

	    externalURLs.add(url);
	  }
	  catch (MalformedURLException ex) {
	    throw new XMLStreamException("<?esxx-import?> attribute 'href' is invalid: " +
					 ex.getMessage());
	  }
	  catch (IOException ex) {
	    throw new XMLStreamException("<?esxx-import?> failed to include document: " +
					 ex.getMessage());
	  }
	}
      }
      
      xsr.close();
    }

    private void handleCode(URL url, int line, String data) {
      codeList.add(new Code(url, line, data));
    }

    private void handleHTTP(Element e) 
      throws org.xml.sax.SAXException {
      String method  = e.getAttributeNS(null, "method").trim();
      String prefix  = e.getAttributeNS(null, "uri-prefix").trim();
      String handler = e.getAttributeNS(null, "handler").trim();

      if (method.equals("")) {
	throw new org.xml.sax.SAXException("<http> attribute 'method' must " +
					   "must be specified");
      }

      if (handler.equals("")) {
	throw new org.xml.sax.SAXException("<http> attribute 'handler' must " +
					   "must be specified");
      }

      if (handler.endsWith(")")) {
	throw new org.xml.sax.SAXException("<http> attribute 'handler' value " +
					   "should not include parentheses");
      }

      httpHandlers.put(method + prefix, handler);
    }

    private void handleSOAP(Element e) 
      throws org.xml.sax.SAXException {
      String object = e.getAttributeNS(null, "object").trim();

      if (object.equals("")) {
	throw new org.xml.sax.SAXException("<soap> attribute 'object' must " +
					   "must be specified");
      }

      soapActions.put(e.getAttributeNS(null, "action"), object);
    }

    private void handleStylesheet(Element e)
      throws org.xml.sax.SAXException {
      String media_type = e.getAttributeNS(null, "media-type").trim();
      String href      = e.getAttributeNS(null, "href").trim();
      String type      = e.getAttributeNS(null, "type").trim();
      
      if (href.equals("")) {
	throw new org.xml.sax.SAXException("<stylesheet> attribute 'href' " +
					   "must be specified");
      }

      if (!type.equals("") && !type.equals("text/xsl")) {
	throw new org.xml.sax.SAXException("<stylesheet> attribute 'type' " +
					   "must be set to 'text/xsl'");
      }

      try {
	stylesheets.put(media_type, new URL(baseURL, href));
      }
      catch (MalformedURLException ex) {
	throw new org.xml.sax.SAXException("<stylesheet> attribute 'href' is invalid: " +
					   ex.getMessage());
      }
    }

    private void handleErrorHandler(Element e)
      throws org.xml.sax.SAXException {
      String handler = e.getAttributeNS(null, "handler").trim();

      if (handler.endsWith(")")) {
	throw new org.xml.sax.SAXException("<error> attribute 'handler' value " +
					   "should not include parentheses");
      }

      errorHandler = handler;
    }

    private XMLInputFactory xmlInputFactory;

    private ESXX esxxObject;
    private URL baseURL;
    private LinkedList<URL> externalURLs = new LinkedList<URL>();

    private Scriptable applicationScope = null;
    private boolean hasExecuted = false;

    private boolean gotESXX = false;

    private Document xml;
    private StringBuilder code = new StringBuilder();
    private LinkedList<Code> codeList = new LinkedList<Code>();

    private NavigableMap<String,String> httpHandlers = new TreeMap<String,String>();
    private Map<String,String> soapActions  = new HashMap<String,String>();
    private Map<String,URL>    stylesheets  = new HashMap<String,URL>();
    private String errorHandler;

    private String[] httpFunctions;
    private Pattern  uriPrefixPattern;
};
