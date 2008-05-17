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

package org.esxx.js.protocol;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.HashMap;
import org.esxx.*;
import org.esxx.js.*;
import org.mozilla.javascript.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class FILEHandler
  extends URLHandler {
  public FILEHandler(URI uri, JSURI jsuri) {
    super(uri, jsuri);
  }

  @Override
  public Object load(Context cx, Scriptable thisObj,
		     String type, HashMap<String,String> params)
    throws Exception {
    // Default file: load() media type is XML
    if (type == null) {
      type = "text/xml";
    }

    if (type.equals("text/xml")) {
      File dir = new File(uri);

      if (dir.exists() && dir.isDirectory()) {
	Document result = createDirectoryListing(dir.listFiles());

	return ESXX.domToE4X(result, cx, thisObj);
      }
    }

    return super.load(cx, thisObj, type, params);
  }

  @Override
  public Object save(Context cx, Scriptable thisObj,
		     Object data, String type, HashMap<String,String> params)
    throws Exception {
    ESXX esxx = ESXX.getInstance();
    File file = new File(uri);

    Response.writeObject(data, type, params, esxx, cx, new FileOutputStream(file));
    return createDirectoryListing(new File[] { file });
  }

  @Override
  public Object append(Context cx, Scriptable thisObj,
		       Object data, String type, HashMap<String,String> params)
    throws Exception {
    ESXX esxx = ESXX.getInstance();
    File file = new File(uri);

    if (file.exists() && file.isDirectory()) {
      String filename = params.get("name");

      if (filename == null) {
	throw Context.reportRuntimeError("append() to a directory reqires the 'name' parameter");
      }

      file = new File(file, filename);

      if (!file.createNewFile()) {
	throw Context.reportRuntimeError("Failed to create " + file);
      }

      Response.writeObject(data, type, params, esxx, cx, new FileOutputStream(file));
    }
    else {
      Response.writeObject(data, type, params, esxx, cx, new FileOutputStream(file, true));
    }

    return createDirectoryListing(new File[] { file });
  }

  @Override
  public Object query(Context cx, Scriptable thisObj, Object[] args)
    throws Exception {
    return createDirectoryListing(new File[] { new File(uri) });
  }

  @Override
  public Object remove(Context cx, Scriptable thisObj,
		       String type, HashMap<String,String> params)
    throws Exception {
    File file = new File(uri);

    return new Boolean(file.delete());
  }


  protected Document createDirectoryListing(File[] list) {
    ESXX     esxx   = ESXX.getInstance();
    Document result = esxx.createDocument("result");
    Element  root   = result.getDocumentElement();

    for (File f : list) {
      Element element = null;

      if (f.isDirectory()) {
	element = result.createElement("directory");
      }
      else if (f.isFile()) {
	element = result.createElement("file");
	addChild(element, "length", Long.toString(f.length()));
      }

      addChild(element, "name", f.getName());
      addChild(element, "path", f.getPath());
      addChild(element, "uri", f.toURI().toString());
      addChild(element, "hidden", f.isHidden() ? "true" : "false");
      addChild(element, "lastModified", Long.toString(f.lastModified()));
      addChild(element, "id", Integer.toHexString(f.hashCode()));
      root.appendChild(element);
    }

    return result;
  }
}