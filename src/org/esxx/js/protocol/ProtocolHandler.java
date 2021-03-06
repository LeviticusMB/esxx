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

package org.esxx.js.protocol;

import java.net.URISyntaxException;
import java.util.HashMap;
import javax.mail.internet.ContentType;
import org.esxx.js.JSURI;
import org.mozilla.javascript.*;

public class ProtocolHandler {
    public ProtocolHandler(JSURI jsuri)
      throws URISyntaxException {
      this.jsuri = jsuri;
    }

    public Object load(Context cx, Scriptable thisObj, ContentType recv_ct)
      throws Exception {
      throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
				       "' does not support load().");
    }

    public Object save(Context cx, Scriptable thisObj,
		       Object data, ContentType send_ct, ContentType recv_ct)
      throws Exception {
      throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
				       "' does not support save().");
    }

    public Object append(Context cx, Scriptable thisObj,
			 Object data, ContentType send_ct, ContentType recv_ct)
      throws Exception {
      throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
				       "' does not support append().");
    }

    public Object modify(Context cx, Scriptable thisObj,
			 Object data, ContentType send_ct, ContentType recv_ct)
      throws Exception {
      throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
				       "' does not support modify().");
    }

    public Object remove(Context cx, Scriptable thisObj,
			 ContentType recv_ct)
      throws Exception {
      throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
				       "' does not support delete().");
    }

    public Object query(Context cx, Scriptable thisObj, Object[] args)
      throws Exception {
      throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
				       "' does not support query().");
    }

    protected ContentType ensureRecvTypeIsXML(ContentType ct) {
      if (ct == null) {
	ct = xmlContentType;
      }
      else if (!ct.match(xmlContentType)) {
	throw Context.reportRuntimeError("URI protocol '" + jsuri.getURI().getScheme() +
					 "' can only return 'text/xml'.");
      }

      return ct;
    }

    protected JSURI jsuri;

    protected static ContentType xmlContentType;
    protected static ContentType binaryContentType;

    static {
      try {
	xmlContentType    = new ContentType("text/xml");
	binaryContentType = new ContentType("application/octet-stream");
      }
      catch (Exception ex) {
	throw new RuntimeException("Unable to create static content-types", ex);
      }
    }
}
