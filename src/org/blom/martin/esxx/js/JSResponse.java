
package org.blom.martin.esxx.js;

import org.blom.martin.esxx.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.mozilla.javascript.*;

public class JSResponse 
  extends ScriptableObject {
    public JSResponse() {
      super();
    }

    public JSResponse(String status, String content_type, Object result) {
      this();

      this.status  = status;
      contentType  = content_type;
      this.result  = result;
    }


    static public Object jsConstructor(Context cx, 
				       java.lang.Object[] args, 
				       Function ctorObj, 
				       boolean inNewExpr) 
      throws ESXXException {
      String status;
      String content_type;
      Object result;

      if (args.length == 1 && 
	  args[0] instanceof NativeArray) {
	// Automatically convert an JS Array into a Response
	NativeArray array = (NativeArray) args[0];
	
	if (array.getLength() > 4) {
	  throw Context.reportRuntimeError("Array response requires 1-4 elements."); 
	}

	args = new Object[(int) array.getLength()];

	for (int i = 0; i < (int) array.getLength(); ++i) {
	  args[i] = array.get(i, array);
	}
      }

      switch (args.length) {
	case 1:
	  status       = "200 OK";
	  content_type = null;
	  result       = args[0];
	  break;
	 
	case 2:
	  status       = "200 OK";
	  content_type = Context.toString(args[0]);
	  result       = args[1];
	  break;
	  
	case 3:
	case 4:
	  status       = Context.toString(args[0]);
	  content_type = Context.toString(args[1]);
	  result       = args[2];
	  break;

	default:
	  throw Context.reportRuntimeError("Response() constructor requires 1-3 arguments."); 
      }

      // Unwrap wrapped objects
      if (result instanceof Wrapper) {
	result = ((Wrapper) result).unwrap();
      }

      // Check for valid result
      if (result instanceof ByteArrayOutputStream ||
	  result instanceof ByteBuffer) {
	if (content_type == null) {
	  content_type = "application/octent-stream";
	}
      }
      else if (result instanceof String) {
	if (content_type == null) {
	  content_type = "text/plain;charset=" + 
	    java.nio.charset.Charset.defaultCharset().name();
	}
      }
      else if (result instanceof BufferedImage) {
	if (content_type == null) {
	  content_type = "image/png";
	}

	// TODO ...
	throw new ESXXException("BufferedImage results not supported yet.");
      }
      else {
	try {
	  result = (org.mozilla.javascript.xmlimpl.XMLLibImpl.toDomNode(result));
	}
	catch (Exception ex) {
	  throw new ESXXException("Unsupported result type: " + result.getClass());
	}
      }

      JSResponse res = new JSResponse(status, content_type, result);

      // Copy properties from fouth argument
      if (args.length == 4) {
	if (args[3] instanceof NativeObject) {
	  Scriptable headers = (Scriptable) args[3];

	  for (Object hdr : headers.getIds()) {
	    if (hdr instanceof String) {
	      String name  = (String) hdr;
	      String value = Context.toString(ScriptableObject.getProperty(headers, name));
	      
	      res.jsFunction_addHeader(name, value);
	    }
	  }
	}
	else {
	  throw Context.reportRuntimeError("Fourth Response() arguments must be an JS Object."); 
	}
      }

      return res;
    }

    public String getClassName() {
      return "Response";
    }


    public void jsFunction_addHeader(String name, String value) {
      ScriptableObject.putProperty(this, name, value);
    }

    public String getStatus() {
      return status;
    }

    public String getContentType() {
      return contentType;
    }


    public Object getResult() {
      return result;
    }


    public void setContentType(String content_type) {
      contentType = content_type;
    }


    public void setResult(Object result) {
      this.result = result;
    }


    public static interface HeaderEnumerator {
	public void header(String name, String value);
    }

    public void enumerateHeaders(HeaderEnumerator he) {
      for (Object hdr : getIds()) {
	if (hdr instanceof String) {
	  String name = (String) hdr;

	  he.header(name, Context.toString(ScriptableObject.getProperty(this, name)));
	}
      }
    }

    private String status;
    private String contentType;
    private Object result;
}